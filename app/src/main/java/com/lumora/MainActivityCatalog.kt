package com.lumora

import android.view.View
import android.widget.*
import com.lumora.adapter.DYNAMIC_BUCKET_ID_PREFIX
import com.lumora.cache.DerivedCache
import com.lumora.cache.FavoritesStore
import com.lumora.model.CategoryFilter
import com.lumora.model.Channel
import com.lumora.model.ContentShelf
import com.lumora.model.MediaType
import com.lumora.util.newestByDate
import com.lumora.util.isAdultCategory
import com.lumora.util.groupDuplicateMovies
import com.lumora.util.groupDuplicateSeries
import com.lumora.util.groupLiveQualityVersions
import com.lumora.util.isNonEnglishTitle
import com.lumora.util.withResolvedYear
import kotlinx.coroutines.*
import java.util.Locale

// ── Catalog classification, derived Live/VOD halves & shelf building ──
//
// Extracted from MainActivity.kt; see that file's header.
/** Human-readable Xtream subscription status, or null if the provider doesn't report an expiry. */
internal fun MainActivity.formatSubscriptionStatus(expDateSeconds: Long?, isTrial: Boolean): String? {
    if (expDateSeconds == null || expDateSeconds <= 0) return null
    val fmt = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
    val dateStr = fmt.format(java.util.Date(expDateSeconds * 1000))
    val nowSeconds = System.currentTimeMillis() / 1000
    val trialPrefix = if (isTrial) "Trial · " else ""
    return if (expDateSeconds < nowSeconds) "⚠ $trialPrefix Expired $dateStr" else "$trialPrefix Active until $dateStr"
}

/**
 * Filtering, dedup-grouping, sorting, and shelf-building over the whole catalog
 * (tens of thousands of items on a big provider) is real CPU work - it must run
 * off the main thread or the UI stalls/looks hung on every load and refresh.
 */
