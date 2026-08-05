package com.lumora

import android.view.View
import android.widget.*
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.lumora.adapter.LiveGuideAdapter
import com.lumora.cache.EpgListCache
import com.lumora.model.CategoryFilter
import com.lumora.model.Channel
import com.lumora.parser.XtreamClient
import com.lumora.player.PlayerManager
import com.lumora.data.local.entity.EpgProgramEntity
import kotlinx.coroutines.*
import java.util.Locale

// ── EPG resolution, live preview pane, numeric entry, Up Next & controls overlay ──
//
// Extracted from MainActivity.kt; see that file's header.
/** The dynamic sidebar row a live channel belongs to (brand row like "Sky Sports", genre
 *  bucket, Jellyfin), or null when it only lives in a plain provider category.
 *
 *  Searches the cached children as well as the visible rows: a brand row is bucketed
 *  under a genre parent on Live TV, so "Sky Sports" isn't in the sidebar list at all
 *  while "Sports" is collapsed. Ties break toward the most specific row - the bucket that
 *  swallowed the brand row also matches the channel, and landing in "Sports" instead of
 *  "Sky Sports" is not where the channel was picked from. */
internal fun MainActivity.dynamicCategoryFor(channel: Channel): CategoryFilter? =
    (categoryAdapter.currentList + categoryChildrenCache.values.flatten())
        .filter { it.isDynamic && channel.id in it.channelIds }
        .minWithOrNull(compareBy({ if (it.isParent) 1 else 0 }, { it.channelIds.size }))

/** The bucket a (possibly hidden) child row lives under, so it can be expanded into view. */
internal fun MainActivity.parentOfCategoryRow(rowId: String?): String? =
    rowId?.let { id -> categoryChildrenCache.entries.firstOrNull { (_, kids) -> kids.any { it.id == id } }?.key }

internal fun MainActivity.scrollLiveListTo(channel: Channel) {
    val pos = liveAdapter.currentList.indexOfFirst { it.id == channel.id }
    if (pos >= 0) binding.liveContent.post { binding.liveContent.scrollToPosition(pos) }
}

// ── EPG ──────────────────────────────────────────

/** Currently-airing program for a live channel, or null if there's no EPG data for it. */
/** Every id worth trying for a channel's EPG: itself first, then its merged quality/source siblings - same physical channel, the provider just didn't attach guide data to every feed. */
/** A channel's own entry plus its merged quality/source siblings - same physical channel,
 *  the provider just didn't attach guide data to every feed. Each carries its own
 *  sourceProviderId, since siblings can come from a different Xtream provider entirely. */
internal fun MainActivity.epgCandidateChannels(channelId: String): List<Channel> {
    val versions = liveVersions[channelId]
    if (versions != null) return versions
    return listOfNotNull(liveChannels.find { it.id == channelId })
}

/** Goes through [resolveEpgPrograms] rather than calling the provider itself, so the
 *  "what's on now" line reads the stored guide when there is one - and warms it when
 *  there isn't - instead of always spending a request of its own. */
internal suspend fun MainActivity.resolveCurrentProgram(channelId: String): XtreamClient.EpgProgram? {
    val nowSeconds = System.currentTimeMillis() / 1000
    val programs = resolveEpgPrograms(channelId) ?: return null
    return programs.firstOrNull { it.isNowAiring(nowSeconds) } ?: programs.firstOrNull()
}

/** Next several EPG entries for a channel, used to build one row of the guide timeline.
 *
 *  Disk first, network second. EpgListCache is per-process, so before this every cold
 *  start re-fetched a short EPG per channel as its row scrolled into view - the guide
 *  filled in over the network every single launch. The Room copy survives the process,
 *  so a relaunch inside [EPG_DISK_TTL_MS] paints the guide straight off disk and the
 *  network is only touched for channels whose cached guide is missing or stale. */
internal suspend fun MainActivity.resolveEpgPrograms(channelId: String): List<XtreamClient.EpgProgram>? {
    if (channelId.isBlank()) return null
    val nowSeconds = System.currentTimeMillis() / 1000
    val cached = withContext(Dispatchers.IO) {
        runCatching {
            val dao = database.epgProgramDao()
            val fetchedAt = dao.lastFetchedAt(channelId) ?: return@runCatching null
            if (System.currentTimeMillis() - fetchedAt >= EPG_DISK_TTL_MS) return@runCatching null
            val rows = dao.upcomingFor(channelId, nowSeconds)
            // Two tests, not one: the fetch has to be recent AND what it fetched has to
            // still reach far enough ahead to fill the row (see EPG_MIN_COVERAGE_SECONDS).
            val coverage = (rows.lastOrNull()?.stopTimestamp ?: 0L) - nowSeconds
            if (coverage < EPG_MIN_COVERAGE_SECONDS) null else rows
        }.getOrNull()
    }
    if (!cached.isNullOrEmpty()) {
        return cached.map { XtreamClient.EpgProgram(it.title, it.startTimestamp, it.stopTimestamp) }
    }

    val client = XtreamClient(BaseApplication.instance.okHttpClient)
    for (ch in epgCandidateChannels(channelId)) {
        val chProvider = xtreamProviderFor(ch) ?: continue
        val programs = runCatching { client.getShortEpg(chProvider, ch.id, 16) }.getOrDefault(emptyList())
        if (programs.isNotEmpty()) {
            persistEpgPrograms(channelId, programs)
            return programs
        }
    }
    return null
}

