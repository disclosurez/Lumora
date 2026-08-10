package com.lumora

import android.app.AlertDialog
import android.content.res.Configuration
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.updateLayoutParams
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lumora.adapter.DYNAMIC_BUCKET_ID_PREFIX
import com.lumora.adapter.LiveGuideAdapter
import com.lumora.adapter.PosterGridAdapter
import com.lumora.adapter.SideMenuCategoryAdapter
import com.lumora.cache.DerivedCache
import com.lumora.cache.ProgramReminder
import com.lumora.reminder.ReminderScheduler
import com.lumora.cache.FavoritesStore
import com.lumora.model.CategoryFilter
import com.lumora.model.Channel
import com.lumora.model.ContentShelf
import com.lumora.model.MediaType
import com.lumora.anime.AnimeCatalogClient
import com.lumora.parser.XtreamClient
import com.lumora.util.deriveBrandCategories
import com.lumora.util.groupCategories
import com.lumora.util.normalizeLiveChannelKey
import com.lumora.util.groupSeriesFilmCategories
import com.lumora.util.CategoryGroup
import com.lumora.util.newestByDate
import com.lumora.util.cleanVodCategoryLabel
import com.lumora.util.isAdultCategory
import com.lumora.util.groupLiveQualityVersions
import kotlinx.coroutines.*

// ── Category rail, tabs & side-menu drill-down ──
//
// Extracted from MainActivity.kt; see that file's header.
internal fun MainActivity.fullListForTab(tab: Int): List<Channel> = when (tab) {
    0 -> liveChannels
    1 -> seriesList
    2 -> filmList
    else -> emptyList()
}

internal fun MainActivity.pinnedCategoriesPrefsKey(tab: Int = activeTab): String = when (tab) {
    0 -> "pinned_categories_live"
    1 -> "pinned_categories_series"
    else -> "pinned_categories_films"
}

internal fun MainActivity.getPinnedCategories(tab: Int = activeTab): MutableSet<String> =
    prefs.getStringSet(pinnedCategoriesPrefsKey(tab), emptySet())?.toMutableSet() ?: mutableSetOf()

internal fun MainActivity.hiddenCategoriesPrefsKey(tab: Int = activeTab): String = when (tab) {
    0 -> "hidden_categories_live"
    1 -> "hidden_categories_series"
    else -> "hidden_categories_films"
}

internal fun MainActivity.getHiddenCategories(tab: Int = activeTab): MutableSet<String> =
    prefs.getStringSet(hiddenCategoriesPrefsKey(tab), emptySet())?.toMutableSet() ?: mutableSetOf()

// Newest/Favourites are synthetic shelves with no sidebar row id - pinning/hiding them
// falls back to the legacy title-based prefs (inert, as it always was: nothing matches
// those titles in the row pipeline).
internal fun MainActivity.togglePinnedShelf(tab: Int, title: String) {
    val pinned = getPinnedCategories(tab)
    if (!pinned.remove(title)) pinned.add(title)
    prefs.edit().putStringSet(pinnedCategoriesPrefsKey(tab), pinned).apply()
    scope.launch { classifyAndShow() }
}

internal fun MainActivity.toggleHiddenShelf(tab: Int, title: String) {
    val hidden = getHiddenCategories(tab)
    if (!hidden.remove(title)) hidden.add(title)
    prefs.edit().putStringSet(hiddenCategoriesPrefsKey(tab), hidden).apply()
    val label = if (title in getHiddenCategories(tab)) "Hidden \"$title\"" else "Unhidden \"$title\""
    Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
    scope.launch { classifyAndShow() }
}

/** Pin a Series/Films poster shelf. Shelves ARE sidebar rows now, so the pin routes
 *  through the shelf's row id into the same per-tab prefs the sidebar uses. Shelves
 *  without a row id (Newest/Favourites) fall back to the legacy title-based pin. */
internal fun MainActivity.togglePinShelfCategory(tab: Int, shelf: ContentShelf) {
    val id = shelf.categoryId
    if (id == null) {
        togglePinnedShelf(tab, shelf.title)
        return
    }
    togglePinCategory(CategoryFilter(id = id, name = shelf.title, count = shelf.items.size), tab)
}

internal fun MainActivity.toggleHiddenShelfCategory(tab: Int, shelf: ContentShelf) {
    val id = shelf.categoryId
    if (id == null) {
        toggleHiddenShelf(tab, shelf.title)
        return
    }
    toggleHiddenSidebarCategory(CategoryFilter(id = id, name = shelf.title, count = shelf.items.size), tab)
}

internal fun MainActivity.togglePinCategory(category: CategoryFilter, tab: Int = activeTab) {
    val id = category.id ?: return
    val pinned = getPinnedCategories(tab)
    val pinningNow = !pinned.remove(id)
    if (pinningNow) pinned.add(id)
    prefs.edit().putStringSet(pinnedCategoriesPrefsKey(tab), pinned).apply()
    // The rebuild that follows can take a moment on a big catalog, and the row only
    // moves once it lands - without a word on screen a hold looked like it did nothing.
    Toast.makeText(
        this,
        if (pinningNow) "Pinned \"${category.name}\" to top" else "Unpinned \"${category.name}\"",
        Toast.LENGTH_SHORT
    ).show()
    scope.launch { rebuildCategoriesForActiveTab() }
}

/** Hides a sidebar category row - a merged "group:" parent hides every raw category
 *  folded into it (matchIds), a plain leaf just hides itself. */
internal fun MainActivity.toggleHiddenSidebarCategory(category: CategoryFilter, tab: Int = activeTab) {
    val ids = category.matchIds.ifEmpty { category.id?.let { setOf(it) } ?: return }
    val hidden = getHiddenCategories(tab)
    val hidingNow = ids.none { it in hidden }
    if (hidingNow) hidden.addAll(ids) else hidden.removeAll(ids)
    prefs.edit().putStringSet(hiddenCategoriesPrefsKey(tab), hidden).apply()
    Toast.makeText(this, if (hidingNow) "Hidden \"${category.name}\"" else "Unhidden \"${category.name}\"", Toast.LENGTH_SHORT).show()
    scope.launch { rebuildCategoriesForActiveTab() }
}

/** Films/Series long-press menu - sidebar row is a single TextView with no room for
 *  inline icon buttons like the shelf headers have, so pin/hide live behind a chooser. */
internal fun MainActivity.showCategoryContextMenu(category: CategoryFilter) {
    val id = category.id ?: return
    // Utility rows (collapse rail, classic-layout toggle) act on the rail rather than
    // filtering it. Pin is inert for them and Hide is destructive-and-unrecoverable, so
    // they get no menu at all rather than one with two bad options.
    if (id in UTILITY_ROW_IDS) return
    // The Jellyfin row is always first by construction, so "Pin to top" would be a
    // no-op - hiding it is the only meaningful action. Same for the synthetic Newest and
    // Continue Watching rows: they're prepended above the pinned block, so pinning them
    // moves them nowhere, and pin is inert for them anyway (guards in
    // buildCategoriesForActiveTab skip rows whose id is pinned).
    if (id == JELLYFIN_CATEGORY_ID || id == NEWEST_CATEGORY_ID || id == CONTINUE_WATCHING_CATEGORY_ID) {
        AlertDialog.Builder(this)
            .setTitle(category.name)
            .setItems(arrayOf("Hide")) { _, _ -> toggleHiddenSidebarCategory(category) }
            .show()
        return
    }
    val isPinned = id in getPinnedCategories()
    val hideIds = category.matchIds.ifEmpty { setOf(id) }
    val isHidden = hideIds.any { it in getHiddenCategories() }
    val options = arrayOf(
        if (isPinned) "Unpin" else "Pin to top",
        if (isHidden) "Unhide" else "Hide"
    )
    AlertDialog.Builder(this)
        .setTitle(category.name)
        .setItems(options) { _, which ->
            when (which) {
                0 -> togglePinCategory(category)
                1 -> toggleHiddenSidebarCategory(category)
            }
        }
        .show()
}

internal fun MainActivity.toggleFavoriteChannel(channel: Channel) {
    if (channel.id.isBlank()) return
    val nowFavorite = FavoritesStore.toggleFavoriteChannel(this, channel.id)
    Toast.makeText(
        this,
        if (nowFavorite) "Added to Favourites" else "Removed from Favourites",
        Toast.LENGTH_SHORT
    ).show()
    if (activeTab == 0) scope.launch { rebuildCategoriesForActiveTab() }
    refreshHomeShelvesIfShowing()
    // The guide's per-row star reads the favourite store at bind time - repaint the
    // list so the toggle lands immediately (submitList diff on the same list is a no-op).
    if (activeTab == 0) liveAdapter.notifyDataSetChanged()
}

/** Long-press handler for any VOD poster (Home/Series/Films shelves and the category
 *  grids). Live entries go through [toggleFavoriteChannel] so a favourited channel lands
 *  in the same set the Live TV Favourites category reads; films and series share the
 *  favourite-series set, which is what both the detail screen's star and the Home
 *  Favorites shelf use. */
internal fun MainActivity.toggleFavoriteVodItem(item: Channel) {
    if (item.id.isBlank()) return
    if (item.mediaType == MediaType.LIVE) {
        toggleFavoriteChannel(item)
        return
    }
    val nowFavorite = FavoritesStore.toggleFavoriteSeries(this, item.id)
    Toast.makeText(
        this,
        if (nowFavorite) "Added to Favourites" else "Removed from Favourites",
        Toast.LENGTH_SHORT
    ).show()
    // Same server push the detail screen's star does - a Jellyfin item's favourite state
    // belongs to the server, not to this install.
    if (item.isJellyfin) {
        scope.launch {
            val client = jellyfinClientOrConnect() ?: return@launch
            withContext(Dispatchers.IO) { runCatching { client.setFavorite(item.id, nowFavorite) } }
        }
    }
    refreshHomeShelvesIfShowing()
    // Rebuilds the Series/Films shelves so the "Favourites" shelf at their top picks the
    // change up without a tab switch. Only worth doing on those tabs - Home is handled
    // above, and Live TV has no VOD shelf to redraw.
    if (!showingHome && activeTab != 0) scope.launch { classifyAndShow() }
}

/** Home is built once, in [selectHome] - anything that changes what belongs on a shelf
 *  while Home is on screen has to ask for it again or the change isn't visible until the
 *  user leaves and comes back. */
internal fun MainActivity.refreshHomeShelvesIfShowing() {
    if (showingHome) homeShelfAdapter.submitList(buildHomeShelves())
}

/** Series-shelf counterpart of [refreshHomeShelvesIfShowing]: Continue Watching (and
 *  favourites/newest) move when playback ends, so the Series poster needs the same
 *  lightweight refresh. Rebuilds the shelf list from cachedSeriesCategoryRows - never
 *  re-runs the expensive buildCategoryRows() pass. */
internal fun MainActivity.refreshSeriesShelvesIfShowing() {
    if (showingHome || activeTab != 1) return
    if (binding.seriesContent.visibility != View.VISIBLE) return
    val favoriteSeries = seriesList.filter { it.id in FavoritesStore.getFavoriteSeriesIds(this) }
    val newestSeries = newestByDate(seriesList)
    val shelves = shelvesFromCategoryRows(cachedSeriesCategoryRows, seriesList)
        .let { s -> (if (newestSeries.isEmpty()) s else listOf(ContentShelf("Newest", newestSeries, categoryId = NEWEST_CATEGORY_ID)) + s) }
        .let { s ->
            val cw = seriesContinueItems()
            if (cw.isEmpty()) s else listOf(ContentShelf("Continue Watching", cw)) + s
        }
        .let { s -> if (favoriteSeries.isEmpty()) s else listOf(ContentShelf("Favourites", favoriteSeries)) + s }
    seriesShelves = shelves
    seriesShelfAdapter.submitList(shelves)
}

