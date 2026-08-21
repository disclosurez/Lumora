package com.lumora

import android.app.AlertDialog
import androidx.core.view.updateLayoutParams
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.view.PixelCopy
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.recyclerview.widget.LinearLayoutManager
import com.lumora.adapter.SideMenuCategoryAdapter
import com.lumora.cache.PlaybackPositionStore
import com.lumora.cache.RecentlyPlayedStore
import com.lumora.download.HlsDownloads
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.model.Provider
import com.lumora.model.ProviderType
import com.lumora.model.IptvProviderConfig
import com.lumora.data.IptvProviderStore
import com.lumora.plugin.ResolveResult
import com.lumora.torrent.TorrentEngine
import com.lumora.torrent.TorrentForegroundService
import com.lumora.player.PlayerManager
import com.lumora.player.VideoAspectFrameLayout
import com.lumora.util.extractLeadingTag
import com.lumora.util.isAdultCategory
import com.lumora.util.normalizeServerUrl
import com.lumora.util.rawMediaItemId
import com.lumora.data.remote.stalker.StalkerProvider
import kotlinx.coroutines.*
import okhttp3.Request

// ── Playback: controls, aspect, stream resolution, failover & overlays ──
//
// Extracted from MainActivity.kt; see that file's header.

/** Backoff schedule for retrying a single-source live channel's exact URL after a hard
 *  player error (see onPlayerError) - a provider-side throttle (HTTP 509 etc.) often clears
 *  within seconds, and there is no other version to fail over to for that case. */
private val LIVE_RETRY_DELAYS_MS = longArrayOf(5_000L, 15_000L, 30_000L)

/** Backoff schedule for a film/episode's own same-URL retry. Shorter and fewer than live's:
 *  a VOD file is being watched from a position, so every second of retrying is a second of
 *  stopped playback the user is sitting through, whereas a live retry only costs airtime
 *  that was going to pass anyway. Two attempts covers the overwhelmingly common case (a
 *  single dropped connection or a provider hiccup mid-file) without a long dead screen. */
private val VOD_RETRY_DELAYS_MS = longArrayOf(2_000L, 6_000L)

/** Player error codes worth retrying the same URL for: the transport failed, not the media.
 *  A missing file, a permission refusal or an unplayable container fails identically on a
 *  second attempt, so those fall straight through to the next source. */
private val RETRYABLE_PLAYER_ERROR_CODES = setOf(
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_TIMEOUT,
)

/** Auto-switches tryNextQualityVersion will make in a row before giving up on hopping and
 *  falling through to the quiet same-URL backoff retry - see its own comment. */
private const val MAX_LIVE_VERSION_SWITCHES = 2
/**
 * The toolbar's refresh button: re-connect to every enabled provider, ignoring the cache.
 *
 * Announced with a toast rather than the status row, because once there is content on
 * screen the status row is suppressed (it shares the content slot - see applyStatus), so
 * pressing refresh over a populated Home gave no sign anything had happened at all.
 */
internal fun MainActivity.reloadCurrentProvider() {
    if (!hasProviderEnabled()) {
        Toast.makeText(this, getString(R.string.play_no_provider_enabled), Toast.LENGTH_LONG).show()
        showProviderSettings()
        return
    }
    Toast.makeText(this, getString(R.string.play_refreshing_providers), Toast.LENGTH_SHORT).show()
    loadAllConfiguredProviders(forceRefresh = true)
}

// ── Player ─────────────────────────────────────

