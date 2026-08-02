package com.lumora.player

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lightweight wrapper around Media3 ExoPlayer.
 * Manages player lifecycle and provides simple playback control.
 */
class PlayerManager(
    private val context: Context
) {
    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true /* handleAudioFocus */
        )
        .build()
        .also { it.setHandleAudioBecomingNoisy(true) }
    private val listeners = CopyOnWriteArrayList<Player.Listener>()
    private var released = false

    val isPlaying: Boolean
        get() = player.isPlaying

    val currentPosition: Long
        get() = player.currentPosition

    val duration: Long
        get() = player.duration

    val playbackState: Int
        get() = player.playbackState

    /** Build a data source factory with optional custom headers. */
    private fun buildDataSourceFactory(
        userAgent: String? = null,
        headers: Map<String, String>? = null
    ): DataSource.Factory {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            // 60s read: slow remote Jellyfin/transcode servers can take a while to start
            // sending the stream - 20s made a cold server start read as "Playback error".
            .setReadTimeoutMs(60_000)

        if (!userAgent.isNullOrBlank()) {
            httpFactory.setUserAgent(userAgent)
        }
        // Extra per-stream headers (e.g. a Referer a hotlink-protected CDN requires). Applied to
        // every request the source makes, so playlist and segment fetches both carry them.
        if (!headers.isNullOrEmpty()) {
            httpFactory.setDefaultRequestProperties(headers)
        }

        return DefaultDataSource.Factory(
            context,
            httpFactory
        )
    }

    /**
     * A subtitle track that lives outside the media container - a sidecar file, or one the
     * server extracts on request (Jellyfin does both). Sideloading these is the only way they
     * reach the track picker at all: nothing in the stream itself advertises them.
     */
    data class ExternalSubtitle(
        val uri: String,
        val mimeType: String,
        val language: String? = null,
        val label: String? = null,
        val isDefault: Boolean = false,
        val isForced: Boolean = false
    )

    /**
     * Prepare and start playing a stream URL.
     *
     * [startPositionMs] seeks *before* prepare rather than after, so a resumed title buffers
     * once at the right place instead of buffering the opening seconds and then throwing
     * that away on a seek.
     */
    fun playUrl(
        url: String,
        userAgent: String? = null,
        subtitles: List<ExternalSubtitle> = emptyList(),
        startPositionMs: Long = 0L,
        headers: Map<String, String>? = null,
        audio: String? = null
    ) {
        val dataSourceFactory = buildDataSourceFactory(userAgent, headers)

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(url)
                    .build()
            )

        // Sidecar subtitles are opt-in: subs are OFF by default, and only the DEFAULT-flagged
        // track is stamped SELECTION_FLAG_DEFAULT when the user has turned them on. Media3
        // leaves non-default text tracks unselected, so off means nothing auto-selects.
        val subtitlesEnabled = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            .getBoolean("subtitles_enabled", false)
        if (subtitles.isNotEmpty()) {
            mediaItemBuilder.setSubtitleConfigurations(
                subtitles.map { subtitle ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.uri))
                        .setMimeType(subtitle.mimeType)
                        .setLanguage(subtitle.language)
                        .setLabel(subtitle.label)
                        // FORCED carries the "only show this for foreign dialogue" meaning the
                        // track was authored with; DEFAULT is what makes the renderer pick it
                        // without the user going into the picker.
                        .setSelectionFlags(if (subtitlesEnabled && subtitle.isDefault) C.SELECTION_FLAG_DEFAULT else 0)
                        .setRoleFlags(if (subtitle.isForced) C.ROLE_FLAG_SUBTITLE or C.ROLE_FLAG_TRANSCRIBES_DIALOG else C.ROLE_FLAG_SUBTITLE)
                        .build()
                }
            )
        }

        val mediaItem = mediaItemBuilder.build()

        // Auto-detects HLS/DASH/SmoothStreaming/progressive (mp4, mkv, ts...) from the
        // URI extension or response content-type instead of assuming everything is HLS.
        val mediaSource = DefaultMediaSourceFactory(dataSourceFactory)
            .createMediaSource(mediaItem)

        // A sideloaded track is there because the source has no other way to show subtitles at
        // all - an anime episode whose only subtitles are the sidecar file plays as raw
        // Japanese without it. So turn text on and point the selector at this track's language
        // rather than relying on SELECTION_FLAG_DEFAULT: text stays disabled across items once
        // anything has switched subtitles off (the flag is per-track, the disable is per-player,
        // and the disable wins), which would have carried straight into the next episode.
        // A stream known to be a dub usually has its dialog baked into the audio track, so the
        // sidecar subtitles only come on when the user opted in (subtitles_with_dub).
        val subtitlesWithDub = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            .getBoolean("subtitles_with_dub", false)
        // Whole force-enable block is gated on the opt-in pref: when subtitles are OFF this
        // keeps Media3's defaults (with no DEFAULT-flagged sidecar track above, no text track
        // auto-selects). When ON, force text tracks on and point the selector at the sidecar's
        // language (including the subtitles.first() fallback) so opt-in users get their subs.
        if (subtitlesEnabled && subtitles.isNotEmpty() && (audio?.equals("dub", ignoreCase = true) != true || subtitlesWithDub)) {
            val preferred = subtitles.firstOrNull { it.isDefault } ?: subtitles.first()
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .apply { preferred.language?.let { setPreferredTextLanguage(it) } }
                // Sources routinely ship a track with no language tag at all; without this the
                // selector skips it and the subtitles the user was given go unused.
                .setSelectUndeterminedTextLanguage(true)
                .build()
        }

        player.setMediaSource(mediaSource)
        // Seek before prepare, not after: the position is applied as the start position when
        // preparation runs, so a resumed title buffers once at the right place instead of
        // buffering the opening seconds and throwing that away on a seek.
        if (startPositionMs > 0) player.seekTo(startPositionMs)
        player.prepare()
        if (audio != null) {
            attachOneShotAudioPreference(audio)
        }
        player.play()
    }

    /**
     * When the caller knows whether this stream is a dub or a sub (the plugin carries the
     * hint on the search result and on the resolve), prefer the matching audio track once the
     * manifest's tracks are known. Ported from Anilili's PlayerSurface: rank every audio
     * track name against the wanted category, and only override when there are multiple
     * tracks and the best is a confident match (rank < 50). One-shot per playUrl call - the
     * listener removes itself after deciding, so a later episode's track listing can't make
     * it re-apply against the wrong media.
     */
    private fun attachOneShotAudioPreference(audio: String) {
        val wantsDub = audio.equals("dub", ignoreCase = true)
        val listener = object : Player.Listener {
            private var decided = false
            override fun onTracksChanged(tracks: Tracks) {
                if (decided) return
                val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
                // Track info isn't here yet (still preparing) - stay attached for the real event.
                if (audioGroups.isEmpty()) return
                decided = true
                player.removeListener(this)
                // A single audio track is all this stream has to offer - nothing to switch to.
                if (audioGroups.sumOf { it.length } <= 1) return
                var bestGroup: Tracks.Group? = null
                var bestIndex = -1
                var bestRank = Int.MAX_VALUE
                for (group in audioGroups) {
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        val name = listOfNotNull(format.label, format.language).joinToString(" ")
                            .trim().ifBlank { "Audio" }
                        val rank = audioTrackRank(name, wantsDub)
                        if (rank < bestRank) {
                            bestRank = rank
                            bestGroup = group
                            bestIndex = i
                        }
                    }
                }
                // Ranks 0/5 are a confident match; anything >= 50 carries no signal, and
                // overriding on that would just fight the source's own default.
                if (bestRank < 50 && bestGroup != null && bestIndex >= 0 && !bestGroup.isTrackSelected(bestIndex)) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .setOverrideForType(
                            TrackSelectionOverride(bestGroup.mediaTrackGroup, listOf(bestIndex))
                        )
                        .build()
                }
            }
        }
        player.addListener(listener)
        // Covers sources that fired onTracksChanged synchronously during prepare(), before the
        // listener was attached; a no-op until track info is actually there.
        listener.onTracksChanged(player.currentTracks)
    }

    /**
     * Anilili's categoryAudioRank table: a track name's affinity for the wanted audio
     * category. 0 = exact match, 5 = likely match, 100 = no signal.
     */
    private fun audioTrackRank(name: String, wantsDub: Boolean): Int {
        val lower = name.lowercase()
        return if (wantsDub) {
            when {
                lower == "en" || lower.contains("english") || lower.contains(" eng") -> 0
                lower.contains("dub") -> 5
                else -> 100
            }
        } else {
            when {
                lower == "ja" || lower.contains("japanese") || lower.contains(" jpn") || lower.contains(" ja") -> 0
                lower.contains("native") -> 5
                else -> 100
            }
        }
    }

    /** Attach to a SurfaceView for video rendering. */
    fun setSurfaceView(surfaceView: SurfaceView) {
        player.setVideoSurfaceView(surfaceView)
    }

    /**
     * Attach to a TextureView instead. Used for the small inline preview pane:
     * a SurfaceView is a hardware overlay that can leave stale/ghosted frames
     * behind when repeatedly resized or hidden/shown; TextureView is a normal
     * composited View and doesn't have that failure mode. Costs a bit more
     * power/perf than SurfaceView, which is why the main fullscreen player
     * still uses setSurfaceView() above.
     */
    fun setTextureView(textureView: android.view.TextureView) {
        player.setVideoTextureView(textureView)
    }

    /** Toggle play/pause. */
    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun play() = player.play()
    fun pause() = player.pause()
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    /** Seek forward/backward by a relative delta, clamped to [0, duration]. */
    fun seekBy(deltaMs: Long) {
        val dur = player.duration
        if (dur <= 0) return
        val pos = player.currentPosition
        if (pos == C.TIME_UNSET || pos < 0) return
        val target = (pos + deltaMs).coerceIn(0L, dur)
        player.seekTo(target)
    }

    fun stop() { player.stop() }

    /** Add a player event listener. */
    fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        player.addListener(listener)
    }

    /** Remove a player event listener. */
    fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        player.removeListener(listener)
    }

    /** Release all player resources. Must be called when done. */
    fun release() {
        if (released) return
        released = true
        listeners.forEach { player.removeListener(it) }
        listeners.clear()
        player.release()
    }

    fun setVolume(volume: Float) = player.setVolume(volume)

    /** Get the underlying ExoPlayer instance for advanced use. */
    fun getExoPlayer(): ExoPlayer = player
}
