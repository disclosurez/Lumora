package com.lumora

import android.animation.AnimatorInflater
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import androidx.core.content.ContextCompat
import android.util.TypedValue
import android.graphics.BitmapFactory
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lumora.adapter.DYNAMIC_BUCKET_ID_PREFIX
import com.lumora.adapter.EpisodeAdapter
import com.lumora.adapter.LiveGuideAdapter
import com.lumora.download.DownloadRecord
import com.lumora.download.DownloadStatus
import com.lumora.download.DownloadStore
import com.lumora.download.HlsDownloads
import com.lumora.download.VodDownloader
import com.lumora.cache.FavoritesStore
import com.lumora.cache.PlaybackPositionStore
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.anime.AnimeCatalogClient
import com.lumora.parser.XtreamClient
import com.lumora.util.cleanVodTitle
import com.lumora.util.extractLeadingTag
import com.lumora.data.remote.stalker.StalkerProvider
import com.lumora.data.remote.jellyfin.JellyfinProvider
import kotlinx.coroutines.*
import okhttp3.Request
import java.util.Locale

// ── Content lists, detail screen, downloads & toolbar ──
//
// Extracted from MainActivity.kt; see that file's header.
/** Kicks off a system DownloadManager job for a movie or single episode; no-op if already queued/downloaded. */
/**
 * The show behind an episode row, for anything that has to search by title.
 *
 * A TMDB episode placeholder carries the series name in `categoryName` (see `tmdbSeasonsFor`)
 * and its own episode title in `name`; its id is the series id with an `:sNeN` suffix. Both are
 * rewound here so a search runs as "Reacher" + S01E01 rather than for the episode's title, which
 * no site indexes. Anything that is not recognisably an episode is returned untouched.
 */
private fun MainActivity.seriesItemForEpisode(channel: Channel): Channel {
    val seriesName = channel.categoryName?.takeIf { it.isNotBlank() } ?: return channel
    if (channel.episodeNum == null || !channel.id.contains(":s")) return channel
    return channel.copy(name = seriesName, id = channel.id.substringBefore(":s"))
}

internal fun MainActivity.downloadItem(channel: Channel) {
    if (channel.id.isBlank()) return
    // Used to return silently when there was no URL, which is every Discover, scraper and
    // torrent item - so the button did nothing at all and said nothing about why.
    // No URL yet (a Discover title, or a TMDB episode placeholder): find a source first and
    // download whatever that resolves to, rather than telling the user to go and play it.
    if (channel.url.isBlank()) {
        if (!canFindStream(channel)) {
            Toast.makeText(this, "No source available for this title", Toast.LENGTH_LONG).show()
            return
        }
        val season = channel.id.substringAfterLast(":s", "").substringBefore("e").toIntOrNull()
        // Search under the *series* name, never the episode row's own.
        //
        // A TMDB episode placeholder is named for its episode ("E01 · Welcome to Margrave"), and
        // handing that to the scrapers searched every site for that phrase - which matches
        // nothing anywhere, so the download silently found no source at all. The season/episode
        // pair below is what identifies the episode; the title has to be the show. This is what
        // the play path already does by passing the series item rather than the episode.
        showFindStreamDialog(seriesItemForEpisode(channel), season, channel.episodeNum) { resolved ->
            downloadItem(resolved)
        }
        return
    }
    // HLS cannot be fetched as one file - it needs its segments pulled into a cache the player
    // can read back offline, which is a different downloader entirely (see HlsDownloads).
    if (HlsDownloads.isHls(channel.url)) {
        if (DownloadStore.get(this, channel.id) != null) {
            Toast.makeText(this, "Already in Downloads", Toast.LENGTH_SHORT).show()
            return
        }
        HlsDownloads.enqueue(this, channel)
        DownloadStore.add(
            this,
            DownloadRecord(
                id = channel.id,
                title = channel.name,
                subtitle = if (channel.mediaType == MediaType.SERIES)
                    channel.categoryName ?: "Episode" else "Movie",
                posterUrl = channel.posterUrl ?: channel.logoUrl,
                mediaType = channel.mediaType.name,
                // Media3 owns this download's lifecycle, not the system DownloadManager, so
                // there is no system id to record against it.
                downloadManagerId = HlsDownloads.NO_SYSTEM_ID,
                status = DownloadStatus.QUEUED,
            )
        )
        Toast.makeText(this, "Downloading \"${channel.name}\"", Toast.LENGTH_SHORT).show()
        if (showingDownloads) refreshDownloadsList()
        return
    }
    VodDownloader.unsupportedReason(channel)?.let { reason ->
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
        return
    }
    if (DownloadStore.get(this, channel.id) != null) {
        Toast.makeText(this, "Already in Downloads", Toast.LENGTH_SHORT).show()
        return
    }
    VodDownloader.enqueue(this, channel)
    Toast.makeText(this, "Downloading \"${channel.name}\"", Toast.LENGTH_SHORT).show()
    if (showingDownloads) refreshDownloadsList()
}

internal fun MainActivity.playDownload(record: DownloadRecord) {
    if (record.status != DownloadStatus.COMPLETE) {
        Toast.makeText(this, "Still downloading…", Toast.LENGTH_SHORT).show()
        return
    }
    // An HLS download is a tree of segments in Media3's cache, not a file - there is no path to
    // build a file:// URL from. It plays by replaying the original stream URL through a data
    // source wired to that cache, which serves it without touching the network.
    if (HlsDownloads.owns(record)) {
        val sourceUrl = HlsDownloads.sourceUrl(this, record.id)
        if (sourceUrl.isNullOrBlank()) {
            Toast.makeText(this, "Download is no longer available", Toast.LENGTH_LONG).show()
            return
        }
        currentIndex = -1
        playOfflineHls(record, sourceUrl)
        return
    }
    if (record.localFilePath.isNullOrBlank()) {
        Toast.makeText(this, "Still downloading…", Toast.LENGTH_SHORT).show()
        return
    }
    val local = Channel(
        id = record.id,
        name = record.title,
        url = "file://${record.localFilePath}",
        posterUrl = record.posterUrl,
        mediaType = runCatching { MediaType.valueOf(record.mediaType) }.getOrDefault(MediaType.MOVIE)
    )
    currentIndex = -1
    showPlayerFor(local)
}

/**
 * Plays a completed HLS download from its cache.
 *
 * The channel carries the stream's *original* URL, not a local path: Media3 keyed every segment
 * it stored by the URL it came from, so replaying that URL through a cache-backed data source is
 * what reads the download back. [MainActivity.offlineHlsPlaybackId] is what tells showPlayerFor
 * to swap the network source out, and it is cleared straight after so it cannot leak into the
 * next thing played.
 */
private fun MainActivity.playOfflineHls(record: DownloadRecord, sourceUrl: String) {
    val local = Channel(
        id = record.id,
        name = record.title,
        url = sourceUrl,
        posterUrl = record.posterUrl,
        mediaType = runCatching { MediaType.valueOf(record.mediaType) }.getOrDefault(MediaType.MOVIE)
    )
    offlineHlsPlaybackId = record.id
    try {
        showPlayerFor(local)
    } finally {
        offlineHlsPlaybackId = null
    }
}

