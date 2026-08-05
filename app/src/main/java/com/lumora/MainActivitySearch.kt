package com.lumora

import android.content.Context
import android.animation.AnimatorInflater
import androidx.core.content.res.ResourcesCompat
import android.view.View
import android.widget.*
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lumora.adapter.SearchEpgResult
import com.lumora.adapter.SearchResultItem
import com.lumora.adapter.SearchResultsAdapter
import com.lumora.cache.EpgListCache
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.parser.XtreamClient
import kotlinx.coroutines.*

// ── Search dialog, ranking & recents ──
//
// Extracted from MainActivity.kt; see that file's header.
internal fun MainActivity.showSearchDialog(initialQuery: String? = null) {
    // Re-entry guard: a second press while the overlay is up would stack a second
    // MainActivity.FullScreenOverlay in the same container (double focus trees, overwritten
    // searchKeyHandler) - especially easy to hit with a phone tap.
    if (activeSearchOverlay != null) return
    pendingSearchRestore = null
    // Search and Settings are sibling content slots in the same weighted LinearLayout, so if
    // Settings is still up when Search opens, the layout splits the content area between the
    // two and they render on top of each other. The toolbar stays usable over Settings by
    // design, so opening Search here has to close Settings first.
    activeSettingsOverlay?.dismiss()
    val searchView = layoutInflater.inflate(R.layout.dialog_search, null)
    val input = searchView.findViewById<EditText>(R.id.searchInput)
    val statusText = searchView.findViewById<TextView>(R.id.searchStatus)
    val resultsList = searchView.findViewById<RecyclerView>(R.id.searchResults)
    val recentsBlock = searchView.findViewById<View>(R.id.searchRecentsBlock)
    val recentsRow = searchView.findViewById<LinearLayout>(R.id.searchRecentsRow)
    val filtersRow = searchView.findViewById<View>(R.id.searchFiltersRow)

    val keyboard = searchView.findViewById<com.lumora.ui.OnScreenKeyboard>(R.id.searchKeyboard)
    applyPanelWidth(searchView.findViewById(R.id.searchPanel), R.dimen.search_panel_width)

    // The platform IME on a Fire TV opens full-screen over the app and takes focus with
    // it, so the query and its results can never be on screen together. Suppress it and
    // drive the field from the on-screen keyboard instead.
    input.showSoftInputOnFocus = false
    fun replaceQuery(text: String) = input.setText(text)
    keyboard.onKey = { ch -> replaceQuery(input.text.toString() + ch) }
    keyboard.onBackspace = { replaceQuery(input.text.toString().dropLast(1)) }
    keyboard.onClear = { replaceQuery("") }
    // A paired bluetooth/USB keyboard can't type into the field any more (it isn't
    // focusable), so the Activity forwards printable keys and backspace here instead.
    searchKeyHandler = { ch -> if (ch == null) keyboard.onBackspace?.invoke() else replaceQuery(input.text.toString() + ch) }

    // Fixed span: the grid shares the panel with the keyboard now, so overall screen
    // width no longer describes the space it actually has.
    val dialogSpanCount = resources.getInteger(R.integer.search_results_span)
    resultsList.layoutManager = GridLayoutManager(this, dialogSpanCount)
    val resultsAdapter = SearchResultsAdapter(
        showTypeBadge = true,
        onItemLongClick = { item -> toggleFavoriteVodItem(item) }
    ) { item ->
        // P1-1: remember the query so Back from the player/detail returns to the
        // results (the session survives picking a result). This is also the moment a
        // search is "committed", so a search is only saved to recents when the user
        // actually picks a result - typing pauses alone shouldn't pollute the list.
        addSearchRecent(input.text.toString().trim())
        pendingSearchRestore = input.text.toString()
        activeSearchOverlay?.dismiss()
        when (item) {
            is SearchResultItem.Media ->
                if (item.channel.mediaType == MediaType.LIVE) playItem(item.channel) else showContentDetail(item.channel)
            is SearchResultItem.Epg -> playItem(item.program.channel)
        }
    }
    resultsAdapter.spanCount = dialogSpanCount
    // P0-1: UP from the top row used to target the non-focusable query field (a dead
    // key - requestFocus failed silently while the press was consumed). Target the
    // keyboard itself instead: it's focusable, and the next D-pad press enters the
    // keys via normal focus search.
    resultsAdapter.topRowFocusUpTargetId = R.id.searchKeyboard
    // P0-1: LEFT from the first column must also return to the keyboard - default
    // focus search can't cross out of the RecyclerView into the keyboard.
    resultsAdapter.leftFocusTarget = keyboard
    resultsAdapter.posterHeightDimen = R.dimen.search_poster_image_height
    resultsList.adapter = resultsAdapter
    // P2-7: DOWN from the keyboard's bottom row (and the K/L columns with nothing
    // below them) lands on the first result row.
    keyboard.bottomRowDownTarget = resultsList

    var searchRunnable: Runnable? = null
    // One scheduling path for both typing and filter-chip presses: debounce, and stamp
    // the run with an id so a slower old run can't overwrite a newer query's results.
    fun scheduleSearch(query: String) {
        searchRunnable?.let { mainHandler.removeCallbacks(it) }
        val runId = ++searchRunId
        val runnable = Runnable { runSearch(query, runId, resultsAdapter, statusText, resultsList) }
        searchRunnable = runnable
        mainHandler.postDelayed(runnable, 200)
    }

    // P2-4: media-type filter chips. EPG results are only shown under All/Live.
    fun applyFilterChip(selected: View) {
        for (id in listOf(R.id.searchFilterAll, R.id.searchFilterLive, R.id.searchFilterFilms, R.id.searchFilterSeries)) {
            searchView.findViewById<View>(id).isSelected = searchView.findViewById<View>(id) === selected
        }
    }
    for ((filter, id) in listOf(
        MainActivity.SearchFilter.ALL to R.id.searchFilterAll,
        MainActivity.SearchFilter.LIVE to R.id.searchFilterLive,
        MainActivity.SearchFilter.MOVIE to R.id.searchFilterFilms,
        MainActivity.SearchFilter.SERIES to R.id.searchFilterSeries
    )) {
        searchView.findViewById<TextView>(id).setOnClickListener {
            searchFilter = filter
            applyFilterChip(it)
            val q = input.text.toString().trim()
            if (q.length >= 2) scheduleSearch(q)
        }
    }
    // Selection must follow the persisted filter, not default to All: restoreSearchIfPending
    // re-opens with the previous session's filter, and a chip row showing "All" while the
    // results are FILMS-filtered is a lie (P1-2).
    applyFilterChip(
        searchView.findViewById(
            when (searchFilter) {
                MainActivity.SearchFilter.ALL -> R.id.searchFilterAll
                MainActivity.SearchFilter.LIVE -> R.id.searchFilterLive
                MainActivity.SearchFilter.MOVIE -> R.id.searchFilterFilms
                MainActivity.SearchFilter.SERIES -> R.id.searchFilterSeries
            }
        )
    )

    // P2-1: recent searches - chips populated from prefs, shown while the query is short.
    fun buildRecents() {
        recentsRow.removeAllViews()
        val recents = searchRecents()
        recentsBlock.visibility = if (recents.isEmpty()) View.GONE else View.VISIBLE
        val gap = (8 * resources.displayMetrics.density).toInt()
        for (q in recents) {
            val chip = TextView(this).apply {
                text = q
                setBackgroundResource(R.drawable.bg_season_chip)
                isClickable = true
                isFocusable = true
                setTextColor(getColor(R.color.text_secondary))
                textSize = 14f
                typeface = androidx.core.content.res.ResourcesCompat.getFont(this@showSearchDialog, R.font.inter_medium)
                stateListAnimator = android.animation.AnimatorInflater
                    .loadStateListAnimator(this@showSearchDialog, R.animator.focus_scale)
                setPadding(gap, (8 * resources.displayMetrics.density).toInt(), gap, (8 * resources.displayMetrics.density).toInt())
                setOnClickListener { input.setText(q) }
                // DOWN from a recents chip would otherwise do nothing (results and
                // filters are GONE while the query is short) - route it into the keys.
                setOnKeyListener { _, keyCode, event ->
                    if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && event.action == android.view.KeyEvent.ACTION_DOWN) {
                        keyboard.requestFocus()
                        true
                    } else false
                }
            }
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = gap }
            recentsRow.addView(chip)
        }
    }
    buildRecents()
    searchView.findViewById<View>(R.id.searchRecentsClear).setOnClickListener {
        clearSearchRecents()
        buildRecents()
    }

    val overlay = MainActivity.FullScreenOverlay(
        binding.searchContainer,
        searchView,
        closeButton = searchView.findViewById(R.id.searchCloseButton),
        // P2-3: land on the G key (center of the letter block), not the top-left "1" -
        // a letter is the overwhelmingly likely first key, and G is closest to all of them.
        initialFocus = { keyboard.firstKey() ?: input }
    )
    // P2-8: keep the tab bar out of reach while search is up - a stray press would
    // dismiss search via selectTab and silently lose the query.
    val tabBarWasVisible = binding.tabBar.visibility == View.VISIBLE
    if (tabBarWasVisible) binding.tabBar.visibility = View.GONE
    binding.homeContent.visibility = View.GONE
    binding.homeSearchBar.visibility = View.GONE
    binding.discoverContent.visibility = View.GONE
    binding.contentRow.visibility = View.GONE
    binding.emptyState.visibility = View.GONE

    // Incremental scroll: load more results as user scrolls near the bottom.
    resultsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy <= 0 || searchDisplayedCount >= searchAllResults.size) return
            val lm = recyclerView.layoutManager as GridLayoutManager
            val lastVisible = lm.findLastVisibleItemPosition()
            if (lastVisible >= lm.itemCount - 6) {
                loadMoreSearchResults(resultsAdapter, statusText)
            }
        }
    })

    input.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            searchRunnable?.let { mainHandler.removeCallbacks(it) }
            val query = s?.toString()?.trim().orEmpty()
            if (query.length < 2) {
                // Invalidate any in-flight async search so it can't publish for the old query.
                searchRunId++
                statusText.text = getString(R.string.type_to_search)
                statusText.visibility = View.VISIBLE
                resultsList.visibility = View.GONE
                filtersRow.visibility = View.GONE
                buildRecents()
                searchAllResults = emptyList()
                searchDisplayedCount = 0
                return
            }
            filtersRow.visibility = View.VISIBLE
            // Recents are the idle-state surface - once results take over, get them off
            // the panel (on portrait they'd squeeze the grid with the filters and keyboard).
            recentsBlock.visibility = View.GONE
            // Fires as-you-type with a short debounce, not on submit - a large catalog
            // filter is cheap enough (Dispatchers.Default, a few thousand items) that
            // waiting for the user to stop typing is the only real cost here.
            scheduleSearch(query)
        }
    })
    overlay.setOnDismissListener {
        searchRunnable?.let { mainHandler.removeCallbacks(it) }
        // Invalidate in-flight async search (EPG fetches can outlive the overlay).
        searchRunId++
        searchKeyHandler = null
        activeSearchOverlay = null
        if (tabBarWasVisible) binding.tabBar.visibility = View.VISIBLE
        applyStatus()
        if (showingHome) selectHome() else if (showingDiscover) selectDiscover() else if (showingDownloads) selectDownloads() else selectTab(activeTab)
    }
    activeSearchOverlay = overlay
    applyStatus()
    if (initialQuery != null) input.setText(initialQuery)
    overlay.show()
}

