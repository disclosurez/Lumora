package com.lumora

import android.app.AlertDialog
import androidx.core.content.ContextCompat
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.lumora.cache.FavoritesStore
import com.lumora.cache.PlaybackPositionStore
import com.lumora.cache.RecentlyPlayedStore
import com.lumora.model.Channel
import com.lumora.model.ContentShelf
import com.lumora.model.MediaType
import com.lumora.plugin.js.PluginScript
import com.lumora.parser.XtreamClient
import com.lumora.util.cleanVodTitle
import com.lumora.util.isAdultCategory
import kotlinx.coroutines.*
import java.util.Locale

// ── Discover (TMDB browse) & Home shelves ──
//
// Extracted from MainActivity.kt; see that file's header.
internal fun MainActivity.setupDiscover() {
    setGridSpan(binding.discoverGrid, discoverGridAdapter, R.id.tabDiscover)
    // setGridSpan only wires the layout manager/span; the adapter still has to be attached.
    binding.discoverGrid.adapter = discoverGridAdapter
    // The inline field isn't a real input (no platform IME on TV, and a focused field
    // with the IME suppressed is a dead end for the remote) - both the field and the
    // Search button open the on-screen-keyboard overlay instead.
    binding.discoverSearchField.setOnClickListener { showDiscoverSearchOverlay() }
    binding.discoverSearchButton.setOnClickListener { showDiscoverSearchOverlay() }
}

/** Opens the Discover (TMDB) search overlay - the keyboard pattern from the main
 *  search overlay, minus a results surface (Discover's own grid shows the matches once
 *  the query is submitted). Dismissing leaves the query behind in the inline field. */
internal fun MainActivity.showDiscoverSearchOverlay() {
    if (activeSettingsOverlay != null || activeSearchOverlay != null) return
    val view = layoutInflater.inflate(R.layout.dialog_discover_search, null)
    val input = view.findViewById<EditText>(R.id.discoverSearchQuery)
    val keyboard = view.findViewById<com.lumora.ui.OnScreenKeyboard>(R.id.discoverSearchKeyboard)
    applyPanelWidth(view.findViewById(R.id.discoverSearchPanel), R.dimen.search_panel_width)
    input.showSoftInputOnFocus = false
    keyboard.onKey = { ch -> input.setText(input.text.toString() + ch) }
    keyboard.onBackspace = { input.setText(input.text.toString().dropLast(1)) }
    keyboard.onClear = { input.setText("") }
    // Hardware (BT/USB) keyboard routes here while the overlay is up.
    searchKeyHandler = { ch ->
        if (ch == null) keyboard.onBackspace?.invoke()
        else input.setText(input.text.toString() + ch)
    }
    val overlay = MainActivity.FullScreenOverlay(
        binding.searchContainer,
        view,
        closeButton = view.findViewById(R.id.discoverSearchClose),
        initialFocus = { keyboard.firstKey() ?: input }
    )
    view.findViewById<View>(R.id.discoverSearchSubmit).setOnClickListener {
        val query = input.text.toString().trim()
        overlay.dismiss()
        if (query.isNotEmpty()) {
            binding.discoverSearchInput.setText(query)
            loadDiscover(query)
        }
    }
    val tabBarWasVisible = binding.tabBar.visibility == View.VISIBLE
    if (tabBarWasVisible) binding.tabBar.visibility = View.GONE
    overlay.setOnDismissListener {
        searchKeyHandler = null
        activeSearchOverlay = null
        if (tabBarWasVisible) binding.tabBar.visibility = View.VISIBLE
        applyStatus()
        // The overlay's dismissal detaches the focused subtree (the keyboard); leave
        // nothing focused and the Discover pane is a dead D-pad. Focus the field that
        // opened it, retried on the next frame like MainActivity.FullScreenOverlay's own focus logic.
        binding.discoverSearchField.post { binding.discoverSearchField.requestFocus() }
    }
    activeSearchOverlay = overlay
    overlay.show()
}

/** Discover is its own pane (like Downloads): browse/search TMDB, no category sidebar. */
internal fun MainActivity.selectDiscover() {
    hideCatchup()
    activeSettingsOverlay?.dismiss()
    activeSearchOverlay?.dismiss()
    showingHome = false
    showingDownloads = false
    showingDiscover = true
    releaseLivePreview()
    binding.contentRow.visibility = View.GONE
    binding.homeContent.visibility = View.GONE
    binding.homeSearchBar.visibility = View.GONE
    binding.discoverContent.visibility = View.VISIBLE
    updateTabStyles(binding.tabDiscover)
    // Recompute span now the pane is on-screen and actually has a width.
    binding.discoverGrid.post { setGridSpan(binding.discoverGrid, discoverGridAdapter, R.id.tabDiscover) }
    if (!tmdbClient.hasKey()) {
        setDiscoverStatus("Discover is unavailable (no TMDB key configured).")
    } else if (discoverGridAdapter.itemCount == 0) {
        loadDiscover(null)
    }
    applyStatus()
}