internal fun MainActivity.deleteDownload(record: DownloadRecord) {
    AlertDialog.Builder(this)
        .setTitle("Delete download?")
        .setMessage("\"${record.title}\" will be removed from this device.")
        .setPositiveButton("Delete") { _, _ ->
            // Media3 owns the cached segments of an HLS download; handing its id to the system
            // downloader would delete nothing and leave the cache filled forever.
            if (HlsDownloads.owns(record)) {
                HlsDownloads.remove(this, record.id)
                DownloadStore.remove(this, record.id)
            } else {
                VodDownloader.delete(this, record)
            }
            refreshDownloadsList()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

internal fun MainActivity.refreshDownloadsList() {
    scope.launch {
        val records = withContext(Dispatchers.IO) {
            DownloadStore.getAll(this@refreshDownloadsList).map { rec ->
                when {
                    rec.status == DownloadStatus.COMPLETE -> rec
                    // An HLS download has no system DownloadManager id, and querying the system
                    // downloader for one it never issued reports FAILED - so these have to be
                    // read out of Media3's index instead.
                    HlsDownloads.owns(rec) -> HlsDownloads.refreshStatus(this@refreshDownloadsList, rec)
                    else -> VodDownloader.refreshStatus(this@refreshDownloadsList, rec)
                }
            }
        }
        downloadAdapter.submitList(records)
        binding.downloadsEmptyText.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        binding.downloadsContent.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
    }
}

// ── Tabs ───────────────────────────────────────

internal fun MainActivity.selectTab(index: Int) {
    activeSettingsOverlay?.dismiss()
    activeSearchOverlay?.dismiss()
    activeTab = index
    showingDownloads = false
    showingDiscover = false
    hideCatchup()
    // Owned here rather than by each caller. Every tab-bar handler already paired
    // "showingHome = false" with this call, so any *other* entry point (the launch
    // resume, which opens Live TV directly) left the flag set - and every later rebuild
    // that routes on it then bounced back to Home, tearing the guide and its live
    // preview down again right after they were built.
    showingHome = false
    binding.discoverContent.visibility = View.GONE
    binding.contentRow.visibility = View.VISIBLE
    binding.homeContent.visibility = View.GONE
    binding.homeSearchBar.visibility = View.GONE
    binding.liveRow.visibility = if (index == 0) View.VISIBLE else View.GONE
    binding.seriesContent.visibility = if (index == 1) View.VISIBLE else View.GONE
    binding.filmsContent.visibility = if (index == 2) View.VISIBLE else View.GONE
    binding.downloadsContent.visibility = View.GONE
    binding.downloadsEmptyText.visibility = View.GONE
    mainHandler.removeCallbacks(downloadsProgressRunnable)
    if (index == 0) {
        // releaseLivePreview() (called when leaving Live TV for any other tab) stops
        // and releases the preview player entirely - just re-showing the pane here
        // left it empty/stopped forever until something else happened to trigger a
        // reload. Explicitly reload whatever channel was last focused.
        showLivePreviewPane()
        buildGuideHeader()
        lastFocusedLiveChannel?.let { requestPreviewLoad(it) }
    } else {
        releaseLivePreview()
    }

    updateTabStyles(listOf(binding.tabLive, binding.tabSeries, binding.tabFilms)[index])
    // Every flag the Catch Up chip's gate reads (activeTab/showingHome/showingDiscover/
    // showingDownloads/showingCatchup) is already final at this point in the function.
    updateCatchupTabVisibility()

    // Series/Films with no IPTV/Jellyfin provider: the catalog these tabs normally browse
    // is empty, but a plugin can still resolve a stream for a TMDB title, so fall back to
    // Discover's own catalog instead of an empty category sidebar.
    if (index != 0 && !hasProviderEnabled() && hasProviderlessSource()) {
        showDiscoverBackedCatalogTab(index)
        return
    }

    selectedCategoryIds = null
    selectedBrandChannelIds = null
    selectedRowId = null
    selectedCategoryLabel = null
    selectedShelfItems = null
    expandedGroupKeys.clear()
    // Nothing from the outgoing tab stays on screen while the new one builds. The
    // sidebar and the content pane were both left up until submitCategories() replaced
    // them, so switching tabs showed the previous tab's categories - and, for a moment,
    // its posters - under the new tab's highlight. Both come back below, populated.
    applySidebarVisibility(tabWantsSidebar = false)
    binding.contentRow.visibility = View.GONE
    // Raise the loading indicator synchronously, in the same frame as the tab-bar
    // highlight, BEFORE the coroutine below does any work: the films/series derive
    // join can take seconds on a large catalog, and the category build is also
    // seconds - without this the user stares at a blank pane with no feedback for
    // the whole join window. applyStatus() lets it through because contentRow is now
    // GONE (the status row shares that same slot, and seriesContent/filmsContent/
    // liveRow are not part of its slotTaken set). It is cleared only at the bottom
    // of the coroutine, once the tab's content has actually landed.
    setStatus("Loading...", visible = true)
    scope.launch {
        // A tab switch away from Live must not race the films/series derive: categories
        // for the Films/Series tabs read filmList/seriesList, so wait for any in-flight
        // derive to land before building them against possibly-stale lists.
        if (index != 0) filmsSeriesDeriveJob?.join()
        // Pre-expand the Sports bucket so its children are visible when the user
        // scrolls down to it, regardless of what's selected at the top.
        if (index == 0) expandedGroupKeys.add("${DYNAMIC_BUCKET_ID_PREFIX}Sports")
        val categories = buildCategoriesForActiveTab()
        // buildCategoriesForActiveTab() is seconds' work on a large catalog, and the user
        // is free to leave the tab while it runs. Everything below puts the category
        // sidebar and the content row back on screen unconditionally, so landing late
        // dropped this tab's sidebar on top of whatever the user had moved to - most
        // visibly Discover, which has no sidebar of its own to overwrite it.
        if (activeTab != index || showingHome || showingDiscover || showingDownloads) {
            setStatus("", visible = false)
            return@launch
        }
        if (index == 0) {
            // Land on the topmost row the user actually curated: the Favourites channel
            // row, else their highest pinned category, and only then fall back to a
            // dynamic bucket (Sports etc). Pinned rows used to be skipped entirely
            // whenever no channel was favourited, which dropped the user into Sports
            // past the categories they'd deliberately pinned to the top.
            val hasFavourites = com.lumora.cache.FavoritesStore.getFavoriteChannelIds(this@selectTab).isNotEmpty()
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
        submitCategories(categories)
        applyCategoryFilter(focusFirstLiveChannel = index == 0)
        // Always scroll back to the very top of the sidebar when switching tabs, so the
        // first row (Live TV's "Show all categories" toggle, Films/Series' first row) is
        // what's on screen rather than wherever the previous tab was scrolled to - and,
        // on Live TV, rather than the auto-selected Favourites/Sports row further down,
        // which hid every row above it. The selection below it is unchanged; only the
        // scroll position is. The adapter's submitList() is async (ListAdapter diff), so
        // post() ensures the RecyclerView has laid out the new items before we scroll.
        binding.categorySidebar.post { binding.categorySidebar.scrollToPosition(0) }
        // Only now, with rows and content both in place. submitCategories() decides
        // whether the sidebar is warranted at all (a single row isn't worth one), so
        // don't override its call here.
        binding.contentRow.visibility = View.VISIBLE
        setStatus("", visible = false)
        applyStatus()
    }
}

// ── Lists ──────────────────────────────────────

internal fun MainActivity.buildGuideHeader() {
    val density = resources.displayMetrics.density
    val slotWidthPx = (30 * LiveGuideAdapter.MINUTE_WIDTH_DP * density).toInt()
    val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    val calendar = java.util.Calendar.getInstance()

    binding.guideHeaderRow.removeAllViews()
    repeat(24) { index ->
        val label = TextView(this).apply {
            text = if (index == 0) "Now" else timeFmt.format(calendar.time)
            setTextColor(getColor(R.color.text_tertiary))
            // Built in code, so it needs the dimen read explicitly to pick up the
            // large-screen tier that the XML-inflated guide rows below it get for free.
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.guide_program_text))
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((6 * density).toInt(), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(slotWidthPx, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        binding.guideHeaderRow.addView(label)
        calendar.add(java.util.Calendar.MINUTE, 30)
    }
    liveAdapter.attachHeader(binding.guideHeaderScroll)
}

internal fun MainActivity.setupChannelList() {
    binding.liveContent.layoutManager = LinearLayoutManager(this)
    binding.liveContent.addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { updateGuideRowWrap() }
    })
    binding.liveContent.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) { binding.liveContent.post { updateGuideRowWrap() } }
        override fun onChildViewDetachedFromWindow(view: View) {}
    })
    binding.seriesContent.layoutManager = LinearLayoutManager(this)
    binding.filmsContent.layoutManager = LinearLayoutManager(this)
    binding.categorySidebar.layoutManager = LinearLayoutManager(this)
    binding.homeContent.layoutManager = LinearLayoutManager(this)
    binding.downloadsContent.layoutManager = LinearLayoutManager(this)
    binding.downloadsContent.adapter = downloadAdapter
}