internal fun MainActivity.setupPlayerControls() {
    // showControls() here restarts the 4s auto-hide: this button consumes the OK press
    // itself, so the Activity-level timer refresh in onKeyDown never sees it, and the
    // bar would otherwise vanish right after the press that paused.
    binding.btnPlayPause.setOnClickListener { playerManager.togglePlayPause(); updatePlayPauseIcon(); showControls() }
    binding.btnPrevChannel.setOnClickListener { navigateChannel(-1) }
    binding.btnNextChannel.setOnClickListener { navigateChannel(1) }
    binding.btnBack.setOnClickListener { hidePlayer(); restoreSearchIfPending() }
    binding.btnPlayerMenu.setOnClickListener { openSideMenu() }
    binding.btnCloseSideMenu.setOnClickListener { closeSideMenu() }
    // TV remotes open the side menu with DPAD_LEFT, so the hamburger is phone-only.
    if (isTv) binding.btnPlayerMenu.visibility = View.GONE

    // Side-menu nav rows mirror the top tab bar: close the drawer, tear down the
    // player, then switch to the destination screen. hidePlayer() runs BEFORE the
    // navigation because the player overlay covers mainContent. The three content
    // rows (Live/Series/Films) branch: pressing the row for the CURRENT tab drills
    // into that section's categories instead of navigating away (see
    // onSideMenuSectionRowClicked).
    binding.navHome.setOnClickListener { closeSideMenu(); hidePlayer(); selectHome() }
    binding.navLive.setOnClickListener { onSideMenuSectionRowClicked(0) }
    binding.navSeries.setOnClickListener { onSideMenuSectionRowClicked(1) }
    binding.navFilms.setOnClickListener { onSideMenuSectionRowClicked(2) }
    binding.navDiscover.setOnClickListener { closeSideMenu(); hidePlayer(); showingHome = false; selectDiscover() }
    binding.navDownloads.setOnClickListener { closeSideMenu(); hidePlayer(); showingHome = false; selectDownloads() }
    // Settings lives behind the browse screen's gear button, which the player covers -
    // this is the only way into it without backing out of playback by hand.
    binding.navSettings.setOnClickListener { closeSideMenu(); hidePlayer(); showProviderSettings() }

    // Category drill-down list under the expanded section row.
    sideMenuCategoryAdapter = SideMenuCategoryAdapter(onCategoryClick = ::onSideMenuCategoryClicked)
    sideMenuCategoryAdapter.onLeftPressed = ::onSideMenuColumnLeft
    // Same fetch the guide grid uses, so a channel already drawn there costs nothing here
    // (results are shared through EpgListCache).
    sideMenuCategoryAdapter.fetchPrograms = { channelId -> resolveEpgPrograms(channelId) }
    // RIGHT on a category opens its channels/titles, same as pressing OK. At the item
    // level there is nothing further right, so it's swallowed rather than playing the
    // item - a direction key should never start playback.
    sideMenuCategoryAdapter.onRightPressed = { category ->
        if (sideMenuChannelCategory == null) onSideMenuCategoryClicked(category)
    }
    binding.sideMenuCategoryList.layoutManager = LinearLayoutManager(this)
    binding.sideMenuCategoryList.adapter = sideMenuCategoryAdapter
    binding.btnAudioTrack.setOnClickListener { showTrackPicker(isAudio = true) }
    binding.btnSubtitleTrack.setOnClickListener { showTrackPicker(isAudio = false) }
    binding.btnChapters.setOnClickListener { showChapterPicker() }
    binding.btnLiveVersions.setOnClickListener { showVersionPicker() }
    binding.btnRewind.setOnClickListener { playerManager.seekBy(-15_000); showControls() }
    binding.btnFastForward.setOnClickListener { playerManager.seekBy(30_000); showControls() }
    applyAspectMode(loadSavedAspectMode())
    binding.btnAspectRatio.setOnClickListener { cycleAspectMode() }

    // Speed control
    speedController = com.lumora.player.playback.PlaybackSpeedController(playerManager.getExoPlayer())
    binding.btnSpeed.setOnClickListener {
        val speeds = arrayOf(
            getString(R.string.play_speed_0_5),
            getString(R.string.play_speed_0_75),
            getString(R.string.speed_default),
            getString(R.string.play_speed_1_25),
            getString(R.string.play_speed_1_5),
            getString(R.string.play_speed_2_0),
        )
        val currentSpeed = speedController.currentSpeed
        val checkedIndex = when {
            currentSpeed <= 0.5f -> 0; currentSpeed <= 0.75f -> 1; currentSpeed <= 1.0f -> 2
            currentSpeed <= 1.25f -> 3; currentSpeed <= 1.5f -> 4; else -> 5
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.play_playback_speed))
            .setSingleChoiceItems(speeds, checkedIndex) { dialog, which ->
                val speed = when (which) { 0 -> 0.5f; 1 -> 0.75f; 2 -> 1.0f; 3 -> 1.25f; 4 -> 1.5f; else -> 2.0f }
                speedController.setSpeed(speed)
                binding.btnSpeed.text = getString(R.string.play_speed_label, speed)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // Audio offset control - see AvOffsetRenderersFactory for how the shift is actually
    // applied. Global only (matches Speed/Sleep Timer): a viewer's HDMI/speaker lag is a
    // property of their setup, not of any one channel.
    val avOffsetManager = com.lumora.player.playback.AvOffsetManager(this)
    binding.btnAudioOffset.text = getString(R.string.play_audio_offset_label, playerManager.getAvOffsetMs())
    binding.btnAudioOffset.setOnClickListener {
        val offsets = intArrayOf(-1000, -500, -250, -100, -50, 0, 50, 100, 250, 500, 1000)
        val labels = offsets.map { getString(R.string.play_audio_offset_label, it) }.toTypedArray()
        val current = playerManager.getAvOffsetMs()
        var checkedIndex = offsets.indexOf(current)
        if (checkedIndex == -1) {
            // A value that isn't one of the presets (never set through this dialog before)
            // still needs a visible selection - nearest preset by absolute distance.
            checkedIndex = offsets.indices.minByOrNull { kotlin.math.abs(offsets[it] - current) } ?: 5
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.play_audio_offset))
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val offsetMs = offsets[which]
                playerManager.setAvOffsetMs(offsetMs)
                avOffsetManager.save(offsetMs)
                binding.btnAudioOffset.text = getString(R.string.play_audio_offset_label, offsetMs)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // Sleep timer
    sleepTimer = com.lumora.player.playback.SleepTimer(playerManager.getExoPlayer()).apply {
        onTickCallback = { display -> binding.btnSleepTimer.text = display }
    }
    binding.btnSleepTimer.setOnClickListener {
        val presets = arrayOf(
            getString(R.string.play_sleep_off),
            getString(R.string.play_sleep_15_min),
            getString(R.string.play_sleep_30_min),
            getString(R.string.play_sleep_45_min),
            getString(R.string.play_sleep_60_min),
            getString(R.string.play_sleep_90_min),
            getString(R.string.play_sleep_120_min),
        )
        val checkedIndex = sleepTimer.currentPreset.ordinal
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.play_sleep_timer))
            .setSingleChoiceItems(presets, checkedIndex) { dialog, which ->
                val preset = com.lumora.player.playback.SleepTimer.Preset.entries[which]
                sleepTimer.start(preset)
                binding.btnSleepTimer.text = if (preset == com.lumora.player.playback.SleepTimer.Preset.OFF) getString(R.string.sleep_timer_button) else presets[which]
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // Up Next - Play Now / Cancel buttons
    binding.upNextPlayNow.setOnClickListener {
        cancelUpNextCountdown()
        executeUpNextAdvance()
    }
    binding.upNextCancel.setOnClickListener {
        cancelUpNext()
    }

    // Cast — uses MediaRouteButton which shows a device picker on tap.
    // Hidden on Android TV because the TV itself is a Cast receiver, not a sender.
    if (isTv) {
        binding.btnCast.visibility = View.GONE
    } else {
        castManager = com.lumora.player.CastManager(this).apply {
            init()
            onCastSessionConnected = { _ ->
                val channel = nowPlayingChannel
                if (channel != null) {
                    if (castChannel(channel, channel.name)) {
                        playerManager.pause()
                    } else {
                        Toast.makeText(this@setupPlayerControls, getString(R.string.play_cast_failed), Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this@setupPlayerControls, getString(R.string.play_cast_play_content_first), Toast.LENGTH_SHORT).show()
                }
            }
        }
        try {
            com.google.android.gms.cast.framework.CastButtonFactory.setUpMediaRouteButton(
                this, binding.btnCast
            )
        } catch (_: Exception) {
            binding.btnCast.visibility = View.GONE
        }
    }

    // Hand-off to another video app - the answer for audio this device has no decoder for.
    setupExternalPlayerButton(binding.btnExternalPlayer)
    relinkPlayerButtonRowFocus()

    // Diagnostics
    binding.btnDiagnostics.setOnClickListener {
        val snapshot = playerDiagnostics.getSnapshot()
        val diag = getString(
            R.string.play_diag_body,
            snapshot.videoDecoder,
            snapshot.videoFormat,
            snapshot.audioFormat,
            snapshot.stallCount,
            snapshot.totalStallDuration / 1000,
            snapshot.playbackState
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.play_diag_title))
            .setMessage(diag)
            .setPositiveButton(getString(R.string.play_ok), null)
            .show()
    }

    // Record button
    binding.btnRecord.setOnClickListener {
        if (nowPlayingChannel?.mediaType != MediaType.LIVE) {
            Toast.makeText(this, getString(R.string.play_recording_live_only), Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        val channel = nowPlayingChannel ?: return@setOnClickListener
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.play_schedule_recording))
            .setMessage(getString(R.string.play_record_confirm, channel.name))
            .setPositiveButton(getString(R.string.play_record_for_2_hours)) { _, _ ->
                val recEntry = com.lumora.recording.RecordingScheduler.createRecording(
                    channelId = channel.id,
                    channelName = channel.name,
                    programTitle = channel.name,
                    startTimeUtc = System.currentTimeMillis() / 1000,
                    stopTimeUtc = (System.currentTimeMillis() / 1000) + 7200
                )
                com.lumora.recording.RecordingScheduler.schedule(this, recEntry)
                scope.launch {
                    database.recordingDao().insert(recEntry)
                }
                Toast.makeText(this, getString(R.string.play_recording_scheduled), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        private var tracking = false
        override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
            // Preview the frame at the scrub target while the bar is being moved - by the
            // user (touch drag or D-pad, both of which arrive as fromUser) rather than by
            // the 1s progress tick, which would flash a thumbnail during normal playback.
            if (!u) return
            val duration = playerManager.duration
            if (duration <= 0) return
            val target = duration * p / 100
            showTrickplayPreview(target)
            // A touch drag commits its seek in onStopTrackingTouch, but D-pad presses
            // never fire that callback - the bar is focused, not touched, so the thumb
            // just slid with no effect. A touched bar is `pressed`; a key-driven one
            // isn't, so seek here for the key case (and clear stall state, like the
            // drag-commit does) and the video actually follows the thumb on a remote.
            if (s?.isPressed != true) {
                playerManager.seekTo(target)
                resetStallTracking()
            }
        }
        override fun onStartTrackingTouch(s: SeekBar?) { tracking = true }
        override fun onStopTrackingTouch(s: SeekBar?) {
            tracking = false
            if (playerManager.duration > 0) {
                playerManager.seekTo((playerManager.duration * (s?.progress ?: 0)) / 100)
                resetStallTracking()
            }
            hideTrickplayPreview()
        }
    })
    // D-pad seeking never goes through onStopTrackingTouch (no touch involved), so the
    // preview has to be dismissed on focus loss too or it stays up over the video.
    binding.seekBar.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) hideTrickplayPreview() }

    // Safe to build here (not as field initializers): the Activity context is fully
    // attached by setupPlayerControls time, so GestureDetector's getResources() call
    // in its constructor cannot NPE.
    gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val width = binding.playerLayout.width
            val target = if (e.x < width / 2) {
                (playerManager.currentPosition - GESTURE_SEEK_MS).coerceAtLeast(0L)
            } else {
                (playerManager.currentPosition + GESTURE_SEEK_MS).coerceAtMost(maxOf(playerManager.duration, 0L))
            }
            playerManager.seekTo(target)
            // Visible feedback for the seek - the time label updates via progressRunnable.
            showControls()
            updatePlayPauseIcon()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // Same two-stage rule as the remote's OK (see dispatchKeyEvent): a tap only
            // shows/hides the controls, pausing is the explicit play/pause button.
            toggleControls()
            return true
        }
    })
    scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            // Pinch around the focal point, clamped inside the surface bounds.
            val surface = binding.playerSurface
            surface.pivotX = detector.focusX.coerceIn(0f, surface.width.toFloat())
            surface.pivotY = detector.focusY.coerceIn(0f, surface.height.toFloat())
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val surface = binding.playerSurface
            val newScale = (surface.scaleX * detector.scaleFactor).coerceIn(ZOOM_MIN, ZOOM_MAX)
            surface.scaleX = newScale
            surface.scaleY = newScale
            return true
        }
    })

    binding.playerLayout.setOnTouchListener { _, event ->
        // Both detectors observe every event; the listener always returns true so touches
        // on the player are fully consumed (single/double-tap, pinch). The controls overlay
        // is a child that keeps its own clickable buttons - those consume their own events.
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        true
    }

    playerManager.addListener(object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            binding.bufferingSpinner.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (state == Player.STATE_BUFFERING) onBufferingStarted() else onBufferingEnded()
            if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                updateProgress(); updatePlayPauseIcon()
                if (state == Player.STATE_READY) {
                    currentStreamPlayed = true
                    liveRetryAttempt = 0
                    liveVersionSwitchAttempt = 0
                    // A stream that reached READY has spent nothing: a later failure in the
                    // same title gets the full retry ladder again rather than inheriting a
                    // count from a hiccup ten minutes ago.
                    vodRetryAttempt = 0
                    currentVersionGroup.getOrNull(currentVersionIndex)?.let { clearStreamDead(it) }
                    maybeShowResumePrompt()
                }
            if (state == Player.STATE_ENDED) {
                saveCurrentPlaybackPosition()
                // The plain save above leaves a just-finished episode near-complete
                // (filtered off Continue Watching), but the season isn't over - keep
                // the series on the "last watching" shelf by advancing the stored entry
                // to the next episode. Real duration (not the 0L the old branch wrote,
                // which the store dropped); the 1ms position is a placeholder that the
                // next episode's own progress ticks overwrite. Exhausted queue = series
                // finished, so drop the entry and let the series leave Home.
                val finished = nowPlayingChannel
                if (finished?.mediaType == MediaType.SERIES) {
                    val finishedDur = playerManager.duration
                    val finishedKey = finished.id.ifBlank { finished.url }
                    val nextIdx = currentEpisodeQueueIndex + 1
                    if (currentEpisodeQueueIndex >= 0 && nextIdx in currentEpisodeQueue.indices) {
                        val next = currentEpisodeQueue[nextIdx]
                        PlaybackPositionStore.save(
                            this@setupPlayerControls,
                            next.id.ifBlank { next.url },
                            1L,
                            finishedDur,
                            next
                        )
                    } else if (currentEpisodeQueueIndex >= 0 && currentEpisodeQueue.isNotEmpty()) {
                        PlaybackPositionStore.clear(this@setupPlayerControls, finishedKey)
                    }
                }
                // If Up Next countdown is already running, it will handle the advance.
                if (upNextActive) return@onPlaybackStateChanged
                // Silent fallback auto-advance when Up Next wasn't triggered
                // (e.g. user seeks to end, skipping the 30s countdown window).
                val queue = currentEpisodeQueue
                val nextIdx = currentEpisodeQueueIndex + 1
                if (nextIdx in queue.indices) {
                    skipResumePrompt = true
                    showPlayerFor(queue[nextIdx])
                    currentEpisodeQueue = queue
                    currentEpisodeQueueIndex = nextIdx
                }
            }
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            // Nothing recorded playback failures anywhere, so a report of "it errored" had no
            // trail at all afterwards - the code, the HTTP status behind it and which title it
            // was are the three things any diagnosis starts from.
            android.util.Log.w(
                "LumoraPlayer",
                "Playback error ${error.errorCodeName} on ${nowPlayingChannel?.name}: ${error.cause?.message ?: error.message}"
            )
            binding.bufferingSpinner.visibility = View.GONE
            resetStallTracking()
            blackFrameStreak = 0
            if (!tryNextQualityVersion()) {
                val liveChannel = nowPlayingChannel?.takeIf { it.mediaType == MediaType.LIVE && !it.isOwnLibrary }
                val vodChannel = nowPlayingChannel?.takeIf { it.mediaType != MediaType.LIVE && !it.isOwnLibrary }
                // Media-server direct-play: one fresh-URL re-resolve before giving up - a
                // transient server timeout or an expired direct-play URL often recovers.
                if (nowPlayingChannel?.isJellyfin == true && !jellyfinRetryAttempted) {
                    retryJellyfinPlayback()
                } else if (nowPlayingChannel?.isPlex == true && !plexRetryAttempted) {
                    // A container Media3 cannot demux is not transient - re-negotiating on the
                    // same terms reproduces it exactly, because the server's direct-play answer
                    // was about what *it* can serve, not what this client can parse. Ask for the
                    // HLS transcode instead, which is the one thing that changes the outcome.
                    retryPlexPlayback(forceTranscode = isContainerParsingError(error))
                } else if (vodChannel != null && retryCurrentVodStream(vodChannel, error)) {
                    // Quiet same-URL retry: spinner only, no toast - a film that comes back on
                    // the second attempt should look like a stall, not like an error the user
                    // has to answer. Nothing else to do here; the retry either plays or comes
                    // back through this listener with the counter spent.
                } else if (vodChannel != null && tryNextVodVersion()) {
                    // Another copy of this exact title (another provider's, or another quality)
                    // is the next thing to try - the same failover live has always had.
                } else if (liveChannel != null && liveRetryAttempt < LIVE_RETRY_DELAYS_MS.size) {
                    // No other version to fail over to (a single-source channel), so retry
                    // the exact same URL - HTTP 509 and similar provider-side throttles are
                    // often transient and clear within seconds.
                    val delayMs = LIVE_RETRY_DELAYS_MS[liveRetryAttempt]
                    liveRetryAttempt++
                    binding.bufferingSpinner.visibility = View.VISIBLE
                    mainHandler.postDelayed({
                        if (nowPlayingChannel?.id != liveChannel.id) return@postDelayed
                        val current = currentVersionGroup.getOrNull(currentVersionIndex) ?: liveChannel
                        playerManager.playUrl(
                            current.url,
                            current.streamUserAgent,
                            preferAudioLanguage = false
                        )
                    }, delayMs)
                } else {
                    // Every internal recovery is spent: retries, version failover and
                    // Jellyfin's re-resolve (if any) all failed. Another player on the device
                    // is the last thing left to try, so offer it rather than leaving the user
                    // on a dead screen with a two-word toast.
                    showPlaybackFailed(getString(R.string.play_stream_error_reason, error.errorCodeName))
                }
            }
        }
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            // Where "plays with no sound" is caught: the media has audio and this device can
            // decode none of it (AC3/E-AC3 on hardware without a Dolby licence, typically).
            // Nothing in ExoPlayer treats that as an error, so it has to be noticed here.
            checkForUndecodableAudio(tracks)
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseIcon()
            if (isPlaying) mainHandler.post(progressRunnable)
            else mainHandler.removeCallbacks(progressRunnable)
        }
        override fun onCues(cues: androidx.media3.common.text.CueGroup) {
            binding.playerSubtitleView.setCues(cues.cues)
        }
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (videoSize.height == 0 || videoSize.width == 0) return
            val rotated = videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270
            val w = if (rotated) videoSize.height else videoSize.width
            val h = if (rotated) videoSize.width else videoSize.height
            binding.playerAspectContainer.videoAspectRatio = (w * videoSize.pixelWidthHeightRatio) / h
            lastVideoWidth = w
            lastVideoHeight = h
        }
    })
}