/** Loads trending (null query) or search results into the Discover grid. */
internal fun MainActivity.loadDiscover(query: String?) {
    if (!tmdbClient.hasKey()) return
    discoverSearchJob?.cancel()
    setDiscoverStatus(if (query == null) "Loading trending…" else "Searching \"$query\"…")
    discoverSearchJob = scope.launch {
        val results = if (query == null) tmdbClient.trending() else tmdbClient.search(query)
        // Without a stream-search plugin (the torrent plugin being the common one), a
        // TMDB-only title is a dead tile - its dialog offers nothing but a trailer.
        // Drop anything that isn't already in the library; with a plugin enabled the
        // plugin can play every title, so nothing gets filtered.
        val pluginEnabled = enabledStreamSearchPlugin() != null
        // With a plugin enabled every title is playable, so nothing has to be matched before
        // the grid can be shown - and matching is the one slow step here. Only the no-plugin
        // filter waits for it.
        val visible = if (pluginEnabled) results else withContext(Dispatchers.Default) {
            results.filter { findCatalogMatches(it).isNotEmpty() }
        }
        discoverGridAdapter.replaceAll(visible)
        // Source badges are decoration on tiles that are already on screen, so they are
        // worked out afterwards and painted in when ready. Nothing waits on them.
        loadDiscoverLibraryBadges(visible)
        setDiscoverStatus(
            when {
                visible.isNotEmpty() -> null
                results.isEmpty() -> if (query == null) "Couldn't load titles. Check your connection." else "No results for \"$query\"."
                else -> "Enable a stream plugin to browse titles outside your library."
            }
        )
    }
}

/** Works out which of the user's sources already carry each visible Discover title, then
 *  repaints the grid so the tiles show it. Deliberately off the load path: it walks the whole
 *  catalogue once per tile, and the grid is useful long before the badges land. */
internal fun MainActivity.loadDiscoverLibraryBadges(items: List<Channel>) {
    discoverBadgeJob?.cancel()
    discoverBadgeJob = scope.launch {
        val badges = withContext(Dispatchers.Default) {
            items.mapNotNull { item ->
                val versions = catalogVersionsFor(findCatalogMatches(item))
                if (versions.isEmpty()) return@mapNotNull null
                val jellyfin = versions.any { it.isJellyfin }
                val iptv = versions.any { !it.isJellyfin }
                item.id to when {
                    jellyfin && iptv -> "Jellyfin + IPTV"
                    jellyfin -> "Jellyfin"
                    else -> "IPTV"
                }
            }.toMap()
        }
        discoverLibrarySources = badges
        if (badges.isNotEmpty()) discoverGridAdapter.notifyItemRangeChanged(0, discoverGridAdapter.itemCount)
    }
}

internal fun MainActivity.setDiscoverStatus(text: String?) {
    binding.discoverStatus.text = text ?: ""
    binding.discoverStatus.visibility = if (text == null) View.GONE else View.VISIBLE
}

/** Discover pick opens an info screen: overview + poster, then either play a matching catalog
 *  item (if this title is already served by a provider) or find a torrent stream for it. */
