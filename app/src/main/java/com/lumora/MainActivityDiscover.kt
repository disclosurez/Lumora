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
    setGridSpan(binding.discoverGrid, discoverGridAdapter, discoverGridFocusUpTargetId())
    // setGridSpan only wires the layout manager/span; the adapter still has to be attached.
    binding.discoverGrid.adapter = discoverGridAdapter
    // The inline field isn't a real input (no platform IME on TV, and a focused field
    // with the IME suppressed is a dead end for the remote) - both the field and the
    // Search button open the on-screen-keyboard overlay instead.
    binding.discoverSearchField.setOnClickListener { showDiscoverSearchOverlay() }
    binding.discoverSearchButton.setOnClickListener { showDiscoverSearchOverlay() }
    binding.discoverFilterAll.setOnClickListener { selectDiscoverFilter(null) }
    binding.discoverFilterMovies.setOnClickListener { selectDiscoverFilter(MediaType.MOVIE) }
    binding.discoverFilterSeries.setOnClickListener { selectDiscoverFilter(MediaType.SERIES) }
    updateDiscoverFilterChipStyles()
}

/** Opens the Discover (TMDB) search overlay - the main search overlay's pattern, keys on the
 *  left and matches on the right, queried as the query changes. A poster can be opened straight
 *  from here; Submit takes the whole result set back to the Discover pane and closes.
 *  Dismissing leaves the query behind in the inline field. */