// ── Aspect ratio / zoom ─────────────────────────

internal fun MainActivity.loadSavedAspectMode(): VideoAspectFrameLayout.Mode =
    runCatching { VideoAspectFrameLayout.Mode.valueOf(prefs.getString(PREF_ASPECT_MODE, null) ?: "") }
        .getOrDefault(VideoAspectFrameLayout.Mode.FIT)

internal fun MainActivity.applyAspectMode(mode: VideoAspectFrameLayout.Mode) {
    binding.playerAspectContainer.resizeMode = mode
    binding.btnAspectRatio.text = when (mode) {
        VideoAspectFrameLayout.Mode.FIT -> getString(R.string.fit)
        VideoAspectFrameLayout.Mode.ZOOM -> getString(R.string.play_aspect_zoom)
        VideoAspectFrameLayout.Mode.FILL -> getString(R.string.play_aspect_stretch)
    }
    prefs.edit().putString(PREF_ASPECT_MODE, mode.name).apply()
}

internal fun MainActivity.cycleAspectMode() {
    val modes = VideoAspectFrameLayout.Mode.entries
    val next = modes[(modes.indexOf(binding.playerAspectContainer.resizeMode) + 1) % modes.size]
    applyAspectMode(next)
    Toast.makeText(this, getString(R.string.play_video_label, binding.btnAspectRatio.text), Toast.LENGTH_SHORT).show()
}

/** [resumeFromMs] carries the position across a version switch (see showVersionPicker):
 *  the replacement stream is a different item with its own saved-position key, so without
 *  it switching version on a half-watched film restarts it from zero. Also suppresses the
 *  resume prompt - the user just answered that question by switching mid-playback.
 *
 *  [externalSubtitles] are sidecar tracks a caller already resolved (the Find Stream
 *  dialog); [pluginStreamAlreadyResolved] marks that same caller's URL as freshly resolved,
 *  so the plugin branch below doesn't resolve it a second time. [audio] is the stream's
 *  audio category hint ("sub"/"dub") when the caller knows it - the player prefers the
 *  matching audio track and gates sidecar subtitles on it. */
