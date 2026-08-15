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
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import com.lumora.player.playback.AvOffsetManager
import com.lumora.player.playback.AvOffsetRenderersFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lightweight wrapper around Media3 ExoPlayer.
 * Manages player lifecycle and provides simple playback control.
 */
class PlayerManager(
    private val context: Context
) {
    // Microseconds, read live by AvOffsetRenderersFactory's video renderer on every output
    // frame - updating it takes effect immediately, no player rebuild needed.
    private var avOffsetUs: Long = AvOffsetManager(context).load().toLong() * 1000L

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(AvOffsetRenderersFactory(context) { avOffsetUs })
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true /* handleAudioFocus */
        )
        .apply {
            // Only deviates from Media3's defaults when the user has asked for it. The scraper
            // providers stream from third-party embed hosts that deliver in bursts and stall
            // between them, and Media3's default 50s ceiling is not enough headroom to ride that
            // out - but a 5-minute buffer is a lot of RAM to hold on the budget TV sticks this
            // app targets, so it is not the default.
            //
            // A LoadControl can only be set when the player is built, so this is read once at
            // construction: toggling the setting takes effect on the next app start, which is
            // what the settings row says.
            if (isExtraBufferingEnabled(context)) {
                setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                            EXTRA_MAX_BUFFER_MS,
                            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                        )
                        .build()
                )
            }
        }
        .build()
        .also { it.setHandleAudioBecomingNoisy(true) }
    private val listeners = CopyOnWriteArrayList<Player.Listener>()
    private var released = false

    // The one-shot audio/forced-subtitle track overrides (see attachOneShot*) stay attached
    // until onTracksChanged hands over a real track list - a dead stream or endless buffering
    // never does, so without a cap they'd accumulate one per playUrl call and then fire against
    // a later play. Remember the last one of each kind so a new playUrl can retire it first.
    private var oneShotAudioListener: Player.Listener? = null
    private var oneShotForcedSubtitleListener: Player.Listener? = null

    /**
     * What the player was last asked to play, as it was finally resolved (Stalker commands,
     * Jellyfin negotiation and plugin resolves all rewrite the URL before it gets here).
     * Kept so the stream can be handed to an external player - see ExternalPlayer - which
     * otherwise has no way to know what the app is actually playing.
     */
    data class ResolvedStream(
        val url: String,
        val userAgent: String?,
        val headers: Map<String, String>?,
    )

    var lastResolvedStream: ResolvedStream? = null
        private set

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
        headers: Map<String, String>? = null,
        maintainTokenQuery: String? = null
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

        val base: DataSource.Factory = DefaultDataSource.Factory(context, httpFactory)
        if (maintainTokenQuery.isNullOrBlank()) return base

        // Some embed hosts sign the *playlist* URL with an auth token in the query string and
        // then expect that same token on every segment request - but the segment paths inside
        // the returned m3u8 are written without it. Left alone, the playlist loads and then
        // every segment 403s, which surfaces as a few seconds of video followed by a hard stop.
        //
        // ResolvingDataSource rewrites each URI as it is opened, which is the only hook that
        // sees the segment requests at all (they are generated by the HLS source, not by us).
        // Requests that already carry a query are left alone, so a host that does sign its
        // segments is not double-tokenised.
        return ResolvingDataSource.Factory(base) { dataSpec ->
            val uri = dataSpec.uri
            if (uri.query.isNullOrEmpty()) {
                dataSpec.withUri(uri.buildUpon().encodedQuery(maintainTokenQuery).build())
            } else {
                dataSpec
            }
        }
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
        audio: String? = null,
        preferAudioLanguage: Boolean = false,
        /**
         * Set for hosts that sign the playlist URL with a token their segment URLs omit - the
         * query string is then re-applied to every otherwise-unqueried request. See
         * [buildDataSourceFactory]. Null (the default) leaves URIs untouched.
         */
        maintainTokenQuery: String? = null,
        /**
         * Container MIME, when the source knows it and the URL does not say. Media3 otherwise
         * infers the type from the URI path, which fails for a signed HLS URL whose path ends in
         * a token rather than `.m3u8` - it falls back to the progressive extractors and dies with
         * UnrecognizedInputFormatException on a playlist they cannot read.
         */
        mimeType: String? = null,
        /**
         * Replaces the network data source entirely - used to play a completed offline download
         * out of its cache. Every other argument that shapes a network request (headers, user
         * agent, token query) is then irrelevant, since nothing is fetched.
         */
        dataSourceOverride: DataSource.Factory? = null
    ) {
        // A pending coroutine can outlive the activity (release() only runs at suspension
        // points); calling into a released ExoPlayer throws. Refuse rather than crash.
        if (released) return
        lastResolvedStream = ResolvedStream(url, userAgent, headers)
        val dataSourceFactory = dataSourceOverride
            ?: buildDataSourceFactory(userAgent, headers, maintainTokenQuery)

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .apply { if (!mimeType.isNullOrBlank()) setMimeType(mimeType) }
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
                        //
                        // FORCED is stamped whatever the subtitles pref says, because it is a
                        // statement about the track, not a request to show it: with text
                        // disabled nothing selects it, and it is the signal
                        // attachOneShotForcedSubtitlePreference looks for when subtitles are
                        // off. It used to be left off entirely, which is why that pass had to
                        // fall back to reading the role flag - and that flag also marks SDH.
                        .setSelectionFlags(
                            when {
                                subtitle.isForced -> C.SELECTION_FLAG_FORCED
                                subtitlesEnabled && subtitle.isDefault -> C.SELECTION_FLAG_DEFAULT
                                else -> 0
                            }
                        )
                        .setRoleFlags(if (subtitle.isForced) C.ROLE_FLAG_SUBTITLE or C.ROLE_FLAG_TRANSCRIBES_DIALOG else C.ROLE_FLAG_SUBTITLE)
                        .build()
                }
            )
        }

        // Per-item track defaults, applied before the source is set so they're in force for
        // the first selection pass.
        //
        // Text off unless the user opted in. Stamping SELECTION_FLAG_DEFAULT on the sidecar
        // configurations above only governs *sideloaded* tracks - subtitles embedded in the
        // media (an mkv whose subtitle track is flagged default in the container) are chosen
        // by the selector, so a VOD file would still start with subtitles burned on screen
        // however the pref was set. Disabling the text type is per-player and covers both.
        //
        // Preferred audio language (Settings > General) for VOD: multi-audio films and
        // episodes routinely list another language first and the selector takes it, so the
        // title opens in a language the user didn't ask for. Live TV is left alone - a
        // foreign-language channel's own audio is the point of it. Cleared (not just
        // skipped) when not wanted, since these parameters persist on the player across
        // items.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .apply {
                if (preferAudioLanguage) {
                    val wanted = preferredAudioLanguage()
                    // Both code forms, since sources tag the same language either way.
                    val iso3 = runCatching { java.util.Locale(wanted).isO3Language }.getOrNull().orEmpty()
                    if (iso3.isNotEmpty() && iso3 != wanted) setPreferredAudioLanguages(wanted, iso3)
                    else setPreferredAudioLanguages(wanted)
                } else {
                    setPreferredAudioLanguages()
                }
            }
            .build()

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
            // The user's chosen language wins; the sidecar's own tag is the fallback for a
            // source that only ships one subtitle track and doesn't tag it usefully.
            val preferred = subtitles.firstOrNull { it.isDefault } ?: subtitles.first()
            val wanted = preferredSubtitleLanguage()
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setPreferredTextLanguages(wanted, preferred.language ?: wanted)
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
        // Subtitles off still means "no wall of subtitles on an English film" - not "lose the
        // translation of the one Russian scene". Forced English tracks are exactly that
        // narrow case, so they're allowed back in.
        if (!subtitlesEnabled) {
            attachOneShotForcedSubtitlePreference(preferredSubtitleLanguage())
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
    /**
     * Turns on an English *forced* subtitle track when the media carries one, while everything
     * else stays off.
     *
     * Done as an explicit override rather than by loosening the track-selection parameters:
     * the selector's own forced-subtitle handling keys off the selected audio language and the
     * DEFAULT flag, which would also let a full default-flagged subtitle track back in - the
     * exact thing "subtitles off" is meant to stop. Deciding from the actual track list is
     * unambiguous.
     *
     * "Forced" is read from the selection flag, from the transcribes-dialog role (what our own
     * sideloaded configurations carry), or from the label, since plenty of sources only say it
     * in the name.
     */
    private fun attachOneShotForcedSubtitlePreference(language: String) {
        // Retire any previous forced-subtitle one-shot that never got to decide (dead stream /
        // endless buffering never deliver a usable onTracksChanged). Prevents per-playUrl
        // accumulation firing against a later play.
        oneShotForcedSubtitleListener?.let { player.removeListener(it) }
        // ISO 639-2 alongside the 639-1 code: sources tag the same language either way
        // ("en" or "eng"), and Media3 hands the format's tag through as it found it.
        val iso3 = runCatching { java.util.Locale(language).isO3Language }.getOrNull().orEmpty()
        val listener = object : Player.Listener {
            private var decided = false
            override fun onTracksChanged(tracks: Tracks) {
                if (decided) return
                val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
                // Still preparing - no track list to judge yet.
                if (tracks.groups.isEmpty()) return
                decided = true
                player.removeListener(this)
                if (oneShotForcedSubtitleListener === this) oneShotForcedSubtitleListener = null
                if (textGroups.isEmpty()) return
                for (group in textGroups) {
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        val tag = format.language?.lowercase()
                        val matchesLanguage = tag != null &&
                            (tag == language || tag.startsWith("$language-") || (iso3.isNotEmpty() && tag == iso3))
                        if (!matchesLanguage) continue
                        val label = format.label?.lowercase().orEmpty()
                        // ROLE_FLAG_TRANSCRIBES_DIALOG used to count as "forced" here. It does
                        // not mean forced: HLS sets it from CHARACTERISTICS
                        // "public.accessibility.transcribes-spoken-dialog", which is how an SDH
                        // rendition is tagged - a full transcript of every line, the exact
                        // opposite of a forced track. Any stream shipping SDH English therefore
                        // came up with subtitles burned across the whole film while the pref
                        // said off. The authored FORCED flag (which our own sideloads now carry
                        // too) and an explicit label are the only trustworthy signals.
                        val isForced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 ||
                            FORCED_LABEL_REGEX.containsMatchIn(label)
                        if (!isForced) continue
                        // A track can be labelled forced and still be the hearing-impaired one
                        // ("English (Forced, SDH)"); the describes-music-and-sound role is what
                        // says so. Without the authored FORCED flag to back the label up, trust
                        // the role and leave it alone.
                        val isSdh = (format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0 ||
                            SDH_LABEL_REGEX.containsMatchIn(label)
                        if (isSdh && (format.selectionFlags and C.SELECTION_FLAG_FORCED) == 0) continue
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(i)))
                            .build()
                        return
                    }
                }
            }

            // Terminal path: the stream died before tracks arrived, so this one-shot will never
            // decide. Remove it now rather than leaving it attached to fire on a later play.
            override fun onPlayerError(error: PlaybackException) {
                if (decided) return
                decided = true
                player.removeListener(this)
                if (oneShotForcedSubtitleListener === this) oneShotForcedSubtitleListener = null
            }
        }
        oneShotForcedSubtitleListener = listener
        player.addListener(listener)
        // Same as the audio preference: covers a source that fired onTracksChanged during
        // prepare(), before this listener existed.
        listener.onTracksChanged(player.currentTracks)
    }

    /** The user's audio language from Settings > General, defaulting to English. */
    private fun preferredAudioLanguage(): String =
        context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            .getString("audio_language", "en") ?: "en"

    /** The user's subtitle language from Settings > General, defaulting to English. */
    private fun preferredSubtitleLanguage(): String =
        context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            .getString("subtitle_language", "en") ?: "en"

    private fun attachOneShotAudioPreference(audio: String) {
        // Retire any previous audio one-shot that never got to decide (dead stream / endless
        // buffering never deliver a usable onTracksChanged). Prevents per-playUrl accumulation
        // firing against a later play.
        oneShotAudioListener?.let { player.removeListener(it) }
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
                if (oneShotAudioListener === this) oneShotAudioListener = null
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

            // Terminal path: the stream died before tracks arrived, so this one-shot will never
            // decide. Remove it now rather than leaving it attached to fire on a later play.
            override fun onPlayerError(error: PlaybackException) {
                if (decided) return
                decided = true
                player.removeListener(this)
                if (oneShotAudioListener === this) oneShotAudioListener = null
            }
        }
        oneShotAudioListener = listener
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

    /**
     * Render into a raw [android.view.Surface]. The Android Auto host hands its navigation
     * template's drawing surface over directly - there is no View to attach - so this is the
     * only way the car session can show a picture (see auto/CarPlayback.kt).
     */
    fun setVideoSurface(surface: android.view.Surface?) {
        if (surface == null) player.clearVideoSurface() else player.setVideoSurface(surface)
    }

    /** Toggle play/pause. */
    fun togglePlayPause() {
        if (released) return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun play() {
        if (released) return
        player.play()
    }

    fun pause() {
        if (released) return
        player.pause()
    }

    fun seekTo(positionMs: Long) {
        if (released) return
        player.seekTo(positionMs)
    }

    /** Seek forward/backward by a relative delta, clamped to [0, duration]. */
    fun seekBy(deltaMs: Long) {
        if (released) return
        val dur = player.duration
        if (dur <= 0) return
        val pos = player.currentPosition
        if (pos == C.TIME_UNSET || pos < 0) return
        val target = (pos + deltaMs).coerceIn(0L, dur)
        player.seekTo(target)
    }

    fun stop() {
        if (released) return
        player.stop()
    }

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

    /** Current A/V offset in milliseconds (positive delays audio, negative advances it). */
    fun getAvOffsetMs(): Int = (avOffsetUs / 1000L).toInt()

    /** Applies immediately - the renderer reads [avOffsetUs] on every output frame. */
    fun setAvOffsetMs(offsetMs: Int) {
        avOffsetUs = offsetMs.toLong() * 1000L
    }

    companion object {
        /** Pref key for the extra-buffering toggle, shared with the scraper settings screen. */
        const val PREF_EXTRA_BUFFERING = "player_extra_buffering"

        /**
         * 5 minutes, matching what the upstream scraper app used. Deliberately far above
         * Media3's 50s default: this exists for hosts that deliver in bursts, and a ceiling
         * that only covers one burst does not help.
         */
        private const val EXTRA_MAX_BUFFER_MS = 300_000

        fun isExtraBufferingEnabled(context: Context): Boolean =
            context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
                .getBoolean(PREF_EXTRA_BUFFERING, false)

        /** Track labels that say the track is forced, in the forms sources actually write it
         *  ("Forced", "English [Forced]", "en-forced"). Word-bounded so "unforced" and
         *  "reinforced" don't match. */
        private val FORCED_LABEL_REGEX = Regex("""\bforced\b""")

        /** Hearing-impaired labels, for tracks whose role flags don't say it: SDH and CC are a
         *  full transcript plus sound description, never a forced track. */
        private val SDH_LABEL_REGEX = Regex("""\b(sdh|cc|hearing[- ]impaired)\b""")
    }
}