/** Long-press a future guide block to arm/disarm a 5-min-before notification for it. */
internal fun MainActivity.toggleProgramReminder(channel: Channel, program: XtreamClient.EpgProgram) {
    if (channel.id.isBlank()) return
    val reminder = ProgramReminder(channel.id, channel.name, program.title, program.startTimestamp)
    if (ReminderScheduler.isScheduled(this, reminder.key)) {
        ReminderScheduler.cancel(this, reminder)
        Toast.makeText(this, "Reminder cancelled", Toast.LENGTH_SHORT).show()
    } else {
        val scheduled = ReminderScheduler.schedule(this, reminder)
        Toast.makeText(
            this,
            if (scheduled) "Reminder set for \"${program.title}\""
            else "Program starts too soon - reminder not set",
            Toast.LENGTH_SHORT
        ).show()
    }
    liveAdapter.notifyDataSetChanged()
}

internal fun MainActivity.liveCategoryPriority(name: String): Int {
    if (isAdultCategory(name)) return 3
    val lower = name.lowercase()
    return when {
        lower.contains("sport") -> 0
        lower.contains("uk") -> 1
        else -> 2
    }
}

/** Building the category list scans the whole active tab's content - real work on a big catalog. */
/** rebuildCategoriesForActiveTab() plus submitting the result to the sidebar - split out
 *  so callers that need to inspect/compute a target category *before* anything renders
 *  (see selectTab()'s default-Sports-category lookup) can do so without each intermediate
 *  lookup flashing onto the sidebar as a real, visible submitList(). */
internal suspend fun MainActivity.rebuildCategoriesForActiveTab(): List<CategoryFilter> {
    val categories = buildCategoriesForActiveTab()
    submitCategories(categories)
    return categories
}

/** Just the sidebar-render step, split out so a caller that already has a freshly-built
 *  list (selectTab()'s default-category lookup) can render it without recomputing -
 *  buildCategoriesForActiveTab() rescans every channel in the tab (brand clustering in
 *  particular is O(channel count)), so on a large catalog that's real time saved. */
internal fun MainActivity.submitCategories(categories: List<CategoryFilter>) {
    // Home, Discover and Downloads are not categorized tabs and have no sidebar. Every
    // caller here is asynchronous, so any of them can land after the user has left the tab
    // the categories were built for - and the sidebar must not reappear over a pane that
    // never had one.
    val onCategorizedTab = !showingHome && !showingDiscover && !showingDownloads && !showingCatchup
    // Collapse composes on top of the tab-context decision here - this is the
    // single canonical re-show point for the rail, so the collapsed pref applies
    // everywhere a tab is (re)built (tab switch, category rebuild, catalog refresh).
    applySidebarVisibility(onCategorizedTab && categories.size > 1)
    // submitList uses AsyncListDiffer which commits the list asynchronously.
    // Set the selected highlight only after the list is committed, otherwise
    // the diff callback can reset the adapter's selected state.
    categoryAdapter.submitList(categories) {
        if (selectedRowId != null) {
            categoryAdapter.setSelected(selectedRowId)
        }
    }
}

/** Phone (not TV) held in portrait - the one case where the rail is hidden by default
 *  rather than by the user's persisted choice. */
internal fun MainActivity.isPortraitPhone(): Boolean =
    !isTv && resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

/** Whether the category rail is collapsed on this device: a persisted pref, except on a
 *  portrait phone, where the rail auto-hides and only the transient (unpersisted)
 *  [MainActivity.portraitSidebarExpanded] can bring it back. Keeping portrait out of the
 *  pref means hiding/showing it there never rewrites what landscape and TV see. */
internal fun MainActivity.isSidebarCollapsed(): Boolean =
    if (isPortraitPhone()) !portraitSidebarExpanded
    else prefs.getBoolean(PREF_CATEGORY_SIDEBAR_COLLAPSED, false)

/** Base (XML) paddingTop of each content view, recorded the first time this device
 *  touches it - so the reserve added below can be undone exactly rather than guessed. */
private val contentBasePaddingTop = mutableMapOf<View, Int>()

/** Single place that decides the sidebar's visibility for a categorized tab: the
 *  tab-context decision (categorized tab with more than one row) comes in as
 *  [tabWantsSidebar], and the collapse pref composes on top. Also drives the
 *  re-expand affordance, which only exists when a categorized tab's rail is collapsed.
 *
 *  The re-expand pill floats over the content (see sidebarExpandButton's layout doc),
 *  and every content view's top row starts within a few dp of the top edge - collapsing
 *  used to drop the pill straight on top of the first channel/poster. Reserving the
 *  pill's footprint as extra top padding on the now-visible content keeps it in its own
 *  space instead of overlapping a real row. Live TV reserves on liveGuideColumn (the
 *  ruler + guide together), not liveContent alone - liveContent already sits below the
 *  ruler, so padding it left the ruler itself, and the pill riding above it, untouched. */
internal fun MainActivity.applySidebarVisibility(tabWantsSidebar: Boolean) {
    lastTabWantsSidebar = tabWantsSidebar
    val collapsed = isSidebarCollapsed()
    binding.categorySidebar.visibility = if (tabWantsSidebar && !collapsed) View.VISIBLE else View.GONE
    binding.sidebarExpandButton.visibility = if (tabWantsSidebar && collapsed) View.VISIBLE else View.GONE
    if (tabWantsSidebar) {
        val content: View = when (activeTab) {
            1 -> binding.seriesContent
            2 -> binding.filmsContent
            else -> binding.liveGuideColumn
        }
        val baseTop = contentBasePaddingTop.getOrPut(content) { content.paddingTop }
        val reservePx = (56 * resources.displayMetrics.density).toInt()
        val newTop = if (collapsed) baseTop + reservePx else baseTop
        if (content.paddingTop != newTop) {
            content.setPadding(content.paddingLeft, newTop, content.paddingRight, content.paddingBottom)
        }
    }
}

/** Collapses the category rail: the current selection/filter stays applied
 *  (no applyCategoryFilter call - only the rail hides), the state persists, and focus
 *  moves to the re-expand button since the focused sidebar row is about to disappear.
 *  Landing on the button (rather than the content's first row) keeps the content's
 *  scroll position untouched - focusing a scrolled-off item would yank the list to it. */
internal fun MainActivity.collapseCategorySidebar() {
    if (isPortraitPhone()) portraitSidebarExpanded = false
    else prefs.edit().putBoolean(PREF_CATEGORY_SIDEBAR_COLLAPSED, true).apply()
    applySidebarVisibility(tabWantsSidebar = true)
    binding.sidebarExpandButton.requestFocus()
    // Names the way back at the one moment the user is guaranteed to be looking at this
    // corner of the screen - an accidental collapse otherwise reads as a dead end, since
    // the rail vanishing is a big change and the pill replacing it is a small one.
    Toast.makeText(this, R.string.categories_hidden_hint, Toast.LENGTH_SHORT).show()
}

/** Categories for [tab]. Defaults to the tab on screen; the player side menu passes an
 *  explicit tab so it can list Series/Films categories while Live is what's playing. */
internal suspend fun MainActivity.buildCategoriesForActiveTab(tab: Int = activeTab): List<CategoryFilter> {
    val startedAt = System.currentTimeMillis()
    val list = fullListForTab(tab)
    val pinned = getPinnedCategories(tab)
    val hiddenIds = getHiddenCategories(tab)
    val expandedSnapshot = expandedGroupKeys.toSet()
    val favoriteChannelIds = if (tab == 0) FavoritesStore.getFavoriteChannelIds(this) else emptySet()
    val animeSectionsSnapshot = animeSections
    // Snapshot on the caller's thread - the pipeline below runs on Dispatchers.Default.
    val versionsById = when (tab) {
        1 -> seriesVersions
        2 -> filmVersions
        else -> emptyMap()
    }
    val useClassicLayout = tab == 0 && prefs.getBoolean(PREF_CLASSIC_CATEGORY_LAYOUT, false)
    val categorize = if (tab == 0) prefs.getBoolean(PREF_CATEGORIZE_LIVE, true) else prefs.getBoolean(PREF_CATEGORIZE_VOD, true)
    // Synthetic Films/Series sidebar rows are computed on the same Default thread as the
    // category pipeline: newestByDate sorts the whole tab list, and seriesContinueItems()
    // reads the position store. Both prepend ABOVE Jellyfin, so the sidebar leads
    // Continue Watching > Newest > Jellyfin > the real categories.
    val (result, newestByTab, seriesContinue) = withContext(Dispatchers.Default) {
        val rows = buildCategoryRows(
            list = list,
            versionsById = versionsById,
            tab = tab,
            pinned = pinned,
            hiddenIds = hiddenIds,
            expanded = expandedSnapshot,
            animeSections = animeSectionsSnapshot,
            useClassicLayout = useClassicLayout,
            favoriteChannelIds = favoriteChannelIds,
            categorize = categorize
        )
        Triple(
            rows,
            if (tab != 0) newestByDate(list) else emptyList(),
            if (tab == 1) seriesContinueItems() else emptyList()
        )
    }
    // The children cache backs the on-screen sidebar's expand/collapse - only the tab
    // that owns the sidebar may write it (the side menu builds other tabs too).
    if (tab == activeTab) categoryChildrenCache = result.childrenByParent
    // Guarded on pinned too: the legacy title-folding (buildCategoryRows) maps a pinned
    // "Newest"/"Continue Watching" shelf title onto a real row id, and a folded row would
    // collide with the synthetic one below - skip ours when the id is already pinned.
    val rows = result.rows.toMutableList()
    if (tab != 0 && NEWEST_CATEGORY_ID !in hiddenIds && NEWEST_CATEGORY_ID !in pinned) {
        rows.add(
            0,
            CategoryFilter(
                id = NEWEST_CATEGORY_ID,
                name = "Newest",
                count = newestByTab.size,
                channelIds = newestByTab.map { it.id }.toSet(),
                isDynamic = true
            )
        )
    }
    // Continue Watching has no channelIds/matchIds - selecting it is a special case in
    // applyCategoryFilter (its episodes are not seriesList members). Only added while it
    // has items, like the Jellyfin row is only added while the tab has Jellyfin content.
    if (tab == 1 && CONTINUE_WATCHING_CATEGORY_ID !in hiddenIds && CONTINUE_WATCHING_CATEGORY_ID !in pinned) {
        if (seriesContinue.isNotEmpty()) {
            rows.add(
                0,
                CategoryFilter(
                    id = CONTINUE_WATCHING_CATEGORY_ID,
                    name = "Continue Watching",
                    count = seriesContinue.size,
                    isDynamic = true
                )
            )
        }
    }
    perf("buildCategories(tab=$tab)", startedAt, "${rows.size} rows from ${list.size} items")
    return rows
}

/** Pure ordering pipeline behind the sidebar - shared with the Series/Films poster
 *  shelves (see computeDerivedContent) so both render the same categories in the same
 *  order, by construction. No prefs/state reads: every caller passes its own snapshots. */