internal fun MainActivity.showPlayerFor(
    channel: Channel,
    resumeFromMs: Long? = null,
    preferredVersionId: String? = null,
    externalSubtitles: List<PlayerManager.ExternalSubtitle> = emptyList(),
    pluginStreamAlreadyResolved: Boolean = false,
    audio: String? = null,
    /** Set only by the scraper path - see PlayerManager.playUrl's parameter of the same name. */
    maintainTokenQuery: String? = null,
    /** Container MIME when the source knows it - see PlayerManager.playUrl. */
    mimeType: String? = null
) {
    // Reset Up Next state on any new playback
    cancelUpNext()
    // Never run the preview decode and the fullscreen decode at once.
    releaseLivePreview()
    // Cleared unconditionally - callers that want episode tracking (Next/Prev,
    // auto-advance) re-set these right after calling this, once playback has
    // actually started for the episode they picked.
    currentEpisodeQueue = emptyList()
    currentEpisodeQueueIndex = -1
    isPlayerVisible = true
    nowPlayingChannel = channel
    // Cleared unconditionally, same as the episode queue above - the series version
    // context only applies to playback started from a series detail screen, which re-sets
    // it right after this call.
    currentSeriesVersionContext = null
    // Live TV has no detail page behind it, so a return target left over from a VOD session
    // must not survive into it. Everything else keeps whatever the caller set: a version
    // switch or an auto-advance to the next episode is still the same title's playback, and
    // backing out of it belongs on the same poster the first episode was started from.
    if (channel.mediaType == MediaType.LIVE) {
        detailReturnItem = null
        detailReturnGroup = null
    }
    resumePromptShown = resumeFromMs != null
    progressTickCount = 0
    binding.mainContent.visibility = View.GONE
    binding.playerLayout.visibility = View.VISIBLE
    binding.playerLayout.keepScreenOn = true
    // Every new video starts unzoomed - a pinch-zoom from a previous session must not
    // carry over into the next title.
    binding.playerSurface.scaleX = 1f
    binding.playerSurface.scaleY = 1f
    binding.playerSurface.pivotX = 0f
    binding.playerSurface.pivotY = 0f
    applyStatus()
    binding.playerChannelName.text = channel.name
    binding.playerSubtitle.visibility = View.GONE
    binding.playerLiveBadge.visibility = if (channel.mediaType == MediaType.LIVE) View.VISIBLE else View.GONE
    if (channel.mediaType == MediaType.LIVE) {
        // Don't add adult channels to recently played.
        if (!isAdultCategory(channel.categoryName, channel.group)) {
            RecentlyPlayedStore.recordPlayed(this, channel.id)
        }
        speedController.resetSpeed()
    }

    // Live channels get a square logo tile (fitCenter, so a wide/odd-aspect logo
    // doesn't get cropped); movies/series get a poster-shaped tile (centerCrop) -
    // squeezing a 2:3 poster into a 52x52 square looked like a stretched thumbnail.
    val density = resources.displayMetrics.density
    val isPoster = channel.mediaType != MediaType.LIVE
    binding.playerLogoBox.layoutParams = binding.playerLogoBox.layoutParams.apply {
        width = ((if (isPoster) 34 else 36) * density).toInt()
        height = ((if (isPoster) 50 else 36) * density).toInt()
    }
    binding.playerChannelLogo.scaleType = if (isPoster) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
    val logoPadding = if (isPoster) 0 else (6 * density).toInt()
    binding.playerChannelLogo.setPadding(logoPadding, logoPadding, logoPadding, logoPadding)
    binding.playerChannelInitial.text = channel.name.firstOrNull()?.uppercase() ?: getString(R.string.play_initial_fallback)
    binding.playerChannelInitial.visibility = View.VISIBLE
    binding.playerChannelLogo.visibility = View.GONE
    binding.playerChannelLogo.setImageDrawable(null)
    val logoUrl = channel.logoUrl ?: channel.posterUrl
    if (!logoUrl.isNullOrBlank()) {
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder().url(logoUrl).build()
                    BaseApplication.instance.okHttpClient.newCall(request).execute()
                        .body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (bitmap != null && nowPlayingChannel?.id == channel.id) {
                binding.playerChannelLogo.setImageBitmap(bitmap)
                binding.playerChannelLogo.visibility = View.VISIBLE
                binding.playerChannelInitial.visibility = View.GONE
            }
        }
    }

    if (channel.mediaType == MediaType.LIVE && channel.id.isNotBlank()) {
        scope.launch {
            val program = runCatching { resolveCurrentProgram(channel.id) }.getOrNull()
            if (nowPlayingChannel?.id != channel.id || program == null) return@launch
            binding.playerSubtitle.text = "${program.title}  ·  ${formatEpgTimeRange(program.startTimestamp, program.stopTimestamp)}"
            binding.playerSubtitle.visibility = View.VISIBLE
        }
    }

    // Live channels may have multiple quality versions; start at the best
    // (versions are pre-sorted highest quality first) and keep the group
    // around so onPlayerError can fall back to the next one.
    val versions = liveVersions[channel.id]
    currentVersionGroup = versions ?: listOf(channel)
    // An explicitly requested version wins over the quality/dead-stream auto-pick: it's
    // the one the user was already watching (launch resume), so re-deriving a "best"
    // choice here would start them on a different stream and, when that one doesn't
    // play, walk the whole group by failover before arriving back where they started.
    val preferredIndex = preferredVersionId?.let { id -> currentVersionGroup.indexOfFirst { it.id == id } }
        ?.takeIf { it >= 0 }
    currentVersionIndex = preferredIndex
        ?: currentVersionGroup.indexOfFirst { !isStreamDead(it) }.takeIf { it >= 0 }
        ?: 0
    // channel.name is the cleaned/generic representative name (guide/shelf display) -
    // the player card shows the exact raw version actually playing instead, same as
    // switchToVersionIndex() does on failover/manual switch.
    if (channel.mediaType == MediaType.LIVE) {
        binding.playerChannelName.text = currentVersionGroup.getOrNull(currentVersionIndex)?.name ?: channel.name
    }

    resetStallTracking()
    beginStreamAttempt()
    startBlackFrameWatch()
    binding.playerAspectContainer.videoAspectRatio = 0f
    playerManager.setSurfaceView(binding.playerSurface)
    showControls()
    binding.bufferingSpinner.visibility = View.VISIBLE
    val startVersion = if (channel.mediaType == MediaType.LIVE) currentVersionGroup.getOrNull(currentVersionIndex) ?: channel else channel
    // Stalker VOD carries a base64 play command, not a URL - it must be create_link'd at
    // play time (the resolved link is short-lived and per-session). Everything else has a
    // direct url already.
    // Reset per-play Jellyfin state before anything below can populate it - a chapters
    // button left over from the last title would otherwise seek into the wrong film.
    jellyfinPlaySession = null
    jellyfinPlayingItemId = null
    jellyfinPlayingServerId = null
    jellyfinRetryAttempted = false
    plexPlaySession = null
    plexPlayingItemId = null
    plexPlayingServerId = null
    plexPlayingDurationMs = null
    plexRetryAttempted = false
    liveRetryAttempt = 0
    liveVersionSwitchAttempt = 0
    vodRetryAttempt = 0
    playbackChapters = emptyList()
    jellyfinTrickplay = null
    trickplayTileCache = null
    updateChaptersButtonVisibility()
    updateVersionsButtonVisibility()
    hideTrickplayPreview()

    when {
        startVersion.url.isBlank() && !startVersion.stalkerCmd.isNullOrBlank() -> scope.launch {
            val resolved = resolveStalkerPlayUrl(startVersion)
            if (nowPlayingChannel?.id != channel.id) return@launch
            if (resolved.isNullOrBlank()) {
                binding.bufferingSpinner.visibility = View.GONE
                Toast.makeText(this@showPlayerFor, getString(R.string.play_couldnt_open_title), Toast.LENGTH_SHORT).show()
            } else {
                // Not the MAC (which Stalker channels carry as their UA for the portal API):
                // the resolved movie.php/live.php stream is plain HTTP and wants a normal
                // player UA. Sending the MAC as User-Agent is what errored the playback.
                playerManager.playUrl(
                    resolved,
                    STREAM_USER_AGENT,
                    audio = audio,
                    preferAudioLanguage = startVersion.mediaType != MediaType.LIVE
                )
            }
            resumeFromMs?.let { playerManager.seekTo(it) }
        }
        // Jellyfin VOD/episodes ask the server how to play them rather than assuming the
        // file is directly playable: `?static=true` hands the raw file over untouched, so
        // anything this device has no decoder for (HEVC 10-bit, TrueHD, DTS) opened to a
        // black screen or silence. PlaybackInfo picks direct play where it fits and an
        // HLS transcode where it doesn't, and brings the subtitle tracks with it.
        startVersion.isJellyfin && startVersion.mediaType != MediaType.LIVE && startVersion.id.isNotBlank() -> scope.launch {
            val startAt = resumeFromMs ?: 0L
            // The server only knows its own bare item id; the catalogue's is qualified with
            // which account it came from (see qualifiedMediaItemId).
            val itemId = rawMediaItemId(startVersion.id)
            val serverId = jellyfinConfigFor(startVersion)?.id
            val jellyfin = jellyfinClientFor(startVersion)
            // The audio-language setting has to travel with the negotiation, not just with
            // the player: a transcoded source arrives with the single audio track the server
            // chose, so a track selection made afterwards has nothing to select.
            val wantedAudioLanguage = prefs.getString(PREF_AUDIO_LANGUAGE, "en") ?: "en"
            val resolved = if (jellyfin == null) null else withContext(Dispatchers.IO) {
                runCatching {
                    jellyfin.resolveStream(
                        itemId,
                        startAt,
                        preferredAudioLanguage = wantedAudioLanguage
                    )
                }.getOrNull()
            }
            if (nowPlayingChannel?.id != channel.id) return@launch
            // A failed negotiation is not a failed play: the plain static URL is what the
            // app always used, and for most files it works - so fall back to it rather
            // than refusing to open the title.
            playerManager.playUrl(
                resolved?.url ?: startVersion.url,
                startVersion.streamUserAgent,
                subtitles = resolved?.let(::externalSubtitlesFor) ?: emptyList(),
                startPositionMs = startAt,
                audio = audio,
                preferAudioLanguage = startVersion.mediaType != MediaType.LIVE
            )
            jellyfinPlaySession = resolved
            jellyfinPlayingItemId = itemId
            jellyfinPlayingServerId = serverId
            reportJellyfinStart(itemId, resolved, startAt)
            loadJellyfinPlaybackExtras(itemId)
        }
        // Plex asks the server the same question Jellyfin's PlaybackInfo answers, through the
        // transcode-decision endpoint: direct play where the file is playable as-is, an HLS
        // transcode where it isn't, plus the sidecar subtitle tracks that come with it.
        startVersion.isPlex && startVersion.mediaType != MediaType.LIVE && startVersion.id.isNotBlank() -> scope.launch {
            val startAt = resumeFromMs ?: 0L
            val itemId = rawMediaItemId(startVersion.id)
            val serverId = plexConfigFor(startVersion)?.id
            val plex = plexClientFor(startVersion)
            // The audio-language setting has to travel with the negotiation, not just with the
            // player: a transcoded source arrives with the single audio track the server
            // chose, so a track selection made afterwards has nothing to select.
            val wantedAudioLanguage = prefs.getString(PREF_AUDIO_LANGUAGE, "en") ?: "en"
            val resolved = if (plex == null) null else withContext(Dispatchers.IO) {
                runCatching {
                    plex.resolveStream(
                        itemId,
                        startAt,
                        preferredAudioLanguage = wantedAudioLanguage
                    )
                }.getOrNull()
            }
            if (nowPlayingChannel?.id != channel.id) return@launch
            // A failed negotiation is not a failed play: the catalogue already carries the
            // part path, so the plain file is still worth trying rather than refusing to open
            // the title.
            val url = resolved?.url ?: plexFallbackUrl(startVersion)
            if (url == null) {
                binding.bufferingSpinner.visibility = View.GONE
                Toast.makeText(this@showPlayerFor, getString(R.string.play_couldnt_open_title), Toast.LENGTH_SHORT).show()
                return@launch
            }
            playerManager.playUrl(
                url,
                startVersion.streamUserAgent,
                subtitles = resolved?.let(::externalSubtitlesForPlex) ?: emptyList(),
                startPositionMs = startAt,
                audio = audio,
                preferAudioLanguage = true,
                // Plex writes segment paths into its HLS playlists with no token on them, so
                // without this a transcode plays one segment and then 401s.
                maintainTokenQuery = resolved?.tokenQuery
            )
            plexPlaySession = resolved
            plexPlayingItemId = itemId
            plexPlayingServerId = serverId
            plexPlayingDurationMs = resolved?.runtimeMs
            reportPlexStart(itemId, resolved, startAt)
            loadPlexPlaybackExtras(itemId)
        }
        // A plugin-resolved stream cannot be replayed from the URL it was saved with: the
        // CDN signs it with an expiry in the path and gates it behind request headers the
        // URL alone doesn't carry, so a Continue Watching tile replaying yesterday's URL
        // 403s twice over. Re-run the plugin's resolve() for a fresh URL, headers and
        // subtitle tracks instead - that's also what makes resuming an anime episode land
        // on the same episode rather than the series' first one.
        !pluginStreamAlreadyResolved && !startVersion.pluginToken.isNullOrBlank() -> scope.launch {
            val startAt = resumeFromMs ?: 0L
            val plugin = pluginScriptManager.getDiscoveredScripts()
                .firstOrNull { it.enabled && it.id == startVersion.pluginId }
            val resolved = when {
                plugin == null -> ResolveResult.Failed(getString(R.string.play_plugin_disabled_message))
                // Same split as the Find Stream dialog: a natively-resolving plugin's token
                // is a magnet for the built-in torrent engine, not something the JS runtime
                // can turn into a URL.
                // Torrent metadata + initial buffering can take minutes with nothing on
                // screen but a spinner, so the progress lines land on the player's subtitle
                // row (free for VOD - only live TV puts EPG text there). TorrentEngine
                // reports from its own IO thread, hence the hop.
                plugin.resolvesNatively -> resolveTorrentStream(
                    startVersion.pluginToken, null, startVersion.episodeNum
                ) { line ->
                    runOnUiThread {
                        if (nowPlayingChannel?.id != channel.id) return@runOnUiThread
                        binding.playerSubtitle.text = line
                        binding.playerSubtitle.visibility = View.VISIBLE
                    }
                }
                else -> jsPluginEngine.resolve(
                    pluginScriptManager.readSource(plugin),
                    startVersion.pluginToken,
                    null,
                    startVersion.episodeNum
                )
            }
            if (nowPlayingChannel?.id != channel.id) return@launch
            binding.playerSubtitle.visibility = View.GONE
            when (resolved) {
                is ResolveResult.Ready -> playerManager.playUrl(
                    resolved.url,
                    startVersion.streamUserAgent,
                    subtitles = resolved.subtitles.map(::externalSubtitleFor),
                    startPositionMs = startAt,
                    headers = resolved.headers.ifEmpty { null },
                    // The fresh resolve may know the audio category even when the original
                    // caller didn't (or better), so its hint wins.
                    audio = resolved.audio ?: audio,
                    preferAudioLanguage = startVersion.mediaType != MediaType.LIVE
                )
                is ResolveResult.Failed -> {
                    binding.bufferingSpinner.visibility = View.GONE
                    Toast.makeText(this@showPlayerFor, resolved.message, Toast.LENGTH_LONG).show()
                }
            }
        }
        else -> {
            playerManager.playUrl(
                startVersion.url,
                startVersion.streamUserAgent,
                subtitles = externalSubtitles,
                headers = startVersion.streamHeaders,
                audio = audio,
                preferAudioLanguage = startVersion.mediaType != MediaType.LIVE,
                maintainTokenQuery = maintainTokenQuery,
                mimeType = mimeType,
                // Set only by playDownload for a completed HLS download, which replays its
                // original URL out of the download cache instead of over the network.
                dataSourceOverride = offlineHlsPlaybackId
                    ?.takeIf { it == startVersion.id }
                    ?.let { HlsDownloads.offlineDataSourceFactory(this) }
            )
            resumeFromMs?.let { playerManager.seekTo(it) }
        }
    }

    // Apply persisted A/V sync offset (per-channel or global)
    val params = avOffsetManager.buildPlaybackParameters(
        playerManager.getExoPlayer().playbackParameters,
        nowPlayingChannel?.id
    )
    playerManager.getExoPlayer().setPlaybackParameters(params)
}

/** Resolves a Stalker VOD/series item's base64 command into a playable URL against the
 *  portal it came from (matched by sourceProviderId). Null if the portal's gone or the
 *  config isn't a Stalker one. */
internal fun MainActivity.stalkerConfigFor(channel: Channel): IptvProviderConfig? =
    channel.sourceProviderId?.let { id -> IptvProviderStore.load(prefs).firstOrNull { it.id == id && it.type == "stalker" } }

internal fun MainActivity.stalkerProviderStub(config: IptvProviderConfig): Provider = Provider(
    name = config.name, type = ProviderType.M3U,
    serverUrl = config.url?.let { normalizeServerUrl(it) }, userAgent = config.userAgent
)

internal suspend fun MainActivity.resolveStalkerPlayUrl(channel: Channel): String? {
    val config = stalkerConfigFor(channel) ?: return null
    // A non-Stalker channel has no base64 play command - guard instead of crashing on a
    // force-unwrap. Callers already treat null as "no resolvable URL".
    val cmd = channel.stalkerCmd ?: return null
    val stalker = StalkerProvider(BaseApplication.instance.okHttpClient)
    return withContext(Dispatchers.IO) {
        // A series episode passes its number so create_link picks the right one within the
        // season it shares a cmd with; a film passes none.
        stalker.resolvePlayUrl(
            stalkerProviderStub(config),
            cmd,
            episode = channel.episodeNum?.takeIf { channel.mediaType == MediaType.SERIES }
        )
    }
}