internal suspend fun MainActivity.classifyAndShow(preserveUi: Boolean = false) {
    val snapshot = allChannels
    val derived = withContext(Dispatchers.Default) { computeDerivedContent(snapshot) }

    liveChannels = derived.liveChannels
    liveVersions = derived.liveVersions
    filmList = derived.filmList
    filmVersions = derived.filmVersions
    filmShelves = derived.filmShelves
    seriesList = derived.seriesList
    seriesVersions = derived.seriesVersions
    seriesShelves = derived.seriesShelves

    // Discover (TMDB browsing) and any stream_search plugin's Find Stream flow need no
    // provider-sourced channels at all - gating the whole content area on allChannels alone
    // trapped a plugin-only setup (e.g. just torrent-search enabled, which contributes no
    // catalog entries) behind the "No provider configured" empty state for no reason.
    val hasContent = allChannels.isNotEmpty() || hasProviderlessSource()

    // Adapters are safe to (re)bind under an open overlay - only the visible screen
    // must not be, since it lives behind the overlay. Bind first, then swap visibility
    // only when nothing is covering the content.
    if (hasContent) {
        binding.liveContent.adapter = liveAdapter
        binding.seriesContent.adapter = seriesShelfAdapter
        binding.filmsContent.adapter = filmsShelfAdapter
        binding.categorySidebar.adapter = categoryAdapter
        binding.homeContent.adapter = homeShelfAdapter
        seriesShelfAdapter.submitList(seriesShelves)
        filmsShelfAdapter.submitList(filmShelves)
    }

    // A settings/search overlay owns the whole content slot while it's up. Toggling a
    // provider off in Settings reloads through here, and flipping the empty state and
    // chrome underneath the overlay is what left "half the settings menu" showing when
    // it closed. Defer that to the overlay's own dismiss handler (selectHome/selectTab),
    // which re-derives all of it against the now-current state.
    if (activeSettingsOverlay != null || activeSearchOverlay != null) return

    if (hasContent) {
        binding.emptyState.visibility = View.GONE
        binding.contentRow.visibility = View.VISIBLE
        updateTopChromeVisibility()
        if (preserveUi) {
            // Surgical refresh of the current tab: the fresh data above already landed in
            // every adapter, but instead of the selectTab/selectHome/selectDiscover dispatch
            // (which resets scroll, position and focus) only the pane actually on screen is
            // re-fed in place. The hasContent branch set contentRow visible for the
            // categorized layout, so the panes that own the slot outright (Home, Discover)
            // put it back before their own refresh.
            if (showingHome) {
                binding.contentRow.visibility = View.GONE
                // Home's own panes, not just the shelf data: whatever hid them is the
                // reason this refresh is happening (the empty state and the settings
                // overlay both set them GONE), and nothing else on this path puts them
                // back - so the first catalog to land after a provider was added
                // replaced the empty state with a blank screen.
                binding.homeContent.visibility = View.VISIBLE
                binding.homeSearchBar.visibility = if (hasProviderEnabled()) View.VISIBLE else View.GONE
                homeShelfAdapter.submitList(buildHomeShelves())
            } else if (showingDiscover) {
                // Discover owns the slot and has no catalog chrome to refresh - the
                // refreshed catalog is picked up when the user switches back.
                binding.contentRow.visibility = View.GONE
            } else if (!showingDownloads) {
                scope.launch {
                    // Guard mirrors selectTab's: the category build is seconds' work on a
                    // large catalog, and the user can leave this tab while it runs - never
                    // land the sidebar over a pane that moved in.
                    if (showingHome || showingDiscover || showingDownloads) {
                        setStatus("", visible = false)
                        return@launch
                    }
                    if (activeTab != 0) filmsSeriesDeriveJob?.join()
                    val categories = buildCategoriesForActiveTab()
                    // Validate the preserved row against the freshly rebuilt categories -
                    // a row whose id no longer exists (provider dropped the group, the
                    // filter hid it) must not keep pointing at nothing. Falls back to the
                    // same default target selectTab would have picked on a fresh entry.
                    if (selectedRowId != null && categories.none { it.id == selectedRowId }) {
                        selectedRowId = null
                        selectedCategoryLabel = null
                        selectedBrandChannelIds = null
                        selectedCategoryIds = null
                        if (activeTab == 0) {
                            val hasFavourites = com.lumora.cache.FavoritesStore.getFavoriteChannelIds(this@classifyAndShow).isNotEmpty()
                            val target = categories.firstOrNull { it.id == FAVOURITES_CATEGORY_ID }?.takeIf { hasFavourites }
                                ?: categories.firstOrNull { it.pinned }
                                ?: categories.firstOrNull { it.id?.startsWith(DYNAMIC_BUCKET_ID_PREFIX) == true }
                            if (target != null) {
                                selectedRowId = target.id
                                selectedCategoryLabel = target.name
                                selectedBrandChannelIds = target.channelIds.ifEmpty { null }
                                selectedCategoryIds = if (target.channelIds.isNotEmpty()) null else target.matchIds
                            }
                        }
                    }
                    // A category-grid filter whose matchIds now match no item would leave an
                    // empty pane - fall back to the All view instead of showing nothing.
                    val matchIds = selectedCategoryIds
                    if (matchIds != null) {
                        val source = when (activeTab) { 0 -> liveChannels; 1 -> seriesList; else -> filmList }
                        val empty = withContext(Dispatchers.Default) { source.none { it.filterKey() in matchIds } }
                        if (empty) selectedCategoryIds = null
                    }
                    submitCategories(categories)
                    applyCategoryFilter(focusFirstLiveChannel = false)
                    binding.contentRow.visibility = View.VISIBLE
                    setStatus("", visible = false)
                    applyStatus()
                }
            }
            // Keep the preview pointed at the current channel's fresh incarnation (null
            // when the refresh removed it). No requestPreviewLoad - the running preview
            // keeps playing, and the next focus pick-up resolves liveVersions[channel.id]
            // against the new map.
            lastFocusedLiveChannel = liveChannels.firstOrNull { it.id == lastFocusedLiveChannel?.id }
        } else {
            if (showingHome) selectHome() else if (showingDiscover) selectDiscover() else selectTab(activeTab)
        }
    } else {
        showEmptyState()
    }
}