internal fun MainActivity.buildCategoryRows(
    list: List<Channel>,
    versionsById: Map<String, List<Channel>>,
    tab: Int,
    pinned: Set<String>,
    hiddenIds: Set<String>,
    expanded: Set<String>,
    animeSections: List<AnimeCatalogClient.Section>,
    useClassicLayout: Boolean,
    favoriteChannelIds: Set<String>,   // tab 0 only
    categorize: Boolean   // render dynamic categories (buckets/brands/service clusters) for this tab
): MainActivity.CategoryBuildResult {
        // Rows are a pure function of these arguments, and the pass behind them (brand
        // clustering walks every live channel name twice) was the last multi-second item
        // on a cold start. Restore the last result whenever every input still matches.
        val startedAt = System.currentTimeMillis()
        val fingerprint = DerivedCache.catalogFingerprint(
            list,
            // The rows are a function of this function's *code* as much as of its
            // arguments - which rows exist at all (the collapse row, say) changes between
            // builds while catalog and prefs stay identical, so a fingerprint over inputs
            // alone keeps restoring rows built by older logic forever.
            //
            // Both parts are needed: versionCode covers released upgrades, and
            // CATEGORY_ROWS_LOGIC_VERSION covers changes made *within* one versionCode -
            // every development build carries the same versionCode, so on its own it
            // silently failed to invalidate anything. Bump it whenever what this function
            // emits changes.
            "rows:$tab:${BuildConfig.VERSION_CODE}.$CATEGORY_ROWS_LOGIC_VERSION:${pinned.hashCode()}:${hiddenIds.hashCode()}:" +
                "${expanded.hashCode()}:${animeSections.hashCode()}:${favoriteChannelIds.hashCode()}:" +
                "${versionsById.size}:$useClassicLayout:$categorize"
        )
        DerivedCache.loadRows(this, tab, fingerprint)?.let { cached ->
            perf("buildCategoryRows(cached,tab=$tab)", startedAt, "${cached.rows.size} rows")
            return MainActivity.CategoryBuildResult(cached.rows, cached.childrenByParent)
        }

        val names = LinkedHashMap<String, String>()
        val counts = LinkedHashMap<String, Int>()
        for (ch in list) {
            // Anime gets its own explicit parent row further down; leaving it in here as an
            // ordinary category would also file it under the "Kids & Family" genre bucket
            // (which matches on the word "anime"), so the catalog would appear twice, once
            // buried two levels deep.
            if (ch.id.startsWith(AnimeCatalogClient.ID_PREFIX)) continue
            val key = ch.filterKey() ?: continue
            if (key in hiddenIds) continue
            val rawLabel = ch.categoryName?.takeIf { it.isNotBlank() } ?: ch.group?.takeIf { it.isNotBlank() } ?: key
            // Films/Series only: strip leading provider decoration ("VOD | ", "EN - ",
            // "4K-D+ - ") so noisy panels get a clean, consistent sidebar and more categories
            // fall into the genre buckets. Live keeps its raw names - its leading country tags
            // ("UK|", "US:") are the grouping people actually want there.
            val label = if (tab != 0) cleanVodCategoryLabel(rawLabel) else rawLabel
            names.putIfAbsent(key, label)
            counts[key] = (counts[key] ?: 0) + 1
        }
        val leaves = names.entries.map { (key, label) ->
            CategoryFilter(id = key, name = label, count = counts[key] ?: 0, pinned = pinned.contains(key), matchIds = setOf(key))
        }

        // Each "unit" is one sidebar row candidate paired with its raw (ungrouped)
        // members, so it can be expanded into isChild rows later whether it ends up
        // top-level (classic layout) or nested under a dynamic bucket.
        fun groupUnit(group: CategoryGroup): Pair<CategoryFilter, List<CategoryFilter>> {
            if (group.members.size == 1 && !group.isCluster) return group.members.first() to group.members
            val groupId = "group:${group.label}"
            val parent = CategoryFilter(
                id = groupId,
                name = group.label,
                count = group.members.sumOf { it.count },
                pinned = pinned.contains(groupId),
                matchIds = group.members.flatMap { it.matchIds }.toSet(),
                isParent = true,
                expanded = expanded.contains(groupId),
                // Clustered groups use a merged label; a plain merged group is still
                // the provider's own category name, just deduplicated.
                isDynamic = group.isCluster
            )
            return parent to group.members
        }

        // Every parent's child rows, whether or not it's currently expanded, so a later
        // expand can splice them straight in instead of rebuilding (see onCategoryClick).
        val childrenByParent = mutableMapOf<String, List<CategoryFilter>>()

        /** One parent's child rows: same-named leaves folded into one, biggest first.
         *
         *  A cluster's members are the raw leaves of the tier groups it absorbed
         *  (groupSeriesFilmCategories flatMaps `group.members`), so a merge that already
         *  happened - "NETFLIX ACTION" and "Netflix Action" becoming one row - is undone at
         *  the moment the cluster is expanded, and both spellings are listed as separate
         *  children of it.
         *
         *  Folded on the name as written, case-insensitively, and deliberately not on the
         *  normalized key the parents use: that key also equates a category with its quality
         *  tiers ("Action" and "Action 4K"), and those are exactly what the child rows exist
         *  to let someone pick between. Counts add up rather than being unioned - leaves are
         *  one per filterKey, so two of them never hold the same title. */
        fun childRowsOf(members: List<CategoryFilter>): List<CategoryFilter> {
            val byName = LinkedHashMap<String, CategoryFilter>()
            for (member in members) {
                val key = member.name.trim().lowercase()
                val existing = byName[key]
                byName[key] = if (existing == null) member else existing.copy(
                    matchIds = existing.matchIds + member.matchIds,
                    channelIds = existing.channelIds + member.channelIds,
                    count = existing.count + member.count
                )
            }
            return byName.values
                .sortedWith(compareByDescending<CategoryFilter> { it.count }.thenBy { it.name.lowercase() })
                .map { it.copy(isChild = true) }
        }

        fun expandUnit(unit: Pair<CategoryFilter, List<CategoryFilter>>): List<CategoryFilter> {
            val (row, rawMembers) = unit
            if (!row.isParent) return listOf(row)
            val children = childRowsOf(rawMembers)
            row.id?.let { childrenByParent[it] = children }
            return if (row.expanded) listOf(row) + children else listOf(row)
        }

        // Categories are frequently the same content repeated per quality tier
        // ("Sport HD"/"Sport SD"/"Sport RAW") or near-duplicate spellings - merge
        // those into expandable parents on every tab, not just Live TV, or picking
        // one from the Films/Series sidebar only grabs one narrow raw slice instead
        // of the full category.
        // Brand/franchise clusters cut across whatever provider category each channel is
        // actually filed under, so they're synthesized from the raw channel list
        // directly rather than from the grouped category rows. Live TV only - a
        // "brand" concept doesn't map onto film/series categories.
        val groupUnits = if (useClassicLayout) {
            // Classic: show every raw provider category individually, no merging
            leaves.map { it to emptyList<CategoryFilter>() }
        } else {
            // A pin is recorded against a *raw* category id (togglePinCategory stores
            // category.id, and in the classic list that's the leaf key). Grouping then
            // folded that exact leaf into a "group:<label>" parent whose own id was
            // never pinned, so pinning something in the classic view and switching back
            // here made it disappear: the pin still existed, but nothing at top level
            // carried it and the leaf itself only reappeared if you expanded the parent.
            // Holding pinned leaves out of the merge keeps them as their own rows, which
            // is what a pin means - and they then land in pinnedRows, directly beneath
            // the dynamic buckets.
            val (pinnedLeaves, groupableLeaves) = leaves.partition { it.id in pinned }
            val grouped = if (tab == 0 || !categorize) groupCategories(groupableLeaves) else groupSeriesFilmCategories(groupableLeaves)
            grouped.map(::groupUnit) + pinnedLeaves.map { it to emptyList<CategoryFilter>() }
        }
            val brandUnits = if (tab == 0 && !useClassicLayout && categorize) {
            deriveBrandCategories(list).map { (label, members) ->
                val brandId = "brand:$label"
                CategoryFilter(
                    id = brandId,
                    name = label,
                    count = members.size,
                    pinned = pinned.contains(brandId),
                    channelIds = members.map { it.id }.toSet(),
                    isDynamic = true
                ) to emptyList<CategoryFilter>()
            }
        } else {
            emptyList()
        }
        val allUnits = groupUnits + brandUnits

        // Every tab leads with a handful of dynamic buckets - Sports/News/Music/Cinema on
        // Live TV, genres on Films/Series - that vacuum up every matching category/brand
        // row regardless of which raw provider category it actually lives in; everything
        // left over cascades below, same priority order as before this existed. The
        // classic pref (Live TV only) bypasses this and shows the old flat/grouped list.
        val dynamicBuckets = if (tab == 0) LIVE_DYNAMIC_BUCKETS else VOD_DYNAMIC_BUCKETS
        // Categories promoted into a bucket row of their own (see below) - held out of the
        // cascade so they aren't listed twice.
        val promotedToBucket = mutableSetOf<String>()
        val (bucketRows, allUnitsEnhanced) = if (!useClassicLayout && categorize) {
            fun bucketFor(name: String): String? {
                val lower = name.lowercase()
                return dynamicBuckets.firstOrNull { (_, keywords) -> keywords.any { lower.contains(it) } }?.first
            }
            val bucketed = LinkedHashMap<String, MutableList<Pair<CategoryFilter, List<CategoryFilter>>>>()
            // Pinned categories are exempt. A pin is an explicit "keep this one where I
            // can reach it", and a bucket swallowed it into a collapsed parent - pin a
            // category from the classic list, switch back to the grouped view, and it
            // was gone from the top of the sidebar entirely. Skipping them here leaves
            // them in the remainder, which lands them in pinnedRows directly beneath
            // the buckets.
            // Brand rows are exempt for the same reason: they're a row in their own
            // right, listed above the buckets, not something to fold into a genre.
            allUnits.forEach { unit ->
                if (unit.first.pinned) return@forEach
                if (tab != 0 && unit.first.isDynamic) {
                    // The cluster row itself stays out of the buckets, but the categories
                    // inside it are exactly what a genre bucket should be offering - without
                    // this, everything a service cluster absorbed was reachable only by that
                    // service, and the genre rows were left with whatever no brand claimed.
                    unit.second.forEach inner@{ child ->
                        if (child.pinned) return@inner
                        bucketFor(child.name)?.let {
                            bucketed.getOrPut(it) { mutableListOf() }.add(child to emptyList())
                        }
                    }
                    return@forEach
                }
                bucketFor(unit.first.name)?.let { bucketed.getOrPut(it) { mutableListOf() }.add(unit) }
            }
            // Index channels by filterKey once, then resolve every bucket member's channel
            // ids by lookup: the previous per-unit `list.filter` rescan was O(bucketed
            // units x the whole list) and dominated the Films/Series sidebar+shelf build
            // on large catalogs. channelIds is consumed as a set below, so order within
            // one member's ids is irrelevant - the set of ids is unchanged (each channel
            // has exactly one filterKey, so no duplication is possible across matchIds).
            val filterKeyIndex = list.groupBy { it.filterKey() }.mapValues { (_, chs) -> chs.map { it.id } }
            // The channels one bucket member stands for, however it identifies them: a brand
            // row carries explicit channelIds, a category row carries filterKeys to resolve.
            fun memberChannelIds(row: CategoryFilter): List<String> {
                if (row.channelIds.isNotEmpty()) return row.channelIds.toList()
                val byKey = row.matchIds.flatMap { filterKeyIndex[it].orEmpty() }
                // Backup: match by categoryName in case filterKey is unreachable
                val byName = if (byKey.isEmpty() && row.name.isNotBlank()) {
                    list.filter { ch ->
                        ch.categoryName?.let { it.equals(row.name, ignoreCase = true) } == true
                    }.map { it.id }
                } else emptyList()
                return if (byName.isNotEmpty()) byName else byKey
            }
            /** Folds bucket members that name the same thing into one row.
             *
             *  A bucket collects rows of two kinds, and they can name the same thing: the
             *  provider's own category ("News"), and a brand cluster the app synthesised from
             *  channel names ("News", from every channel whose first word it is). Nothing had
             *  ever compared the two, and because a synthesised row renders uppercase while a
             *  provider category renders as written, the pair showed up under News as "NEWS"
             *  and "News" - visibly two rows of the same channels.
             *
             *  Merged on the same key groupCategories uses, so spelling drift and quality
             *  tiers fold together here exactly as they do there. The survivor keeps the
             *  first member's label - units arrive categories-first, so the provider's own
             *  wording wins over a synthesised one - and takes the union of both members'
             *  channels, which the row is already able to express: a row carrying both
             *  channelIds and matchIds selects on either (see applyCategoryFilter). */
            fun mergeSameNamedMembers(
                members: List<Pair<CategoryFilter, List<CategoryFilter>>>
            ): List<Pair<CategoryFilter, List<CategoryFilter>>> {
                if (members.size < 2) return members
                val byName = LinkedHashMap<String, Pair<CategoryFilter, List<CategoryFilter>>>()
                for (unit in members) {
                    val key = normalizeLiveChannelKey(unit.first.name).ifBlank { unit.first.id ?: unit.first.name }
                    val existing = byName[key]
                    if (existing == null) {
                        byName[key] = unit
                        continue
                    }
                    val ids = (memberChannelIds(existing.first) + memberChannelIds(unit.first)).toSet()
                    byName[key] = existing.first.copy(
                        channelIds = ids,
                        matchIds = existing.first.matchIds + unit.first.matchIds,
                        // Counts can't be added - the two rows overlap by definition, and a
                        // brand row's channels are usually a subset of the category's.
                        count = ids.size
                    ) to (existing.second + unit.second)
                }
                return byName.values.toList()
            }
            val rows = dynamicBuckets.mapNotNull { (label, _) ->
                val members = mergeSameNamedMembers(bucketed[label] ?: return@mapNotNull null)
                val bucketId = "$DYNAMIC_BUCKET_ID_PREFIX$label"
                val isExpanded = expanded.contains(bucketId)
                val channelIds = members.flatMap { (row, _) -> memberChannelIds(row) }.toSet()
                // A bucket holding one category is that category with a different name on it:
                // the wrapper costs a level to open and leaves the category itself listed
                // again further down. Promote the member into the bucket's slot instead.
                val single = members.singleOrNull()
                    ?.takeIf { tab != 0 && !it.first.pinned && !it.first.isDynamic }
                if (single != null) {
                    val (row, rawMembers) = single
                    row.id?.let { promotedToBucket.add(it) }
                    val promoted = row.copy(name = label, isDynamic = true)
                    return@mapNotNull if (!promoted.isParent) {
                        listOf(promoted)
                    } else {
                        // Its children are registered here rather than by expandUnit, which
                        // only ever sees the rows still left in the cascade - same folding
                        // and biggest-first order either way.
                        val children = childRowsOf(rawMembers)
                        promoted.id?.let { childrenByParent[it] = children }
                        if (promoted.expanded) listOf(promoted) + children else listOf(promoted)
                    }
                }
                val parent = CategoryFilter(
                    id = bucketId,
                    name = label,
                    count = members.sumOf { it.first.count },
                    pinned = pinned.contains(bucketId),
                    channelIds = channelIds,
                    isParent = true,
                    expanded = isExpanded,
                    isDynamic = true
                )
                // Biggest category first, alphabetical between equals. Inside a genre the
                // question is "where is most of this content", and alphabetical order
                // answered a different one - a two-channel category opened the row while the
                // one holding hundreds sat further down.
                // Built even while collapsed, and cached, so expanding is a splice
                // rather than a full rescan of the tab.
                val children = members.map { it.first.copy(isChild = true, isParent = false, expanded = false) }
                    .sortedWith(compareByDescending<CategoryFilter> { it.count }.thenBy { it.name.lowercase() })
                childrenByParent[bucketId] = children
                if (isExpanded) listOf(parent) + children else listOf(parent)
            }.flatten()
            // Brand-row channels from a bucket should also be reachable from that
            // bucket's classic provider categories. For each classic leaf inside a
            // bucket that has brand rows, add the brand channel IDs to the leaf's
            // channelIds so both the bucket AND the classic category show them.
            val enhancedUnits = if (tab == 0) {
                val bucketExtra = mutableMapOf<String, MutableSet<String>>()
                for ((label, _) in dynamicBuckets) {
                    val bucketMembers = bucketed[label] ?: continue
                    val brandIds = bucketMembers.filter { (row, _) -> row.channelIds.isNotEmpty() }
                        .flatMap { (row, _) -> row.channelIds }.toSet()
                    if (brandIds.isEmpty()) continue
                    for ((row, _) in bucketMembers.filter { (row, _) -> row.channelIds.isNullOrEmpty() }) {
                        row.id?.let { id -> bucketExtra.getOrPut(id) { mutableSetOf() }.addAll(brandIds) }
                    }
                }
                if (bucketExtra.isNotEmpty()) {
                    allUnits.map { unit ->
                        val (row, children) = unit
                        val extra = bucketExtra[row.id] ?: return@map unit
                        row.copy(channelIds = row.channelIds + extra) to children
                    }
                } else allUnits
            } else allUnits
            rows to enhancedUnits
        } else {
            emptyList<CategoryFilter>() to allUnits
        }
        // Series/Films: merged (grouped) categories surface above plain single-provider
        // leaves, alphabetical within each cluster - sorted here, at the unit level,
        // so an expanded parent's own children stay adjacent to it (sorting the already-
        // flattened rows would scatter them back in with unrelated leaves by name).
        // Channels in dynamic buckets should also appear in their original provider
        // categories below the buckets - don't filter out bucketed units here, except the
        // ones a bucket promoted into a row of their own.
        val leftoverUnits = allUnitsEnhanced.filterNot { it.first.id in promotedToBucket }
        // Series/Films: clustered service categories go above the genre buckets -
        // they're the rows people go looking for by name - and the provider's own
        // categories below both. Splitting them out here rather than
        // sorting them to the front keeps the three blocks separable at assembly time.
        val (serviceUnits, plainUnits) =
            if (tab != 0 && categorize) leftoverUnits.partition { it.first.isDynamic } else emptyList<Pair<CategoryFilter, List<CategoryFilter>>>() to leftoverUnits
        val remainderUnits = plainUnits
            .let { units ->
                if (tab != 0) units.sortedWith(
                    compareBy(
                        // Films/Series only: a category with a handful of titles in it is
                        // never what someone is scrolling the rail for, and a long tail of
                        // them buried the real categories. Thin ones sink below the rest,
                        // adult ones stay below everything (0 = normal, 1 = thin, 2 = adult).
                        {
                            when {
                                isAdultCategory(it.first.name) -> 2
                                it.first.count in 0 until SMALL_VOD_CATEGORY_THRESHOLD -> 1
                                else -> 0
                            }
                        },
                        { if (it.first.isParent) 0 else 1 },
                        // Categories by size, biggest first - the rows right after the
                        // Jellyfin/anime blocks are the ones people actually browse, so
                        // the fullest category leads instead of an arbitrary alphabetical one.
                        { -it.first.count },
                        { it.first.name.lowercase() }
                    )
                )
                else units
            }
        val brandRows = serviceUnits.sortedBy { it.first.name.lowercase() }.flatMap(::expandUnit)
        // Films/Series: the sub-threshold tail folds into one expandable row instead of
        // taking a dozen lines of rail for a handful of titles each. Pinned categories are
        // exempt (a pin asks for a row of its own) and so are adult ones, which stay below
        // everything where the hide/parental machinery expects to find them.
        val (thinUnits, mainUnits) = if (tab != 0 && categorize) {
            remainderUnits.partition {
                !it.first.pinned && !isAdultCategory(it.first.name) &&
                    it.first.count in 1 until SMALL_VOD_CATEGORY_THRESHOLD
            }
        } else {
            emptyList<Pair<CategoryFilter, List<CategoryFilter>>>() to remainderUnits
        }
        // Two rows folded into one collapsed row saves nothing, so the fold needs a third.
        val otherRows = if (thinUnits.size < 3 || OTHER_CATEGORY_ID in hiddenIds) {
            emptyList()
        } else {
            val members = thinUnits.map { it.first }
            val otherExpanded = expanded.contains(OTHER_CATEGORY_ID)
            val children = members.map { it.copy(isChild = true, isParent = false, expanded = false) }
                .sortedWith(compareBy({ -it.count }, { it.name.lowercase() }))
            childrenByParent[OTHER_CATEGORY_ID] = children
            val parent = CategoryFilter(
                id = OTHER_CATEGORY_ID,
                name = "Other",
                count = members.sumOf { it.count },
                pinned = pinned.contains(OTHER_CATEGORY_ID),
                // The two resolution branches are either/or downstream, so channel ids are
                // only carried when every member resolves that way - a mixed union would
                // silently drop whatever the other branch was holding.
                matchIds = members.flatMap { it.matchIds }.toSet(),
                isParent = true,
                expanded = otherExpanded,
                channelIds = if (members.all { it.channelIds.isNotEmpty() }) {
                    members.flatMap { it.channelIds }.toSet()
                } else {
                    emptySet()
                },
                isDynamic = true
            )
            if (otherExpanded) listOf(parent) + children else listOf(parent)
        }
        val cascadeRows = if (otherRows.isEmpty()) {
            remainderUnits.flatMap(::expandUnit)
        } else {
            mainUnits.flatMap(::expandUnit) + otherRows
        }

        val (pinnedRows, unpinnedRows) = cascadeRows.partition { it.pinned }
        val allRow = CategoryFilter(id = null, name = "All", count = list.size)
        // Live TV sorts "All" below the dynamic buckets - Favourites/pinned/buckets
        // are what people actually want first there. Other tabs have no buckets, so
        // "All" just stays at the top like before.
        val result = mutableListOf<CategoryFilter>()
        // Films/Series lead with a "Jellyfin" row when this tab has any Jellyfin-sourced
        // items - a personal library is browsed as a library, not hunted for across the
        // IPTV providers' categories it gets merged into. Carries explicit channelIds
        // (same mechanism as a brand row) because provenance is per-Channel, not a
        // provider category anything is filed under.
        if (tab != 0 && JELLYFIN_CATEGORY_ID !in hiddenIds) {
            // A title the Jellyfin library *and* an IPTV provider both carry is one
            // deduped card, and the representative that wins the card is whichever copy
            // had a poster - often the IPTV one. Matching on the representative's own
            // isJellyfin flag alone dropped those titles out of the Jellyfin row even
            // though the library has them, so match on any version in the group.
            val jellyfinIds = list.filter { ch ->
                ch.isJellyfin || versionsById[ch.id]?.any { it.isJellyfin } == true
            }.map { it.id }.toSet()
            if (jellyfinIds.isNotEmpty()) {
                result.add(
                    CategoryFilter(
                        id = JELLYFIN_CATEGORY_ID,
                        name = "Jellyfin",
                        count = jellyfinIds.size,
                        channelIds = jellyfinIds,
                        isDynamic = true
                    )
                )
            }
        }
        // Series: the anime catalog is one expandable row whose children are the catalog's
        // sections, so browsing it is "Anime > Currently Airing" rather than one 500-title
        // heap. Cached cold starts restore the channels but not the sections (they aren't
        // in the flat channel cache), so the row degrades to a plain, unexpandable Anime
        // category until the next catalog refresh rather than vanishing.
        if (tab == 1 && ANIME_CATEGORY_ID !in hiddenIds) {
            // Anime titles are deduped against the rest of the catalog by name like
            // everything else, so a show an IPTV provider also carries becomes one card -
            // and the copy that wins it is whichever had a poster, very often the provider's.
            // Matching on the representative's own "anime:" id therefore lost every title
            // the provider happened to stock, which with a large provider is most of them
            // and took the whole Anime row with it. Same failure the Jellyfin row above
            // already handles: look at every version in the group, not just the winner.
            //
            // Mapped rather than filtered, because the sections below index titles by their
            // catalog id: once a title is represented by the provider's copy, its section
            // has to point at that representative or the row would be empty.
            val animeRepById = HashMap<String, String>()
            for (ch in list) {
                if (ch.id.startsWith(AnimeCatalogClient.ID_PREFIX)) animeRepById[ch.id] = ch.id
                versionsById[ch.id]?.forEach { version ->
                    if (version.id.startsWith(AnimeCatalogClient.ID_PREFIX)) {
                        animeRepById[version.id] = ch.id
                    }
                }
            }
            val animeIds = animeRepById.values.toSet()
            if (animeIds.isNotEmpty()) {
                val children = animeSections.mapNotNull { section ->
                    val ids = section.channelIds.mapNotNullTo(mutableSetOf()) { animeRepById[it] }
                    if (ids.isEmpty()) return@mapNotNull null
                    CategoryFilter(
                        id = "$ANIME_CATEGORY_ID:${section.label}",
                        name = section.label,
                        count = ids.size,
                        pinned = pinned.contains("$ANIME_CATEGORY_ID:${section.label}"),
                        isChild = true,
                        channelIds = ids
                    )
                }
                val isExpanded = expanded.contains(ANIME_CATEGORY_ID)
                val parent = CategoryFilter(
                    id = ANIME_CATEGORY_ID,
                    name = "Anime",
                    count = animeIds.size,
                    pinned = pinned.contains(ANIME_CATEGORY_ID),
                    channelIds = animeIds,
                    isParent = children.isNotEmpty(),
                    expanded = isExpanded && children.isNotEmpty(),
                    isDynamic = true
                )
                if (children.isNotEmpty()) childrenByParent[ANIME_CATEGORY_ID] = children
                result.add(parent)
                if (parent.expanded) result.addAll(children)
            }
        }
        if (tab != 0) {
            // Sidebar collapse utility row, mirroring the classic-layout toggle
            // (count = -1 so no shelf/count machinery treats it as a category, and
            // it's in NON_PINNABLE_CATEGORY_IDS so it gets no star).
            result.add(CategoryFilter(id = COLLAPSE_CATEGORIES_TOGGLE_ID, name = "Collapse categories", count = -1))
        }
        if (tab != 0) result.add(allRow)
        if (tab == 0) {
            val favoriteCount = list.count { it.id in favoriteChannelIds }
            if (favoriteCount > 0) {
                result.add(CategoryFilter(id = FAVOURITES_CATEGORY_ID, name = "Favourites", count = favoriteCount))
                result.add(CategoryFilter(id = COLLAPSE_CATEGORIES_TOGGLE_ID, name = "Collapse categories", count = -1))
                result.add(
                    CategoryFilter(
                        id = CLASSIC_LAYOUT_TOGGLE_ID,
                        name = if (useClassicLayout) "Group into categories" else "Show all categories (classic list)",
                        count = -1
                    )
                )
            } else {
                result.add(CategoryFilter(id = COLLAPSE_CATEGORIES_TOGGLE_ID, name = "Collapse categories", count = -1))
                result.add(
                    CategoryFilter(
                        id = CLASSIC_LAYOUT_TOGGLE_ID,
                        name = if (useClassicLayout) "Group into categories" else "Show all categories (classic list)",
                        count = -1
                    )
                )
            }
        }
        // Pinned (favourite) categories always come first - above dynamic clusters,
        // genre buckets, and everything else - so the user's pinned items are always
        // one D-pad press away regardless of how the sidebar otherwise arranges itself.
        result += pinnedRows.sortedBy { it.name.lowercase() }
        // Series/Films: clustered service categories, then genre buckets, then the
        // provider's own list. Live TV has no brand block here (its brand rows come from
        // deriveBrandCategories and go through bucketing), so it just leads with buckets.
        result += brandRows
        result += bucketRows
        if (tab == 0) result.add(allRow)
        // Live TV is mainly watched for sport, then UK channels - surface those first.
        result += if (tab == 0) {
            unpinnedRows.sortedWith(compareBy({ liveCategoryPriority(it.name) }, { it.name.lowercase() }))
        } else {
            // Already unit-sorted above (grouped categories first, then leaves,
            // alphabetical within each) - re-sorting here would undo that.
            unpinnedRows
        }
        // Legacy shelf pin/hide prefs stored shelf titles ("KIDS & FAMILY"); rows are
        // now keyed by id. Fold any stored value that names a real row (case-
        // insensitively) into that row's id so pre-migration pins/hides keep working.
        // Only title folds are applied here - id-keyed entries already did their job
        // during construction (leaves, Jellyfin, Anime), so re-filtering them post-hoc
        // would change the sidebar's existing hide semantics for bucket/brand/group rows.
        val knownRowIds = result.mapNotNullTo(mutableSetOf()) { it.id }
        val idForName = mutableMapOf<String, String>()
        for (row in result) {
            val id = row.id ?: continue
            idForName.putIfAbsent(row.name.lowercase(), id)
        }
        val legacyPinnedIds = pinned.mapNotNullTo(linkedSetOf()) { value ->
            if (value in knownRowIds) null else idForName[value.lowercase()]
        }
        val legacyHiddenIds = hiddenIds.mapNotNullTo(linkedSetOf()) { value ->
            if (value in knownRowIds) null else idForName[value.lowercase()]
        }
        val finalRows = result
            // Utility rows are exempt: a hidden one can't be unhidden (its context menu is
            // the only route back, and it's gone with the row), so an accidental long-press
            // → Hide silently removed the only way to collapse the rail, permanently.
            .filterNot { it.id in legacyHiddenIds && it.id !in UTILITY_ROW_IDS }
            .map { row -> if (row.id in legacyPinnedIds && !row.pinned) row.copy(pinned = true) else row }
        val built = MainActivity.CategoryBuildResult(finalRows, childrenByParent.toMap())
        DerivedCache.saveRows(
            this,
            tab,
            fingerprint,
            DerivedCache.RowsSnapshot(built.rows, built.childrenByParent)
        )
        return built
}