internal fun MainActivity.playItem(channel: Channel) {
    // User-initiated play: never inherit a resume-prompt suppression left over from
    // an auto-advance that never reached STATE_READY.
    skipResumePrompt = false
    when (channel.mediaType) {
        MediaType.SERIES -> { showContentDetail(channel); return }
        MediaType.MOVIE -> { showContentDetail(channel); return }
        else -> {}
    }
    val idx = liveChannels.indexOf(channel)
    if (idx >= 0) { currentIndex = idx; showPlayerFor(channel) }
}

/** One series' details and season/episode lists, whichever backend it came from. Jellyfin
 *  and Stalker carry plot/date/rating on the catalog item itself (no per-series detail
 *  endpoint); Xtream's get_series_info returns both in one call.
 *
 *  Shared by the detail screen and the in-player version picker - the picker has to pull
 *  a *different* provider's copy of the same show on demand to find its matching episode,
 *  which is exactly this call against a different Channel. */
internal suspend fun MainActivity.loadSeriesContent(
    item: Channel
): Pair<XtreamClient.ContentDetails?, List<Pair<String, List<Channel>>>> {
    val itemDetails = XtreamClient.ContentDetails(
        plot = item.description,
        genre = item.categoryName,
        rating = item.rating,
        backdropUrl = item.backdropUrl,
        releaseDate = item.releaseDate
    )
    val stalkerConfig = stalkerConfigFor(item)
    return when {
        // Anime catalog: build a flat episode list from the total episode count
        // carried on the Channel. Each episode click triggers a plugin stream search
        // for that specific episode (see showContentDetail's onEpisodeClick).
        item.id.startsWith(AnimeCatalogClient.ID_PREFIX) -> {
            val epCount = item.episodeNum?.coerceAtLeast(1) ?: 12
            val episodes = (1..epCount).map { epNum ->
                Channel(
                    id = "${item.id}:ep$epNum",
                    name = "Episode $epNum",
                    url = "",
                    posterUrl = item.posterUrl,
                    backdropUrl = item.backdropUrl,
                    mediaType = MediaType.SERIES,
                    episodeNum = epNum,
                    categoryName = item.categoryName,
                    group = item.group
                )
            }
            itemDetails to listOf("Season 1" to episodes)
        }
        item.isJellyfin -> {
            val jellyfin = jellyfinClientOrConnect()
            val (episodes, seasons) = if (jellyfin != null) {
                withContext(Dispatchers.IO) { jellyfin.getEpisodes(item.id) to jellyfin.getSeasons(item.id) }
            } else {
                emptyList<JellyfinProvider.JellyfinItem>() to emptyList()
            }
            val stub = jellyfinProviderStub(jellyfinServerUrl())
            // Watched/resume state for these episodes comes from the same UserData the
            // catalog fetch reads, so an episode list opened here shows progress made in
            // any other client (EpisodeAdapter reads it out of PlaybackPositionStore).
            importJellyfinUserState(episodes, includePlayed = true)
            // Season *names* come from the server - grouping on ParentIndexNumber alone
            // can only ever produce "Season 0" for specials, which is not what any
            // Jellyfin library calls that row.
            val seasonNames = seasons.mapNotNull { season ->
                season.indexNumber?.let { it to season.name }
            }.toMap()
            itemDetails to episodes
                .groupBy { it.seasonNumber ?: 0 }
                .toSortedMap()
                .map { (num, eps) ->
                    val label = seasonNames[num] ?: if (num == 0) "Specials" else "Season $num"
                    label to eps.map { JellyfinProvider.toChannel(it, stub) }
                }
        }
        stalkerConfig != null -> {
            val stalker = StalkerProvider(BaseApplication.instance.okHttpClient)
            itemDetails to withContext(Dispatchers.IO) {
                stalker.getEpisodes(stalkerProviderStub(stalkerConfig), item.id, item.categoryId)
                    .map { (label, eps) ->
                        label to eps.map { it.copy(streamUserAgent = stalkerConfig.userAgent, sourceProviderId = stalkerConfig.id) }
                    }
            }
        }
        else -> {
            val client = XtreamClient(BaseApplication.instance.okHttpClient)
            val info = withContext(Dispatchers.IO) { client.getSeriesFull(xtreamProviderFor(item) ?: provider, item.id) }
            info.details to info.seasons
        }
    }
}

/** Chip label for one version of a duplicated title: which provider it came from first,
 *  since that's what actually distinguishes two copies once several providers are merged,
 *  then the title's own source/quality tag ("4K-D+") when it has one. */
internal fun MainActivity.versionChipLabel(version: Channel, index: Int): String =
    listOfNotNull(providerNameFor(version), extractLeadingTag(version.name))
        .joinToString(" · ")
        .ifBlank { "Version ${index + 1}" }

/** One version-picker chip, styled to sit inline next to Play. item_category's own text
 *  size is the sidebar's, so it's stepped down to the general caption dimen (still scales
 *  up on a large screen). item_category's root is now a container (star + label), so the
 *  label is detached and its chip chrome re-applied - the container is just a scaffold. */
internal fun MainActivity.inflateVersionChip(parent: ViewGroup, label: String): TextView {
    val root = layoutInflater.inflate(R.layout.item_category, parent, false)
    val chip = root.findViewById<TextView>(R.id.categoryLabel)
    (root as ViewGroup).removeView(chip)
    chip.text = label
    chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_caption))
    val padH = (12 * resources.displayMetrics.density).toInt()
    val padV = (8 * resources.displayMetrics.density).toInt()
    chip.setPadding(padH, padV, padH, padV)
    chip.background = ContextCompat.getDrawable(this, R.drawable.bg_select_item)
    chip.stateListAnimator = AnimatorInflater.loadStateListAnimator(this, R.animator.focus_scale)
    chip.isClickable = true
    chip.isFocusable = true
    chip.layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
    return chip
}

/** Unified detail screen for a movie or series: poster/plot/cast plus its versions or
 *  episode list. [versionGroup] carries the duplicate set through when the screen re-opens
 *  on a sibling copy (series only - see the series version chips below), since the map is
 *  keyed by the group's representative and a sibling isn't in it. */