/** Paint-the-Live-ASAP path: derive only the live half off the main thread, then run the
 *  same first-paint sequence classifyAndShow() uses for the Live tab. If the user has
 *  already left for another tab, only the live side is filled in - selectTab and other
 *  tabs' state are left to their own render path, never touched from here. */
internal fun MainActivity.classifyAndShowLiveFirst() {
    scope.launch {
        withContext(Dispatchers.Default) { deriveLiveHalf(allChannels) }
        // Mirror of classifyAndShow()'s first-paint bind block, live side only - the
        // other tabs' adapters and shelves belong to their own render path.
        val hasContent = allChannels.isNotEmpty() || hasProviderlessSource()
        if (hasContent) {
            // Mirror of classifyAndShow()'s adapter-bind block (all five, not just the
            // live side): on warm/stale-cache starts this is the only bind that runs, so
            // the sidebar and Home/shelf panes would otherwise stay blank.
            binding.liveContent.adapter = liveAdapter
            binding.seriesContent.adapter = seriesShelfAdapter
            binding.filmsContent.adapter = filmsShelfAdapter
            binding.categorySidebar.adapter = categoryAdapter
            binding.homeContent.adapter = homeShelfAdapter
        }
        // Settings/search overlays own the whole content slot while up - defer chrome
        // swaps to their dismiss handlers, same as classifyAndShow().
        if (activeSettingsOverlay != null || activeSearchOverlay != null) return@launch
        if (hasContent) {
            binding.emptyState.visibility = View.GONE
            binding.contentRow.visibility = View.VISIBLE
            updateTopChromeVisibility()
            when {
                showingHome -> selectHome()
                showingDiscover -> selectDiscover()
                activeTab == 0 -> selectTab(activeTab)
                // Guard: user already moved to another tab - live side only, no selectTab.
                else -> Unit
            }
            // The number to judge a cold start by: process start to content on screen.
            // The per-pass lines above only account for their own work, and miss process
            // creation, Application.onCreate, layout inflation and the first frame.
            perf("FIRST CONTENT", BaseApplication.instance.processStartedAt)
        } else {
            showEmptyState()
        }
    }
}

/** The expensive films/series pass (dedup/sort/category-row/shelf build), off the main
 *  thread, with a late-write guard: if allChannels was swapped while the snapshot was
 *  being derived, the result is dropped - a newer load owns the render. Never calls
 *  selectTab and never raises status; the caller decides when the result shows. */
internal fun MainActivity.deriveFilmsSeries() {
    val snapshot = allChannels
    filmsSeriesDeriveJob = scope.launch(Dispatchers.Default) {
        if (allChannels !== snapshot) return@launch
        val result = deriveFilmsSeriesHalf(snapshot)
        withContext(Dispatchers.Main) {
            // Guard again before touching the UI: the shelf build is seconds of work on
            // a big catalog, and a newer load may have swapped allChannels mid-derive.
            // isActive too - a cancelled job must never land its fields on the UI.
            if (allChannels !== snapshot || !isActive) return@withContext
            filmList = result.filmList
            filmVersions = result.filmVersions
            filmShelves = result.filmShelves
            seriesList = result.seriesList
            seriesVersions = result.seriesVersions
            seriesShelves = result.seriesShelves
            cachedSeriesCategoryRows = result.seriesCategoryRows
            // Mirror of classifyAndShow()'s shelf submits for the films/series shelves.
            seriesShelfAdapter.submitList(seriesShelves)
            filmsShelfAdapter.submitList(filmShelves)
            if (showingHome) homeShelfAdapter.submitList(buildHomeShelves())
        }
    }
}

/** Merges one provider's freshly-fetched channels into allChannels, replacing anything
 *  previously loaded from that same provider (matched by config id). */
internal fun MainActivity.mergeProviderPartial(configId: String?, channels: List<Channel>) {
    allChannels = allChannels.filterNot { it.sourceProviderId == configId } + channels
}