/** The Xtream provider a Channel actually came from, for detail/EPG calls that need to
 *  hit the matching server/credentials - not whichever Xtream provider loaded last into
 *  the legacy single `provider` field. Null for non-Xtream items. */
internal fun MainActivity.xtreamProviderFor(channel: Channel): Provider? {
    val config = channel.sourceProviderId?.let { xtreamProviderConfigs[it] } ?: return null
    return Provider(
        name = config.name, type = ProviderType.XTREAM,
        serverUrl = config.url?.let { normalizeServerUrl(it) },
        username = config.username, password = config.password
    )
}

/** The name of the provider a Channel came from, for labelling version chips. A media-server
 *  item is labelled with its account's own name, since several Jellyfin/Plex accounts can be
 *  configured at once and "Jellyfin" alone wouldn't say which library the version is in.
 *  Everything else is an IptvProviderConfig matched by sourceProviderId. Null when the config
 *  has since been deleted (cached items outlive it). */
internal fun MainActivity.providerNameFor(channel: Channel): String? = when {
    channel.isOwnLibrary -> mediaServerOwner(channel, mediaServers())?.name?.takeIf { it.isNotBlank() }
        ?: if (channel.isJellyfin) "Jellyfin" else "Plex"
    else -> channel.sourceProviderId?.let { providerNamesById[it] }?.takeIf { it.isNotBlank() }
}

internal fun MainActivity.streamKey(channel: Channel) = channel.id.ifBlank { channel.url }

internal fun MainActivity.markStreamDead(channel: Channel) {
    deadStreamUntil[streamKey(channel)] = System.currentTimeMillis() + DEAD_STREAM_COOLDOWN_MS
    saveDeadStreams()
}

/** Dead marks outlive the process: a stream that was broken a minute before the app was
 *  closed is still broken when it reopens, and an in-memory-only map handed it back as a
 *  fresh candidate on every launch. Expired entries are dropped as they're written. */
internal fun MainActivity.saveDeadStreams() {
    val now = System.currentTimeMillis()
    deadStreamUntil.entries.removeAll { it.value <= now }
    val json = org.json.JSONObject()
    deadStreamUntil.forEach { (key, until) -> json.put(key, until) }
    prefs.edit().putString(PREF_DEAD_STREAMS, json.toString()).apply()
}

internal fun MainActivity.loadDeadStreams() {
    val raw = prefs.getString(PREF_DEAD_STREAMS, null) ?: return
    runCatching {
        val json = org.json.JSONObject(raw)
        val now = System.currentTimeMillis()
        json.keys().forEach { key ->
            val until = json.optLong(key)
            if (until > now) deadStreamUntil[key] = until
        }
    }
}

internal fun MainActivity.isStreamDead(channel: Channel): Boolean {
    val until = deadStreamUntil[streamKey(channel)] ?: return false
    if (System.currentTimeMillis() >= until) {
        deadStreamUntil.remove(streamKey(channel))
        return false
    }
    return true
}

/** Clears a dead mark once a stream actually plays - a same-URL backoff retry (see
 *  onPlayerError) can recover from a transient failure that tryNextQualityVersion had
 *  already marked dead for the full hour-long cooldown; leaving that mark in place would
 *  make a future scan skip a version that just proved itself working. */
internal fun MainActivity.clearStreamDead(channel: Channel) {
    if (deadStreamUntil.remove(streamKey(channel)) != null) saveDeadStreams()
}

/** Retries playback with the next-best quality version of the current live channel, if
 *  any - the one being left behind failed (that's why this got called), so it's marked
 *  dead for a cooldown window instead of being tried again a few seconds later. */
/** One-shot Jellyfin direct-play recovery: a source error (server read timeout, expired
 *  direct-play URL) re-resolves the item for a fresh URL rather than erroring out - the
 *  failed URL is short-lived and per-session, so a fresh resolveStream is the right fix. */
internal fun MainActivity.retryJellyfinPlayback() {
    val channel = nowPlayingChannel ?: return
    if (!channel.isJellyfin || channel.id.isBlank()) return
    jellyfinRetryAttempted = true
    val itemId = rawMediaItemId(channel.id)
    scope.launch {
        val startAt = playerManager.currentPosition
        val jellyfin = jellyfinClientFor(channel)
        val resolved = if (jellyfin == null) null else withContext(Dispatchers.IO) {
            runCatching {
                jellyfin.resolveStream(
                    itemId,
                    startAt,
                    preferredAudioLanguage = prefs.getString(PREF_AUDIO_LANGUAGE, "en") ?: "en"
                )
            }.getOrNull()
        }
        if (nowPlayingChannel?.id != channel.id) return@launch
        playerManager.playUrl(
            resolved?.url ?: channel.url,
            channel.streamUserAgent,
            subtitles = resolved?.let(::externalSubtitlesFor) ?: emptyList(),
            startPositionMs = startAt,
            preferAudioLanguage = channel.mediaType != MediaType.LIVE
        )
        jellyfinPlaySession = resolved
        jellyfinPlayingItemId = itemId
        jellyfinPlayingServerId = jellyfinConfigFor(channel)?.id
    }
}

internal fun MainActivity.tryNextQualityVersion(message: String? = null): Boolean {
    if (nowPlayingChannel?.mediaType != MediaType.LIVE) return false
    // Capped separately from the dead-stream scan below: a provider-wide throttle (the same
    // account/IP getting 509'd) fails every version in the group in turn, each one briefly
    // opening before getting cut - scanning the whole group on every failure then reads as
    // the channel rapidly flipping instead of the quiet same-URL backoff retry it should
    // fall through to. Reset on a confirmed STATE_READY or a fresh showPlayerFor.
    if (liveVersionSwitchAttempt >= MAX_LIVE_VERSION_SWITCHES) return false
    currentVersionGroup.getOrNull(currentVersionIndex)?.let { markStreamDead(it) }
    // Scans the whole group, not just forward from the current index: marching only
    // forward meant a channel that had already failed through to its last version could
    // never come back to an earlier one even once its hour-long dead-mark (see
    // DEAD_STREAM_COOLDOWN_MS) expired mid-session - tryNextQualityVersion would keep
    // returning false forever after that point instead of ever re-trying it.
    val nextIndex = currentVersionGroup.indices
        .filterNot { it == currentVersionIndex }
        .firstOrNull { !isStreamDead(currentVersionGroup[it]) }
        ?: return false
    liveVersionSwitchAttempt++
    switchToVersionIndex(nextIndex, message ?: getString(R.string.play_switching_alt_quality))
    return true
}

/**
 * Retries the exact stream that just failed, after a short backoff, keeping the position.
 *
 * Live has had this for a while; VOD had nothing between "one hard error" and the generic
 * "Playback error" toast, so a single dropped connection mid-film ended playback and left
 * switching source as the user's job - for a stream that plays fine on a second attempt.
 *
 * Returns whether a retry was actually scheduled: false when the retries are spent, the error
 * is not the transport's fault ([isRetryablePlaybackError]), or there is nothing to replay
 * (a local download, whose data source this cannot rebuild).
 */
/**
 * True when the player rejected the *bytes* rather than failing to fetch them: no extractor
 * recognised the container, or the one that did couldn't read it.
 *
 * Worth separating from a transport failure because the remedy is opposite. A read timeout
 * wants the same request again; this wants a different one, since the same file will fail the
 * same way every time.
 */
internal fun isContainerParsingError(error: PlaybackException): Boolean =
    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED

internal fun MainActivity.retryCurrentVodStream(channel: Channel, error: PlaybackException): Boolean {
    if (vodRetryAttempt >= VOD_RETRY_DELAYS_MS.size) return false
    if (!isRetryablePlaybackError(error)) return false
    // What is actually playing, not channel.url: a plugin/scraper/Stalker stream was resolved
    // at play time and the Channel itself carries no usable URL.
    val stream = playerManager.lastResolvedStream?.takeIf { it.url.isNotBlank() } ?: return false
    val scheme = runCatching { android.net.Uri.parse(stream.url).scheme }.getOrNull()?.lowercase()
    // A completed download plays through a cache data source this has no way to rebuild, and a
    // local file that fails does not fail transiently anyway.
    if (scheme == null || scheme == "file" || scheme == "content") return false
    val delayMs = VOD_RETRY_DELAYS_MS[vodRetryAttempt]
    vodRetryAttempt++
    // Resume where it died. currentPosition still reports the failed position at this point;
    // the saved store entry is the fallback for an error thrown before playback ever started.
    val resumeMs = playerManager.currentPosition.takeIf { it > 0 }
        ?: PlaybackPositionStore.get(this, streamKey(channel))?.positionMs?.takeIf { it > 0 }
        ?: 0L
    binding.bufferingSpinner.visibility = View.VISIBLE
    mainHandler.postDelayed({
        if (nowPlayingChannel?.id != channel.id) return@postDelayed
        // Replayed through PlayerManager rather than rebuilt from the URL: the original call
        // also carried sidecar subtitles, a container MIME and (for scraper streams) a token
        // query, and a retry missing those can fail for reasons the first attempt never had.
        playerManager.replayLast(resumeMs)
    }, delayMs)
    return true
}

/** True when the same URL is worth asking for again: the transport failed (connection reset,
 *  timeout, a 5xx/429/509 from the provider), rather than the media being wrong or absent. A
 *  404, a permission refusal or an unreadable container fails the same way every time. */
internal fun isRetryablePlaybackError(error: PlaybackException): Boolean {
    val causes = generateSequence(error.cause) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
    val http = causes.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()
    if (http != null) return http.responseCode >= 500 || http.responseCode == 429
    // Something in the media itself defeated a parser - a malformed container, or a subtitle
    // cue the parser rejects. Media3 reports those as IO errors (the extractor throws inside a
    // load task, and Loader wraps a RuntimeException in an IOException), but they are entirely
    // deterministic: the same bytes fail at the same offset every time, so retrying only
    // spends the backoff before arriving at the same place.
    if (causes.any { it is androidx.media3.common.ParserException || it is RuntimeException }) return false
    return error.errorCode in RETRYABLE_PLAYER_ERROR_CODES
}

/** Cause chains are walked with a bound: a self-referential cause would otherwise hang the
 *  main thread here, and nothing useful lives that deep anyway. */
private const val MAX_CAUSE_DEPTH = 10

/**
 * Fails a film or episode over to another copy of the same title, the way live already fails
 * over between a channel's versions.
 *
 * Films: the other entries in this title's [MainActivity.filmVersions] group, swapped in place
 * with the position carried across. Episodes: another provider's copy of the show, which means
 * fetching that provider's episode list and finding the same season/episode in it - so the
 * switch is asynchronous and can still come back empty, in which case the caller's error path
 * has already been skipped; [showPlaybackFailed] is called from there instead.
 *
 * The copy being left behind is marked dead first, so the scan can't hand it straight back and
 * a group of broken sources is walked once rather than cycled.
 */