internal fun MainActivity.showContentDetail(item: Channel, versionGroup: List<Channel>? = null) {
    isContentDetailVisible = true
    // What Back should land on when this screen closes. Set before anything else so a
    // detail re-opened on a sibling version (the chips further down) updates it too.
    detailReturnItemId = item.id
    binding.mainContent.visibility = View.GONE
    binding.contentDetailLayout.visibility = View.VISIBLE
    applyStatus()

    val backdrop = binding.detailBackdrop
    val titleText = binding.detailTitle
    val metaText = binding.detailMeta
    val plotText = binding.detailPlot
    val castText = binding.detailCast
    val plotLabel = binding.detailPlotLabel
    val castLabel = binding.detailCastLabel
    val releaseDateText = binding.detailReleaseDate
    val sectionLabel = binding.detailSectionLabel
    val statusText = binding.detailStatus
    val itemsList = binding.detailItemsList
    val seasonScroll = binding.detailSeasonScroll
    val seasonRow = binding.detailSeasonRow
    val playButton = binding.detailPlayButton
    val playButtonLabel = binding.detailPlayButtonLabel
    val favoriteButton = binding.detailFavoriteButton
    val favoriteIcon = binding.detailFavoriteIcon
    val versionsScroll = binding.detailVersionsScroll
    val versionsRow = binding.detailVersionsRow
    val downloadButton = binding.detailDownloadButton

    // These views are reused across opens now (no longer a fresh dialog each time) -
    // reset everything a previous item may have left behind before showing new data.
    backdrop.setImageDrawable(null)
    plotText.visibility = View.GONE
    castText.visibility = View.GONE
    plotLabel.visibility = View.GONE
    castLabel.visibility = View.GONE
    releaseDateText.visibility = View.GONE
    playButton.visibility = View.GONE
    // Only the series path (wirePlayButton) ever writes this, tagging it with the
    // episode it would resume - "Resume S1E1". These views are reused across opens, so
    // without a reset that tag stayed on the button when a *film* was opened next.
    playButtonLabel.text = "Play"
    favoriteButton.visibility = View.GONE
    downloadButton.visibility = View.GONE
    downloadButton.setOnClickListener(null)
    seasonScroll.visibility = View.GONE
    seasonRow.removeAllViews()
    selectedSeasonChip = null
    versionsScroll.visibility = View.GONE
    versionsRow.removeAllViews()
    sectionLabel.text = ""
    itemsList.visibility = View.GONE
    itemsList.adapter = null
    statusText.text = getString(R.string.loading)
    statusText.visibility = View.VISIBLE
    playButton.setOnClickListener(null)
    binding.detailBackButton.setOnClickListener { hideContentDetail() }
    // Nothing requests focus just because contentDetailLayout became visible - without
    // this the D-pad has no reliable starting point on this screen (same class of bug
    // fixed elsewhere via restoreTabFocus()). Landing on Play once it loads is more
    // useful than the back button, so this gets overridden below once it's known visible.
    binding.detailBackButton.requestFocus()
    // ...and again once the frame has settled, because a screen opened from a dismissing
    // dialog can have that first request cancelled by the dialog's window teardown. Only
    // when nothing else claimed focus in the meantime, so it can't steal from the Play
    // button (or a season chip) that legitimately took it while content was loading.
    binding.contentDetailLayout.post {
        if (isContentDetailVisible && binding.contentDetailLayout.findFocus() == null) {
            binding.detailBackButton.requestFocus()
        }
    }

    val isSeries = item.mediaType == MediaType.SERIES
    titleText.text = item.name
    metaText.text = listOfNotNull(
        item.year,
        item.rating?.takeIf { it.isNotBlank() }?.let { "★ $it" },
        item.categoryName?.takeIf { it.isNotBlank() }
    ).joinToString("  ·  ")
    itemsList.layoutManager = LinearLayoutManager(this)
    loadDetailImage(item.posterUrl ?: item.logoUrl, backdrop)
    wireFindStreamButton(item)
    wireTrailerButton(item)

    // Series version chips. A film's versions are alternate streams of one thing, so its
    // chips play directly; a series' are whole separate episode lists, one per provider
    // that carries the title, so picking one re-opens this screen on that copy instead.
    // Before this, every duplicate but the representative was dropped at grouping time -
    // if the copy that won the card had a thin or broken episode list, the other
    // provider's was unreachable.
    val seriesGroup = if (isSeries) versionGroup ?: seriesVersions[item.id] else null
    if (seriesGroup != null && seriesGroup.size > 1) {
        versionsScroll.visibility = View.VISIBLE
        seriesGroup.forEachIndexed { index, version ->
            val chip = inflateVersionChip(versionsRow, versionChipLabel(version, index))
            chip.isSelected = version.id == item.id
            chip.setOnClickListener {
                if (version.id != item.id) showContentDetail(version, seriesGroup)
            }
            versionsRow.addView(chip)
        }
    }

    if (isSeries && item.id.isNotBlank()) {
        favoriteButton.visibility = View.VISIBLE
        fun refreshFavoriteIcon() {
            favoriteIcon.text = if (FavoritesStore.isFavoriteSeries(this, item.id)) "★" else "☆"
        }
        refreshFavoriteIcon()
        favoriteButton.setOnClickListener {
            val nowFavorite = FavoritesStore.toggleFavoriteSeries(this, item.id)
            refreshFavoriteIcon()
            // A Jellyfin item's favourite state belongs to the server - push it so the
            // star shows up in every other client, and survives a reinstall here.
            if (item.isJellyfin && item.id.isNotBlank()) {
                scope.launch {
                    val client = jellyfinClientOrConnect() ?: return@launch
                    withContext(Dispatchers.IO) { runCatching { client.setFavorite(item.id, nowFavorite) } }
                }
            }
            scope.launch { classifyAndShow() }
        }
    }

    lateinit var itemAdapter: EpisodeAdapter
    // Season/episode list is loaded async after the adapter below is built; the
    // watched-toggle callback fires on user interaction (always after load), so it
    // reads this holder rather than the coroutine's local.
    var detailSeasons: List<Pair<String, List<Channel>>> = emptyList()
    // The detail Play button targets the first unwatched episode; a check-toggle
    // shifts that target, so toggles refresh it. Assigned once wirePlayButton exists
    // (declared below - local funs can't be forward-referenced).
    var playButtonRefresh: (() -> Unit)? = null

    // A season chip's checkmark reads checked when every episode in that season is
    // watched. Re-runs after any episode or season toggle.
    fun refreshSeasonChipStates(seasons: List<Pair<String, List<Channel>>>) {
        for (i in 0 until seasonRow.childCount) {
            val cell = seasonRow.getChildAt(i) as? ViewGroup ?: continue
            val check = cell.findViewById<View>(R.id.seasonCheckButton) ?: continue
            val episodes = seasons.getOrNull(i)?.second ?: emptyList()
            if (episodes.isEmpty()) {
                // A season with nothing in it can't be marked - hide its dead check.
                check.visibility = View.GONE
                continue
            }
            check.visibility = View.VISIBLE
            val watched = episodes.all { ep ->
                val key = ep.id.ifBlank { ep.url }
                key.isNotBlank() && PlaybackPositionStore.get(this@showContentDetail, key)?.isNearComplete == true
            }
            check.isSelected = watched
            check.contentDescription = getString(
                if (watched) R.string.season_mark_unwatched else R.string.season_mark_watched
            )
        }
    }

    // Whole-season watched toggle: mark/unmark every episode in one press. The
    // episode list repaints via notifyDataSetChanged (DiffUtil wouldn't rebind on a
    // submitList since the Channel items are unchanged), then chips and the Play
    // target follow.
    fun toggleSeasonWatched(seasons: List<Pair<String, List<Channel>>>, index: Int) {
        val episodes = seasons.getOrNull(index)?.second ?: return
        if (episodes.isEmpty()) return
        val allWatched = episodes.all { ep ->
            val key = ep.id.ifBlank { ep.url }
            key.isNotBlank() && PlaybackPositionStore.get(this@showContentDetail, key)?.isNearComplete == true
        }
        for (ep in episodes) {
            val key = ep.id.ifBlank { ep.url }
            if (key.isBlank()) continue
            if (allWatched) {
                PlaybackPositionStore.clear(this@showContentDetail, key)
            } else {
                PlaybackPositionStore.save(this@showContentDetail, key, 1L, 1L, ep)
            }
        }
        itemAdapter.notifyDataSetChanged()
        refreshSeasonChipStates(seasons)
        playButtonRefresh?.invoke()
        // Watched state moved for a whole season - the Home up-next tiles are stale, and
        // the Series tab's CW surfaces (poster shelf, sidebar count, open grid) are built
        // from the same store, so they need a refresh too.
        clearUpNextMemo()
        refreshSeriesShelvesIfShowing()
        if (showingHome) homeShelfAdapter.submitList(buildHomeShelves())
        // preserveUi: the plain form ends in selectTab(), which clears the selected
        // category, tears the sidebar down and rebuilds the pane from scratch - all
        // behind this detail screen, which then had its season chip yanked out from
        // under the user's cursor and landed focus somewhere arbitrary on close.
        if (!showingHome && activeTab != 0) scope.launch { classifyAndShow(preserveUi = true) }
    }

    itemAdapter = EpisodeAdapter(
        onEpisodeClick = { chosen ->
            // User-initiated play - see playItem for why the suppression flag is cleared here.
            skipResumePrompt = false
            hideContentDetail()
            // Anime items have no direct stream URL — route through plugin.
            if (item.id.startsWith(AnimeCatalogClient.ID_PREFIX)) {
                val plugin = enabledStreamSearchPlugin(item)
                if (plugin != null) {
                    showStreamSearchDialog(plugin, item, season = null, episode = chosen.episodeNum)
                }
            } else if (chosen.url.isBlank()) {
                // A TMDB-built episode placeholder (see tmdbSeasonsFor) - there is no stream
                // until one is found for this specific episode.
                val season = chosen.id.substringAfterLast(":s").substringBefore("e").toIntOrNull()
                showFindStreamDialog(item, season, chosen.episodeNum)
            } else {
                currentIndex = if (isSeries) -1 else filmList.indexOf(item)
                val queue = if (isSeries) itemAdapter.currentList else emptyList()
                showPlayerFor(chosen)
                detailReturnItem = item
                detailReturnGroup = seriesGroup
                if (isSeries) {
                    currentEpisodeQueue = queue
                    currentEpisodeQueueIndex = queue.indexOf(chosen)
                    currentSeriesVersionContext = item to (seriesGroup ?: listOf(item))
                }
            }
        },
        showDownloadButton = !isTv,
        onDownloadClick = { episode -> downloadItem(episode) },
        isDownloaded = { episode -> DownloadStore.get(this, episode.id) != null },
        seriesName = if (isSeries) item.name else null,
        onWatchedToggle = { _, _ ->
            // One episode flipped - refresh the season chips' all-watched state and the
            // Play button's first-unwatched target. The toggled row itself already
            // repainted inside the adapter. Watched state changed, so the Home up-next
            // memo is stale too - and the Series tab's CW poster shelf / sidebar count /
            // open CW grid are all built from the store, so they need a refresh or a
            // watched episode lingers in Continue Watching.
            clearUpNextMemo()
            refreshSeriesShelvesIfShowing()
            if (showingHome) homeShelfAdapter.submitList(buildHomeShelves())
            // preserveUi for the same reason as toggleSeasonWatched above.
            if (!showingHome && activeTab != 0) scope.launch { classifyAndShow(preserveUi = true) }
            refreshSeasonChipStates(detailSeasons)
            playButtonRefresh?.invoke()
        }
    )
    itemsList.adapter = itemAdapter

    fun applyDetails(details: XtreamClient.ContentDetails?) {
        if (details == null) return
        if (!details.releaseDate.isNullOrBlank()) {
            releaseDateText.text = "Released: ${details.releaseDate}"
            releaseDateText.visibility = View.VISIBLE
        }
        if (!details.plot.isNullOrBlank()) {
            plotText.text = details.plot
            plotText.visibility = View.VISIBLE
            plotLabel.visibility = View.VISIBLE
        }
        val castLine = listOfNotNull(
            details.genre?.takeIf { it.isNotBlank() }?.let { "Genre: $it" },
            details.director?.takeIf { it.isNotBlank() }?.let { "Director: $it" },
            details.cast?.takeIf { it.isNotBlank() }?.let { "Cast: $it" }
        ).joinToString("\n")
        if (castLine.isNotBlank()) {
            castText.text = castLine
            castText.visibility = View.VISIBLE
            castLabel.visibility = View.VISIBLE
        }
        if (!details.backdropUrl.isNullOrBlank()) loadDetailImage(details.backdropUrl, backdrop)
    }

    /**
     * Fills the blanks in a film's / show's own detail block from TMDB - plot, backdrop,
     * release date, genre, director, cast - then re-applies it.
     *
     * Same rule and shape as the per-episode enrichment: provider data always wins, TMDB
     * only supplies what came back empty, and it runs after the provider's own details are
     * already on screen so nothing waits on it. Plenty of panels return a film with no
     * overview and no backdrop at all, which left the detail screen as a title and a Play
     * button.
     */
    fun enrichDetailsFromTmdb(details: XtreamClient.ContentDetails?, isSeries: Boolean, requestedItemId: String) {
        if (!tmdbClient.hasKey()) return
        val needs = details == null ||
            details.plot.isNullOrBlank() || details.backdropUrl.isNullOrBlank() ||
            details.releaseDate.isNullOrBlank() || details.genre.isNullOrBlank() ||
            details.director.isNullOrBlank() || details.cast.isNullOrBlank()
        if (!needs) return
        scope.launch {
            // Panel-supplied TMDB id first - a title search resolves an ambiguous name
            // ("Run", "Fearless", "Deep Cover") to the wrong film's plot and backdrop.
            val resolved = item.tmdbTypeAndId()
                ?: tmdbClient.resolveId(cleanVodTitle(item.name), item.tmdbYear(), isSeries)
                ?: return@launch
            val tmdb = tmdbClient.titleDetails(resolved.first, resolved.second) ?: return@launch
            // The user can have moved to another title while the two calls ran - applying
            // then would write one film's plot onto another's screen.
            if (nowShowingDetailId != requestedItemId) return@launch
            applyDetails(
                XtreamClient.ContentDetails(
                    plot = details?.plot?.takeIf { it.isNotBlank() } ?: tmdb.overview,
                    genre = details?.genre?.takeIf { it.isNotBlank() } ?: tmdb.genre,
                    director = details?.director?.takeIf { it.isNotBlank() } ?: tmdb.director,
                    cast = details?.cast?.takeIf { it.isNotBlank() } ?: tmdb.cast,
                    rating = details?.rating?.takeIf { it.isNotBlank() } ?: tmdb.rating,
                    backdropUrl = details?.backdropUrl?.takeIf { it.isNotBlank() } ?: tmdb.backdropUrl,
                    releaseDate = details?.releaseDate?.takeIf { it.isNotBlank() } ?: tmdb.releaseDate
                )
            )
        }
    }

    // TMDB episode enrichment state, per open detail screen. The show's TMDB id is resolved
    // at most once and shared by every season (held as a Deferred rather than a plain field
    // so two quick season switches await one search instead of racing two).
    var tmdbTvIdJob: Deferred<Int?>? = null
    var seasonEnrichToken = 0

    /**
     * Fills in what the provider left blank on this season's episode rows - title, plot,
     * still image - from TMDB, then re-submits the season.
     *
     * Only ever fills blanks: a provider that ships real episode metadata is the better
     * source (it describes the copy actually being played), and TMDB is the fallback for
     * the many panels that return nothing but "Episode 4". Runs after the season is already
     * on screen rather than inside loadSeriesContent, so the list paints at provider speed
     * and enrichment lands underneath it; and only for the season being looked at, so
     * opening a 12-season show costs one request, not twelve.
     *
     * The enriched copies go to the adapter alone, never back into `seasons`: only
     * name/description/posterUrl change, and every other consumer of that list keys off
     * id/url/episodeNum, which are untouched.
     */
    fun enrichSeasonFromTmdb(seasons: List<Pair<String, List<Channel>>>, index: Int) {
        if (!tmdbClient.hasKey()) return
        val (label, episodes) = seasons.getOrNull(index) ?: return
        if (episodes.none { needsEpisodeMetadata(it, item.name) }) return
        // Bumped per season switch: a slow response for the season the user has already
        // chipped away from must not overwrite the one now on screen.
        val token = ++seasonEnrichToken
        scope.launch {
            val idJob = tmdbTvIdJob ?: async {
                item.tmdbTypeAndId()?.takeIf { it.first == "tv" }?.second
                    ?: tmdbClient.resolveId(cleanVodTitle(item.name), item.tmdbYear(), isSeries = true)
                        ?.takeIf { it.first == "tv" }?.second
            }.also { tmdbTvIdJob = it }
            val tvId = idJob.await() ?: return@launch
            // Season label is the provider's ("Season 3", "S3", a Jellyfin custom name);
            // its number is what TMDB indexes by, and position is the fallback for a label
            // carrying no digits at all.
            val seasonNumber = Regex("""\d+""").find(label)?.value?.toIntOrNull() ?: (index + 1)
            val meta = tmdbClient.tvEpisodes(tvId, seasonNumber)
            if (meta.isEmpty() || token != seasonEnrichToken) return@launch
            var changed = false
            val merged = episodes.map { ep ->
                val m = ep.episodeNum?.let { meta[it] } ?: return@map ep
                val name = if (isPlaceholderEpisodeTitle(ep, item.name) && !m.name.isNullOrBlank()) {
                    // Keep the "S01E04 · " prefix the rest of the app parses back out
                    // (Up Next, search, watch history all read it off `name`).
                    val prefix = EPISODE_NAME_PREFIX_REGEX.find(ep.name)?.value.orEmpty()
                    prefix + m.name
                } else {
                    ep.name
                }
                val description = ep.description?.takeIf { it.isNotBlank() } ?: m.overview
                val poster = ep.posterUrl?.takeIf { it.isNotBlank() } ?: m.stillUrl
                if (name == ep.name && description == ep.description && poster == ep.posterUrl) {
                    ep
                } else {
                    changed = true
                    ep.copy(name = name, description = description, posterUrl = poster)
                }
            }
            if (changed && token == seasonEnrichToken) itemAdapter.submitList(merged)
        }
    }

    fun showSeason(seasons: List<Pair<String, List<Channel>>>, index: Int) {
        for (i in 0 until seasonRow.childCount) {
            val cell = seasonRow.getChildAt(i) as? ViewGroup ?: continue
            val chip = cell.findViewById<View>(R.id.seasonChipLabel) ?: continue
            chip.isSelected = i == index
            // The focus "pop" is a stateListAnimator, so a chip that loses focus in the
            // same frame it's clicked can keep the scaled-up transform the animator was
            // mid-way through - leaving a visibly enlarged leftover chip behind after
            // switching seasons. Snap every chip back to rest; the animator re-applies
            // from there on the next real focus change.
            chip.animate().cancel()
            chip.scaleX = 1f
            chip.scaleY = 1f
        }
        // UP escaping the episode list's first row jumps straight to this chip rather
        // than through default focus search - see dispatchKeyEvent's episode-list block.
        // The chip is the focusable label inside the cell, not the cell itself.
        selectedSeasonChip = (seasonRow.getChildAt(index) as? ViewGroup)
            ?.findViewById<View>(R.id.seasonChipLabel)
        itemAdapter.submitList(seasons[index].second)
        enrichSeasonFromTmdb(seasons, index)
    }

    // Series had no equivalent of the film branch's Play button below - the only
    // action on the whole screen was the small favorite star, with no way to jump
    // straight into playback without first picking a season/episode manually. Finds
    // whichever episode was left in progress most recently (across every season, not
    // just the one currently shown), or falls back to the very first episode if
    // nothing's been started yet.
    data class SeriesTargetSelection(val target: Channel, val ordered: List<Channel>, val isResume: Boolean)

    fun findSeriesTargetEpisode(seasons: List<Pair<String, List<Channel>>>): SeriesTargetSelection? {
        // Cross-season episode chain - the same ordering the Home-tile auto-advance
        // queue uses: seasons in the order they were loaded, episodes within each
        // season sorted by number.
        val ordered = seasons.flatMap { (_, eps) ->
            eps.sortedBy { it.episodeNum ?: Int.MAX_VALUE }
        }
        // The "next episode to watch" is whatever was left part-watched most recently
        // (across every season, not just the one currently shown): the in-progress
        // episode with the newest saved position.
        val inProgress = ordered.mapNotNull { ep ->
            val key = ep.id.ifBlank { ep.url }
            if (key.isBlank()) return@mapNotNull null
            PlaybackPositionStore.get(this, key)
                ?.takeIf { !it.isNearComplete && it.positionMs > 0 }
                ?.let { ep to it }
        }.maxByOrNull { it.second.updatedAt }
        // Nothing part-watched: scan the chain in order, skipping finished
        // (near-complete) episodes, and land on the first episode that still needs
        // watching. Every episode finished falls back to episode 1.
        val target = inProgress?.first ?: ordered.firstOrNull { ep ->
            val key = ep.id.ifBlank { ep.url }
            key.isNotBlank() && PlaybackPositionStore.get(this, key)?.isNearComplete != true
        } ?: ordered.firstOrNull() ?: return null
        return SeriesTargetSelection(target, ordered, inProgress != null)
    }

    fun wirePlayButton(seasons: List<Pair<String, List<Channel>>>, refocus: Boolean = true) {
        val selection = findSeriesTargetEpisode(seasons) ?: return
        val target = selection.target
        val ordered = selection.ordered
        val seasonPair = seasons.firstOrNull { (_, eps) -> eps.any { it.id == target.id } }
        val seasonNum = seasonPair?.first?.let { Regex("""\d+""").find(it)?.value }
        // "Play"/"Resume" alone didn't say *which* episode - with several seasons in
        // play this was a guessing game before committing to it.
        val tag = if (seasonNum != null && target.episodeNum != null) "S${seasonNum}E${target.episodeNum}" else null
        playButtonLabel.text = listOfNotNull(if (selection.isResume) "Resume" else "Play", tag).joinToString(" ")
        playButton.visibility = View.VISIBLE
        // Landing focus on Play once the screen opens; refocus=false on watch-toggle
        // refreshes (label/target changed) must NOT steal focus - the user is on a check.
        if (refocus) playButton.requestFocus()
        playButton.setOnClickListener {
            // User-initiated play - see playItem for why the suppression flag is cleared here.
            skipResumePrompt = false
            hideContentDetail()
            // Anime items route through the plugin instead of direct playback.
            if (item.id.startsWith(AnimeCatalogClient.ID_PREFIX)) {
                val plugin = enabledStreamSearchPlugin(item)
                if (plugin != null) {
                    showStreamSearchDialog(plugin, item, season = null, episode = target.episodeNum)
                }
            } else {
                currentIndex = -1
                // Full cross-season chain behind the chosen episode, so it keeps
                // auto-advancing through the whole show, not just the current season.
                showPlayerFor(target)
                detailReturnItem = item
                detailReturnGroup = seriesGroup
                currentEpisodeQueue = ordered
                currentEpisodeQueueIndex = ordered.indexOf(target)
                currentSeriesVersionContext = item to (seriesGroup ?: listOf(item))
            }
        }
    }

    // After a check-toggle moves which episode is "next", the Play button's label and
    // target must follow - re-wired without stealing focus from the check the user is on.
    playButtonRefresh = { if (detailSeasons.isNotEmpty()) wirePlayButton(detailSeasons, refocus = false) }

    val requestedItemId = item.id
    val isJellyfin = item.isJellyfin
    scope.launch {
        try {
            val client = XtreamClient(BaseApplication.instance.okHttpClient)
            if (isSeries) {
                sectionLabel.text = "Episodes"
                val (details, librarySeasons) = loadSeriesContent(item)
                if (nowShowingDetailId != requestedItemId) return@launch
                applyDetails(details)
                enrichDetailsFromTmdb(details, isSeries = true, requestedItemId = requestedItemId)
                // A Discover title the library does not have has no provider behind it, so
                // loadSeriesContent finds nothing. TMDB knows the episodes even when no source
                // does, and Find Stream can play any of them - so list those rather than showing
                // an empty state for a series that is perfectly watchable.
                //
                // Resolved into `seasons` itself rather than carried alongside it: everything
                // below - the chips, their click and watched-toggle handlers, the target-episode
                // lookup - closes over this one list, and having a second "effective" copy meant
                // the chips were built from TMDB while their handlers still indexed the empty
                // library list, which crashed on the first chip press.
                // Not "library or TMDB" - a provider that carries a series often carries only
                // part of it, and choosing the library wholesale showed those seasons as
                // complete when they were one episode of eight. Merged, so what the provider
                // has plays directly and what it is missing routes to Find Stream.
                val seasons = if (librarySeasons.all { it.second.isEmpty() }) tmdbSeasonsFor(item)
                    else mergeMissingEpisodesFromTmdb(item, librarySeasons)
                if (nowShowingDetailId != requestedItemId) return@launch
                detailSeasons = seasons
                if (seasons.all { it.second.isEmpty() }) {
                    statusText.text = "No episodes found"
                } else {
                    statusText.visibility = View.GONE
                    itemsList.visibility = View.VISIBLE
                    // Built for a single-season show too, not just multi-season ones. The
                    // row was the only place the whole-season watched check lives, so a
                    // one-season show had no way to tick the season off at all - only
                    // episode-by-episode. One chip is also the honest label for what the
                    // list below is showing.
                    if (seasons.isNotEmpty()) {
                        seasonScroll.visibility = View.VISIBLE
                        seasons.forEachIndexed { index, (label, _) ->
                            val cell = layoutInflater.inflate(R.layout.item_season_chip, seasonRow, false) as ViewGroup
                            val chip = cell.findViewById<TextView>(R.id.seasonChipLabel)
                            val check = cell.findViewById<TextView>(R.id.seasonCheckButton)
                            chip.text = label
                            cell.layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
                            // Chip click still selects the season - unchanged.
                            chip.setOnClickListener { showSeason(seasons, index) }
                            // The check click only toggles the whole season's watched state.
                            check.setOnClickListener { toggleSeasonWatched(seasons, index) }
                            // Same intra-cell LEFT/RIGHT wiring as the episode row's check:
                            // default focus search won't cross into a focusable child at
                            // the cell's edge, so label <-> check navigate explicitly.
                            chip.setOnKeyListener { _, keyCode, event ->
                                if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                                    keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                                ) {
                                    check.requestFocus()
                                    true
                                } else {
                                    false
                                }
                            }
                            check.setOnKeyListener { _, keyCode, event ->
                                if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                                    keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                                ) {
                                    chip.requestFocus()
                                    true
                                } else {
                                    false
                                }
                            }
                            seasonRow.addView(cell)
                        }
                        refreshSeasonChipStates(seasons)
                    }
                    // Default the season selector to the season of the episode the user
                    // is actually on (the same "next episode to watch" the Play button
                    // targets), not season 1 - a resume from Continue Watching shouldn't
                    // land on the wrong season's episode list. Re-runs on return from
                    // playback (hidePlayer re-opens the detail), so the chip follows the
                    // episode the user just finished too.
                    val targetEp = findSeriesTargetEpisode(seasons)?.target
                    val targetSeasonIndex = seasons.indexOfFirst { (_, eps) -> eps.any { it.id == targetEp?.id } }.coerceAtLeast(0)
                    showSeason(seasons, targetSeasonIndex)
                    wirePlayButton(seasons)
                }
            } else {
                // Xtream has a separate get_vod_info call for a film's plot/cast/genre;
                // Jellyfin has no equivalent (nor any need for one) - the item already
                // carries all of that from the catalog fetch, same as the series branch
                // above. Calling getVodInfo() here regardless of provider used to send
                // an Xtream-shaped request with a Jellyfin item id to an Xtream-only
                // endpoint, which is why overview/cast/genre came back empty for every
                // Jellyfin film.
                // getVodInfo is an Xtream-only call. Jellyfin and Stalker both carry the
                // film's plot/date/rating on the catalog item itself, so use that - sending
                // an Xtream-shaped get_vod_info for a Stalker item hit the wrong endpoint
                // and came back empty, which is why overview/release date were blank.
                val itemXtream = xtreamProviderFor(item)
                val details = if (isJellyfin || itemXtream == null) {
                    XtreamClient.ContentDetails(
                        plot = item.description,
                        genre = item.categoryName,
                        rating = item.rating,
                        backdropUrl = item.backdropUrl,
                        releaseDate = item.releaseDate
                    )
                } else {
                    withContext(Dispatchers.IO) { client.getVodInfo(itemXtream, item.id) }
                }
                if (nowShowingDetailId != requestedItemId) return@launch
                applyDetails(details)
                enrichDetailsFromTmdb(details, isSeries = false, requestedItemId = requestedItemId)
                val versions = filmVersions[item.id] ?: listOf(item)
                statusText.visibility = View.GONE

                // The obvious action for a film is "play it" - a button, not a list
                // labeled "Versions" with one cryptically-named entry in it.
                // No episode tag to add here (that's series-only), but a part-watched
                // film should still read "Resume" rather than "Play", same as one does
                // in the Continue Watching shelf it was probably reached from.
                val filmKey = item.id.ifBlank { item.url }
                val filmProgress = filmKey.takeIf { it.isNotBlank() }
                    ?.let { PlaybackPositionStore.get(this@showContentDetail, it) }
                    ?.takeIf { !it.isNearComplete && it.positionMs > 0 }
                playButtonLabel.text = if (filmProgress != null) "Resume" else "Play"
                // A title opened from Discover that the library does not have has no URL to
                // play - its only route to a stream is Find Stream. Showing Play there would be
                // a button that can only fail, so it stays hidden and Find Stream takes focus.
                val playable = item.url.isNotBlank() || versions.any { it.url.isNotBlank() }
                playButton.visibility = if (playable) View.VISIBLE else View.GONE
                if (playable) playButton.requestFocus()
                else binding.detailFindStreamButton.takeIf { it.visibility == View.VISIBLE }
                    ?.requestFocus()
                playButton.setOnClickListener {
                    // User-initiated play - see playItem for why the suppression flag is cleared here.
                    skipResumePrompt = false
                    hideContentDetail()
                    currentIndex = filmList.indexOf(item)
                    showPlayerFor(versions.first())
                    detailReturnItem = item
                    detailReturnGroup = versionGroup
                }
                if (!isTv) {
                    downloadButton.visibility = View.VISIBLE
                    downloadButton.setOnClickListener { downloadItem(versions.first()) }
                }
                // Version picker sits right next to Play as small chips, not buried
                // in a full-width list below the plot/cast - tapping one plays that
                // specific version directly instead of requiring a second Play tap.
                if (versions.size > 1) {
                    versionsScroll.visibility = View.VISIBLE
                    versions.forEachIndexed { index, version ->
                        val chip = inflateVersionChip(versionsRow, versionChipLabel(version, index))
                        chip.isSelected = index == 0
                        chip.setOnClickListener {
                            hideContentDetail()
                            currentIndex = filmList.indexOf(item)
                            showPlayerFor(version)
                            detailReturnItem = item
                            detailReturnGroup = versionGroup
                        }
                        versionsRow.addView(chip)
                    }
                }
            }
        } catch (e: Exception) {
            if (nowShowingDetailId == requestedItemId) statusText.text = "Failed to load details: ${e.message?.take(60)}"
        }
    }
    nowShowingDetailId = item.id
}