internal fun MainActivity.showDiscoverSearchOverlay() {
    if (activeSettingsOverlay != null || activeSearchOverlay != null) return
    val view = layoutInflater.inflate(R.layout.dialog_discover_search, null)
    val input = view.findViewById<EditText>(R.id.discoverSearchQuery)
    val keyboard = view.findViewById<com.lumora.ui.OnScreenKeyboard>(R.id.discoverSearchKeyboard)
    val submit = view.findViewById<View>(R.id.discoverSearchSubmit)
    val status = view.findViewById<TextView>(R.id.discoverSearchStatus)
    val resultsList = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.discoverSearchResults)
    applyPanelWidth(view.findViewById(R.id.discoverSearchPanel), R.dimen.search_panel_width)
    input.showSoftInputOnFocus = false

    // Fixed span, same as the main overlay: the grid shares the panel with the keyboard, so
    // overall screen width no longer describes the space it actually has.
    val span = resources.getInteger(R.integer.search_results_span)
    resultsList.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, span)
    // Results and their library badges as last published to the grid - what Submit and a
    // poster pick hand over to the Discover pane, so neither has to refetch what is already
    // on screen.
    var shown: List<Channel> = emptyList()
    var shownBadges: Map<String, String> = emptyMap()
    /** Which query [shown] belongs to - Submit pressed inside the debounce window would
     *  otherwise hand the pane the previous query's results. */
    var shownQuery: String? = null
    val resultsAdapter = com.lumora.adapter.PosterGridAdapter(
        badgeFor = { item -> shownBadges[item.id]?.let { it to R.color.primary } }
    ) { item ->
        // Picking a title also leaves the pane showing this search, so Back from the detail
        // screen lands on the results rather than on whatever was there before the overlay.
        activeSearchOverlay?.dismiss()
        binding.discoverSearchInput.setText(input.text.toString().trim())
        discoverTypeFilter = null
        updateDiscoverFilterChipStyles()
        discoverLibrarySources = shownBadges
        discoverGridAdapter.replaceAll(shown)
        setDiscoverStatus(null)
        onDiscoverItemClick(item)
    }
    resultsAdapter.spanCount = span
    // UP off the top row and LEFT off the first column both have to cross out of the
    // RecyclerView to the keyboard, which default focus search cannot do (see the adapter).
    resultsAdapter.topRowFocusUpTargetId = R.id.discoverSearchKeyboard
    resultsAdapter.leftFocusTarget = keyboard
    resultsAdapter.posterHeightDimen = R.dimen.search_poster_image_height
    resultsList.adapter = resultsAdapter

    var searchJob: Job? = null
    var pendingSearch: Runnable? = null
    fun showResults(query: String?, results: List<Channel>, badges: Map<String, String>, statusText: String?) {
        shown = results
        shownBadges = badges
        shownQuery = query
        resultsAdapter.replaceAll(results)
        resultsList.visibility = if (results.isEmpty()) View.GONE else View.VISIBLE
        status.text = statusText ?: ""
        status.visibility = if (statusText == null) View.GONE else View.VISIBLE
    }
    /** Runs [query] against TMDB and paints the grid. Cancelling the previous job is what
     *  keeps a slow earlier query from landing on top of a newer one's results. */
    fun runSearch(query: String) {
        searchJob?.cancel()
        status.text = getString(R.string.plug_searching_query, query)
        status.visibility = View.VISIBLE
        searchJob = scope.launch {
            val results = tmdbClient.search(query)
            // Same gate as loadDiscover(): with no stream-search plugin or scraper enabled a
            // TMDB-only title is a dead tile, so only titles the library already carries are
            // offered. Matching is the slow step, so it only runs when it decides something.
            val pluginEnabled = hasProviderlessSource()
            val visible = if (pluginEnabled) results else withContext(Dispatchers.Default) {
                results.filter { findCatalogMatches(it).isNotEmpty() }
            }
            showResults(
                query,
                visible,
                emptyMap(),
                when {
                    visible.isNotEmpty() -> null
                    results.isEmpty() -> getString(R.string.plug_no_results_for, query)
                    else -> getString(R.string.plug_enable_stream_plugin_browse)
                }
            )
            // Badges walk the whole catalogue once per tile, so they land after the posters
            // are already up - nothing on screen waits for them.
            val badges = discoverBadgesFor(visible)
            if (badges.isNotEmpty()) {
                shownBadges = badges
                resultsAdapter.notifyItemRangeChanged(0, resultsAdapter.itemCount)
            }
        }
    }
    /** Debounced so a remote held on a letter doesn't fire a TMDB request per key. Under two
     *  characters there is nothing worth asking for, so the grid goes back to its idle state. */
    fun scheduleSearch(query: String) {
        pendingSearch?.let { mainHandler.removeCallbacks(it) }
        if (query.length < 2) {
            searchJob?.cancel()
            showResults(null, emptyList(), emptyMap(), getString(R.string.type_to_search))
            return
        }
        val runnable = Runnable { runSearch(query) }
        pendingSearch = runnable
        mainHandler.postDelayed(runnable, 250)
    }
    input.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            scheduleSearch(s?.toString()?.trim().orEmpty())
        }
    })
    // Re-opening the overlay starts from whatever is currently being browsed, so refining a
    // query means editing it rather than retyping it letter by letter on a remote. Set after
    // the watcher is attached so the existing query is searched again on open too.
    input.setText(binding.discoverSearchInput.text.toString())

    keyboard.onKey = { ch -> input.setText(input.text.toString() + ch) }
    keyboard.onBackspace = { input.setText(input.text.toString().dropLast(1)) }
    keyboard.onClear = { input.setText("") }
    // DOWN off the bottom key row (SHIFT/SPACE/DEL/CLEAR) has only Submit below it - route
    // it there explicitly rather than leaving the crossing out of the keyboard's own focus
    // tree to default focus search.
    keyboard.bottomRowDownTarget = submit
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
    submit.setOnClickListener {
        val query = input.text.toString().trim()
        overlay.dismiss()
        binding.discoverSearchInput.setText(query)
        discoverTypeFilter = null
        updateDiscoverFilterChipStyles()
        when {
            // Submitting an emptied query is how a search is undone - it puts Discover back
            // on Trending, the same state the Trending chip gives. Doing nothing there left
            // the grid showing the old results with an empty field above them.
            query.isEmpty() -> loadDiscover(null)
            // The overlay already holds this query's results; handing them straight over
            // saves a second identical round trip to TMDB.
            shownQuery == query && shown.isNotEmpty() -> {
                discoverLibrarySources = shownBadges
                discoverGridAdapter.replaceAll(shown)
                setDiscoverStatus(null)
            }
            else -> loadDiscover(query)
        }
    }
    val tabBarWasVisible = binding.tabBar.visibility == View.VISIBLE
    if (tabBarWasVisible) binding.tabBar.visibility = View.GONE
    // searchContainer is a weighted sibling of discoverContent in the same vertical
    // LinearLayout, not a window over it: leaving the Discover pane visible splits the
    // column between the two, so the panel only got the bottom half and everything under
    // the top keyboard rows - the rest of the letters, SHIFT/SPACE/DEL/CLEAR, and Submit -
    // was laid out past the bottom of the screen and unreachable. The main search overlay
    // hides the same panes for the same reason (showSearchDialog).
    binding.discoverContent.visibility = View.GONE
    binding.emptyState.visibility = View.GONE
    overlay.setOnDismissListener {
        searchKeyHandler = null
        activeSearchOverlay = null
        // The overlay's grid is gone; a debounced or in-flight query would only paint a
        // detached adapter (and, on a pick, race the pane's own tiles).
        pendingSearch?.let { mainHandler.removeCallbacks(it) }
        searchJob?.cancel()
        resultsAdapter.cancelPendingWork()
        if (tabBarWasVisible) binding.tabBar.visibility = View.VISIBLE
        binding.discoverContent.visibility = View.VISIBLE
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
    binding.discoverGrid.post {
        setGridSpan(binding.discoverGrid, discoverGridAdapter, discoverGridFocusUpTargetId())
    }
    updateDiscoverFilterChipStyles()
    if (!tmdbClient.hasKey()) {
        setDiscoverStatus(getString(R.string.plug_discover_unavailable))
    } else if (discoverGridAdapter.itemCount == 0) {
        // Re-entering Discover with a chip already picked (Movies/Series) keeps browsing
        // that instead of silently falling back to Trending.
        when (val type = discoverTypeFilter) {
            null -> loadDiscover(null)
            else -> loadDiscoverByType(type)
        }
    }
    updateCatchupTabVisibility()
    applyStatus()
}

/** Series/Films tab content for a no-provider, plugin-only setup - [index] 1 for Series, 2
 *  for Films. Reuses the shelf tabs' own grid adapters (already wired to playItem, which for
 *  a MOVIE/SERIES channel opens the same detail screen Discover's own click handler does -
 *  the two are only ever different when a catalog match exists, and there is none here since
 *  there's no provider catalog to match against). No category sidebar: there's no
 *  provider-derived category data to build one from, same as Discover itself. */