internal fun MainActivity.tryNextVodVersion(): Boolean {
    val playing = nowPlayingChannel ?: return false
    if (playing.mediaType == MediaType.LIVE) return false
    val resumeMs = playerManager.currentPosition.takeIf { it > 0 }
    val seriesContext = currentSeriesVersionContext
    if (seriesContext != null) {
        val (series, group) = seriesContext
        markStreamDead(series)
        val target = group.firstOrNull { it.id != series.id && !isStreamDead(it) } ?: return false
        Toast.makeText(this, getString(R.string.play_switching_source), Toast.LENGTH_SHORT).show()
        binding.bufferingSpinner.visibility = View.VISIBLE
        scope.launch {
            val played = switchToSeriesVersion(target, playing, group, resumeMs)
            // The other provider not carrying this episode is a dead end like any other - land
            // on the same offer the error path would have shown.
            if (!played) showPlaybackFailed(getString(R.string.play_playback_error))
        }
        return true
    }
    val group = filmVersions[playing.id]
        ?: filmVersions.values.firstOrNull { grp -> grp.any { it.id == playing.id } }
        ?: return false
    markStreamDead(playing)
    val next = group.firstOrNull { it.id != playing.id && !isStreamDead(it) } ?: return false
    Toast.makeText(this, getString(R.string.play_switching_source), Toast.LENGTH_SHORT).show()
    showPlayerFor(next, resumeFromMs = resumeMs)
    return true
}

/** The end of the line: every internal recovery is spent, so the stream is handed to whatever
 *  other player the device has rather than leaving a dead screen behind a two-word toast. */
internal fun MainActivity.showPlaybackFailed(reason: String) {
    binding.bufferingSpinner.visibility = View.GONE
    Toast.makeText(this, getString(R.string.play_playback_error), Toast.LENGTH_SHORT).show()
    suggestExternalPlayer(reason)
}

/** Swaps playback to an arbitrary version within the current channel's merged quality/source
 *  group - used both for manual picks (showLiveVersionPicker) and auto-failover (above). */
internal fun MainActivity.switchToVersionIndex(index: Int, message: String? = null) {
    if (index !in currentVersionGroup.indices) return
    currentVersionIndex = index
    val next = currentVersionGroup[index]
    resetStallTracking()
    beginStreamAttempt()
    startBlackFrameWatch()
    binding.playerAspectContainer.videoAspectRatio = 0f
    binding.playerChannelName.text = next.name
    Toast.makeText(this, message ?: getString(R.string.play_switching_to, extractLeadingTag(next.name) ?: next.name), Toast.LENGTH_SHORT).show()
    binding.bufferingSpinner.visibility = View.VISIBLE
    playerManager.playUrl(
        next.url,
        next.streamUserAgent,
        preferAudioLanguage = next.mediaType != MediaType.LIVE
    )
}

/** Rebuilds the track-button row's left/right focus chain across only the buttons that are
 *  actually visible right now.
 *
 *  The XML chain is static and links straight through buttons that get hidden at runtime:
 *  btnCast is GONE on TV (the TV is a Cast receiver, not a sender) and btnChapters is GONE
 *  unless a Jellyfin item has chapters. From API 26 FocusFinder walks a GONE link onward to
 *  that view's own nextFocus target, so the chain self-heals; on API 25 and below (Fire TV
 *  7.1) it hands the GONE view straight back and requestFocus() on it fails - RIGHT out of
 *  Sleep did nothing at all and every button past it (External, Diag, Rec, Versions, Fit,
 *  Audio, Subs) was unreachable on those sticks. */
/** Moves focus one step along the controls bar in [direction] (a `View.FOCUS_*`), returning
 *  whether it landed anywhere.
 *
 *  Needed because the Activity claims some direction keys while the bar is up (LEFT, so the
 *  side menu can't fly out from under a user walking the button row) and Activity.onKeyDown
 *  runs before ViewRootImpl's focus navigation - consuming the key there means the framework
 *  never performs the move, so it has to be performed by hand.
 *
 *  The explicit nextFocus link is tried first and resolved against the overlay only; the
 *  geometric fallback is fenced to the overlay too, since focusSearch runs over the whole
 *  window and the browse screen behind the player is still focusable. */
internal fun MainActivity.focusOverlayNeighbour(direction: Int): Boolean {
    val focused = currentFocus ?: return false
    val linkId = if (direction == View.FOCUS_LEFT) focused.nextFocusLeftId else focused.nextFocusRightId
    val target = linkId.takeIf { it != View.NO_ID }
        ?.let { binding.controlsOverlay.findViewById<View>(it) }
        ?.takeIf { it.isFocusable && it.visibility == View.VISIBLE }
        ?: focused.focusSearch(direction)
    if (target == null || target === focused) return false
    var p = target.parent
    while (p != null && p !== binding.controlsOverlay) p = (p as? View)?.parent
    if (p == null) return false
    return target.requestFocus(direction)
}

/** Whether [showVersionPicker] would actually find something to list, mirroring its own
 *  three-way check without opening the dialog - so the button can be hidden rather than
 *  opening only to toast "nothing else to switch to". Series playback sets
 *  currentSeriesVersionContext AFTER its showPlayerFor call returns (see the Lists.kt/
 *  showSeriesVersionPicker call sites), so callers there re-invoke this once it's set
 *  rather than relying on the one inside showPlayerFor. */
internal fun MainActivity.updateVersionsButtonVisibility() {
    val playing = nowPlayingChannel
    val hasVersions = when {
        playing == null -> false
        playing.mediaType == MediaType.LIVE -> currentVersionGroup.size > 1
        currentSeriesVersionContext != null -> {
            val (series, group) = currentSeriesVersionContext!!
            group.size > 1 || canFindStream(series)
        }
        else -> {
            val group = filmVersions[playing.id]
                ?: filmVersions.values.firstOrNull { grp -> grp.any { it.id == playing.id } }
                ?: emptyList()
            group.size > 1 || canFindStream(playing)
        }
    }
    binding.btnLiveVersions.visibility = if (hasVersions) View.VISIBLE else View.GONE
    relinkPlayerButtonRowFocus()
}

internal fun MainActivity.relinkPlayerButtonRowFocus() {
    val row = binding.playerTrackButtons
    val visible = (0 until row.childCount)
        .map { row.getChildAt(it) }
        .filter { it.visibility == View.VISIBLE && it.isFocusable && it.id != View.NO_ID }
    visible.forEachIndexed { index, view ->
        view.nextFocusLeftId = visible.getOrNull(index - 1)?.id ?: View.NO_ID
        view.nextFocusRightId = visible.getOrNull(index + 1)?.id ?: View.NO_ID
    }
}

/** Lets the user manually pick a specific version of whatever's playing - the auto-picked
 *  one isn't always the working one (a live channel's best quality can be the one that
 *  buffers or is geo-blocked; a film's first source can be dead; one provider's copy of a
 *  show can have a broken or incomplete episode list).
 *
 *  Three shapes behind one button, because "version" means something different per type:
 *  live/film versions are alternate streams of the same item, swapped in place; a series
 *  episode's alternatives live in another provider's separate episode list, which has to
 *  be fetched and matched by season/episode before there's anything to play. */
internal fun MainActivity.showVersionPicker() {
    val playing = nowPlayingChannel ?: return
    when {
        playing.mediaType == MediaType.LIVE -> {
            if (currentVersionGroup.size <= 1) {
                Toast.makeText(this, getString(R.string.play_no_other_versions_channel), Toast.LENGTH_SHORT).show()
                return
            }
            val labels = currentVersionGroup.map { it.name }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.play_channel_version_title))
                .setSingleChoiceItems(labels, currentVersionIndex) { dialog, which ->
                    switchToVersionIndex(which)
                    dialog.dismiss()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
        currentSeriesVersionContext != null -> showSeriesVersionPicker(playing)
        else -> showFilmVersionPicker(playing)
    }
}

/** Alternate sources for the film that's playing. filmVersions is keyed by the group's
 *  representative, and the thing playing may itself be a non-representative version
 *  (picked from the detail screen's chips), so the group is found by membership. */
internal fun MainActivity.showFilmVersionPicker(playing: Channel) {
    val group = filmVersions[playing.id]
        ?: filmVersions.values.firstOrNull { grp -> grp.any { it.id == playing.id } }
        ?: emptyList()
    // One copy in the library is not "no versions": searching for another source is itself a
    // version of this title, and it's the only route left when the one copy is the one that
    // just failed. So the library's versions are listed when there are any, Find & Play is
    // appended whenever anything can search, and only both being empty is a dead end.
    val versions = if (group.size > 1) group else emptyList()
    val canFind = canFindStream(playing)
    if (versions.isEmpty() && !canFind) {
        Toast.makeText(this, getString(R.string.play_no_other_versions_title), Toast.LENGTH_SHORT).show()
        return
    }
    val labels = (versions.mapIndexed { index, version -> versionChipLabel(version, index) } +
        listOfNotNull(getString(R.string.play_find_and_play).takeIf { canFind })).toTypedArray()
    val currentIndex = versions.indexOfFirst { it.id == playing.id }
    // The replacement is a different item with its own saved-position key, so the current
    // position is carried across by hand - otherwise switching source mid-film restarts it.
    val resumeMs = playerManager.currentPosition.takeIf { it > 0 }
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.play_version_title))
        .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
            dialog.dismiss()
            when {
                which >= versions.size -> showFindStreamDialog(playing)
                which != currentIndex -> showPlayerFor(versions[which], resumeFromMs = resumeMs)
            }
        }
        .setNegativeButton(getString(R.string.cancel), null)
        .show()
}

/** Other providers' copies of the show whose episode is playing. Each copy is a separate
 *  catalog entry with its own episode list, so switching means fetching that list and
 *  finding the same season/episode in it - which can legitimately fail (a provider may
 *  simply not carry that episode), hence the explicit message rather than a silent no-op. */