/** Writes a freshly fetched guide to disk under the id the guide asked for - not the
 *  provider-specific id it was fetched with, since a quality-merged channel resolves
 *  through whichever of its versions answered (see epgCandidateChannels). */
internal fun MainActivity.persistEpgPrograms(channelId: String, programs: List<XtreamClient.EpgProgram>) {
    val rows = programs.map {
        EpgProgramEntity(
            channelId = channelId,
            startTimestamp = it.startTimestamp,
            stopTimestamp = it.stopTimestamp,
            title = it.title
        )
    }
    scope.launch(Dispatchers.IO) {
        runCatching {
            // Replace rather than merge: a re-fetch is the provider's current truth, and
            // leaving old rows behind would keep a superseded schedule in the guide.
            database.epgProgramDao().deleteForChannel(channelId)
            database.epgProgramDao().upsertAll(rows)
        }
    }
}

/** Drops guide rows whose programmes have already finished. Once per session, off the
 *  first-paint path - without it the table only ever grows. */
internal fun MainActivity.pruneStoredEpg() {
    scope.launch(Dispatchers.IO) {
        runCatching {
            val dao = database.epgProgramDao()
            val dropped = dao.pruneEndedBefore(System.currentTimeMillis() / 1000 - EPG_PRUNE_GRACE_SECONDS)
            android.util.Log.i("LumoraPerf", "epg store: ${dao.count()} rows kept, $dropped pruned")
        }
    }
}

// ── Live TV inline preview ──────────────────────

internal fun MainActivity.ensurePreviewPlayer(): PlayerManager {
    previewPlayerManager?.let { return it }
    val manager = PlayerManager(this)
    // Audible. The preview is the only thing playing while the guide is up - the
    // fullscreen player releases it before it starts, so the two never overlap and
    // there's no focus fight to mute this for.
    manager.setVolume(1f)
    manager.addListener(object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            binding.previewBuffering.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
        }
        override fun onPlayerError(error: PlaybackException) {
            binding.previewBuffering.visibility = View.GONE
        }
    })
    previewPlayerManager = manager
    return manager
}

internal fun MainActivity.showLivePreviewPane() {
    if (liveChannels.isEmpty()) return
    binding.livePreviewGutter.visibility = View.VISIBLE
    binding.livePreviewPane.visibility = View.VISIBLE
    // releaseLivePreview() hides this surface (not just its parent) to stop a stale
    // frame compositing; nothing brought it back, so the pane reopened permanently
    // blank after the first time the preview was ever torn down. Un-hide it here, where
    // the pane is being shown, rather than at the tail of release().
    binding.previewSurface.visibility = View.VISIBLE
    ensurePreviewPlayer().setTextureView(binding.previewSurface)
    binding.liveContent.post { updateGuideRowWrap() }
}

/** The preview pane floats over the top-right corner of the guide instead of reserving
 *  a permanent column, so rows read like text wrapping around it: whichever rows are
 *  currently scrolled behind it get a right-side margin to clear it, every row below
 *  goes back to full width. Recomputed on scroll and whenever a row is (re)bound, since
 *  which channel occupies "behind the preview" changes as the guide scrolls. */

internal fun MainActivity.updateGuideRowWrap() {
    val showingPreview = binding.livePreviewGutter.visibility == View.VISIBLE
    val reservedPx = if (showingPreview) {
        // The pane's real on-screen width, exactly - no added buffer. An extra margin
        // here was tried twice (first 16dp, then 32dp) to guard against focus-scale
        // bleed that turned out not to be the real bug, and each just left a visible
        // gap between a reserved row's content and the pane's actual left edge.
        binding.livePreviewPane.getGlobalVisibleRect(previewGlobalRect)
        previewGlobalRect.width()
    } else 0
    for (i in 0 until binding.liveContent.childCount) {
        val child = binding.liveContent.getChildAt(i)
        val overlapsPreview = showingPreview && run {
            child.getGlobalVisibleRect(guideRowGlobalRect)
            guideRowGlobalRect.bottom > previewGlobalRect.top && guideRowGlobalRect.top < previewGlobalRect.bottom
        }
        (binding.liveContent.getChildViewHolder(child) as? LiveGuideAdapter.RowViewHolder)
            ?.setReservedEnd(if (overlapsPreview) reservedPx else 0)
    }
}