/** Column count for the single-category poster grid, sized off the RecyclerView's actual
 *  width where possible (it's already laid out by the time a category gets picked). */
internal fun MainActivity.gridSpanCount(recyclerView: RecyclerView): Int {
    // The rail only costs the grid width while it is on screen - a portrait phone hides it
    // by default, so subtracting it there would under-count columns before first layout.
    val railPx = if (isSidebarCollapsed()) 0 else resources.getDimensionPixelSize(R.dimen.category_sidebar_width)
    val widthPx = recyclerView.width.takeIf { it > 0 }
        ?: (resources.displayMetrics.widthPixels - railPx)
    val widthDp = widthPx / resources.displayMetrics.density
    // Both bounds come from resources so each device class tunes its own grid: the
    // minimum column width drops on a portrait phone (which would otherwise fit only one
    // poster beside the sidebar), and the max span caps a TV at 5 rather than the 6+ its
    // width alone would allow.
    val minColumnDp = resources.getDimension(R.dimen.poster_grid_min_column_width) /
        resources.displayMetrics.density
    return (widthDp / minColumnDp).toInt()
        .coerceIn(1, resources.getInteger(R.integer.poster_grid_max_span))
}

/** Sets up a GridLayoutManager on [recyclerView] and tells [adapter] its span count and
 *  where D-pad UP from the top row should land ([topRowFocusUpTargetId], e.g. the active
 *  tab button) - see PosterGridAdapter.topRowFocusUpTargetId for why that can't just be
 *  left to automatic focus search. */