internal fun MainActivity.showDiscoverBackedCatalogTab(index: Int) {
    val wantedType = if (index == 1) MediaType.SERIES else MediaType.MOVIE
    val adapter = if (index == 1) seriesGridAdapter else filmsGridAdapter
    val recycler = if (index == 1) binding.seriesContent else binding.filmsContent
    val tabId = if (index == 1) R.id.tabSeries else R.id.tabFilms
    applySidebarVisibility(tabWantsSidebar = false)
    setGridSpan(recycler, adapter, tabId)
    recycler.adapter = adapter
    binding.contentRow.visibility = View.VISIBLE
    setStatus(getString(R.string.loading), visible = true)
    scope.launch {
        if (!tmdbClient.hasKey()) {
            adapter.replaceAll(emptyList())
            setStatus(getString(R.string.plug_discover_unavailable), visible = true)
            return@launch
        }
        val results = fetchPopularPaged(wantedType)
        // The user may have left this tab (or the whole no-provider state may have
        // changed under them, e.g. a provider was just added) while the fetch was in
        // flight - a stale response landing after that must not paint over whatever is
        // on screen now.
        if (activeTab != index || showingHome || showingDiscover || showingDownloads || hasProviderEnabled()) {
            setStatus("", visible = false)
            return@launch
        }
        adapter.replaceAll(results)
        setStatus(if (results.isEmpty()) getString(R.string.plug_couldnt_load_titles) else "", visible = results.isEmpty())
        applyStatus()
        focusFirstItemWhenReady(recycler)
    }
}

/** TMDB's popularity ranking for one type, paged 5 deep (trending/week's one mixed page
 *  gives only ~10-per-type - not enough to scroll) - shared by the Series/Films no-provider
 *  fallback and Discover's own Movies/Series filter chips. */
internal suspend fun MainActivity.fetchPopularPaged(type: MediaType): List<Channel> {
    val pages = (1..5).map { page ->
        scope.async { if (type == MediaType.SERIES) tmdbClient.popularTv(page) else tmdbClient.popularMovies(page) }
    }
    // TMDB's popular ranking can shift rank between two page requests, which lands the
    // same title on two consecutive pages under the same id - dedupe that first.
    // Regional versions of the same format (Paradise Hotel US/Sweden/Norway, all
    // separately popular at once) carry different ids but read as the same duplicated
    // tile with no way to tell them apart - the grid has no year/country subtitle to
    // disambiguate them the way the detail screen would - so fold those together too,
    // keeping the highest-ranked (first) copy of each title.
    return pages.awaitAll().flatten()
        .distinctBy { it.id }
        .distinctBy { it.name.trim().lowercase(Locale.US) }
}

/** Loads trending (null query) or search results into the Discover grid. */
internal fun MainActivity.loadDiscover(query: String?) {
    if (!tmdbClient.hasKey()) return
    discoverSearchJob?.cancel()
    setDiscoverStatus(if (query == null) getString(R.string.plug_loading_trending) else getString(R.string.plug_searching_query, query))
    discoverSearchJob = scope.launch {
        val results = if (query == null) tmdbClient.trending() else tmdbClient.search(query)
        // Without a stream-search plugin (the torrent plugin being the common one), a
        // TMDB-only title is a dead tile - its dialog offers nothing but a trailer.
        // Drop anything that isn't already in the library; with a plugin enabled the
        // plugin can play every title, so nothing gets filtered.
        val pluginEnabled = hasProviderlessSource()
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
                results.isEmpty() -> if (query == null) getString(R.string.plug_couldnt_load_titles) else getString(R.string.plug_no_results_for, query)
                else -> getString(R.string.plug_enable_stream_plugin_browse)
            }
        )
    }
}

/** Loads one of Discover's Movies/Series filter chips - [type]'s own paginated Popular
 *  ranking, same source and gates (English-only, News dropped, no-plugin library filter)
 *  as loadDiscover() uses for trending/search. */
internal fun MainActivity.loadDiscoverByType(type: MediaType) {
    if (!tmdbClient.hasKey()) return
    discoverSearchJob?.cancel()
    setDiscoverStatus(if (type == MediaType.SERIES) getString(R.string.plug_loading_series) else getString(R.string.plug_loading_movies))
    discoverSearchJob = scope.launch {
        val results = fetchPopularPaged(type)
        val pluginEnabled = hasProviderlessSource()
        val visible = if (pluginEnabled) results else withContext(Dispatchers.Default) {
            results.filter { findCatalogMatches(it).isNotEmpty() }
        }
        discoverGridAdapter.replaceAll(visible)
        loadDiscoverLibraryBadges(visible)
        setDiscoverStatus(
            when {
                visible.isNotEmpty() -> null
                results.isEmpty() -> getString(R.string.plug_couldnt_load_titles)
                else -> getString(R.string.plug_enable_stream_plugin_browse)
            }
        )
    }
}

/** Switches Discover's active filter chip and reloads the grid accordingly - [type] null is
 *  Trending (and what a submitted search runs under; picking it clears any typed query so
 *  the grid isn't left showing stale search results under the wrong chip). */
internal fun MainActivity.selectDiscoverFilter(type: MediaType?) {
    discoverTypeFilter = type
    updateDiscoverFilterChipStyles()
    if (type == null) {
        binding.discoverSearchInput.setText("")
        loadDiscover(null)
    } else {
        loadDiscoverByType(type)
    }
}

internal fun MainActivity.updateDiscoverFilterChipStyles() {
    val active = discoverTypeFilter
    binding.discoverFilterAll.isSelected = active == null
    binding.discoverFilterMovies.isSelected = active == MediaType.MOVIE
    binding.discoverFilterSeries.isSelected = active == MediaType.SERIES
    discoverGridAdapter.topRowFocusUpTargetId = discoverGridFocusUpTargetId()
}

/** Where D-pad UP off the grid's top row lands. The Trending/Films/Series chips used to be
 *  skipped entirely (UP went straight to the Discover tab), so switching type meant going up
 *  to the tab bar and back down twice - the chip row is the natural stop above the grid, and
 *  landing on the *active* chip means RIGHT/LEFT immediately reaches the other two. */