/** Live-only re-render, coalesced: the first call paints the Live tab ASAP (live half +
 *  first paint); later calls while on the Live tab surgically re-filter the live data in
 *  place - no position reset, no selectTab, nothing outside the live side touched. */
internal fun MainActivity.renderLivePartial() {
    if (!uiPainted) {
        uiPainted = true
        classifyAndShowLiveFirst()
    } else if (activeTab == 0) {
        // Coalesce: N near-simultaneous provider completions each land here - if one
        // surgical re-render is already in flight, drop this one (the in-flight pass
        // reads allChannels fresh and sees the merged result).
        if (liveRenderJob?.isActive == true) return
        liveRenderJob = scope.launch {
            withContext(Dispatchers.Default) { deriveLiveHalf(allChannels) }
            // Mirror applyCategoryFilter()'s live branch (source filter) minus the
            // scroll-to-top - keep the user's position, just re-submit fresh data.
            val source = liveChannels
            val isFavourites = selectedRowId == FAVOURITES_CATEGORY_ID
            val favoriteIds = if (isFavourites) FavoritesStore.getFavoriteChannelIds(this@renderLivePartial) else emptySet()
            val brandIds = selectedBrandChannelIds
            val matchIds = selectedCategoryIds
            val filtered = withContext(Dispatchers.Default) {
                when {
                    isFavourites -> source.filter { it.id in favoriteIds }
                    brandIds != null && matchIds != null -> source.filter { it.id in brandIds || it.filterKey() in matchIds }
                    brandIds != null -> source.filter { it.id in brandIds }
                    matchIds == null -> source
                    else -> source.filter { it.filterKey() in matchIds }
                }
            }
            // A category-grid filter whose matchIds now match no item (a partial merge
            // that hasn't covered the selected category yet) would leave an empty pane -
            // fall back to the All view instead of showing nothing.
            val empty = if (matchIds != null) {
                withContext(Dispatchers.Default) { source.none { it.filterKey() in matchIds } }
            } else false
            if (matchIds != null && empty) {
                selectedCategoryIds = null
                liveAdapter.submitList(source)
            } else {
                liveAdapter.submitList(filtered)
            }
        }
    }
}

// No hideNonEnglish/hideAdult parameters: both halves read those prefs themselves (the
// derive-cache fingerprint depends on them), so the arguments were passed in and ignored.
internal fun MainActivity.computeDerivedContent(allChannels: List<Channel>): MainActivity.DerivedContent {
    deriveLiveHalf(allChannels)
    val result = deriveFilmsSeriesHalf(allChannels)
    filmList = result.filmList
    filmVersions = result.filmVersions
    filmShelves = result.filmShelves
    seriesList = result.seriesList
    seriesVersions = result.seriesVersions
    seriesShelves = result.seriesShelves
    cachedSeriesCategoryRows = result.seriesCategoryRows
    return MainActivity.DerivedContent(liveChannels, liveVersions, filmList, filmVersions, filmShelves, seriesList, seriesVersions, seriesShelves)
}

/** Live half of the derive pass: filter + adult-drop + quality-version grouping into
 *  liveChannels/liveVersions. Cheap relative to the films/series half (no shelves), so
 *  it's extracted first and reused by the paint-Live-ASAP path. */