internal fun MainActivity.setGridSpan(recyclerView: RecyclerView, adapter: PosterGridAdapter, topRowFocusUpTargetId: Int) {
    val span = gridSpanCount(recyclerView)
    recyclerView.layoutManager = GridLayoutManager(this, span)
    adapter.spanCount = span
    adapter.topRowFocusUpTargetId = topRowFocusUpTargetId
}

/** "See All" on a shelf header - same vertical grid a sidebar category pick opens,
 *  just seeded directly from that shelf's own items instead of matching category ids. */
internal fun MainActivity.showSeeAll(shelf: ContentShelf) {
    selectedShelfItems = shelf.items
    selectedCategoryLabel = shelf.title
    selectedRowId = null
    selectedCategoryIds = null
    selectedBrandChannelIds = null
    scope.launch { applyCategoryFilter() }
}

// Series/Films normally use category-based shelves; picking one
// specific category from the sidebar swaps that tab's RecyclerView to a vertical,
// scrollable poster grid instead - a horizontal strip isn't enough room to browse
// a whole category in. Filtering the full catalog is real work, so it runs off-main.
internal suspend fun MainActivity.applyCategoryFilter(focusFirstLiveChannel: Boolean = false) {
    // Claim this run. Anything that resumes from the background filter below only gets to
    // touch an adapter if no newer switch has started since - see categoryFilterGeneration.
    val generation = ++categoryFilterGeneration
    val matchIds = selectedCategoryIds
    val tab = activeTab
    when (tab) {
        0 -> {
            val source = liveChannels
            val isFavourites = selectedRowId == FAVOURITES_CATEGORY_ID
            val favoriteIds = if (isFavourites) FavoritesStore.getFavoriteChannelIds(this) else emptySet()
            val brandIds = selectedBrandChannelIds
            val filtered = withContext(Dispatchers.Default) {
                when {
                    isFavourites -> source.filter { it.id in favoriteIds }
                    brandIds != null && matchIds != null ->
                        source.filter { it.id in brandIds || it.filterKey() in matchIds }
                    brandIds != null -> source.filter { it.id in brandIds }
                    matchIds == null -> source
                    else -> source.filter { it.filterKey() in matchIds }
                }
            }
            // A newer category switch started while this filter ran - its result is the
            // one the user is waiting for, so drop ours rather than overwrite it.
            if (generation != categoryFilterGeneration) return
            liveAdapter.submitList(filtered) {
                if (!focusFirstLiveChannel) return@submitList
                val first = filtered.firstOrNull() ?: return@submitList
                requestPreviewLoad(first)
                // submitList's commit callback fires once the diff is applied, but the
                // row's ViewHolder isn't necessarily laid out yet on this same frame - a
                // single post() still occasionally lands before RecyclerView's own
                // pending layout pass, so nest two: the first just waits for that layout
                // request to be queued, the second runs after it's actually done.
                binding.liveContent.post {
                    binding.liveContent.post {
                        (binding.liveContent.findViewHolderForAdapterPosition(0) as? LiveGuideAdapter.RowViewHolder)
                            ?.requestChannelFocus()
                    }
                }
            }
            binding.liveContent.scrollToPosition(0)
        }
        1 -> {
            val source = seriesList
            val shelfItems = selectedShelfItems
            // Continue Watching's items are in-progress episodes, not seriesList members -
            // a filterKey() match against seriesList would render an empty grid. Serve the
            // continue list directly.
            if (selectedRowId == CONTINUE_WATCHING_CATEGORY_ID) {
                setGridSpan(binding.seriesContent, seriesGridAdapter, R.id.tabSeries)
                binding.seriesContent.adapter = seriesGridAdapter
                seriesGridAdapter.replaceAll(seriesContinueItems())
                binding.seriesContent.scrollToPosition(0)
                return
            }
            // A dynamic row (genre bucket or streaming service) carries an explicit set
            // of channel ids rather than provider category ids, because it deliberately
            // spans several of them. Only Live TV honoured that, so on Series/Films
            // picking one set matchIds to null and fell straight through to the "no
            // filter, show the shelves" branch - which looked like the click did nothing.
            val brandIds = selectedBrandChannelIds
            if (shelfItems != null) {
                setGridSpan(binding.seriesContent, seriesGridAdapter, R.id.tabSeries)
                binding.seriesContent.adapter = seriesGridAdapter
                seriesGridAdapter.replaceAll(shelfItems)
            } else if (brandIds != null) {
                val filtered = withContext(Dispatchers.Default) { source.filter { it.id in brandIds } }
                // A newer category switch started while this filter ran - its result is the
                // one the user is waiting for, so drop ours rather than overwrite it.
                if (generation != categoryFilterGeneration) return
                setGridSpan(binding.seriesContent, seriesGridAdapter, R.id.tabSeries)
                binding.seriesContent.adapter = seriesGridAdapter
                seriesGridAdapter.replaceAll(filtered)
            } else if (matchIds == null) {
                binding.seriesContent.layoutManager = LinearLayoutManager(this)
                binding.seriesContent.adapter = seriesShelfAdapter
                seriesShelfAdapter.submitList(seriesShelves)
            } else {
                val filtered = withContext(Dispatchers.Default) { source.filter { it.filterKey() in matchIds } }
                // A newer category switch started while this filter ran - its result is the
                // one the user is waiting for, so drop ours rather than overwrite it.
                if (generation != categoryFilterGeneration) return
                setGridSpan(binding.seriesContent, seriesGridAdapter, R.id.tabSeries)
                binding.seriesContent.adapter = seriesGridAdapter
                seriesGridAdapter.replaceAll(filtered)
            }
            binding.seriesContent.scrollToPosition(0)
        }
        2 -> {
            val source = filmList
            val shelfItems = selectedShelfItems
            val brandIds = selectedBrandChannelIds // see the Series branch above
            if (shelfItems != null) {
                setGridSpan(binding.filmsContent, filmsGridAdapter, R.id.tabFilms)
                binding.filmsContent.adapter = filmsGridAdapter
                filmsGridAdapter.replaceAll(shelfItems)
            } else if (brandIds != null) {
                val filtered = withContext(Dispatchers.Default) { source.filter { it.id in brandIds } }
                // A newer category switch started while this filter ran - its result is the
                // one the user is waiting for, so drop ours rather than overwrite it.
                if (generation != categoryFilterGeneration) return
                setGridSpan(binding.filmsContent, filmsGridAdapter, R.id.tabFilms)
                binding.filmsContent.adapter = filmsGridAdapter
                filmsGridAdapter.replaceAll(filtered)
            } else if (matchIds == null) {
                binding.filmsContent.layoutManager = LinearLayoutManager(this)
                binding.filmsContent.adapter = filmsShelfAdapter
                filmsShelfAdapter.submitList(filmShelves)
            } else {
                val filtered = withContext(Dispatchers.Default) { source.filter { it.filterKey() in matchIds } }
                // A newer category switch started while this filter ran - its result is the
                // one the user is waiting for, so drop ours rather than overwrite it.
                if (generation != categoryFilterGeneration) return
                setGridSpan(binding.filmsContent, filmsGridAdapter, R.id.tabFilms)
                binding.filmsContent.adapter = filmsGridAdapter
                filmsGridAdapter.replaceAll(filtered)
            }
            binding.filmsContent.scrollToPosition(0)
        }
    }
}