internal fun MainActivity.discoverGridFocusUpTargetId(): Int = when (discoverTypeFilter) {
    MediaType.MOVIE -> R.id.discoverFilterMovies
    MediaType.SERIES -> R.id.discoverFilterSeries
    else -> R.id.discoverFilterAll
}

/** Works out which of the user's sources already carry each visible Discover title, then
 *  repaints the grid so the tiles show it. Deliberately off the load path: it walks the whole
 *  catalogue once per tile, and the grid is useful long before the badges land. */
internal fun MainActivity.loadDiscoverLibraryBadges(items: List<Channel>) {
    discoverBadgeJob?.cancel()
    discoverBadgeJob = scope.launch {
        val badges = discoverBadgesFor(items)
        discoverLibrarySources = badges
        if (badges.isNotEmpty()) discoverGridAdapter.notifyItemRangeChanged(0, discoverGridAdapter.itemCount)
    }
}

/** Tile id -> "which of the user's sources already carry this title", for [items]. Shared by
 *  the Discover pane's badge pass and the search overlay's own grid, which shows the same
 *  tiles before they ever reach the pane. Runs on Dispatchers.Default: it walks the whole
 *  catalogue once per tile. */
internal suspend fun MainActivity.discoverBadgesFor(items: List<Channel>): Map<String, String> =
    withContext(Dispatchers.Default) {
        items.mapNotNull { item ->
            val versions = catalogVersionsFor(findCatalogMatches(item))
            if (versions.isEmpty()) return@mapNotNull null
            // Which servers actually carry it, named individually - "my library" would be
            // a worse badge than the server's name when both are configured and only one
            // has the title.
            val servers = listOfNotNull(
                "Jellyfin".takeIf { versions.any { v -> v.isJellyfin } },
                "Plex".takeIf { versions.any { v -> v.isPlex } }
            )
            val iptv = versions.any { !it.isOwnLibrary }
            item.id to when {
                servers.isNotEmpty() && iptv -> servers.joinToString(" + ") + " + IPTV"
                servers.isNotEmpty() -> servers.joinToString(" + ")
                else -> "IPTV"
            }
        }.toMap()
    }

internal fun MainActivity.setDiscoverStatus(text: String?) {
    binding.discoverStatus.text = text ?: ""
    binding.discoverStatus.visibility = if (text == null) View.GONE else View.VISIBLE
}

/** Discover pick opens an info screen: overview + poster, then either play a matching catalog
 *  item (if this title is already served by a provider) or find a torrent stream for it. */
/**
 * Opens a Discover title on the same detail screen everything else uses.
 *
 * It used to build its own dialog - backdrop, a few labels, and Open/Find stream/Trailer
 * buttons - which meant a title reached from Discover looked and behaved differently from the
 * identical title reached from the library, and only the "Open" button led anywhere familiar.
 *
 * When the library already has the title, the library copy is what opens, with the whole set of
 * matches so the version chips can switch between sources. Otherwise the TMDB entry itself opens:
 * the same screen, minus a Play button it has no URL for, with Find Stream as the action.
 */
internal fun MainActivity.onDiscoverItemClick(item: Channel) {
    // Every copy, not the best one: the detail screen needs the whole set for its version chips.
    val versions = catalogVersionsFor(findCatalogMatches(item))
    val match = versions.firstOrNull()
    if (match != null) showContentDetail(match, versions.takeIf { it.size > 1 })
    else showContentDetail(item)
}

internal fun MainActivity.startDiscoverStreamSearch(item: Channel) {
    if (!canFindStream(item)) {
        Toast.makeText(
            this,
            getString(R.string.plug_enable_stream_plugin_or_sites),
            Toast.LENGTH_LONG
        ).show()
        return
    }
    val search: (Int?, Int?) -> Unit = { season, episode ->
        showFindStreamDialog(item, season, episode)
    }
    if (item.mediaType == MediaType.SERIES) showSeriesEpisodePicker(item, search)
    else search(null, null)
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
            name.startsWith("$target ") && isIgnorableTitleSuffix(name.removePrefix("$target ")) -> 1
            else -> continue
        }
        val yearBonus = if (item.year != null && candidate.year == item.year) 0 else 100
        val extra = (name.length - target.length).coerceIn(0, 99)
        val sourceBonus = if (candidate.isOwnLibrary) 0 else 200
        scored += (rank * 10_000 + yearBonus + sourceBonus + extra) to candidate
    }
    return scored.sortedBy { it.first }.map { it.second }.distinctBy { it.id.ifBlank { it.url } }
}

/** The full set of copies to offer for [match] - the matches Discover found, plus whatever
 *  the duplicate-grouping pass already knows about (which is keyed by the group's
 *  representative, so a match that is a *member* of a group finds nothing by direct lookup
 *  and has to be searched for). Deduped, own-library copies first. */
internal fun MainActivity.catalogVersionsFor(matches: List<Channel>): List<Channel> {
    val versions = if (matches.firstOrNull()?.mediaType == MediaType.SERIES) seriesVersions else filmVersions
    val out = LinkedHashMap<String, Channel>()
    for (match in matches) {
        val key = match.id.ifBlank { match.url }
        out.putIfAbsent(key, match)
        val group = versions[match.id] ?: versions.values.firstOrNull { g -> g.any { it.id == match.id } }
        group?.forEach { out.putIfAbsent(it.id.ifBlank { it.url }, it) }
    }
    return out.values.sortedBy { if (it.isOwnLibrary) 0 else 1 }
}