internal fun MainActivity.releaseLivePreview() {
    previewLoadRunnable?.let { mainHandler.removeCallbacks(it) }
    previewLoadRunnable = null
    previewChannelId = null
    previewTargetChannel = null
    mainHandler.removeCallbacks(previewBlackFrameCheckRunnable)
    binding.livePreviewGutter.visibility = View.GONE
    binding.livePreviewPane.visibility = View.GONE
    updateGuideRowWrap()
    // A hardware-overlay SurfaceView can keep compositing its last frame even
    // after the Java view tree is hidden; explicitly stop playback and hide the
    // surface itself (not just its parent) so it actually goes away.
    binding.previewSurface.visibility = View.GONE
    binding.previewBuffering.visibility = View.GONE
    previewPlayerManager?.let { manager ->
        manager.stop()
        manager.release()
    }
    previewPlayerManager = null
}

// ── Numeric Remote Input ──────────────────────
internal fun MainActivity.handleDigitInput(digit: Int) {
    if (digitInputBuffer.length >= 6) return
    digitInputBuffer.append(digit)
    isDigitEntryActive = true
    showNumericOverlay()
    // Reset the timeout on every keypress
    mainHandler.removeCallbacks(digitInputTimeoutRunnable)
    mainHandler.postDelayed(digitInputTimeoutRunnable, 1500)
}

internal fun MainActivity.resolveDigitInput() {
    if (!isDigitEntryActive || digitInputBuffer.isEmpty()) {
        hideNumericOverlay()
        return
    }
    val channelNum = digitInputBuffer.toString()
    val match = liveChannels.firstOrNull { it.tvgChno == channelNum || it.tvgChno?.toIntOrNull()?.toString() == channelNum }
    if (match != null) {
        hideNumericOverlay()
        clearDigitBuffer()
        playItem(match)
    } else {
        // Flash "not found" briefly on the overlay, then dismiss
        binding.numericInputChannelName.text = "Not found"
        binding.numericInputChannelName.visibility = View.VISIBLE
        mainHandler.postDelayed({ hideNumericOverlay(); clearDigitBuffer() }, 800)
    }
}

internal fun MainActivity.showNumericOverlay() {
    binding.numericInputDigits.text = digitInputBuffer.toString()
    binding.numericInputChannelName.visibility = View.GONE
    binding.numericInputOverlay.visibility = View.VISIBLE
}

internal fun MainActivity.hideNumericOverlay() {
    binding.numericInputOverlay.visibility = View.GONE
    isDigitEntryActive = false
}

internal fun MainActivity.clearDigitBuffer() {
    digitInputBuffer.clear()
    isDigitEntryActive = false
    mainHandler.removeCallbacks(digitInputTimeoutRunnable)
}

// ── Up Next / Auto-Advance ────────────────────
internal fun MainActivity.checkUpNextTrigger() {
    if (upNextActive) return // already showing
    if (!playerManager.isPlaying) return
    if (currentEpisodeQueueIndex < 0) return // not in an episode queue
    val nextIdx = currentEpisodeQueueIndex + 1
    if (nextIdx !in currentEpisodeQueue.indices) return // no next episode
    val duration = playerManager.duration
    val position = playerManager.currentPosition
    if (duration <= 0 || duration - position > MainActivity.UP_NEXT_COUNTDOWN_SECONDS * 1000L) return // more than the countdown window left
    upNextEpisode = currentEpisodeQueue[nextIdx]
    showUpNextOverlay()
}

internal fun MainActivity.showUpNextOverlay() {
    upNextActive = true
    upNextCountdown = MainActivity.UP_NEXT_COUNTDOWN_SECONDS
    binding.upNextTitle.text = upNextEpisode?.name ?: ""
    binding.upNextCountdown.text = upNextCountdown.toString()
    binding.upNextOverlay.visibility = View.VISIBLE
    binding.upNextPlayNow.requestFocus()
    mainHandler.post(upNextTickRunnable)
}

internal fun MainActivity.updateUpNextOverlay() {
    binding.upNextCountdown.text = upNextCountdown.toString()
}

internal fun MainActivity.executeUpNextAdvance() {
    cancelUpNextCountdown()
    val nextEp = upNextEpisode ?: return
    upNextEpisode = null
    upNextActive = false
    binding.upNextOverlay.visibility = View.GONE
    // Stopping the current player triggers STATE_ENDED, which would also try to
    // advance if we don't clear the queue first.
    val queue = currentEpisodeQueue
    val idx = currentEpisodeQueueIndex
    currentEpisodeQueue = emptyList()
    currentEpisodeQueueIndex = -1
    skipResumePrompt = true
    showPlayerFor(nextEp)
    // Restore the queue so Next/Prev work for subsequent episodes
    currentEpisodeQueue = queue
    currentEpisodeQueueIndex = idx + 1
}