internal fun MainActivity.onCategorySelected(category: CategoryFilter) {
    if (category.id == COLLAPSE_CATEGORIES_TOGGLE_ID) {
        collapseCategorySidebar()
        return
    }
    if (category.id == CLASSIC_LAYOUT_TOGGLE_ID) {
        val useClassic = prefs.getBoolean(PREF_CLASSIC_CATEGORY_LAYOUT, false)
        prefs.edit().putBoolean(PREF_CLASSIC_CATEGORY_LAYOUT, !useClassic).apply()
        val newClassic = !useClassic
        scope.launch {
            // Classic mode shows ALL live channels flat (no version grouping), so
            // channels like SD variants that were collapsed into a higher-quality
            // representative are individually visible and contribute to their own
            // provider categories. Re-derive liveChannels/liveVersions before
            // rebuilding the sidebar so categories reflect the full channel list.
            val hideAdult = prefs.getBoolean(PREF_HIDE_ADULT, true)
            val snapshot = allChannels
            val rawLive = snapshot.filter { it.mediaType == MediaType.LIVE && !it.name.contains("##") }
                .filterNot { hideAdult && isAdultCategory(it.categoryName, it.group) }
            if (newClassic || !prefs.getBoolean(PREF_GROUP_CHANNELS, true)) {
                liveChannels = rawLive
                liveVersions = emptyMap()
            } else {
                val (grouped, vers) = groupLiveQualityVersions(rawLive)
                liveChannels = grouped
                liveVersions = vers
            }
            rebuildCategoriesForActiveTab()
        }
        return
    }
    // A tap on a parent row always toggles its expansion (and selects it) - the old
    // select-first-toggle-on-second-tap scheme read as "collapse needs a double
    // click". category.expanded is the pre-tap state the row was bound with.
    val id = category.id
    val expandChanged = category.isParent && id != null
    if (expandChanged && id != null) {
        if (!expandedGroupKeys.remove(id)) expandedGroupKeys.add(id)
    }
    selectedShelfItems = null
    selectedRowId = category.id
    selectedCategoryLabel = category.name
    selectedBrandChannelIds = category.channelIds.ifEmpty { null }
    selectedCategoryIds = if (category.id == null) null else category.matchIds
    if (expandChanged) {
        if (category.expanded) {
            // Was expanded -> collapsing: just remove child rows from the existing
            // list without a full category rebuild - avoids the expensive channel
            // scan on every tap. (These two branches were inverted before: collapse
            // paid the full rebuild, expand ran this no-op removal and appeared to
            // ignore the first click.)
            val currentList = categoryAdapter.currentList.toMutableList()
            val parentIdx = currentList.indexOfFirst { it.id == id }
            if (parentIdx >= 0) {
                var removeEnd = parentIdx + 1
                while (removeEnd < currentList.size && currentList[removeEnd].isChild) removeEnd++
                if (removeEnd > parentIdx + 1) {
                    currentList.subList(parentIdx + 1, removeEnd).clear()
                }
                currentList[parentIdx] = currentList[parentIdx].copy(expanded = false)
            }
            // setSelected must run after the diff commits, or it highlights against the
            // pre-diff list and can notify a stale index.
            categoryAdapter.submitList(currentList) { categoryAdapter.setSelected(selectedRowId) }
            scope.launch { applyCategoryFilter() }
        } else {
            // Was collapsed -> expanding. The children were already computed by the
            // last build and cached, so splice them in the same way collapse removes
            // them. This used to run a full rebuild - a rescan of every channel in the
            // tab - which is why expanding lagged while collapsing was instant.
            val children = id?.let { categoryChildrenCache[it] }
            if (children != null) {
                val currentList = categoryAdapter.currentList.toMutableList()
                val parentIdx = currentList.indexOfFirst { it.id == id }
                if (parentIdx >= 0) {
                    currentList[parentIdx] = currentList[parentIdx].copy(expanded = true)
                    currentList.addAll(parentIdx + 1, children)
                }
                // setSelected must run after the diff commits, or it highlights against the
                // pre-diff list and can notify a stale index.
                categoryAdapter.submitList(currentList) { categoryAdapter.setSelected(selectedRowId) }
                scope.launch { applyCategoryFilter() }
            } else {
                // No cached children (first build hasn't run, or the row postdates it) -
                // fall back to the full rebuild rather than silently expanding to nothing.
                scope.launch {
                    rebuildCategoriesForActiveTab()
                    applyCategoryFilter()
                }
            }
        }
    } else {
        // Just the highlighted row + filtered content changed, not which rows exist -
        // rebuilding the whole list (rescans every channel in the tab) for that alone
        // is exactly the "picking a category takes forever" complaint. setSelected()
        // already re-renders the sidebar's highlight on its own.
        categoryAdapter.setSelected(selectedRowId)
        scope.launch { applyCategoryFilter(focusFirstLiveChannel = activeTab == 0) }
    }
}

internal fun MainActivity.setStatus(text: String, visible: Boolean) {
    statusText = text
    statusWanted = visible
    applyStatus()
}

/**
 * Decides whether the status actually goes on screen, from the current state rather than
 * from what was true when the message was raised.
 *
 * A provider load runs for a long time - a large Stalker portal is a minute of streaming -
 * and the user is free to move around while it does. statusRow is a sibling of the content
 * panes holding the same 0dp/weight=1 slot, so any pane shown while it was up got half the
 * screen and "Connecting to <provider>..." got the other half. Raising it once and leaving
 * it also meant opening Settings afterwards couldn't take it down, because nothing
 * re-evaluated it until the next message.
 *
 * So the status only owns the slot when nothing else does, and every screen change calls
 * this. The load itself is unaffected - it just stops being narrated over whatever the user
 * went to look at instead.
 */
internal fun MainActivity.applyStatus() {
    binding.statusText.text = statusText
    val slotTaken = activeSettingsOverlay != null || activeSearchOverlay != null ||
        isPlayerVisible || isContentDetailVisible ||
        binding.contentRow.visibility == View.VISIBLE ||
        binding.homeContent.visibility == View.VISIBLE ||
        binding.discoverContent.visibility == View.VISIBLE
    val show = statusWanted && !slotTaken
    binding.statusRow.visibility = if (show) View.VISIBLE else View.GONE
    // In-progress messages ("Loading...", "Connecting...") get a spinner; final
    // results ("N items", errors) don't - "..." is what already distinguishes them
    // at every call site, no need for a second parameter everywhere.
    binding.statusSpinner.visibility =
        if (show && statusText.trimEnd().endsWith("...")) View.VISIBLE else View.GONE
}

// ── Tabs ───────────────────────────────────────

internal fun MainActivity.setupTabs() {
    binding.tabHome.setOnClickListener { selectHome() }
    binding.tabLive.setOnClickListener { selectTab(0) }
    binding.tabCatchup.setOnClickListener { showingHome = false; selectCatchup() }
    binding.tabSeries.setOnClickListener { selectTab(1) }
    binding.tabFilms.setOnClickListener { selectTab(2) }
    binding.tabDiscover.setOnClickListener { showingHome = false; selectDiscover() }
    binding.tabDownloads.setOnClickListener { showingHome = false; selectDownloads() }
    setupDiscover()
    // D-pad focus moving between tabs leaves a stale sliver of the previous tab's
    // rounded-border background behind on some TV-stick GPUs - the view's own
    // self-invalidate on unfocus doesn't always clear it. Forcing the whole bar to
    // redraw on every focus change is a blunt but reliable fix.
    val invalidateBarOnFocus = View.OnFocusChangeListener { _, _ -> binding.tabBar.invalidate() }
    for (tv in listOf(binding.tabHome, binding.tabLive, binding.tabCatchup, binding.tabSeries, binding.tabFilms, binding.tabDiscover, binding.tabDownloads)) {
        tv.onFocusChangeListener = invalidateBarOnFocus
    }
    // Hide tab bar + search until an enabled provider exists
    updateTopChromeVisibility()
}