/** Tokens a catalogue appends to a title without changing which film it is: a bare release
 *  year, and the edition/quality/language decoration cleanVodTitle didn't already strip. */
private val IGNORABLE_TITLE_SUFFIX_WORDS = setOf(
    "hd", "fhd", "uhd", "sd", "4k", "8k", "3d", "hdr", "sdr", "hevc", "h264", "h265", "raw",
    "imax", "remastered", "restored", "extended", "uncut", "unrated", "dc", "directors", "cut",
    "multi", "multisub", "multisubs", "sub", "subs", "subbed", "dub", "dubbed", "eng", "vip",
    "atmos", "dts", "dolby", "bluray", "web", "webdl", "webrip"
)

/** True when everything trailing the target title is decoration rather than more title.
 *
 *  Deliberately an allowlist. This used to be the inverse - accept any remainder that didn't
 *  look like a sequel marker ("2", "Part II") - which meant a target that is a *prefix* of a
 *  longer, unrelated film matched it: Discover's "The Last House" reported the provider's
 *  "The Last House on the Left" as the same film. Whole words the list doesn't know are
 *  title words, so they reject the match. */
private fun isIgnorableTitleSuffix(remainder: String): Boolean {
    val tokens = remainder.split(' ').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return false
    return tokens.all { token ->
        val year = token.toIntOrNull()
        if (year != null) year in 1900..2100 else token in IGNORABLE_TITLE_SUFFIX_WORDS
    }
}

internal fun MainActivity.normalizeMatchTitle(title: String): String =
    title.lowercase(Locale.US).replace(Regex("\\(\\d{4}\\)"), " ")
        .replace(Regex("[^a-z0-9]+"), " ").trim()

/**
 * Fetches the show's seasons from TMDB, then lets the user pick season → episode.
 *
 * [onPick] receives the chosen season/episode, or nulls when there is nothing to choose from
 * (no TMDB id, or TMDB has no season data) and the title has to be searched whole. Takes a
 * callback rather than a [PluginScript] because both stream sources need this same picker - an
 * installed plugin and the built-in site scrapers - and only the step after it differs.
 */