internal fun MainActivity.showSeriesVersionPicker(playing: Channel) {
    val (series, group) = currentSeriesVersionContext ?: return
    // As with films: other providers' copies when there are any, plus Find & Play whenever
    // something can search for this episode - a show carried by one provider still has
    // somewhere else to look.
    val versions = if (group.size > 1) group else emptyList()
    val canFind = canFindStream(series)
    if (versions.isEmpty() && !canFind) {
        Toast.makeText(this, getString(R.string.play_no_other_versions_series), Toast.LENGTH_SHORT).show()
        return
    }
    val labels = (versions.mapIndexed { index, version -> versionChipLabel(version, index) } +
        listOfNotNull(getString(R.string.play_find_and_play).takeIf { canFind })).toTypedArray()
    val currentIndex = versions.indexOfFirst { it.id == series.id }
    val episodeNum = playing.episodeNum
    // Which season is playing, read off the episode itself ("S04E01 · ..."). Matching on
    // the episode number alone walked the target's seasons in order and took the first
    // one carrying that number - so every "episode 1" resolved to S01E01 no matter which
    // season was actually playing. The old fallback, comparing season *lengths*, only
    // ever helped when both providers happened to split the show identically, and never
    // at all from the Play button (whose queue is the whole cross-season chain).
    val seasonNum = seasonNumberOf(playing)
    val queueSeasonSize = currentEpisodeQueue.size.takeIf { it > 0 }
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.play_series_version_title))
        .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
            dialog.dismiss()
            // Find & Play searches for the episode that is actually playing, rather than
            // asking again which one - it's already on screen.
            if (which >= versions.size) {
                showFindStreamDialog(series, seasonNum, episodeNum)
                return@setSingleChoiceItems
            }
            if (which == currentIndex) return@setSingleChoiceItems
            val target = versions[which]
            Toast.makeText(this, getString(R.string.play_loading_version, versionChipLabel(target, which)), Toast.LENGTH_SHORT).show()
            val resumeMs = playerManager.currentPosition.takeIf { it > 0 }
            scope.launch {
                if (!switchToSeriesVersion(target, playing, group, resumeMs, queueSeasonSize)) {
                    val what = if (seasonNum != null && episodeNum != null) "S${seasonNum}E$episodeNum" else getString(R.string.play_this_episode)
                    Toast.makeText(this@showSeriesVersionPicker, getString(R.string.play_provider_missing_episode, what), Toast.LENGTH_SHORT).show()
                }
            }
        }
        .setNegativeButton(getString(R.string.cancel), null)
        .show()
}

/**
 * Plays [target]'s copy of the episode currently playing, and returns whether it found one.
 *
 * A provider's copy of a show is a separate catalog entry with its own episode list, so the
 * switch is a fetch plus a season/episode match rather than swapping a URL. False means that
 * provider genuinely doesn't carry this episode - the caller decides what to say about it,
 * since a manual pick and an automatic failover want different messages.
 *
 * [queueSeasonSize] is the size of the queue the playing episode came from, used only as a
 * tie-break when the playing episode states no season of its own.
 */
internal suspend fun MainActivity.switchToSeriesVersion(
    target: Channel,
    playing: Channel,
    group: List<Channel>,
    resumeMs: Long?,
    queueSeasonSize: Int? = currentEpisodeQueue.size.takeIf { it > 0 }
): Boolean {
    val episodeNum = playing.episodeNum
    val seasonNum = seasonNumberOf(playing)
    val (_, seasons) = runCatching { loadSeriesContent(target) }.getOrElse { null to emptyList() }
    // The target's own season number, taken from its episodes first (they carry the same
    // "S04E01" marker) and from the season label as the fallback, since a provider may label a
    // season anything ("Series 4", a Jellyfin custom name).
    fun seasonNumberFor(label: String, eps: List<Channel>): Int? =
        eps.firstNotNullOfOrNull { seasonNumberOf(it) }
            ?: Regex("""\d+""").find(label)?.value?.toIntOrNull()
    val match = when {
        // Season known on both sides: only that season's copy of the episode is the right
        // answer. No match there means this provider genuinely doesn't carry it - saying so
        // beats silently playing a different episode.
        seasonNum != null -> seasons.firstNotNullOfOrNull { (label, eps) ->
            if (seasonNumberFor(label, eps) != seasonNum) null
            else eps.firstOrNull { it.episodeNum != null && it.episodeNum == episodeNum }
        }
        // No season stated by the playing episode - prefer a season the same length as the
        // queue it came from, then any season carrying that episode number.
        else -> seasons.firstNotNullOfOrNull { (_, eps) ->
            eps.firstOrNull { it.episodeNum != null && it.episodeNum == episodeNum && (queueSeasonSize == null || eps.size == queueSeasonSize) }
        } ?: seasons.firstNotNullOfOrNull { (_, eps) ->
            eps.firstOrNull { it.episodeNum != null && it.episodeNum == episodeNum }
        }
    } ?: return false
    val newQueue = seasons.firstOrNull { (_, eps) -> eps.any { it.id == match.id } }?.second ?: listOf(match)
    showPlayerFor(match, resumeFromMs = resumeMs)
    currentEpisodeQueue = newQueue
    currentEpisodeQueueIndex = newQueue.indexOfFirst { it.id == match.id }
    currentSeriesVersionContext = target to group
    updateVersionsButtonVisibility()
    return true
}

// ── Buffer-based auto-failover ─────────────────
// onPlayerError already fails over on a hard error; this covers the "plays but
// buffers constantly" case, which ExoPlayer never surfaces as an error at all.
internal fun MainActivity.onBufferingStarted() {
    if (nowPlayingChannel?.mediaType != MediaType.LIVE) return
    // The buffering a stream does before it has ever reached READY is it starting up,
    // not stalling. Counting it meant a launch-time tune - where the app is also parsing
    // the channel cache and building categories, so the first fill is slow - burned
    // through the stall threshold and failed over to version after version.
    if (!currentStreamPlayed) return
    if (bufferingStartMs != 0L) return
    val now = System.currentTimeMillis()
    bufferingStartMs = now
    stallTimestamps.add(now)
    stallTimestamps.removeAll { it < now - STALL_WINDOW_MS }
    if (stallTimestamps.size >= STALL_COUNT_THRESHOLD) {
        attemptBufferFailover()
        return
    }
    mainHandler.postDelayed(longStallCheckRunnable, STALL_LONG_MS)
}

internal fun MainActivity.onBufferingEnded() {
    bufferingStartMs = 0L
    mainHandler.removeCallbacks(longStallCheckRunnable)
}

internal fun MainActivity.resetStallTracking() {
    bufferingStartMs = 0L
    stallTimestamps.clear()
    mainHandler.removeCallbacks(longStallCheckRunnable)
}

internal fun MainActivity.attemptBufferFailover() {
    if (nowPlayingChannel?.mediaType != MediaType.LIVE) return
    if (withinFailoverGrace()) { resetStallTracking(); return }
    resetStallTracking()
    // Out of versions to fall back on means the app has nothing left to try on its own -
    // constant rebuffering with no alternative is exactly when another player is worth a go.
    if (!tryNextQualityVersion(getString(R.string.play_stream_buffering_switching))) {
        suggestExternalPlayer(getString(R.string.play_buffering_no_fallback))
    }
}

/** Whether the current stream is still too young to judge. Every automatic failover is a
 *  verdict on a stream that's been given a fair chance to settle; without this the app
 *  cycles through the whole version group in the first few seconds of a tune, each switch
 *  restarting the clock on the next one. Hard playback errors bypass this - those are
 *  conclusive on their own. */
internal fun MainActivity.withinFailoverGrace(): Boolean =
    System.currentTimeMillis() - currentStreamStartMs < FAILOVER_GRACE_MS

/** Marks the start of a playback attempt for failover purposes. */
internal fun MainActivity.beginStreamAttempt() {
    currentStreamStartMs = System.currentTimeMillis()
    currentStreamPlayed = false
    externalPlayerSuggestedForStream = false
    // Whatever this dialog was about belongs to the attempt that just ended, not the one
    // starting now - stale otherwise if that attempt failed over automatically and this new
    // one goes on to play fine.
    externalPlayerDialog?.dismiss()
    externalPlayerDialog = null
}

// ── Black-frame auto-failover ──────────────────
// A dead feed sometimes never stalls or errors at all - the server just serves a
// technically-valid, steadily-decoding encode of a blank black frame instead, so
// neither onPlayerError nor the buffer-stall watchdog above ever fires. Sample the
// actual rendered surface periodically and treat sustained near-black output as a
// dead feed too.
internal fun MainActivity.startBlackFrameWatch() {
    if (isDestroyed) return
    blackFrameStreak = 0
    mainHandler.removeCallbacks(blackFrameCheckRunnable)
    mainHandler.postDelayed(blackFrameCheckRunnable, BLACK_FRAME_INITIAL_DELAY_MS)
}

internal fun MainActivity.checkForBlackFrame() {
    if (nowPlayingChannel?.mediaType != MediaType.LIVE || !isPlayerVisible || !playerManager.isPlaying) {
        mainHandler.postDelayed(blackFrameCheckRunnable, BLACK_FRAME_CHECK_INTERVAL_MS)
        return
    }
    val surfaceView = binding.playerSurface
    if (surfaceView.width <= 0 || surfaceView.height <= 0) {
        mainHandler.postDelayed(blackFrameCheckRunnable, BLACK_FRAME_CHECK_INTERVAL_MS)
        return
    }
    // PixelCopy.request is API 26+; on API 25 (minSdk, Fire TV 7.1) the class is missing and
    // the failure surfaces as an uncaught NoClassDefFoundError, which the Exception catch below
    // can't see. Fall back to the preview pane's TextureView.getBitmap sampling path - only a
    // TextureView is readable that way, and the fullscreen surface is a SurfaceView, so there's
    // no sample and no verdict: the streak resets and the watchdog just re-arms. Black-frame
    // failover stays inert on pre-O instead of crashing the first live tune.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        blackFrameStreak = 0
        mainHandler.postDelayed(blackFrameCheckRunnable, BLACK_FRAME_CHECK_INTERVAL_MS)
        return
    }
    val sample = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
    try {
        PixelCopy.request(surfaceView, sample, { result ->
            if (isDestroyed || !isPlayerVisible) { sample.recycle(); return@request }
            val isBlack = result == PixelCopy.SUCCESS && averageLuma(sample) < BLACK_FRAME_LUMA_THRESHOLD
            sample.recycle()
            blackFrameStreak = if (isBlack) blackFrameStreak + 1 else 0
            if (blackFrameStreak >= BLACK_FRAME_STREAK_THRESHOLD && !withinFailoverGrace()) {
                blackFrameStreak = 0
                if (!tryNextQualityVersion(getString(R.string.play_channel_offline_switching))) {
                    Toast.makeText(this, getString(R.string.play_channel_offline), Toast.LENGTH_SHORT).show()
                }
            } else {
                mainHandler.postDelayed(blackFrameCheckRunnable, BLACK_FRAME_CHECK_INTERVAL_MS)
            }
        }, mainHandler)
    } catch (e: Exception) {
        sample.recycle()
        mainHandler.postDelayed(blackFrameCheckRunnable, BLACK_FRAME_CHECK_INTERVAL_MS)
    }
}