internal fun MainActivity.updateTabStyles(selected: View) {
    for (tv in listOf(binding.tabHome, binding.tabLive, binding.tabCatchup, binding.tabSeries, binding.tabFilms, binding.tabDiscover, binding.tabDownloads)) {
        val isSelected = tv === selected
        tv.isSelected = isSelected
        val (labelId, iconId, indicatorId) = when (tv.id) {
            R.id.tabLive -> Triple(R.id.tabLiveLabel, R.id.tabLiveIcon, R.id.tabLiveIndicator)
            R.id.tabCatchup -> Triple(R.id.tabCatchupLabel, R.id.tabCatchupIcon, R.id.tabCatchupIndicator)
            R.id.tabSeries -> Triple(R.id.tabSeriesLabel, R.id.tabSeriesIcon, R.id.tabSeriesIndicator)
            R.id.tabFilms -> Triple(R.id.tabFilmsLabel, R.id.tabFilmsIcon, R.id.tabFilmsIndicator)
            R.id.tabHome -> Triple(R.id.tabHomeLabel, R.id.tabHomeIcon, R.id.tabHomeIndicator)
            R.id.tabDiscover -> Triple(R.id.tabDiscoverLabel, R.id.tabDiscoverIcon, R.id.tabDiscoverIndicator)
            R.id.tabDownloads -> Triple(R.id.tabDownloadsLabel, R.id.tabDownloadsIcon, R.id.tabDownloadsIndicator)
            else -> continue
        }
        val label = tv.findViewById<TextView>(labelId)
        val icon = tv.findViewById<ImageView>(iconId)
        val indicator = tv.findViewById<View>(indicatorId)
        label?.let {
            it.setTextColor(getColor(if (isSelected) R.color.text_primary else R.color.text_secondary))
            it.typeface = ResourcesCompat.getFont(this, if (isSelected) R.font.inter_semibold else R.font.inter_medium)
        }
        icon?.setColorFilter(
            getColor(if (isSelected) R.color.text_primary else R.color.text_tertiary),
            android.graphics.PorterDuff.Mode.SRC_IN
        )
        indicator?.visibility = if (isSelected) View.VISIBLE else View.GONE
    }
    selected.requestFocus()
}

internal fun MainActivity.selectHome() {
    activeSettingsOverlay?.dismiss()
    activeSearchOverlay?.dismiss()
    showingHome = true
    showingDownloads = false
    showingDiscover = false
    hideCatchup()
    releaseLivePreview()
    binding.discoverContent.visibility = View.GONE
    binding.contentRow.visibility = View.GONE
    binding.homeContent.visibility = View.VISIBLE
    // Search on Home is only useful with something to search; with no enabled provider
    // updateTopChromeVisibility() keeps it hidden. selectHome used to force it visible
    // unconditionally, which is why it lingered on the empty first screen.
    binding.homeSearchBar.visibility = if (hasProviderEnabled()) View.VISIBLE else View.GONE
    applyPanelWidth(binding.homeSearchBar, R.dimen.home_search_bar_width)
    updateTabStyles(binding.tabHome)
    homeShelfAdapter.submitList(buildHomeShelves())
    applyStatus()
}

/** Applies [widthDimen] as an explicit width, treating 0 as "leave it as laid out".
 *  Lets a phone keep a match_parent panel while a TV gets a fixed, centred one, without
 *  a second copy of the layout - a dimen resource can't itself hold match_parent. */
internal fun MainActivity.applyPanelWidth(view: View, widthDimen: Int) {
    val width = resources.getDimensionPixelSize(widthDimen)
    if (width <= 0) return
    view.layoutParams = view.layoutParams?.also { it.width = width } ?: return
}

/** Downloads reuses the contentRow's FrameLayout but skips the category sidebar and
 *  the live/series/films lists entirely - it's not part of the categorized catalog. */
internal fun MainActivity.selectDownloads() {
    activeSettingsOverlay?.dismiss()
    activeSearchOverlay?.dismiss()
    showingDownloads = true
    showingDiscover = false
    hideCatchup()
    releaseLivePreview()
    binding.discoverContent.visibility = View.GONE
    binding.contentRow.visibility = View.VISIBLE
    binding.homeContent.visibility = View.GONE
    binding.homeSearchBar.visibility = View.GONE
    applySidebarVisibility(tabWantsSidebar = false)
    binding.liveRow.visibility = View.GONE
    binding.seriesContent.visibility = View.GONE
    binding.filmsContent.visibility = View.GONE
    updateTabStyles(binding.tabDownloads)
    refreshDownloadsList()
    mainHandler.post(downloadsProgressRunnable)
    applyStatus()
}

// ── Side menu ─────────────────────────────────

internal fun MainActivity.isPlayerSideMenuOpen(): Boolean = binding.playerSideMenu.visibility == View.VISIBLE

internal fun MainActivity.openSideMenu() {
    // Also covers the re-open-during-close race: the panel is still VISIBLE while the
    // close animation runs, so a stray LEFT there is ignored rather than fighting the
    // in-flight transform.
    if (isPlayerSideMenuOpen()) return
    // Fresh open: no category drill-down until the user asks for it. The section on
    // screen is where a later collapse hands focus back to, until a row is expanded.
    collapseSideMenuCategories()
    sideMenuExpandedTab = activeTab.coerceIn(0, 2)
    // The drawer covers the bottom-right corner the Up Next card sits in - clear it
    // so the menu opens over clean video, same as showControls does.
    if (upNextActive) binding.upNextOverlay.visibility = View.GONE
    // The menu stays put until the user dismisses it - no auto-hide countdown.
    mainHandler.removeCallbacks(hideControlsRunnable)
    binding.controlsOverlay.visibility = View.GONE
    // Slide in from the left edge. translationX starts a full panel-width off-screen
    // (width is 0 while GONE, so the shared dimen keeps the code and layout in sync),
    // then the visibility flip can't flash the panel in place.
    val menuWidth = resources.getDimensionPixelSize(R.dimen.player_side_menu_width)
    binding.playerSideMenu.translationX = -menuWidth.toFloat()
    binding.playerSideMenu.visibility = View.VISIBLE
    binding.playerSideMenu.animate()
        .translationX(0f)
        .setDuration(250)
        .setInterpolator(DecelerateInterpolator())
        .start()
    // Mark the section the user is actually in and land focus on it, so the remote's
    // UP/DOWN immediately walks the nav list from "where you are".
    val activeNavRow = updateSideMenuSelection()
    updateSideMenuChevrons()
    activeNavRow.requestFocus()
}

internal fun MainActivity.closeSideMenu() {
    if (!isPlayerSideMenuOpen()) return
    val menuWidth = resources.getDimensionPixelSize(R.dimen.player_side_menu_width)
    binding.playerSideMenu.animate()
        .translationX(-menuWidth.toFloat())
        .setDuration(250)
        .withEndAction {
            binding.playerSideMenu.visibility = View.GONE
            binding.playerSideMenu.translationX = 0f
        }
        .start()
    // Menu closed - drop the "where you are" highlight and any open drill-down so a
    // fresh open re-derives both.
    clearSideMenuSelection()
    collapseSideMenuCategories()
    // Menu closed over the video - bring Up Next back if it was mid-countdown,
    // exactly like hideControls does. The bottom bar stays hidden, so the video is
    // fullscreen again = "back to what's playing".
    if (upNextActive) binding.upNextOverlay.visibility = View.VISIBLE
}

/** The side-menu row for the section currently on screen - mirrors the top tab bar:
 *  Home / Discover / Downloads own their panes; Live / Series / Films are the three
 *  categorized tabs (activeTab). */
internal fun MainActivity.activeSideMenuRow(): View = when {
    showingHome -> binding.navHome
    showingDiscover -> binding.navDiscover
    showingDownloads -> binding.navDownloads
    else -> listOf(binding.navLive, binding.navSeries, binding.navFilms)[activeTab.coerceIn(0, 2)]
}

/** Highlights the active section's row (brand_muted fill + primary border via
 *  bg_select_item's selected state) and clears the rest - same "where you are" signal
 *  updateTabStyles gives the top tab bar. Returns the active row so openSideMenu can
 *  land focus on it. */
internal fun MainActivity.updateSideMenuSelection(): View {
    val active = activeSideMenuRow()
    for (row in listOf(
        binding.navHome, binding.navLive, binding.navSeries,
        binding.navFilms, binding.navDiscover, binding.navDownloads
    )) {
        row.isSelected = row === active
    }
    return active
}

internal fun MainActivity.clearSideMenuSelection() {
    for (row in listOf(
        binding.navHome, binding.navLive, binding.navSeries,
        binding.navFilms, binding.navDiscover, binding.navDownloads
    )) {
        row.isSelected = false
    }
}

// ── Side-menu category drill-down ─────────────

/** Live/Series/Films nav row: flies its category column out to the right (or folds it
 *  back in on a second press). All three expand - the section you're *playing* has no
 *  special status here, so Series/Films list their categories while Live is on screen.
 *  Leaving the player happens by picking something inside the column, not by pressing
 *  the section row. */
internal fun MainActivity.onSideMenuSectionRowClicked(tab: Int) {
    if (sideMenuCategoriesExpanded && sideMenuExpandedTab == tab) collapseSideMenuCategories()
    else expandSideMenuCategories(tab)
}

internal fun MainActivity.expandSideMenuCategories(tab: Int) {
    // A different section was already flown out - swap the column's contents rather
    // than animating it shut and straight back open.
    val wasExpanded = sideMenuCategoriesExpanded
    sideMenuCategoriesExpanded = true
    sideMenuExpandedTab = tab
    sideMenuChannelCategory = null
    updateSideMenuChevrons()
    if (!wasExpanded) animateSideMenuCategoryPanel(open = true)

    val cached = sideMenuCategoryCache[tab]
    if (cached != null) {
        showSideMenuCategories(tab, cached)
        return
    }
    // The on-screen sidebar already holds the active tab's rows - reuse them and skip
    // the rebuild. Any other tab has to be built here (same pipeline, other tab's list).
    val fromSidebar = if (tab == activeTab) categoryAdapter.currentList else emptyList()
    if (fromSidebar.isNotEmpty()) {
        sideMenuCategoryCache[tab] = fromSidebar
        showSideMenuCategories(tab, fromSidebar)
        return
    }
    binding.sideMenuColumnTitle.setText(R.string.side_menu_loading)
    sideMenuCategoryAdapter.submitList(emptyList())
    scope.launch {
        val rows = buildCategoriesForActiveTab(tab)
        // The user may have folded the column shut, or moved to another section,
        // while this was building.
        if (!sideMenuCategoriesExpanded || sideMenuExpandedTab != tab) return@launch
        sideMenuCategoryCache[tab] = rows
        showSideMenuCategories(tab, rows)
    }
}

/** Renders the category level of the column for [tab]. [focusId] overrides which row
 *  opens focused - used when walking back from the channel level, so focus returns to
 *  the category the user drilled into rather than to the applied filter. */