internal fun MainActivity.showSeriesEpisodePicker(
    item: Channel,
    onPick: (season: Int?, episode: Int?) -> Unit,
) {
    val tvId = item.id.substringAfterLast(':').toIntOrNull()
    if (tvId == null) { onPick(null, null); return }
    val loading = AlertDialog.Builder(this)
        .setTitle(item.name)
        .setMessage(getString(R.string.plug_loading_episodes))
        .setNegativeButton(getString(R.string.cancel), null)
        .create()
    loading.show()
    scope.launch {
        val seasons = tmdbClient.tvSeasons(tvId)
        loading.dismiss()
        if (seasons.isEmpty()) {
            // No season data - fall back to searching the title as a whole.
            onPick(null, null)
            return@launch
        }
        val seasonLabels = seasons.map { "${it.name} (${it.episodeCount} eps)" }.toTypedArray()
        AlertDialog.Builder(this@showSeriesEpisodePicker)
            .setTitle(getString(R.string.plug_choose_season, item.name))
            .setItems(seasonLabels) { _, si ->
                val season = seasons[si]
                val epLabels = (1..season.episodeCount).map { getString(R.string.plug_episode, it) }.toTypedArray()
                AlertDialog.Builder(this@showSeriesEpisodePicker)
                    .setTitle(season.name)
                    .setItems(epLabels) { _, ei -> onPick(season.number, ei + 1) }
                    .setNegativeButton(getString(R.string.plug_back)) { _, _ -> showSeriesEpisodePicker(item, onPick) }
                    .show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
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
    }
}

/** Resolves the series a Home-tile episode belongs to: exact categoryId (the series id
 *  Xtream parseEpisode and the Jellyfin/Plex toChannel both stamp on episodes) match through the
 *  catalog first, then the "{series} · {episode}" name-prefix fallback for snapshots that
 *  predate categoryId. Null if unresolvable - callers fall back to direct play. */
internal fun MainActivity.resolveHomeTileSeries(channel: Channel): Channel? {
    // Exact series-id match. Ids are provider-scoped (Xtream series id, Jellyfin item id,
    // Plex rating key), so cross-matching is impossible - matching the source flags is the
    // only guard needed, with sourceProviderId compared only when the snapshot carries one
    // (older saves don't).
    channel.categoryId?.takeIf { it.isNotBlank() }?.let { id ->
        allChannels.firstOrNull {
            it.mediaType == MediaType.SERIES && it.id == id &&
                it.isJellyfin == channel.isJellyfin && it.isPlex == channel.isPlex &&
                (channel.sourceProviderId == null || it.sourceProviderId == channel.sourceProviderId)
        }?.let { return it }
    }
    // Name-prefix fallback for old snapshots: "Series Name · S01E02 · Title", longest
    // name wins. Same-provider guard only when the snapshot knows its provider.
    return allChannels
        .filter {
            it.mediaType == MediaType.SERIES &&
                it.isJellyfin == channel.isJellyfin && it.isPlex == channel.isPlex &&
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
    // A media server's chain comes from the server itself (getEpisodes/getSeasons), not
    // Xtream getSeriesFull - and those tiles resolve to the series detail page anyway, so
    // this fallback never needs to build a Jellyfin or Plex queue.
    if (channel.isOwnLibrary) return
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
 *  one clear empties the row everywhere. Media-server resume lives on the server, so those
 *  entries are dropped there too (best effort) and removed from memory immediately. Also
 *  un-hides the CW shelf so future watching isn't stuck behind a stale hide flag. */
internal fun MainActivity.clearContinueWatching() {
    PlaybackPositionStore.clearAll(this)
    clearUpNextMemo()
    // Grouped by the account each entry came from: with several media servers configured, an
    // item id only means anything to its own server, and clearing it against another would at
    // best 404 and at worst clear an unrelated title that happens to share the id.
    val jellyfinIdsByServer = jellyfinResumeItems.groupBy(
        { it.sourceProviderId.orEmpty() },
        { com.lumora.util.rawMediaItemId(it.id) }
    )
    val plexIdsByServer = plexResumeItems.groupBy(
        { it.sourceProviderId.orEmpty() },
        { com.lumora.util.rawMediaItemId(it.id) }
    )
    jellyfinResumeByServer.clear()
    plexResumeByServer.clear()
    for ((serverId, ids) in jellyfinIdsByServer) {
        val client = jellyfinClients[serverId] ?: jellyfinClients.values.singleOrNull() ?: continue
        scope.launch(Dispatchers.IO) { ids.forEach { id -> runCatching { client.clearUserData(id) } } }
    }
    for ((serverId, ids) in plexIdsByServer) {
        val client = plexClients[serverId] ?: plexClients.values.singleOrNull() ?: continue
        scope.launch(Dispatchers.IO) { ids.forEach { id -> runCatching { client.clearUserData(id) } } }
    }
    getHiddenHomeShelves().let { if (it.remove(getString(R.string.category_continue_watching))) prefs.edit().putStringSet("hidden_home_shelves", it).apply() }
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
    return ordered.firstOrNull { !isItemWatched(it) }
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
        .filterNot { it.isOwnLibrary } // server-side "Next Up" shelf already covers these
        .mapNotNull { it.categoryId?.takeIf { id -> id !in upNextTiles && id !in upNextFetching } }
        .take(MAX_UP_NEXT_SERIES)
    if (pending.isNotEmpty()) fetchUpNextSeries(pending)
    // Trail order = most recently completed first; present the memoized tiles in that
    // order (LinkedHashMap insertion order is fetch-completion order, which is arbitrary).
    return trails.mapNotNull { t -> upNextTiles[t.categoryId] }
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

    // The media servers' own resume lists lead Continue Watching: they know about playback
    // from every other client, which a purely local position store never can. Local
    // entries follow, minus anything a server already covered (same item, one card).
    val localContinue = PlaybackPositionStore.getAllInProgress(this)
    val serverContinue = jellyfinResumeItems + plexResumeItems
    // Up-next series tiles: series whose watched trail ends at a completed episode have
    // no in-progress entry, so they'd otherwise drop out of Continue Watching entirely.
    // buildUpNextSeriesTiles returns what's already resolved and kicks the async fetch
    // for the rest - the row fills in as episodes arrive.
    val upNext = buildUpNextSeriesTiles().filterNot(::isAdultHomeItem)
    // Labelled and postered from the parent series - see continueWatchingTiles. Applied after
    // the dedupe so the catalogue pass runs over the final row, not the raw merge.
    val continueItems = continueWatchingTiles(
        (serverContinue + localContinue + upNext)
            .distinctBy { it.id.ifBlank { it.url } }
            .filterNot(::isAdultHomeItem)
    )
    if (continueItems.isNotEmpty()) shelves.add(ContentShelf(getString(R.string.category_continue_watching), continueItems))

    // "Next Up" is the row that makes a series library usable - the next unwatched episode
    // of everything in flight, straight from the server's own tracking.
    val nextUpItems = (jellyfinNextUpItems + plexNextUpItems)
        .distinctBy { it.id.ifBlank { it.url } }
        .filterNot(::isAdultHomeItem)
    if (nextUpItems.isNotEmpty()) shelves.add(ContentShelf(getString(R.string.category_next_up), nextUpItems))

    val recentItems = RecentlyPlayedStore.getRecentIds(this)
        .mapNotNull { id -> liveChannels.firstOrNull { it.id == id } }
        .filterNot(::isAdultHomeItem)
    if (recentItems.isNotEmpty()) shelves.add(ContentShelf(getString(R.string.category_recently_played), recentItems))

    // Favourited live channels get their own Home row. Long-pressing a channel in the
    // guide has always favourited it, but the result was only ever visible as the
    // Favourites category inside Live TV - Home, the screen the app opens on, showed
    // nothing at all, so the favourites looked like they hadn't saved.
    val favChannelIds = FavoritesStore.getFavoriteChannelIds(this)
    val favChannels = liveChannels.filter { it.id in favChannelIds }.filterNot(::isAdultHomeItem)
    if (favChannels.isNotEmpty()) shelves.add(ContentShelf(getString(R.string.category_favourite_channels), favChannels))

    // One shelf for both, since favourite VOD is stored in a single set (see
    // FavoritesStore.KEY_FAVORITE_SERIES) - a favourited film used to be saved and then
    // never shown anywhere, because only seriesList was searched for the ids.
    val favIds = FavoritesStore.getFavoriteSeriesIds(this)
    val favItems = (seriesList + filmList).filter { it.id in favIds }.filterNot(::isAdultHomeItem)
    if (favItems.isNotEmpty()) shelves.add(ContentShelf(getString(R.string.category_favourites), favItems))

    return shelves.filter { it.title !in hidden }
}

/** Series-only Continue Watching for the Series tab - same merge as the Home shelf
 *  (server resume list first, then local in-progress entries minus anything the server
 *  already covered), filtered down to series and adult-dropped. Shared by the Series
 *  sidebar row, its content grid, and the Series poster shelf. */
internal fun MainActivity.seriesContinueItems(): List<Channel> {
    val local = PlaybackPositionStore.getAllInProgress(this).filter { it.mediaType == MediaType.SERIES }
    val server = (jellyfinResumeItems + plexResumeItems).filter { it.mediaType == MediaType.SERIES }
    val serverIds = server.map { it.id }.toSet()
    return continueWatchingTiles(
        (server + local.filterNot { it.id in serverIds }).filterNot(::isAdultHomeItem)
    )
}

/**
 * Re-labels episode tiles with the show they belong to, and gives them the show's artwork:
 * "SAS Rogue Heroes · S03E03", over the series poster.
 *
 * A Continue Watching entry is a snapshot of the *episode* that was playing, so the tile
 * carried whatever the provider called that episode - frequently a bare "S03E03", or an
 * episode title with no hint of the show - over an episode still, which for most providers is
 * nothing at all. Neither says which series is being offered, which is the one thing the row
 * exists to answer.
 *
 * Only the display fields are rewritten. The id, url, categoryId and episodeNum are the
 * originals, so clicking still resolves to the series page (or resumes the episode) exactly as
 * before, and the playback-position key is untouched.
 *
 * Anything that isn't a resolvable episode is returned as it came: a film, a top-level series
 * entry, or an episode whose series isn't in the catalogue (a provider that never stamped the
 * parent id, or a show since removed). Those keep the name the snapshot was saved with rather
 * than getting a half-built label.
 */
/** Anywhere in a name, not just at the front - see [tileSeasonNumber]. */
internal val ANY_SEASON_MARKER_REGEX = Regex("""(?i)\bS(\d{1,2})E\d{1,3}\b""")

/**
 * The season number to print on a Continue Watching tile, or null if nothing states one.
 *
 * [seasonNumberOf] is the shared helper, but its regex is anchored to the start of the name,
 * so it only reads providers that lead with the marker ("S03E03 · Title"). Plenty bury it
 * instead ("SAS Rogue Heroes - S03E03 - Title"), so fall back to finding it anywhere. That
 * looser match is confined to this label: a false positive here misprints a tile, whereas
 * [seasonNumberOf] feeds episode-queue and find-stream decisions and should stay strict.
 *
 * Jellyfin and Plex episodes state the season as a field that [Channel] has nowhere to keep,
 * and their names carry no marker at all - those tiles get a bare episode number.
 */
internal fun tileSeasonNumber(episode: Channel): Int? =
    seasonNumberOf(episode)
        ?: ANY_SEASON_MARKER_REGEX.find(episode.name)?.groupValues?.getOrNull(1)?.toIntOrNull()

internal fun MainActivity.continueWatchingTiles(items: List<Channel>): List<Channel> {
    val wanted = items.mapNotNullTo(HashSet()) { item ->
        item.categoryId?.takeIf { it.isNotBlank() && item.episodeNum != null }
    }
    if (wanted.isEmpty()) return items
    // One pass over the catalogue for the whole row. resolveHomeTileSeries scans allChannels
    // per call, which is fine for the single lookup a click does but not for a shelf rebuild:
    // a six-figure catalogue times a row of tiles is millions of comparisons on the main
    // thread. Candidates are grouped by id because the source guards below still have to run -
    // ids are provider-scoped, so collisions are only possible across providers.
    val candidates = HashMap<String, MutableList<Channel>>()
    for (ch in allChannels) {
        if (ch.mediaType != MediaType.SERIES) continue
        if (ch.id !in wanted) continue
        candidates.getOrPut(ch.id) { mutableListOf() }.add(ch)
    }
    if (candidates.isEmpty()) return items

    return items.map { item ->
        val episode = item.episodeNum ?: return@map item
        val seriesId = item.categoryId?.takeIf { it.isNotBlank() } ?: return@map item
        val series = candidates[seriesId]?.firstOrNull {
            it.isJellyfin == item.isJellyfin && it.isPlex == item.isPlex &&
                (item.sourceProviderId == null || it.sourceProviderId == item.sourceProviderId)
        } ?: return@map item
        val seriesName = cleanVodTitle(series.name).ifBlank { return@map item }
        val marker = tileSeasonNumber(item)
            ?.let { season -> "S%02dE%02d".format(season, episode) }
            ?: "E%02d".format(episode)
        item.copy(
            name = "$seriesName · $marker",
            posterUrl = series.posterUrl ?: series.logoUrl ?: item.posterUrl,
            logoUrl = series.logoUrl ?: series.posterUrl ?: item.logoUrl
        )
    }
}

/**
 * The TMDB show id behind [item], or null when there is no way to be sure of one.
 *
 * The trap here is that "the trailing number in the id" is *not* a TMDB id for anything except
 * a Discover title. A panel's series id is its own primary key - "1234" - and handing that to
 * TMDB does not fail, it happily returns a completely different show (1234 is "Clapperboard",
 * one season, no episodes). That is how a library series ended up listing a stranger's season
 * structure - one episode per season - rather than its own.
 *
 * So the id is trusted only when it is declared as a TMDB one, either as a `tmdb:tv:N` Discover
 * id or as a `tmdb_id` the panel itself sent. Anything else is matched by title and year, the
 * same lookup the episode-metadata enrichment already does.
 */
private suspend fun MainActivity.tmdbTvIdFor(item: Channel): Int? {
    tmdbTypeAndId(item.id)?.let { (type, id) -> if (type == "tv") return id }
    item.tmdbTypeAndId()?.let { (type, id) -> if (type == "tv") return id }
    return runCatching {
        tmdbClient.resolveId(cleanVodTitle(item.name), item.tmdbYear(), isSeries = true)
            ?.takeIf { it.first == "tv" }?.second
    }.getOrNull()
}

/**
 * Seasons and episodes for a title the library does not have, built from TMDB.
 *
 * A Discover series has no provider behind it, so `loadSeriesContent` finds nothing and the
 * detail screen used to say "No episodes found" - accurate about the library, useless to the
 * user, since Find Stream could have played any of those episodes.
 *
 * The Channels produced here are placeholders: they carry the episode's number, title and still,
 * and deliberately no `url`, because there is no stream until a source is found for that specific
 * episode. [onEpisodeClick] in showContentDetail routes a URL-less episode to Find Stream.
 *
 * Season 0 (specials) is dropped - TMDB lists it first, which would otherwise make it the season
 * the screen opens on.
 */
internal suspend fun MainActivity.tmdbSeasonsFor(item: Channel): List<Pair<String, List<Channel>>> {
    val tvId = tmdbTvIdFor(item) ?: return emptyList()
    val seasons = runCatching { tmdbClient.tvSeasons(tvId) }.getOrElse { emptyList() }
        .filter { it.number > 0 && it.episodeCount > 0 }
    if (seasons.isEmpty()) return emptyList()

    return seasons.map { season ->
        // Episode titles are a second request per season, so they are fetched rather than
        // guessed - a list of "Episode 1..10" is not much better than the empty state.
        val details = runCatching { tmdbClient.tvEpisodes(tvId, season.number) }
            .getOrElse { emptyMap() }
        val episodes = (1..season.episodeCount).map { number ->
            val ep = details[number]
            Channel(
                id = "${item.id}:s${season.number}e$number",
                name = ep?.name?.takeIf { it.isNotBlank() }
                    ?.let { "E%02d · %s".format(number, it) }
                    ?: "E%02d".format(number),
                // No URL on purpose - see this function's kdoc.
                url = "",
                posterUrl = ep?.stillUrl ?: item.posterUrl,
                backdropUrl = item.backdropUrl,
                mediaType = MediaType.SERIES,
                episodeNum = number,
                // The air date is what tells a placeholder for an episode nobody carries yet
                // apart from one that simply isn't in the library - see util.isUnreleasedEpisode.
                releaseDate = ep?.airDate,
                description = ep?.overview,
                categoryName = item.name,
                group = item.group,
            )
        }
        // TMDB's own name for the season, but only when it agrees with the number. Most shows
        // name seasons "Season 3"; an anthology names them after the case ("Murder in the Rocky
        // Mountains"), and a few name them for the year. Those went on the chip verbatim, so a
        // series showed "Season 1, Season 2, Season 4, Murder in the Rocky Mountains" - and
        // since mergeMissingEpisodesFromTmdb reads the season number back out of this label,
        // a nameless number meant null: sorted last, and every named season colliding on the
        // same null key.
        val labelNumber = Regex("""\d+""").find(season.name)?.value?.toIntOrNull()
        val label = if (labelNumber == season.number) season.name
            else getString(R.string.list_season_number, season.number)
        label to episodes
    }
}

/**
 * Fills the gaps in a provider's episode list from TMDB.
 *
 * A provider carrying a series is not a promise that it carries all of it - panels routinely
 * list one or two episodes of a season and nothing else. Preferring the library wholesale meant
 * those seasons displayed as complete when they were a fraction, with no hint the rest existed
 * and no way to reach them.
 *
 * So the two are merged rather than one chosen: an episode the provider has keeps its real URL
 * and plays directly, and every number TMDB knows about that the provider is missing is added as
 * a URL-less placeholder that routes to Find Stream. Seasons TMDB has and the provider does not
 * are appended whole.
 *
 * Returns [librarySeasons] unchanged when TMDB knows nothing, so a failed lookup can only cost
 * the extra episodes, never the ones that were already there.
 */
internal suspend fun MainActivity.mergeMissingEpisodesFromTmdb(
    item: Channel,
    librarySeasons: List<Pair<String, List<Channel>>>,
): List<Pair<String, List<Channel>>> {
    val tmdbSeasons = tmdbSeasonsFor(item)
    if (tmdbSeasons.isEmpty()) return librarySeasons

    /** "Season 2" / "S2" / "2" all have to line up with TMDB's own label. */
    fun seasonNumber(label: String): Int? = Regex("""\d+""").find(label)?.value?.toIntOrNull()

    val byNumber = librarySeasons.associateBy { seasonNumber(it.first) }
    val merged = tmdbSeasons.map { (tmdbLabel, tmdbEpisodes) ->
        val number = seasonNumber(tmdbLabel)
        val library = byNumber[number]?.second.orEmpty()
        if (library.isEmpty()) return@map tmdbLabel to tmdbEpisodes
        val have = library.mapNotNull { it.episodeNum }.toSet()
        val filled = (library + tmdbEpisodes.filter { it.episodeNum !in have })
            .sortedBy { it.episodeNum ?: Int.MAX_VALUE }
        (byNumber[number]?.first ?: tmdbLabel) to filled
    }
    // Anything the provider has that TMDB does not know about must not be dropped.
    val covered = merged.mapNotNull { seasonNumber(it.first) }.toSet()
    val extras = librarySeasons.filter { seasonNumber(it.first) !in covered }
    return (merged + extras).sortedBy { seasonNumber(it.first) ?: Int.MAX_VALUE }
}