/** The "S01E04 · " marker both Xtream and Jellyfin bake onto an episode name. */
private val EPISODE_NAME_PREFIX_REGEX = Regex("""^S\d+E\d+ · """)
private val EPISODE_SEASON_REGEX = Regex("""^S(\d+)E\d+""")

/** The season an episode belongs to. A Channel has an episodeNum but no season field, so
 *  it's read back out of the "S04E01 · " marker its provider baked into the name, or out
 *  of a TMDB placeholder's ":s4e1" id. Null when neither says - callers must not assume
 *  season 1 from that, since "no season stated" and "season 1" are different things. */
internal fun seasonNumberOf(episode: Channel): Int? =
    EPISODE_SEASON_REGEX.find(episode.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: episode.id.takeIf { it.contains(":s") }
            ?.substringAfterLast(":s")?.substringBefore("e")?.toIntOrNull()
/** What a provider with no episode titles actually sends: nothing, or the word itself
 *  ("Episode", "Episode 4", "Ep 4"). Anything else is a real title and is left alone. */
private val PLACEHOLDER_EPISODE_TITLE_REGEX = Regex("""^(?:episode|ep|bölüm|folge)\s*\d*$""", RegexOption.IGNORE_CASE)

/** The episode's title with everything the row already strips for display removed - the
 *  "S01E04 · " marker and, for the providers that bake it in, the series name. */
private fun bareEpisodeTitle(episode: Channel, seriesName: String): String {
    var title = episode.name.replaceFirst(EPISODE_NAME_PREFIX_REGEX, "")
    if (seriesName.isNotBlank()) {
        title = title.replaceFirst(
            Regex("^" + Regex.escape(seriesName) + """\s*-\s*S\d+E\d+\s*-\s*""", RegexOption.IGNORE_CASE),
            ""
        )
    }
    return title.trim()
}

private fun isPlaceholderEpisodeTitle(episode: Channel, seriesName: String): Boolean {
    val title = bareEpisodeTitle(episode, seriesName)
    return title.isBlank() || PLACEHOLDER_EPISODE_TITLE_REGEX.matches(title)
}

/** True when TMDB has something worth asking for: a missing title, plot, or still. */
private fun needsEpisodeMetadata(episode: Channel, seriesName: String): Boolean =
    episode.description.isNullOrBlank() ||
        episode.posterUrl.isNullOrBlank() ||
        isPlaceholderEpisodeTitle(episode, seriesName)

internal fun MainActivity.hideContentDetail() {
    isContentDetailVisible = false
    applyStatus()
    nowShowingDetailId = null
    binding.contentDetailLayout.visibility = View.GONE
    binding.mainContent.visibility = View.VISIBLE
    // Back out of a series/film and you should be standing where you left: on that poster,
    // in that shelf, at that scroll position. The list underneath was only hidden, never
    // rebuilt, so the view is still there to focus - restoreTabFocus() (which lands on the
    // Series/Films tab at the top of the screen) is the fallback for when it genuinely isn't,
    // e.g. a detail opened from search or Discover over a list that never held it.
    val target = detailReturnItemId?.let { id -> binding.mainContent.findItemViewByChannelId(id) }
    detailReturnItemId = null
    if (target != null) target.post { target.requestFocus() } else restoreTabFocus()
}

/** Depth-first search for the poster tagged with [channelId] - see PosterGridAdapter.bind.
 *  Only laid-out views are reachable this way, which is exactly the intent: a recycled item
 *  has no view to focus and the caller falls back. */
internal fun View.findItemViewByChannelId(channelId: String): View? {
    if (tag == channelId && isFocusable) return this
    if (this !is ViewGroup) return null
    for (index in 0 until childCount) {
        getChildAt(index).findItemViewByChannelId(channelId)?.let { return it }
    }
    return null
}

/**
 * Whenever a fullscreen overlay (player, content detail) closes, or a tab/category
 * switches, something must explicitly claim Android focus again or D-pad input goes
 * inert with no visible sign why - the previously-focused view is very often gone
 * (recycled) by the time we get back here. Re-focusing the active tab is a safe,
 * always-valid fallback regardless of what closed.
 */
// ── EPG Source List Dialog ──────────────────────

internal fun MainActivity.showEpgSourceListDialog() {
    scope.launch {
        val sources = withContext(Dispatchers.IO) { database.epgSourceDao().getAll() }
        if (sources.isEmpty()) {
            Toast.makeText(this@showEpgSourceListDialog, "No EPG sources. Add one.", Toast.LENGTH_SHORT).show()
            showAddEpgSourceDialog()
            return@launch
        }
        val names = sources.map { "${it.name} (${it.url.take(40)}...)" }.toTypedArray()
        AlertDialog.Builder(this@showEpgSourceListDialog)
            .setTitle("EPG Sources")
            .setItems(names) { _, which ->
                val source = sources[which]
                AlertDialog.Builder(this@showEpgSourceListDialog)
                    .setTitle("Delete source?")
                    .setMessage("Remove \"${source.name}\"?")
                    .setPositiveButton("Delete") { _, _ ->
                        scope.launch { database.epgSourceDao().delete(source) }
                        Toast.makeText(this@showEpgSourceListDialog, "EPG source deleted", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setPositiveButton("Add", { _, _ -> showAddEpgSourceDialog() })
            .setNeutralButton("Refresh now") { _, _ ->
                com.lumora.data.sync.EpgSyncWorker.enqueue(this@showEpgSourceListDialog)
                Toast.makeText(this@showEpgSourceListDialog, "Downloading EPG…", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}

internal fun MainActivity.restoreTabFocus() {
    val target = when {
        showingHome -> binding.tabHome
        showingDiscover -> binding.tabDiscover
        showingDownloads -> binding.tabDownloads
        activeTab == 0 -> binding.tabLive
        activeTab == 1 -> binding.tabSeries
        else -> binding.tabFilms
    }
    target.post { target.requestFocus() }
}

internal fun MainActivity.loadDetailImage(url: String?, imageView: ImageView) {
    if (url.isNullOrBlank()) return
    scope.launch {
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).build()
                BaseApplication.instance.okHttpClient.newCall(request).execute()
                    .body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
        if (bitmap != null) imageView.setImageBitmap(bitmap)
    }
}

// ── Track selection ─────────────────────────────

internal fun MainActivity.showTrackPicker(isAudio: Boolean) {
    val player = playerManager.getExoPlayer()
    // A transcoded Jellyfin source is handed over with exactly one audio track, unnamed -
    // hence the lone "Track 1" the picker used to show for an item that has three
    // languages in it. The server knows all of them, so on that path the list comes from
    // the negotiation and picking one re-negotiates instead of overriding a track that
    // isn't there.
    val jellyfinAudio = jellyfinPlaySession
        ?.takeIf { isAudio && it.playMethod == "Transcode" && it.audioStreams.size > 1 }
    if (jellyfinAudio != null) {
        val streams = jellyfinAudio.audioStreams
        val current = streams.indexOfFirst { it.index == jellyfinAudio.audioStreamIndex }
        AlertDialog.Builder(this)
            .setTitle("Audio Track")
            .setSingleChoiceItems(streams.map(::jellyfinAudioLabel).toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                if (which != current) switchJellyfinAudioStream(streams[which].index)
            }
            .setNegativeButton("Cancel", null)
            .show()
        return
    }
    val tracks = if (isAudio) trackController.audioTracks(player) else trackController.subtitleTracks(player)

    val labels = mutableListOf<String>()
    val actions = mutableListOf<() -> Unit>()

    if (!isAudio) {
        labels.add("Off")
        actions.add { trackController.selectSubtitleTrack(player, null) }
    }
    tracks.forEach { track ->
        labels.add(track.name)
        actions.add {
            if (isAudio) trackController.selectAudioTrack(player, track.id)
            else trackController.selectSubtitleTrack(player, track.id)
        }
    }

    if (tracks.isEmpty()) {
        Toast.makeText(this, if (isAudio) "No alternate audio tracks" else "No subtitles available", Toast.LENGTH_SHORT).show()
        return
    }

    val checkedIndex = if (isAudio) {
        tracks.indexOfFirst { it.isSelected }
    } else {
        val selected = tracks.indexOfFirst { it.isSelected }
        if (selected >= 0) selected + 1 else 0
    }

    AlertDialog.Builder(this)
        .setTitle(if (isAudio) "Audio Track" else "Subtitles")
        .setSingleChoiceItems(labels.toTypedArray(), checkedIndex) { dialog, which ->
            actions[which]()
            dialog.dismiss()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

// ── Toolbar ────────────────────────────────────

/** Toolbar clock. Ticks once a minute, aligned to the wall-clock minute boundary rather
 *  than a fixed 60s period from whenever it started - otherwise the displayed minute flips
 *  up to 59s late. Only runs while the Activity is resumed (see onResume/onPause): the row
 *  is inside mainContent, which is GONE during playback, so a ticking handler in the
 *  background would be pure wakeups for a view nobody can see. */
internal fun MainActivity.updateToolbarClock() {
    binding.toolbarClock.text = android.text.format.DateFormat.getTimeFormat(this)
        .format(java.util.Date())
}

internal fun MainActivity.startToolbarClock() {
    updateToolbarClock()
    scheduleNextClockTick()
}

internal fun MainActivity.scheduleNextClockTick() {
    mainHandler.removeCallbacks(clockTickRunnable)
    val msIntoMinute = System.currentTimeMillis() % 60_000L
    mainHandler.postDelayed(clockTickRunnable, 60_000L - msIntoMinute)
}

internal fun MainActivity.stopToolbarClock() {
    mainHandler.removeCallbacks(clockTickRunnable)
}

internal fun MainActivity.setupToolbar() {
    startToolbarClock()
    binding.btnSettings.setOnClickListener { showProviderSettings() }
    binding.btnRefresh.setOnClickListener { reloadCurrentProvider() }
    binding.btnSearch.setOnClickListener { showSearchDialog() }
    wireStartupChooser()
    binding.homeSearchBar.setOnClickListener { showSearchDialog() }
    // Phone-only re-expand affordance for a collapsed category rail: restores the
    // sidebar (persisted pref flips back), then refocuses the row the user had selected
    // so the D-pad doesn't land them at the rail's top with their category nowhere.
    binding.sidebarExpandButton.setOnClickListener {
        // Portrait's auto-hide is transient state, not the pref - see isSidebarCollapsed().
        if (isPortraitPhone()) portraitSidebarExpanded = true
        else prefs.edit().putBoolean(PREF_CATEGORY_SIDEBAR_COLLAPSED, false).apply()
        // The button is only ever visible on a categorized tab with a collapsed rail, so
        // "wants sidebar" is true by construction.
        applySidebarVisibility(tabWantsSidebar = true)
        val selectedPos = categoryAdapter.currentList
            .indexOfFirst { it.id == categoryAdapter.selectedId }
            .coerceAtLeast(0)
        binding.categorySidebar.post {
            val target = binding.categorySidebar.layoutManager?.findViewByPosition(selectedPos)
            if (target != null) {
                target.requestFocus()
            } else {
                // Rows not laid out yet right after the rail becomes visible - retry once
                // (same double-post pattern as focusFirstItemWhenReady).
                binding.categorySidebar.post {
                    binding.categorySidebar.layoutManager?.findViewByPosition(selectedPos)?.requestFocus()
                }
            }
        }
    }
}