/** Ranks a title's relevance to [query] (lower is better) - exact match first, then
 *  "starts with", then matching at a word boundary ("man" hits "Iron Man" but not
 *  "Batman"), plain substring last. Word-boundary keeps a query like "man" usable on
 *  a catalog with thousands of vaguely-matching substrings instead of it being buried. */
internal fun MainActivity.searchRank(name: String, query: String, boundaryRegex: Regex): Int {
    val lower = name.lowercase()
    return when {
        lower == query -> 0
        lower.startsWith(query) -> 1
        boundaryRegex.containsMatchIn(lower) -> 2
        else -> 3
    }
}

internal fun MainActivity.runSearch(query: String, runId: Int, adapter: SearchResultsAdapter, statusText: TextView, resultsList: RecyclerView) {
    statusText.text = getString(R.string.search_status_searching)
    statusText.visibility = View.VISIBLE
    resultsList.visibility = View.GONE
    searchAllResults = emptyList()
    searchDisplayedCount = 0
    scope.launch {
        val filter = searchFilter
        val mediaResults = withContext(Dispatchers.Default) {
            val lower = query.lowercase()
            // P1-2: compiled once per query, not per comparison - the boundary regex was
            // built inside searchRank (called by the comparator), so sorting ~50k titles
            // compiled ~1.5M Patterns and cost seconds on a TV stick.
            val boundaryRegex = Regex("\\b" + Regex.escape(lower))
            val source = when (filter) {
                MainActivity.SearchFilter.ALL -> liveChannels + filmList + seriesList
                MainActivity.SearchFilter.LIVE -> liveChannels
                MainActivity.SearchFilter.MOVIE -> filmList
                MainActivity.SearchFilter.SERIES -> seriesList
            }
            source
                .filter { it.name.lowercase().contains(lower) }
                .sortedWith(compareBy({ searchRank(it.name, lower, boundaryRegex) }, { it.name.lowercase() }))
                .map { SearchResultItem.Media(it) }
        }
        // Stale run (query changed or overlay dismissed while this was in flight) - the
        // EPG fetches make runs long enough that publishing late would clobber newer
        // results, so check before and after the slow part.
        if (runId != searchRunId) return@launch
        fun publish(all: List<SearchResultItem>) {
            if (all.isEmpty()) {
                statusText.text = getString(R.string.search_status_no_results, query) + "\n" +
                    getString(R.string.search_status_no_results_hint)
                statusText.visibility = View.VISIBLE
                resultsList.visibility = View.GONE
            } else {
                searchAllResults = all
                val batchSize = 50
                val firstBatch = all.take(batchSize)
                searchDisplayedCount = firstBatch.size
                statusText.text = getString(R.string.search_status_count, searchDisplayedCount, all.size)
                statusText.visibility = View.VISIBLE
                resultsList.visibility = View.VISIBLE
                adapter.submitList(firstBatch)
            }
        }
        // P2-1: media matches land immediately; the EPG phase below fetches short guides
        // for up to 10 channels over the network and can take seconds on a slow panel -
        // don't make the user stare at "Searching…" the whole time. When there are no
        // media matches, wait for EPG before declaring "no results" - it may still match.
        if (mediaResults.isNotEmpty()) publish(mediaResults)
        val epgResults = buildEpgSearchResults(query, filter)
        if (runId != searchRunId) return@launch
        publish(mediaResults + epgResults)
    }
}