internal fun MainActivity.deriveLiveHalf(list: List<Channel>) {
    val startedAt = System.currentTimeMillis()
    val hideAdult = prefs.getBoolean(PREF_HIDE_ADULT, true)
    val useClassic = prefs.getBoolean(PREF_CLASSIC_CATEGORY_LAYOUT, false)
    val groupChannels = prefs.getBoolean(PREF_GROUP_CHANNELS, true)

    // Same catalogue, same prefs, same answer - restore last time's grouping instead of
    // re-running it (see DerivedCache).
    val fingerprint = liveDerivedFingerprint(list, hideAdult, useClassic, groupChannels)
    DerivedCache.loadLive(this, fingerprint, list)?.let { snapshot ->
        liveChannels = snapshot.channels
        liveVersions = snapshot.versions
        perf("deriveLiveHalf(cached)", startedAt, "${liveChannels.size} live out")
        return
    }

    val rawLive = list.filter { ch ->
        ch.mediaType == MediaType.LIVE &&
            !ch.name.contains("##") &&
            !(hideAdult && isAdultCategory(ch.categoryName, ch.group))
    }
    if (useClassic || !groupChannels) {
        // Classic: no quality version merging — show every channel as-is from the provider.
        // Version map is empty since every variant appears as its own channel entry.
        liveChannels = rawLive
        liveVersions = emptyMap()
    } else {
        val (grouped, vers) = groupLiveQualityVersions(rawLive)
        liveChannels = grouped
        liveVersions = vers
    }
    perf("deriveLiveHalf", startedAt, "${list.size} in / ${liveChannels.size} live out")
    DerivedCache.saveLive(this, fingerprint, DerivedCache.LiveSnapshot(liveChannels, liveVersions))
}

/** Cache key for the live derive: the catalogue itself, plus every pref that changes what
 *  the pass produces. Anything not listed here (categorize, pinned/hidden, favourites)
 *  feeds the category rows, which are still built fresh on every load. */
internal fun MainActivity.liveDerivedFingerprint(
    list: List<Channel>,
    hideAdult: Boolean,
    useClassic: Boolean,
    groupChannels: Boolean
): String = DerivedCache.catalogFingerprint(list, "live:$hideAdult:$useClassic:$groupChannels")

/** Cache key for the films/series derive - see [liveDerivedFingerprint]. */
internal fun MainActivity.vodDerivedFingerprint(
    list: List<Channel>,
    hideAdult: Boolean,
    hideNonEnglish: Boolean,
    groupChannels: Boolean
): String = DerivedCache.catalogFingerprint(list, "vod:$hideAdult:$hideNonEnglish:$groupChannels")

/** One cold-start budget line per pass (adb logcat -s LumoraPerf). Cheap enough to
 *  leave in a release build - a handful of calls per load - and the only way to tell
 *  which pass owns a slow start on the actual TV hardware. */
internal fun MainActivity.perf(stage: String, startedAt: Long, detail: String = "") {
    android.util.Log.i("LumoraPerf", "$stage: ${System.currentTimeMillis() - startedAt}ms $detail")
}

/** Films/series half of the derive pass: dedup/sort, category rows, and shelf build into
 *  a [MainActivity.FilmsSeriesContent] result (no field side effects - the caller assigns them on its
 *  own thread). The expensive half - run off the main thread. */