internal fun MainActivity.onDiscoverItemClick(item: Channel) {
    // Every copy, not the best one: the dialog names each source it found, and the detail
    // screen needs the whole set so its version chips can switch between them.
    val matches = findCatalogMatches(item)
    val versions = catalogVersionsFor(matches)
    val match = versions.firstOrNull()

    val density = resources.displayMetrics.density
    val pad = (20 * density).toInt()
    val backdrop = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (200 * density).toInt()
        )
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    loadDetailImage(item.backdropUrl ?: item.posterUrl, backdrop)

    val meta = listOfNotNull(
        if (item.mediaType == MediaType.SERIES) "Series" else "Movie",
        item.year,
        item.rating?.let { "★ $it" }
    ).joinToString("   ·   ")

    fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(this@onDiscoverItemClick, R.color.text_secondary))
        setPadding(0, (6 * density).toInt(), 0, 0)
    }

    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad / 2, pad, 0)
        addView(TextView(this@onDiscoverItemClick).apply {
            text = item.name
            setTextColor(ContextCompat.getColor(this@onDiscoverItemClick, R.color.text_primary))
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        addView(label(meta))
        item.description?.let { addView(label(it)) }
        // Name the sources rather than saying "provider": with a Jellyfin server and one or
        // more IPTV panels merged into one catalogue, "which of my sources actually has
        // this" is the whole question this line exists to answer - and the copies differ
        // (the Jellyfin one may carry the season the IPTV one is missing).
        if (versions.isNotEmpty()) {
            val sources = versions.mapNotNull { providerNameFor(it) }.distinct()
            addView(label(
                if (sources.isEmpty()) "✓ In your library"
                else "✓ In your library · ${sources.joinToString(", ")}"
            ))
        }
    }
    val buttonRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.END
        setPadding(pad, (12 * density).toInt(), pad, pad)
    }
    fun actionButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = (8 * density).toInt() }
        setOnClickListener { onClick() }
    }

    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(backdrop)
        addView(content)
        addView(buttonRow)
    }
    val scroll = ScrollView(this).apply { addView(body) }

    val dialog = AlertDialog.Builder(this).setView(scroll).create()
    // Whatever a button does, it happens on the frame *after* the dialog is gone. Dismissing
    // a dialog tears its window down and re-runs focus resolution on the Activity behind it,
    // which lands on whatever was focused before (the Discover tile - by then hidden inside
    // the GONE mainContent) and silently overrides the focus the incoming screen just asked
    // for. That is what left the series detail with no focused view at all: seasons and
    // episodes were on screen and the D-pad did nothing.
    fun afterDismiss(action: () -> Unit) {
        dialog.dismiss()
        binding.root.post(action)
    }
    // Prefer the already-owned copy; the torrent path is offered too, but only when a
    // stream-search plugin is actually enabled to serve it.
    if (match != null) {
        buttonRow.addView(actionButton("Play") {
            // The group goes with it, so the detail screen opens on the best copy with the
            // others as switchable chips - the same thing opening the series from the
            // library gives you. Without it a match that is a *member* of a duplicate group
            // (rather than its representative) resolved to no group at all, and the other
            // provider's copy was unreachable from Discover.
            afterDismiss { showContentDetail(match, versions.takeIf { it.size > 1 }) }
        })
    }
    if (enabledStreamSearchPlugin() != null) {
        buttonRow.addView(actionButton("Find stream") {
            afterDismiss { startDiscoverStreamSearch(item) }
        })
    }
    if (tmdbClient.hasKey()) {
        buttonRow.addView(actionButton("Trailer") {
            afterDismiss { showTrailerForDiscoverItem(item) }
        })
    }
    buttonRow.addView(actionButton("Close") { dialog.dismiss() })
    dialog.show()
}

/** Kicks off a stream-search plugin for a Discover title (episode picker for series). */
internal fun MainActivity.startDiscoverStreamSearch(item: Channel) {
    val plugin = enabledStreamSearchPlugin(item)
    if (plugin == null) {
        Toast.makeText(
            this,
            "Enable a stream plugin in Settings → Plugins to play Discover titles.",
            Toast.LENGTH_LONG
        ).show()
        return
    }
    if (item.mediaType == MediaType.SERIES) showSeriesEpisodePicker(plugin, item)
    else showStreamSearchDialog(plugin, item)
}

/** Every copy of a Discover title the library holds, best first.
 *
 *  Plural on purpose. A title is routinely carried by more than one source - a Jellyfin
 *  server and an IPTV panel, or two panels - and they are not interchangeable: the Jellyfin
 *  copy may have the season the IPTV one is missing. Returning one arbitrary winner is what
 *  hid an owned Jellyfin series behind a thinner IPTV entry with the same name.
 *
 *  Jellyfin sorts first among equally good matches: it is the user's own library, so its
 *  episode list and watch state are the authoritative ones.
 *
 *  Matching is deliberately strict: the title, optionally with trailing junk, and nothing
 *  else. An earlier version also accepted the target appearing as whole words *anywhere* in
 *  the name, to cope with catalogue prefixes ("NF - The Odyssey") - but that also matched
 *  "NF - Troy The Odyssey", a different film, and reported it as owned. Provider decoration
 *  is stripped with cleanVodTitle() instead, which is what the prefix case actually needed,
 *  so a title containing another title no longer matches at all. */