internal fun MainActivity.cancelUpNext() {
    cancelUpNextCountdown()
    upNextEpisode = null
    upNextActive = false
    binding.upNextOverlay.visibility = View.GONE
}

internal fun MainActivity.cancelUpNextCountdown() {
    mainHandler.removeCallbacks(upNextTickRunnable)
}

/** Two-press channel open: first OK opens the channel in the preview pane; a second
 *  OK on the same channel opens it fullscreen. */
internal fun MainActivity.onChannelOkPress(channel: Channel) {
    if (previewTargetChannel?.id == channel.id) {
        playItem(channel)
    } else {
        previewTargetChannel = channel
        requestPreviewLoad(channel)
    }
}

/** Debounced so fast D-pad scrolling through the list doesn't spawn a load per row. */
internal fun MainActivity.requestPreviewLoad(channel: Channel) {
    lastFocusedLiveChannel = channel
    previewTargetChannel = channel
    if (activeTab != 0 || isPlayerVisible) return
    if (channel.id.isNotBlank() && channel.id == previewChannelId) return
    previewLoadRunnable?.let { mainHandler.removeCallbacks(it) }
    val runnable = Runnable { loadPreview(channel) }
    previewLoadRunnable = runnable
    mainHandler.postDelayed(runnable, 500)
}

internal fun MainActivity.loadPreview(channel: Channel) {
    if (activeTab != 0 || isPlayerVisible) return
    // A streak must never carry across a new load (or a version switch): start it
    // clean so only this load's own frames can trip the detector.
    previewBlackFrameStreak = 0
    previewChannelId = channel.id
    binding.previewChannelName.text = channel.name
    binding.previewBuffering.visibility = View.VISIBLE
    previewVersionGroup = liveVersions[channel.id] ?: listOf(channel)
    previewVersionIndex = previewVersionGroup.indexOfFirst { !isStreamDead(it) }.takeIf { it >= 0 } ?: 0
    val startVersion = previewVersionGroup.getOrNull(previewVersionIndex) ?: channel
    ensurePreviewPlayer().playUrl(startVersion.url, startVersion.streamUserAgent)
    startPreviewBlackFrameWatch()
}

internal fun MainActivity.formatEpgTimeRange(startSeconds: Long, stopSeconds: Long): String {
    val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return "${fmt.format(java.util.Date(startSeconds * 1000))} – ${fmt.format(java.util.Date(stopSeconds * 1000))}"
}

internal fun MainActivity.navigateChannel(dir: Int) {
    val episodeQueue = currentEpisodeQueue
    if (currentEpisodeQueueIndex >= 0 && episodeQueue.isNotEmpty()) {
        val idx = currentEpisodeQueueIndex + dir
        if (idx in episodeQueue.indices) {
            showPlayerFor(episodeQueue[idx])
            currentEpisodeQueue = episodeQueue
            currentEpisodeQueueIndex = idx
        } else {
            Toast.makeText(this, if (dir < 0) "First episode" else "Last episode", Toast.LENGTH_SHORT).show()
        }
        return
    }
    val list = when (activeTab) { 0 -> liveChannels; 1 -> seriesList; 2 -> filmList; else -> liveChannels }
    val idx = currentIndex + dir
    if (idx in list.indices) { currentIndex = idx; showPlayerFor(list[idx]) }
    else { Toast.makeText(this, if (dir < 0) "First" else "Last", Toast.LENGTH_SHORT).show() }
}

internal fun MainActivity.showControls() {
    // The side menu and the bottom bar are mutually exclusive chrome - any other
    // reveal path (center press, media key, gesture tap) dismisses the drawer
    // rather than stacking the bar over it.
    if (isPlayerSideMenuOpen()) closeSideMenu()
    // Up Next shares the bottom-right corner with the controls bar's track buttons -
    // don't let both render at once.
    if (upNextActive) binding.upNextOverlay.visibility = View.GONE
    binding.controlsOverlay.visibility = View.VISIBLE
    // Becoming visible doesn't hand D-pad focus to anything by itself - without an
    // explicit request nothing in the overlay is reachable at all, since no view had
    // focus while it was hidden.
    if (!binding.btnPlayPause.isFocused) binding.btnPlayPause.requestFocus()
    mainHandler.removeCallbacks(hideControlsRunnable)
    mainHandler.postDelayed(hideControlsRunnable, 4000)
}

internal fun MainActivity.hideControls() {
    binding.controlsOverlay.visibility = View.GONE
    if (upNextActive) binding.upNextOverlay.visibility = View.VISIBLE
}

internal fun MainActivity.toggleControls() {
    if (binding.controlsOverlay.visibility == View.VISIBLE) hideControls() else showControls()
}