internal fun MainActivity.deriveFilmsSeriesHalf(list: List<Channel>): MainActivity.FilmsSeriesContent {
    val startedAt = System.currentTimeMillis()
    val hideNonEnglish = prefs.getBoolean(PREF_HIDE_NON_ENGLISH, true)
    val hideAdult = prefs.getBoolean(PREF_HIDE_ADULT, true)
    val groupChannels = prefs.getBoolean(PREF_GROUP_CHANNELS, true)
    val categorizeVod = prefs.getBoolean(PREF_CATEGORIZE_VOD, true)
    fun isAdult(ch: Channel) = hideAdult && isAdultCategory(ch.categoryName, ch.group)

    // One pass, not four: the chained filter/filterNot/map each walked the whole 50k+
    // catalogue and allocated its own intermediate list. Same predicates, same order.
    // The dedup/sort half is a pure function of the catalogue and three prefs, so it is
    // restored from disk when nothing that feeds it has changed (see DerivedCache). The
    // category rows and shelves below are always rebuilt: they also depend on pinned,
    // hidden, favourites and Continue Watching, which move constantly.
    val vodFingerprint = vodDerivedFingerprint(list, hideAdult, hideNonEnglish, groupChannels)
    val cachedVod = DerivedCache.loadVod(this, vodFingerprint, list)

    val films: List<Channel>
    val versions: Map<String, List<Channel>>
    if (cachedVod != null) {
        films = cachedVod.films
        versions = cachedVod.filmVersions
    } else {
        val rawFilms = list.mapNotNull { ch ->
            if (ch.mediaType != MediaType.MOVIE) null
            else if (hideNonEnglish && isNonEnglishTitle(ch.name)) null
            else if (isAdult(ch)) null
            else ch.withResolvedYear()
        }
        val (groupedFilms, filmVers) = if (groupChannels) groupDuplicateMovies(rawFilms) else rawFilms to emptyMap()
        films = groupedFilms.sortedByDescending { it.year?.toIntOrNull() ?: -1 }
        versions = filmVers
    }
    // "Newest" pools the most recent releases (by date, not rating) into one shelf
    // pinned at the top, sorted by release date descending regardless of category.
    val newestFilms = newestByDate(films)
    // Poster shelves derive from the SAME category rows as the sidebar - same categories,
    // same order, guaranteed by construction. Snapshot the per-tab pinned/hidden sets
    // once here (they're per-tab prefs keys and both tabs' shelves are built in this one
    // pass). Never write categoryChildrenCache from here - that stays sidebar-owned.
    val filmPinned = getPinnedCategories(2)
    val filmHidden = getHiddenCategories(2)
    val seriesPinned = getPinnedCategories(1)
    val seriesHidden = getHiddenCategories(1)
    val animeSectionsSnapshot = animeSections
    val filmCategoryRows = buildCategoryRows(
        list = films, versionsById = versions, tab = 2,
        pinned = filmPinned, hiddenIds = filmHidden, expanded = emptySet(),
        animeSections = emptyList(), useClassicLayout = false, favoriteChannelIds = emptySet(),
        categorize = categorizeVod
    )
    // The old buildShelves() count-sorting is gone - shelf order IS the sidebar's row
    // order. Films keeps its single "Newest" prepend, exactly as before.
    val filmShelvesLocal = shelvesFromCategoryRows(filmCategoryRows.rows, films)
        .let { shelves -> if (newestFilms.isEmpty()) shelves else listOf(ContentShelf("Newest", newestFilms, categoryId = NEWEST_CATEGORY_ID)) + shelves }

    val series: List<Channel>
    val seriesVers: Map<String, List<Channel>>
    if (cachedVod != null) {
        series = cachedVod.series
        seriesVers = cachedVod.seriesVersions
    } else {
        val rawSeries = list.mapNotNull { ch ->
            if (ch.mediaType != MediaType.SERIES) null
            else if (hideNonEnglish && isNonEnglishTitle(ch.name)) null
            else if (isAdult(ch)) null
            else ch.withResolvedYear()
        }
        // Real release date (from the provider's bulk series list) sorts more precisely
        // than year alone; falls back to year for anything that came back without one.
        val (groupedSeries, vers) = if (groupChannels) groupDuplicateSeries(rawSeries) else rawSeries to emptyMap()
        series = groupedSeries
            .sortedWith(compareByDescending<Channel> { it.releaseDate ?: "" }.thenByDescending { it.year?.toIntOrNull() ?: -1 })
        seriesVers = vers
        DerivedCache.saveVod(
            this,
            vodFingerprint,
            DerivedCache.VodSnapshot(films, versions, series, seriesVers)
        )
    }
    // Favourited series get their own shelf pinned above everything else in the
    // Series tab itself, not just on Home.
    val favoriteSeriesIds = FavoritesStore.getFavoriteSeriesIds(this)
    val favoriteSeries = series.filter { it.id in favoriteSeriesIds }
    val newestSeries = newestByDate(series)
    val seriesCategoryRows = buildCategoryRows(
        list = series, versionsById = seriesVers, tab = 1,
        pinned = seriesPinned, hiddenIds = seriesHidden, expanded = emptySet(),
        animeSections = animeSectionsSnapshot, useClassicLayout = false, favoriteChannelIds = emptySet(),
        categorize = categorizeVod
    )
    // Newest and Favourites stay pinned at the very top of the poster, above the
    // sidebar-derived category shelves. Continue Watching slots between them, matching
    // the sidebar order (Favourites > Continue Watching > Newest > categories).
    val seriesShelvesLocal = shelvesFromCategoryRows(seriesCategoryRows.rows, series)
        .let { shelves ->
            (if (newestSeries.isEmpty()) shelves else listOf(ContentShelf("Newest", newestSeries, categoryId = NEWEST_CATEGORY_ID)) + shelves)
        }
        .let { shelves ->
            val cw = seriesContinueItems()
            if (cw.isEmpty()) shelves else listOf(ContentShelf("Continue Watching", cw)) + shelves
        }
        .let { shelves ->
            if (favoriteSeries.isEmpty()) shelves else listOf(ContentShelf("Favourites", favoriteSeries)) + shelves
        }

    perf("deriveFilmsSeriesHalf", startedAt, "${films.size} films / ${series.size} series")
    return MainActivity.FilmsSeriesContent(
        filmList = films,
        filmVersions = versions,
        filmShelves = filmShelvesLocal,
        seriesList = series,
        seriesVersions = seriesVers,
        seriesShelves = seriesShelvesLocal,
        seriesCategoryRows = seriesCategoryRows.rows
    )
}