internal fun MainActivity.findCatalogMatches(item: Channel): List<Channel> {
    val target = normalizeMatchTitle(item.name)
    if (target.isBlank()) return emptyList()
    // Cheap gate before the expensive one. Normalising and cleaning a title runs the best
    // part of a dozen regexes, and a merged catalogue runs to six figures of channels -
    // doing that for every candidate of every result is minutes of work, which is what left
    // Discover sitting on "Loading trending…". Cleaning only ever *removes* text, so any
    // real match must still contain the target's longest word verbatim; a plain substring
    // test rejects almost everything for the price of an indexOf.
    val probe = target.split(' ').maxByOrNull { it.length }.orEmpty()
    val scored = mutableListOf<Pair<Int, Channel>>()
    for (candidate in allChannels) {
        if (candidate.mediaType != item.mediaType) continue
        if (probe.isNotEmpty() && !candidate.name.contains(probe, ignoreCase = true)) continue
        // A year both sides agree on is a hard filter, exactly as before: two films can
        // share a title, and the year is the only thing that tells them apart.
        if (item.year != null && candidate.year != null && candidate.year != item.year) continue
        // cleanVodTitle first: catalogue names carry source/quality decoration ("NF - ",
        // "4K-AMZ - ", "[MULTI]") that has nothing to do with the title, and stripping it is
        // what lets an exact comparison work at all.
        val name = normalizeMatchTitle(cleanVodTitle(candidate.name))
        val rank = when {
            name == target -> 0
            name.startsWith("$target ") && !looksLikeSequelSuffix(name.removePrefix("$target ")) -> 1
            else -> continue
        }
        val yearBonus = if (item.year != null && candidate.year == item.year) 0 else 100
        val extra = (name.length - target.length).coerceIn(0, 99)
        val sourceBonus = if (candidate.isJellyfin) 0 else 200
        scored += (rank * 10_000 + yearBonus + sourceBonus + extra) to candidate
    }
    return scored.sortedBy { it.first }.map { it.second }.distinctBy { it.id.ifBlank { it.url } }
}

/** The full set of copies to offer for [match] - the matches Discover found, plus whatever
 *  the duplicate-grouping pass already knows about (which is keyed by the group's
 *  representative, so a match that is a *member* of a group finds nothing by direct lookup
 *  and has to be searched for). Deduped, Jellyfin first. */
internal fun MainActivity.catalogVersionsFor(matches: List<Channel>): List<Channel> {
    val versions = if (matches.firstOrNull()?.mediaType == MediaType.SERIES) seriesVersions else filmVersions
    val out = LinkedHashMap<String, Channel>()
    for (match in matches) {
        val key = match.id.ifBlank { match.url }
        out.putIfAbsent(key, match)
        val group = versions[match.id] ?: versions.values.firstOrNull { g -> g.any { it.id == match.id } }
        group?.forEach { out.putIfAbsent(it.id.ifBlank { it.url }, it) }
    }
    return out.values.sortedBy { if (it.isJellyfin) 0 else 1 }
}

/** Trailing text that makes a title a *different* film rather than a decorated copy of the
 *  same one: "The Odyssey 2", "The Odyssey Part II". A 4-digit year is not a sequel marker -
 *  "The Odyssey 2026" is the same film with its year appended, which catalogues do. */
private fun looksLikeSequelSuffix(remainder: String): Boolean {
    val first = remainder.substringBefore(' ')
    if (first == "part" || first == "chapter") return true
    if (first in setOf("ii", "iii", "iv", "v", "vi")) return true
    val number = first.toIntOrNull() ?: return false
    return number < 1900
}

internal fun MainActivity.normalizeMatchTitle(title: String): String =
    title.lowercase(Locale.US).replace(Regex("\\(\\d{4}\\)"), " ")
        .replace(Regex("[^a-z0-9]+"), " ").trim()