/** EPG search: matches TV programs across the guide. Sources: the already-loaded
 *  in-memory EpgListCache (channels the user has scrolled past in the guide), plus
 *  on-demand short-EPG fetches for the first few channels whose NAME matches the query
 *  (a program-title match for a channel that was never visited requires its guide to
 *  exist, so fetching it here is what makes the result appear). Program-title matches
 *  qualify directly; a channel-name match contributes its current and next program.
 *  Capped at 30 results and 10 fetches - EPG search is a bonus surface, not a second
 *  catalog, and each fetch is a network call. */
internal suspend fun MainActivity.buildEpgSearchResults(query: String, filter: MainActivity.SearchFilter): List<SearchResultItem.Epg> {
    if (filter == MainActivity.SearchFilter.MOVIE || filter == MainActivity.SearchFilter.SERIES) return emptyList()
    val lower = query.lowercase()
    val nowSeconds = System.currentTimeMillis() / 1000
    val results = linkedMapOf<String, SearchResultItem.Epg>()

    fun add(channel: Channel, program: XtreamClient.EpgProgram) {
        results["${channel.id}:${program.startTimestamp}"] = SearchResultItem.Epg(
            SearchEpgResult(
                programTitle = program.title,
                metaLine = "${channel.name} · ${formatEpgTimeRange(program.startTimestamp, program.stopTimestamp)}",
                channel = channel
            )
        )
    }

    fun addPrograms(channel: Channel, programs: List<XtreamClient.EpgProgram>) {
        val nameMatches = channel.name.lowercase().contains(lower)
        var addedUpcoming = false
        for (p in programs) {
            when {
                p.title.lowercase().contains(lower) -> add(channel, p)
                nameMatches && p.isNowAiring(nowSeconds) -> add(channel, p)
                nameMatches && !addedUpcoming && p.startTimestamp > nowSeconds -> {
                    add(channel, p)
                    addedUpcoming = true
                }
            }
            if (results.size >= MAX_EPG_SEARCH_RESULTS) return
        }
    }

    // Already-loaded EPG (channels the guide has visited).
    for (ch in liveChannels) {
        EpgListCache.get(ch.id)?.let { addPrograms(ch, it) }
        if (results.size >= MAX_EPG_SEARCH_RESULTS) break
    }
    // On-demand: name-matching channels that have no guide data cached yet.
    val toFetch = liveChannels.asSequence()
        .filter { it.name.lowercase().contains(lower) && !EpgListCache.has(it.id) }
        .distinctBy { it.id }
        .take(MAX_EPG_SEARCH_FETCHES)
        .toList()
    for (ch in toFetch) {
        if (results.size >= MAX_EPG_SEARCH_RESULTS) break
        val programs = resolveEpgPrograms(ch.id) ?: continue
        // Share with the guide: the next time this row scrolls into view it's a cache hit.
        EpgListCache.put(ch.id, programs)
        addPrograms(ch, programs)
    }
    return results.values.toList()
}