/** Maps sidebar category rows (the output of [buildCategoryRows], already in sidebar
 *  order) to poster shelves, in that same order - the poster renders exactly the
 *  categories the sidebar shows, guaranteed by construction. Rows that don't resolve
 *  to items (empty after pin/hide) are dropped, mirroring a sidebar click on them.
 *
 *  Item resolution mirrors applyCategoryFilter()'s two branches: an explicit
 *  channelIds set (Jellyfin, anime, genre buckets, brand rows) filters by channel id;
 *  everything else (leaves, group: parents, clustered service categories) filters by
 *  filterKey() against matchIds. */
internal fun MainActivity.shelvesFromCategoryRows(rows: List<CategoryFilter>, list: List<Channel>): List<ContentShelf> {
    // key: categoryId ?: title, merge same-name rows
    val merged = LinkedHashMap<String, ContentShelf>()
    // One pass to index the catalogue, instead of one pass per category row below.
    val byId = HashMap<String, Channel>(list.size)
    val byFilterKey = HashMap<String, MutableList<Channel>>()
    val orderInList = HashMap<String, Int>(list.size)
    for ((i, ch) in list.withIndex()) {
        byId[ch.id] = ch
        orderInList.putIfAbsent(ch.id, i)
        ch.filterKey()?.let { byFilterKey.getOrPut(it) { mutableListOf() }.add(ch) }
    }
    for (row in rows) {
        if (row.id == null) continue          // All row - the poster IS the All view
        if (row.isChild) continue             // content already in the parent's union
        if (row.count <= 0) continue          // toggle/utility rows (classic-layout toggle, etc.)
        // The dedicated Jellyfin row stays in the sidebar for browsing but doesn't get a
        // poster shelf - its titles are already interleaved into Newest and the genre
        // shelves, and a whole shelf of one provider read as clutter in the poster view.
        if (row.id == JELLYFIN_CATEGORY_ID) continue
        // Indexed lookup, not a scan per row: with ~500 rows over a 20k-title catalogue
        // the old `list.filter` per row was ~10M predicate evaluations (seconds on a TV
        // stick). Both branches re-sort by the item's position in `list` so the shelf
        // order is byte-for-byte what the scan produced.
        val items = when {
            row.channelIds.isNotEmpty() ->
                row.channelIds.mapNotNull { byId[it] }.sortedBy { orderInList[it.id] ?: 0 }
            else ->
                row.matchIds.flatMap { byFilterKey[it].orEmpty() }.sortedBy { orderInList[it.id] ?: 0 }
        }
        if (items.isEmpty()) continue
        val shelf = ContentShelf(title = row.name, items = items, pinned = row.pinned, categoryId = row.id)
        // Non-null: rows with a null id were skipped at the top of the loop.
        val key = row.id
        val existing = merged[key]
        merged[key] = if (existing == null) shelf else existing.copy(items = existing.items + items)
    }
    return merged.values.toList()
}