/** Fetches the show's seasons from TMDB, then lets the user pick season → episode to search. */
internal fun MainActivity.showSeriesEpisodePicker(plugin: PluginScript, item: Channel) {
    val tvId = item.id.substringAfterLast(':').toIntOrNull()
    if (tvId == null) { showStreamSearchDialog(plugin, item); return }
    val loading = AlertDialog.Builder(this)
        .setTitle(item.name)
        .setMessage("Loading episodes…")
        .setNegativeButton("Cancel", null)
        .create()
    loading.show()
    scope.launch {
        val seasons = tmdbClient.tvSeasons(tvId)
        loading.dismiss()
        if (seasons.isEmpty()) {
            // No season data - fall back to searching the title as a whole.
            showStreamSearchDialog(plugin, item)
            return@launch
        }
        val seasonLabels = seasons.map { "${it.name} (${it.episodeCount} eps)" }.toTypedArray()
        AlertDialog.Builder(this@showSeriesEpisodePicker)
            .setTitle("${item.name} — choose a season")
            .setItems(seasonLabels) { _, si ->
                val season = seasons[si]
                val epLabels = (1..season.episodeCount).map { "Episode $it" }.toTypedArray()
                AlertDialog.Builder(this@showSeriesEpisodePicker)
                    .setTitle(season.name)
                    .setItems(epLabels) { _, ei ->
                        showStreamSearchDialog(plugin, item, season.number, ei + 1)
                    }
                    .setNegativeButton("Back") { _, _ -> showSeriesEpisodePicker(plugin, item) }
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

internal fun MainActivity.onHomeItemClick(channel: Channel) {
    // User-initiated play - see playItem for why the suppression flag is cleared here.
    skipResumePrompt = false
    when (channel.mediaType) {
        MediaType.LIVE -> playItem(channel)
        MediaType.MOVIE -> {
            currentIndex = filmList.indexOf(channel)
            showPlayerFor(channel)
            // Back out to the film's own poster, same as playing it from its detail page.
            // Not for a plugin-resolved entry: its id is a resolve token, not a catalog
            // item, so there is no detail page to return it to.
            if (channel.pluginToken == null) detailReturnItem = channel
        }
        MediaType.SERIES -> {
            // An up-next tile (synthesized for a series whose watched trail is complete)
            // plays the next episode directly, queue included - one click continues the
            // show. Identified by its episode id being registered in upNextQueues.
            val upNextQueue = upNextQueues[channel.id.ifBlank { channel.url }]
            if (upNextQueue != null) {
                val index = upNextQueue.indexOfFirst {
                    it.id.ifBlank { it.url } == channel.id.ifBlank { channel.url }
                }
                showPlayerFor(channel)
                currentEpisodeQueue = upNextQueue
                currentEpisodeQueueIndex = if (index >= 0) index else 0
                return
            }
            // An episode tile (Continue Watching) carries an episode number; clicking it
            // should land on the series' detail page - the season chip lands on the
            // episode's season and the Play button already points at the next-unwatched
            // episode - rather than resuming the episode directly. A top-level series
            // entry (Favorites, category grids) has no episode number and goes to the
            // detail page as normal. url is NOT a reliable discriminator - catalog
            // series items can carry one. If the episode's series can't be resolved,
            // fall back to resuming the episode directly.
            if (channel.episodeNum != null) {
                val series = resolveHomeTileSeries(channel)
                if (series != null) {
                    showContentDetail(series)
                } else {
                    showPlayerFor(channel)
                    // A Continue Watching / Next Up tile is a lone episode with no queue
                    // behind it - nothing would auto-advance when it ends. Back-fill the
                    // same cross-season episode chain the detail page plays from.
                    populateHomeTileEpisodeQueue(channel)
                }
            } else {
                showContentDetail(channel)
            }
        }
        else -> {}
    }
}

/** Resolves the series a Home-tile episode belongs to: exact categoryId (the series id
 *  Xtream parseEpisode and Jellyfin toChannel both stamp on episodes) match through the
 *  catalog first, then the "{series} · {episode}" name-prefix fallback for snapshots that
 *  predate categoryId. Null if unresolvable - callers fall back to direct play. */
internal fun MainActivity.resolveHomeTileSeries(channel: Channel): Channel? {
    // Exact series-id match. Ids are provider-scoped (Xtream series id, Jellyfin item
    // id), so cross-matching is impossible - isJellyfin is the only guard needed, with
    // sourceProviderId compared only when the snapshot carries one (older saves don't).
    channel.categoryId?.takeIf { it.isNotBlank() }?.let { id ->
        allChannels.firstOrNull {
            it.mediaType == MediaType.SERIES && it.id == id && it.isJellyfin == channel.isJellyfin &&
                (channel.sourceProviderId == null || it.sourceProviderId == channel.sourceProviderId)
        }?.let { return it }
    }
    // Name-prefix fallback for old snapshots: "Series Name · S01E02 · Title", longest
    // name wins. Same-provider guard only when the snapshot knows its provider.
    return allChannels
        .filter {
            it.mediaType == MediaType.SERIES && it.isJellyfin == channel.isJellyfin &&
                (channel.sourceProviderId == null || it.sourceProviderId == channel.sourceProviderId)
        }
        .filter { it.name.isNotBlank() && channel.name.startsWith(it.name + " · ") }
        .maxByOrNull { it.name.length }
}

/** A Home tile can be one episode standing alone (Continue Watching, Jellyfin Next Up),
 *  played with no queue - so when it ends nothing auto-advances. Back-fill the series'
 *  full episode chain (all seasons, season-major then episode-major, the order the detail
 *  page plays) and index it from the played episode. Any failure leaves the queue empty,
 *  which is exactly what happened before this existed. */
internal fun MainActivity.populateHomeTileEpisodeQueue(channel: Channel) {
    val playedId = channel.id
    if (playedId.isBlank()) return
    // Jellyfin's chain comes from the server (getEpisodes/getSeasons), not Xtream
    // getSeriesFull - and its tiles now resolve to the series detail page anyway, so
    // this fallback never needs to build a Jellyfin queue.
    if (channel.isJellyfin) return
    scope.launch {
        val ordered = withContext(Dispatchers.IO) {
            val seriesId = channel.categoryId ?: return@withContext emptyList<Channel>()
            val client = XtreamClient(BaseApplication.instance.okHttpClient)
            // Seasons arrive season-major already; sort each season's episodes by
            // episode number, then flatten into the cross-season chain.
            client.getSeriesFull(xtreamProviderFor(channel) ?: provider, seriesId).seasons
                .flatMap { (_, eps) -> eps.sortedBy { it.episodeNum ?: Int.MAX_VALUE } }
        }
        // Don't clobber a queue belonging to whatever is playing now if the user moved on
        // while the fetch was in flight.
        if (nowPlayingChannel?.id != playedId) return@launch
        val index = ordered.indexOfFirst { it.id == playedId }
        if (index >= 0) {
            currentEpisodeQueue = ordered
            currentEpisodeQueueIndex = index
        }
    }
}

internal fun MainActivity.getHiddenHomeShelves(): MutableSet<String> =
    prefs.getStringSet("hidden_home_shelves", emptySet())?.toMutableSet() ?: mutableSetOf()

internal fun MainActivity.toggleHiddenHomeShelf(title: String) {
    val hidden = getHiddenHomeShelves()
    if (!hidden.remove(title)) hidden.add(title)
    prefs.edit().putStringSet("hidden_home_shelves", hidden).apply()
    homeShelfAdapter.submitList(buildHomeShelves())
}

/** X on the "Continue Watching" shelf clears the resume data itself, not just hides the
 *  shelf on the tab it was pressed on. Home, Series and Films all read the same store, so
 *  one clear empties the row everywhere. Jellyfin resume lives on the server, so those
 *  entries are dropped there too (best effort) and removed from memory immediately. Also
 *  un-hides the CW shelf so future watching isn't stuck behind a stale hide flag. */
internal fun MainActivity.clearContinueWatching() {
    PlaybackPositionStore.clearAll(this)
    clearUpNextMemo()
    val serverIds = jellyfinResumeItems.map { it.id }.toList()
    jellyfinResumeItems = emptyList()
    val client = jellyfinClient
    if (client != null && serverIds.isNotEmpty()) {
        scope.launch(Dispatchers.IO) {
            serverIds.forEach { id -> runCatching { client.clearUserData(id) } }
        }
    }
    getHiddenHomeShelves().let { if (it.remove("Continue Watching")) prefs.edit().putStringSet("hidden_home_shelves", it).apply() }
    getHiddenCategories(1).let { if (it.remove("Continue Watching")) prefs.edit().putStringSet(hiddenCategoriesPrefsKey(1), it).apply() }
    getHiddenCategories(2).let { if (it.remove("Continue Watching")) prefs.edit().putStringSet(hiddenCategoriesPrefsKey(2), it).apply() }
    homeShelfAdapter.submitList(buildHomeShelves())
    if (!showingHome && activeTab != 0) scope.launch { classifyAndShow() }
}

/** Adult content never reaches a Home shelf, regardless of the "Hide adult categories"
 *  setting. That setting governs *browsing* - somebody who unlocks it with the PIN is
 *  choosing to go and look. Continue Watching and Recently Played are different: they
 *  render unprompted on the first screen after launch, in front of whoever happens to
 *  be in the room, so they stay filtered either way.
 *
 *  Three signals, in order of trust: the catalog entry for this id (authoritative, but
 *  only for items still in the catalog - a series episode never is), the category/group
 *  snapshotted at save time, then the title itself as a last resort for entries written
 *  before that snapshot existed. The title check can over-match a legitimate film with
 *  "adult" in its name, which is the right way round to be wrong here. */
internal fun MainActivity.isAdultHomeItem(item: Channel): Boolean {
    val catalog = item.id.takeIf { it.isNotBlank() }?.let { id -> allChannels.firstOrNull { it.id == id } }
    return isAdultCategory(catalog?.categoryName ?: item.categoryName, catalog?.group ?: item.group) ||
        isAdultCategory(item.name)
}

/** First unwatched episode of a series in play order (season-major, then episode
 *  number) - the same ordering the detail page and auto-advance use. Null when the
 *  whole series is watched: a completed series gets no up-next tile. */
internal fun MainActivity.nextEpisodeFor(seasons: List<Pair<String, List<Channel>>>): Channel? {
    val ordered = seasons.flatMap { (_, eps) -> eps.sortedBy { it.episodeNum ?: Int.MAX_VALUE } }
    return ordered.firstOrNull { ep ->
        val key = ep.id.ifBlank { ep.url }
        key.isNotBlank() && PlaybackPositionStore.get(this, key)?.isNearComplete != true
    }
}

/** Builds an up-next tile's display name: "Series · S01E05 · Title". Episode titles often
 *  already carry the series name (Xtream bakes it in), so a leading series-name
 *  occurrence and the "SxxEyy · " marker are peeled from the title before the series
 *  prefix is added - otherwise the series reads twice. */
internal fun MainActivity.upNextTileName(seriesName: String, episodeName: String): String {
    val sMark = Regex("""^S\d+E\d+""").find(episodeName)?.value
    val title = episodeName
        .replaceFirst(Regex("^" + Regex.escape(seriesName) + """\s*[·-]\s*"""), "")
        .replaceFirst(Regex("""^S\d+E\d+\s*·\s*"""), "")
        .replaceFirst(Regex("^" + Regex.escape(seriesName) + """\s*-\s*"""), "")
    return listOfNotNull(seriesName, sMark, title.takeIf { it.isNotBlank() }).joinToString(" · ")
}

/** Continue Watching extension: a series whose watched trail ends at a completed
 *  episode has nothing in Continue Watching (it only keeps in-progress entries), so its
 *  next episode would be unreachable from Home. Resolve those lazily - return whatever
 *  next-episode tiles are already memoized, and kick an async bounded fetch for the
 *  rest. Cheap when everything's resolved: just a store read + memo lookups. */
internal fun MainActivity.buildUpNextSeriesTiles(): List<Channel> {
    // Home-only feature: other tabs' shelf builds (clear/watch toggle paths) shouldn't
    // kick six network fetches for a row that isn't visible.
    if (!showingHome) return emptyList()
    val trails = PlaybackPositionStore.getCompletedSeriesTrails(this)
    val pending = trails
        .filterNot { it.isJellyfin } // server-side "Next Up" shelf already covers Jellyfin
        .mapNotNull { it.categoryId?.takeIf { id -> id !in upNextTiles && id !in upNextFetching } }
        .take(MAX_UP_NEXT_SERIES)
    if (pending.isNotEmpty()) fetchUpNextSeries(pending)
    // Trail order = most recently completed first; present the memoized tiles in that
    // order (LinkedHashMap insertion order is fetch-completion order, which is arbitrary).
    return trails.mapNotNull { t -> upNextTiles[t.categoryId]?.takeIf { it != null } }
}

/** Fetches the episode lists for up to [MAX_UP_NEXT_SERIES] series (one network call
 *  each, Xtream-only because Jellyfin has its own Next Up), computes each series' next
 *  unwatched episode, and rebuilds the Home shelves once. Results commit atomically only
 *  if the memo epoch hasn't moved (see [clearUpNextMemo]) - a fetch that outlives a
 *  watched-state change must not write pre-change tiles.
 *  Only a *resolved* "no next episode" (fully watched / genuinely empty seasons) is
 *  memoized as no-tile; catalog misses and network failures stay unresolved so the next
 *  Home rebuild retries them. */
internal fun MainActivity.fetchUpNextSeries(seriesIds: List<String>) {
    val epoch = upNextEpoch
    upNextFetching.addAll(seriesIds)
    scope.launch {
        val resolved = HashMap<String, Channel?>()
        val queues = HashMap<String, List<Channel>>()
        for (seriesId in seriesIds) {
            if (epoch != upNextEpoch) break
            val series = allChannels.firstOrNull {
                it.mediaType == MediaType.SERIES && it.id == seriesId
            } ?: continue // not in catalog yet - leave unresolved, retry next build
            val seasons = withContext(Dispatchers.IO) {
                runCatching { loadSeriesContent(series).second }.getOrNull()
            } ?: continue // network failure - leave unresolved, retry next build
            val next = nextEpisodeFor(seasons)
            if (next == null) {
                // Resolved: fully watched (or no playable episodes) - no tile, ever.
                resolved[seriesId] = null
                continue
            }
            val chain = seasons.flatMap { (_, eps) -> eps.sortedBy { it.episodeNum ?: Int.MAX_VALUE } }
            queues[next.id.ifBlank { next.url }] = chain
            // Prefix the series name so a bare "S02E03 · Title" tile reads as the show
            // it belongs to - but peel any series-name occurrence already baked into the
            // episode title first (Xtream titles often read "Clarkson's Farm (2021) -
            // Tractoring"), or the series shows twice.
            resolved[seriesId] = next.copy(name = upNextTileName(series.name, next.name))
        }
        // Commit only if no watched-state change invalidated the memo mid-fetch. No
        // upNextFetching cleanup here: the clear already wiped the set, and removing
        // ids now could yank a *newer* epoch's in-flight claim for the same series.
        if (epoch != upNextEpoch) return@launch
        val foundAny = resolved.values.any { it != null }
        upNextTiles.putAll(resolved)
        upNextQueues.putAll(queues)
        seriesIds.forEach { upNextFetching.remove(it) }
        if (foundAny && showingHome) homeShelfAdapter.submitList(buildHomeShelves())
    }
}

internal fun MainActivity.buildHomeShelves(): List<ContentShelf> {
    val shelves = mutableListOf<ContentShelf>()
    val hidden = getHiddenHomeShelves()

    // Jellyfin's own resume list leads Continue Watching: the server knows about playback
    // from every other client, which a purely local position store never can. Local
    // entries follow, minus anything the server already covered (same item, one card).
    val localContinue = PlaybackPositionStore.getAllInProgress(this)
    val serverContinue = jellyfinResumeItems
    val serverIds = serverContinue.map { it.id }.toSet()
    // Up-next series tiles: series whose watched trail ends at a completed episode have
    // no in-progress entry, so they'd otherwise drop out of Continue Watching entirely.
    // buildUpNextSeriesTiles returns what's already resolved and kicks the async fetch
    // for the rest - the row fills in as episodes arrive.
    val upNext = buildUpNextSeriesTiles().filterNot(::isAdultHomeItem)
    val continueItems = (serverContinue + localContinue + upNext)
        .distinctBy { it.id.ifBlank { it.url } }
        .filterNot(::isAdultHomeItem)
    if (continueItems.isNotEmpty()) shelves.add(ContentShelf("Continue Watching", continueItems))

    // "Next Up" is the row that makes a series library usable - the next unwatched episode
    // of everything in flight, straight from the server's own tracking.
    val nextUpItems = jellyfinNextUpItems.filterNot(::isAdultHomeItem)
    if (nextUpItems.isNotEmpty()) shelves.add(ContentShelf("Next Up", nextUpItems))

    val recentItems = RecentlyPlayedStore.getRecentIds(this)
        .mapNotNull { id -> liveChannels.firstOrNull { it.id == id } }
        .filterNot(::isAdultHomeItem)
    if (recentItems.isNotEmpty()) shelves.add(ContentShelf("Recently Played", recentItems))

    // Favourited live channels get their own Home row. Long-pressing a channel in the
    // guide has always favourited it, but the result was only ever visible as the
    // Favourites category inside Live TV - Home, the screen the app opens on, showed
    // nothing at all, so the favourites looked like they hadn't saved.
    val favChannelIds = FavoritesStore.getFavoriteChannelIds(this)
    val favChannels = liveChannels.filter { it.id in favChannelIds }.filterNot(::isAdultHomeItem)
    if (favChannels.isNotEmpty()) shelves.add(ContentShelf("Favourite Channels", favChannels))

    // One shelf for both, since favourite VOD is stored in a single set (see
    // FavoritesStore.KEY_FAVORITE_SERIES) - a favourited film used to be saved and then
    // never shown anywhere, because only seriesList was searched for the ids.
    val favIds = FavoritesStore.getFavoriteSeriesIds(this)
    val favItems = (seriesList + filmList).filter { it.id in favIds }.filterNot(::isAdultHomeItem)
    if (favItems.isNotEmpty()) shelves.add(ContentShelf("Favorites", favItems))

    return shelves.filter { it.title !in hidden }
}

/** Series-only Continue Watching for the Series tab - same merge as the Home shelf
 *  (server resume list first, then local in-progress entries minus anything the server
 *  already covered), filtered down to series and adult-dropped. Shared by the Series
 *  sidebar row, its content grid, and the Series poster shelf. */
internal fun MainActivity.seriesContinueItems(): List<Channel> {
    val local = PlaybackPositionStore.getAllInProgress(this).filter { it.mediaType == MediaType.SERIES }
    val server = jellyfinResumeItems.filter { it.mediaType == MediaType.SERIES }
    val serverIds = server.map { it.id }.toSet()
    return (server + local.filterNot { it.id in serverIds }).filterNot(::isAdultHomeItem)
}