internal fun MainActivity.loadMoreSearchResults(adapter: SearchResultsAdapter, statusText: TextView) {
    val remaining = searchAllResults.size - searchDisplayedCount
    if (remaining <= 0) return
    val batchSize = 50.coerceAtMost(remaining)
    val currentList = adapter.currentList.toMutableList()
    currentList.addAll(searchAllResults.subList(searchDisplayedCount, searchDisplayedCount + batchSize))
    searchDisplayedCount += batchSize
    adapter.submitList(currentList)
    statusText.text = getString(R.string.search_status_count, searchDisplayedCount, searchAllResults.size)
}

// ── Search recents ──────────────────────────────

internal fun MainActivity.searchRecents(): List<String> =
    getSharedPreferences("search_recents", Context.MODE_PRIVATE)
        .getString("list", "")?.split("\n").orEmpty().filter { it.isNotBlank() }

internal fun MainActivity.addSearchRecent(q: String) {
    if (q.isBlank()) return
    val prefs = getSharedPreferences("search_recents", Context.MODE_PRIVATE)
    prefs.edit().putString("list", (listOf(q) + searchRecents()).distinct().take(10).joinToString("\n")).apply()
}

internal fun MainActivity.clearSearchRecents() {
    getSharedPreferences("search_recents", Context.MODE_PRIVATE).edit().remove("list").apply()
}