internal fun MainActivity.averageLuma(bitmap: Bitmap): Int {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    var sum = 0L
    for (p in pixels) {
        sum += (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3
    }
    return if (pixels.isNotEmpty()) (sum / pixels.size).toInt() else 0
}

/** Same black-frame detection as fullscreen playback, but for the muted inline preview
 *  player used while browsing the guide - it plays a version group's best entry same as
 *  fullscreen, so it can hit the exact same dead/blank-feed case, silently (no Toast,
 *  nothing to interrupt) skipping to the next non-dead version instead. */
internal fun MainActivity.startPreviewBlackFrameWatch() {
    previewBlackFrameStreak = 0
    mainHandler.removeCallbacks(previewBlackFrameCheckRunnable)
    mainHandler.postDelayed(previewBlackFrameCheckRunnable, BLACK_FRAME_INITIAL_DELAY_MS)
}

internal fun MainActivity.checkForPreviewBlackFrame() {
    if (activeTab != 0 || isPlayerVisible || binding.livePreviewPane.visibility != View.VISIBLE) return
    // Never sample while the preview player is buffering - mid-buffer frames are
    // usually black, and a slow stall could streak past the threshold and falsely
    // kill a healthy version. Re-arm and try again once it reaches READY.
    val previewState = previewPlayerManager?.playbackState
    if (previewState == null || previewState == Player.STATE_BUFFERING) {
        mainHandler.postDelayed(previewBlackFrameCheckRunnable, BLACK_FRAME_CHECK_INTERVAL_MS)
        return
    }
    val textureView = binding.previewSurface
    if (!textureView.isAvailable) {
        mainHandler.postDelayed(previewBlackFrameCheckRunnable, BLACK_FRAME_CHECK_INTERVAL_MS)
        return
    }
    val sample = runCatching { textureView.getBitmap(32, 18) }.getOrNull()
    val isBlack = sample != null && averageLuma(sample) < BLACK_FRAME_LUMA_THRESHOLD
    sample?.recycle()
    previewBlackFrameStreak = if (isBlack) previewBlackFrameStreak + 1 else 0
    if (previewBlackFrameStreak < BLACK_FRAME_STREAK_THRESHOLD) {
        mainHandler.postDelayed(previewBlackFrameCheckRunnable, BLACK_FRAME_CHECK_INTERVAL_MS)
        return
    }
    previewBlackFrameStreak = 0
    previewVersionGroup.getOrNull(previewVersionIndex)?.let { markStreamDead(it) }
    var nextIndex = previewVersionIndex + 1
    while (nextIndex < previewVersionGroup.size && isStreamDead(previewVersionGroup[nextIndex])) nextIndex++
    if (nextIndex >= previewVersionGroup.size) return // nothing else to try - leave it, stop watching
    previewVersionIndex = nextIndex
    val next = previewVersionGroup[nextIndex]
    ensurePreviewPlayer().playUrl(next.url, next.streamUserAgent)
    mainHandler.postDelayed(previewBlackFrameCheckRunnable, BLACK_FRAME_CHECK_INTERVAL_MS)
}

/** Live channels are never resumable; movies/episodes are, once far enough in.
 *
 *  Called on every STATE_READY, and a mid-playback rebuffer is another BUFFERING ->
 *  READY pair - so the question has to be answered once per stream, not once per READY.
 *  A stream that started with no saved position keeps writing one as it plays, and
 *  without the flag being settled here the first rebuffer would offer to resume the
 *  position playback just wrote. */
internal fun MainActivity.maybeShowResumePrompt() {
    if (resumePromptShown) return
    val channel = nowPlayingChannel ?: return
    // An auto-advanced episode should just start from its beginning - no resume
    // question. Consume the suppression either way so it never leaks into a later,
    // user-initiated play.
    if (skipResumePrompt) {
        skipResumePrompt = false
        resumePromptShown = true
        return
    }
    resumePromptShown = true
    if (channel.mediaType == MediaType.LIVE) return
    val key = channel.id.ifBlank { channel.url }
    val saved = PlaybackPositionStore.get(this, key) ?: return
    if (saved.isNearComplete || saved.positionMs < 5000) return

    playerManager.pause()
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.play_resume_playback_title))
        .setMessage(getString(R.string.play_resume_message, formatTime(saved.positionMs)))
        .setPositiveButton(getString(R.string.play_resume)) { _, _ -> playerManager.seekTo(saved.positionMs); playerManager.play() }
        .setNegativeButton(getString(R.string.play_start_over)) { _, _ -> playerManager.seekTo(0); playerManager.play() }
        .setCancelable(false)
        .show()
}

internal fun MainActivity.saveCurrentPlaybackPosition() {
    val channel = nowPlayingChannel ?: return
    if (channel.mediaType == MediaType.LIVE) return
    // A catch-up programme is played as MOVIE to get VOD controls, but it is not a
    // library item: its URL is a timeshift window that the panel drops as the archive
    // rolls, so a resume position would age into a Continue Watching tile that plays
    // nothing. The archive is browsable again from the Catch Up tab either way.
    if (channel.id.startsWith(CATCHUP_ID_PREFIX)) return
    if (isAdultCategory(channel.categoryName, channel.group)) return
    val dur = playerManager.duration
    val pos = playerManager.currentPosition
    if (pos == androidx.media3.common.C.TIME_UNSET || pos < 0) return
    if (dur <= 0) return
    val key = channel.id.ifBlank { channel.url }
    // Jellyfin episodes carry no series id of their own (toChannel drops it), so stamp
    // the parent series id here - the detail page sets currentSeriesVersionContext for
    // its plays - letting a later Continue Watching click resolve the series page.
    // Movies and live channels are untouched.
    val saveChannel = if (channel.mediaType == MediaType.SERIES) {
        channel.copy(categoryId = channel.categoryId ?: currentSeriesVersionContext?.first?.id)
    } else channel
    val wasWatched = PlaybackPositionStore.get(this, key)?.isNearComplete == true
    PlaybackPositionStore.save(this, key, pos, dur, saveChannel)
    // Crossing the completion threshold is what "watched" means for a title that was
    // actually played, so publish it to every other copy - and to the media servers - the
    // moment it happens. Only on the transition: re-saving an already-finished position on
    // every teardown would re-push the same mark to every server each time.
    if (!wasWatched && PlaybackPositionStore.get(this, key)?.isNearComplete == true) {
        setItemWatched(saveChannel, watched = true)
    }
}

internal fun MainActivity.hidePlayer() {
    saveCurrentPlaybackPosition()
    // Watched state may have moved during playback - the Home up-next memo is stale.
    clearUpNextMemo()
    // Before nowPlayingChannel is cleared: the server turns this final position into a
    // watched mark or a resume point, and closes out any transcode it started.
    if (reportJellyfinStopped()) refreshJellyfinRowsAfterPlayback()
    if (reportPlexStopped()) refreshPlexRowsAfterPlayback()
    hideTrickplayPreview()
    // What was playing is the best preview target when nothing in the guide was ever
    // focused - a launch that resumes straight into the player never fires a focus
    // event, so lastFocusedLiveChannel is null and the preview pane came back blank.
    val wasPlaying = nowPlayingChannel?.takeIf { it.mediaType == MediaType.LIVE }
    isPlayerVisible = false
    nowPlayingChannel = null
    binding.playerLayout.visibility = View.GONE
    binding.mainContent.visibility = View.VISIBLE
    binding.playerLayout.keepScreenOn = false
    mainHandler.removeCallbacks(hideControlsRunnable)
    // A fresh player session starts with the side menu tucked away - kill any
    // in-flight slide animation and reset the transform it left behind.
    binding.playerSideMenu.animate().cancel()
    binding.playerSideMenu.visibility = View.GONE
    binding.playerSideMenu.translationX = 0f
    sideMenuCategoryWidthAnimator?.cancel()
    sideMenuCategoryWidthAnimator = null
    sideMenuCategoriesExpanded = false
    sideMenuChannelCategory = null
    sideMenuChannelRows = emptyList()
    sideMenuColumnBusy = false
    // The catalog can change between sessions (refresh, provider toggled) - next
    // player session rebuilds rather than serving stale category rows.
    sideMenuCategoryCache.clear()
    binding.sideMenuCategoryPanel.visibility = View.GONE
    binding.sideMenuCategoryPanel.updateLayoutParams<ViewGroup.LayoutParams> { width = 0 }
    mainHandler.removeCallbacks(progressRunnable)
    mainHandler.removeCallbacks(longStallCheckRunnable)
    mainHandler.removeCallbacks(blackFrameCheckRunnable)
    mainHandler.removeCallbacks(upNextTickRunnable)
    playerManager.stop()
    sleepTimer.stop()
    // A plugin-served stream (Find Stream) keeps a torrent + local server alive for as long
    // as we're playing its URL - release it now.
    activeTorrentSession?.let { engine -> Thread { runCatching { engine.stop() } }.start() }
    activeTorrentSession = null
    TorrentForegroundService.stop(this)
    if (isCastManagerReady) castManager.stopCasting()
    if (activeTab == 0) {
        showLivePreviewPane()
        val previewTarget = lastFocusedLiveChannel ?: wasPlaying
        previewTarget?.let { requestPreviewLoad(it) }
        // Backing out lands in the channel's own dynamic row (Sports/News bucket, brand
        // row, Jellyfin) when it belongs to one - that's the list it was picked from, so
        // returning to whatever filter happened to be selected loses the user's place.
        val dynamicRow = previewTarget?.let { dynamicCategoryFor(it) }
        if (dynamicRow != null && dynamicRow.id != selectedRowId) {
            selectedShelfItems = null
            selectedRowId = dynamicRow.id
            selectedCategoryLabel = dynamicRow.name
            selectedBrandChannelIds = dynamicRow.channelIds.ifEmpty { null }
            selectedCategoryIds = if (dynamicRow.channelIds.isNotEmpty()) null else dynamicRow.matchIds
            // Scroll only once the new filter's list is in place - the position of the
            // channel is meaningless against the outgoing one.
            scope.launch {
                // The row can be a brand row inside a collapsed bucket, in which case
                // selecting it without expanding its parent highlights nothing.
                if (categoryAdapter.currentList.none { it.id == dynamicRow.id }) {
                    parentOfCategoryRow(dynamicRow.id)?.let { parentId ->
                        expandedGroupKeys.add(parentId)
                        rebuildCategoriesForActiveTab()
                    }
                }
                categoryAdapter.setSelected(selectedRowId)
                applyCategoryFilter()
                previewTarget.let { scrollLiveListTo(it) }
            }
        } else {
            // Scroll the channel list so the last-watched channel is visible when
            // the player closes, instead of showing the first channel (which
            // applyCategoryFilter scrolled to on tab switch). The list may have a
            // category filter active, so find the position in the adapter list
            // rather than assuming liveChannels order.
            previewTarget?.let { scrollLiveListTo(it) }
        }
    }
    // Whatever just finished playing may have changed Continue Watching - refresh
    // Home so it's not stale until the next unrelated rebuild happens to touch it.
    if (showingHome) homeShelfAdapter.submitList(buildHomeShelves())
    // Same for the Series poster shelf and its Continue Watching / Up Next rows.
    refreshSeriesShelvesIfShowing()
    // Backing out of a film or an episode lands on that title's poster - the screen it was
    // started from - instead of the grid behind it, which is several D-pad moves and a
    // scroll position away from where the user actually was.
    val returnTo = detailReturnItem
    val returnGroup = detailReturnGroup
    detailReturnItem = null
    detailReturnGroup = null
    if (returnTo != null) {
        showContentDetail(returnTo, returnGroup)
        return
    }
    restoreTabFocus()
}