internal fun MainActivity.showSideMenuCategories(tab: Int, rows: List<CategoryFilter>, focusId: String? = null) {
    sideMenuChannelCategory = null
    binding.sideMenuColumnTitle.text = getString(
        when (tab) {
            0 -> R.string.tab_live_tv
            1 -> R.string.series_tab
            else -> R.string.films_tab
        }
    )
    sideMenuColumnBusy = true
    // Category rows are not channels, so there is no EPG to line up under them.
    sideMenuCategoryAdapter.showNowPlaying = false
    // Cleared first: submitting over a non-empty list makes AsyncListDiffer compute a
    // diff on a background thread (a second, on an 800-channel level swap), and the
    // column is a *different* list at each level, so that diff is wasted work with a
    // race attached. Clearing first takes both submits down AsyncListDiffer's
    // synchronous paths - the swap lands before the next key can be pressed.
    sideMenuCategoryAdapter.submitList(null)
    sideMenuCategoryAdapter.submitList(rows) {
        sideMenuColumnBusy = false
        // Mirror the browsing sidebar's applied filter, but only on the tab that
        // sidebar actually belongs to.
        sideMenuCategoryAdapter.setSelected(focusId ?: if (tab == activeTab) selectedRowId else null)
        focusSideMenuCategoryList()
    }
}

/** Renders the item level: what's actually inside [category] - live channels, or the
 *  films/series in a VOD category. Reuses the same column and adapter (item rows carry
 *  count = -1 so no "(n)" is drawn); LEFT walks back to the categories. */
internal fun MainActivity.showSideMenuItems(category: CategoryFilter, items: List<Channel>) {
    sideMenuChannelCategory = category
    sideMenuChannelRows = items
    binding.sideMenuColumnTitle.text = category.name
    sideMenuColumnBusy = true
    // Channel rows carry "what's on now" under the name; category rows never do.
    sideMenuCategoryAdapter.showNowPlaying = true
    sideMenuCategoryAdapter.submitList(null)
    sideMenuCategoryAdapter.submitList(
        items.map { CategoryFilter(id = it.id, name = it.name, count = -1) }
    ) {
        sideMenuColumnBusy = false
        // Highlight what's playing so the column opens on the current item.
        sideMenuCategoryAdapter.setSelected(nowPlayingChannel?.id)
        focusSideMenuCategoryList()
    }
}

/** LEFT inside the column: back to the categories from the channel level, otherwise
 *  out onto the section row that opened it. */
internal fun MainActivity.onSideMenuColumnLeft() {
    if (sideMenuColumnBusy) return
    val category = sideMenuChannelCategory
    if (category != null) {
        showSideMenuCategories(
            sideMenuExpandedTab,
            sideMenuCategoryCache[sideMenuExpandedTab].orEmpty(),
            focusId = category.id
        )
    } else {
        sectionRowForTab(sideMenuExpandedTab).requestFocus()
    }
}

/** Moves focus into the column, onto its selected row (first row if none). Scrolls
 *  there first: a row that was never laid out has no ViewHolder to focus, so a long
 *  category list would otherwise swallow the focus move. Double post: the first waits
 *  for the pending layout request to be queued, the second runs after it has run. */
internal fun MainActivity.focusSideMenuCategoryList() {
    val list = binding.sideMenuCategoryList
    val target = sideMenuCategoryAdapter.currentList
        .indexOfFirst { it.id == sideMenuCategoryAdapter.selectedId }
        .coerceAtLeast(0)
    list.scrollToPosition(target)
    list.post {
        list.post {
            (list.findViewHolderForAdapterPosition(target)
                as? SideMenuCategoryAdapter.ViewHolder)?.itemView?.requestFocus()
        }
    }
}

internal fun MainActivity.collapseSideMenuCategories() {
    if (!sideMenuCategoriesExpanded) return
    sideMenuCategoriesExpanded = false
    val row = sectionRowForTab(sideMenuExpandedTab)
    updateSideMenuChevrons()
    // Pull focus off the column before it starts shrinking, or the framework drops
    // focus to the root when the collapsing panel goes GONE under it.
    if (isPlayerSideMenuOpen()) row.requestFocus()
    animateSideMenuCategoryPanel(open = false)
    sideMenuCategoryAdapter.setSelected(null)
    sideMenuChannelCategory = null
    sideMenuChannelRows = emptyList()
    sideMenuColumnBusy = false
}

/** Slides the category column out of / back into the nav column's right edge by
 *  animating the clipping container's width. The panel itself is wrap_content, so its
 *  right edge, rounded corners and shadow travel with it. */
internal fun MainActivity.animateSideMenuCategoryPanel(open: Boolean) {
    val panel = binding.sideMenuCategoryPanel
    panel.animation?.cancel()
    sideMenuCategoryWidthAnimator?.cancel()
    val target = if (open) resources.getDimensionPixelSize(R.dimen.player_side_menu_category_width) else 0
    val from = if (panel.visibility == View.VISIBLE) panel.width else 0
    if (open) panel.visibility = View.VISIBLE
    sideMenuCategoryWidthAnimator = android.animation.ValueAnimator.ofInt(from, target).apply {
        duration = 220
        interpolator = DecelerateInterpolator()
        addUpdateListener { anim ->
            panel.updateLayoutParams<ViewGroup.LayoutParams> { width = anim.animatedValue as Int }
        }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (!open) {
                    panel.visibility = View.GONE
                    panel.updateLayoutParams<ViewGroup.LayoutParams> { width = 0 }
                }
                sideMenuCategoryWidthAnimator = null
            }
        })
        start()
    }
}

/** The content section whose nav row currently holds focus, or null for any other row. */
internal fun MainActivity.focusedSideMenuSectionTab(): Int? = when {
    binding.navLive.hasFocus() -> 0
    binding.navSeries.hasFocus() -> 1
    binding.navFilms.hasFocus() -> 2
    else -> null
}

internal fun MainActivity.sectionRowForTab(tab: Int): View = when (tab) {
    0 -> binding.navLive
    1 -> binding.navSeries
    else -> binding.navFilms
}

/** Expand affordance: every content section carries one, since all three open their
 *  categories. Points right at the column it opens, flipped back left while that
 *  section's column is out. */
internal fun MainActivity.updateSideMenuChevrons() {
    for ((tab, chevron) in listOf(
        0 to binding.navLiveChevron, 1 to binding.navSeriesChevron, 2 to binding.navFilmsChevron
    )) {
        chevron.rotation = if (sideMenuCategoriesExpanded && sideMenuExpandedTab == tab) 180f else 0f
    }
}

/** A category picked from the column. Drills one step further right into what's inside
 *  it - channels on Live, titles on Series/Films - and picking one of those plays it
 *  (live swaps the stream in place; a film/series opens its detail page). A category
 *  that resolves to nothing falls back to the browsing screen with it applied as the
 *  sidebar filter. */
internal fun MainActivity.onSideMenuCategoryClicked(category: CategoryFilter) {
    // A level swap is a whole new list in the same RecyclerView, committed off-thread by
    // DiffUtil. A key press landing inside that window acts on a row that is about to be
    // rebound to a different item - which drilled into whatever category happened to be
    // under the focus ring rather than the one on screen when the key went down.
    if (sideMenuColumnBusy) return
    val tab = sideMenuExpandedTab
    // Item level: the "category" is really a channel/title row - play it.
    if (sideMenuChannelCategory != null) {
        val item = sideMenuChannelRows.firstOrNull { it.id == category.id } ?: return
        closeSideMenu()
        // A film/series opens its detail page, which lives behind the player - drop
        // playback first, the way the tab bar's own navigation does.
        if (item.mediaType != MediaType.LIVE) hidePlayer()
        playItem(item)
        return
    }
    val items = resolveSideMenuCategoryItems(tab, category)
    if (items.isNotEmpty()) {
        showSideMenuItems(category, items)
        return
    }
    // Nothing resolved (a synthetic row like Continue Watching, whose members aren't
    // members of the tab list) - fall back to navigating with the filter applied.
    closeSideMenu()
    hidePlayer()
    // hidePlayer() on Live asynchronously re-selects the last-played channel's row (its
    // dynamic-row branch), so the pick is re-asserted inside the coroutine - queued
    // after that work - rather than set before, which that branch would clobber. Mirror
    // of onCategorySelected's field assignments, plus the tab switch when the column
    // belonged to a section other than the one on screen.
    scope.launch {
        if (tab != activeTab) selectTab(tab)
        selectedShelfItems = null
        selectedRowId = category.id
        selectedCategoryLabel = category.name
        selectedBrandChannelIds = category.channelIds.ifEmpty { null }
        selectedCategoryIds = if (category.id == null) null else category.matchIds
        categoryAdapter.setSelected(selectedRowId)
        applyCategoryFilter()
    }
}

/** What a category row on [tab] resolves to: explicit channel ids when present (brand
 *  rows / dynamic buckets), provider category ids otherwise; the "All" row (id == null)
 *  means the whole tab. Reads the same derived per-tab lists the category rows were
 *  built from, so the counts on the rows match what opens. */
internal fun MainActivity.resolveSideMenuCategoryItems(tab: Int, category: CategoryFilter): List<Channel> {
    val source = fullListForTab(tab)
    if (category.id == null) return source
    return if (category.channelIds.isNotEmpty()) {
        source.filter { it.id in category.channelIds }
    } else {
        source.filter { it.filterKey() in category.matchIds }
    }
}

internal fun MainActivity.updatePlayPauseIcon() {
    binding.btnPlayPause.setImageResource(
        if (playerManager.isPlaying) R.drawable.ic_pause
        else R.drawable.ic_play
    )
}

internal fun MainActivity.updateProgress() {
    if (!isPlayerVisible) return
    if (binding.seekBar.isPressed) return
    val pos = playerManager.currentPosition
    val dur = playerManager.duration
    binding.currentTime.text = formatTime(pos)
    if (dur > 0) {
        binding.duration.text = formatTime(dur)
        binding.seekBar.progress = ((pos.toFloat() / dur) * 100).toInt()
        binding.seekBar.keyProgressIncrement = maxOf(1, (30_000f / dur * 100).toInt())
    }
    binding.seekBar.isEnabled = dur > 0

    // Ticks every ~1s while playing; persist progress every ~5s instead of every tick.
    progressTickCount++
    if (progressTickCount % 5 == 0) saveCurrentPlaybackPosition()
    // Jellyfin expects a heartbeat roughly every 10s - it's what keeps the server's
    // resume point current and stops it reaping an active transcode as abandoned.
    if (progressTickCount % 10 == 0) reportJellyfinProgress()
}

internal fun MainActivity.formatTime(ms: Long): String {
    val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
}

/** Bumped whenever buildCategoryRows' output changes shape - see the rows fingerprint.
 *  2: utility rows (collapse rail / classic layout) became un-hideable.
 *  5: leading content-type tags peeled off Films/Series category labels, and thin
 *     (< SMALL_VOD_CATEGORY_THRESHOLD) categories sorted to the bottom of the rail.
 *  6: hyphen-joined type words ("DOCUS-SERIES") strip off a category stem, so they
 *     cluster under their brand instead of forming their own row.
 *  7: quality tiers merge before clustering, cluster children feed the genre buckets, a
 *     bucket over one category promotes it instead of wrapping it, and the thin tail
 *     folds into one "Other" row. */
private const val CATEGORY_ROWS_LOGIC_VERSION = 10

/** Films/Series rail: a category with fewer titles than this sorts below the full ones.
 *  Counts are per-row, so a merged/clustered parent is judged on its members' total. */
private const val SMALL_VOD_CATEGORY_THRESHOLD = 20
