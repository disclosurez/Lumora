package com.lumora

import android.widget.Toast
import android.view.View
import com.lumora.cache.PlaybackPositionStore
import com.lumora.model.Channel
import com.lumora.model.MediaChapter
import com.lumora.model.Provider
import com.lumora.model.ProviderType
import com.lumora.player.PlayerManager
import com.lumora.data.remote.plex.PlexProvider
import kotlinx.coroutines.*
import java.util.UUID

// ── Plex: connect, user state, playback reporting ──
//
// Sibling of MainActivityJellyfin.kt - the two media-server slots are independent and behave
// the same way from the rest of the app's point of view. Differences worth knowing before
// editing this file, all of them inherent to Plex rather than choices made here:
//
//  - **Sign-in is an account flow.** There is no username/password endpoint on a Plex server;
//    the user signs in to plex.tv (PIN + QR on the TV), and the account hands back the list
//    of servers it can reach. What gets stored is a per-server token plus the endpoint that
//    was found to actually answer from this network.
//  - **No Live TV.** Plex Live TV is a tuner-session flow that does not fit Lumora's
//    URL-per-channel live model, so the Plex slot offers Movies and Series only.
//  - **Favourites stay local.** Plex has no per-item favourite flag (it has ratings), so
//    unlike Jellyfin there is nothing to push a star to.

/** The connected Plex server's base URL, or null when the slot is empty. Stored already
 *  normalized: it is whichever of the server's published endpoints answered during sign-in
 *  (see PlexProvider.pickConnection), not something the user typed. */
internal fun MainActivity.plexServerUrl(): String? =
    prefs.getString("plex_url", null)?.takeIf { it.isNotBlank() }

internal fun MainActivity.plexToken(): String? =
    prefs.getString("plex_token", null)?.takeIf { it.isNotBlank() }

/**
 * This install's Plex client identifier, minted once and kept.
 *
 * It has to be stable across the whole sign-in (the PIN is issued against it, and the
 * account's server list is scoped to it) and across restarts (it is what makes the TV one
 * entry in the user's Plex device list rather than a new one every launch).
 */
internal fun MainActivity.plexClientIdentifier(): String {
    prefs.getString("plex_client_id", null)?.takeIf { it.isNotBlank() }?.let { return it }
    val id = UUID.randomUUID().toString()
    prefs.edit().putString("plex_client_id", id).apply()
    return id
}

internal fun MainActivity.newPlexProvider(): PlexProvider =
    PlexProvider(BaseApplication.instance.okHttpClient, plexClientIdentifier())

/** toChannel() only reads serverUrl off a Provider - a minimal stand-in instead of the
 *  shared `provider` field, which belongs solely to the IPTV slots. Passing that field here
 *  would build Plex item URLs against an Xtream host. */
internal fun MainActivity.plexProviderStub(url: String?): Provider =
    Provider(name = "Plex", type = ProviderType.M3U, serverUrl = url)

/** Binds a client to the stored server + token. No network call beyond the one
 *  [PlexProvider.connect] makes to confirm the session still works. */
internal suspend fun MainActivity.connectPlex(url: String): Result<PlexProvider> {
    val token = plexToken() ?: return Result.failure(Exception("Plex: not signed in"))
    val plex = newPlexProvider()
    val result = withContext(Dispatchers.IO) { plex.connect(url, token) }
    return if (result.isSuccess) Result.success(plex)
    else Result.failure(Exception("Plex: ${result.exceptionOrNull()?.message?.take(60)}"))
}

/** The live Plex session, reconnecting on demand. A cold start that hits the channel cache
 *  returns from loadAllConfiguredProviders() before any Plex fetch runs, so plexClient is
 *  null while Plex series are already on screen - without this a series detail page would
 *  show "No episodes found" on every cached launch. */
internal suspend fun MainActivity.plexClientOrConnect(): PlexProvider? {
    plexClient?.let { return it }
    val url = plexServerUrl() ?: return null
    return connectPlex(url).getOrNull()?.also { plexClient = it }
}

/** Kept alive post-load for fetching a Plex series' episodes when its detail page opens -
 *  that has no IPTV equivalent path to fall back to. */
internal suspend fun MainActivity.fetchPlexChannels(): FetchResult {
    val url = plexServerUrl() ?: return FetchResult.Failure("Plex: no server")
    return try {
        val plex = connectPlex(url).getOrElse {
            return FetchResult.Failure(it.message ?: "Plex: couldn't connect")
        }
        val stub = plexProviderStub(url)
        val items: List<Channel> = withContext(Dispatchers.IO) {
            // Both crawls run together rather than one after the other: each is a paginated
            // walk of a whole library, so serially they cost the sum of the two. Each gate is
            // still checked before anything is started - an off type is never crawled.
            coroutineScope {
                val moviesDeferred = if (plexAllowsMovies()) async { plex.getMovies() } else null
                val seriesDeferred = if (plexAllowsSeries()) async { plex.getSeries() } else null
                val movies = moviesDeferred?.await().orEmpty()
                val series = seriesDeferred?.await().orEmpty()
                importPlexUserState(movies + series)
                movies.map { PlexProvider.toChannel(it, stub) } +
                    series.map { PlexProvider.toChannel(it, stub) }
            }
        }
        plexClient = plex
        refreshPlexRows(plex, stub)
        FetchResult.Success(items)
    } catch (e: CancellationException) {
        // Not a fetch failure - the loader was cancelled (provider toggled, a newer load
        // started, the timeout fired). Swallowing it here would report "Plex: Job was
        // cancelled" as a provider error and leave this coroutine looking like it completed
        // normally while its parent is cancelled.
        throw e
    } catch (e: Exception) {
        FetchResult.Failure("Plex: ${e.message?.take(60)}")
    }
}

// ── Plex server-side state sync ────────────────

/**
 * Pulls the server's per-user watch state into the local stores, so resume points and
 * watched marks made in *any* Plex client show up here. Same contract as
 * [importJellyfinUserState], including the rule that local progress is only overwritten when
 * the server is ahead: a resume point written here and not yet reported (offline, or the
 * report failed) is still the more recent truth, and clobbering it would rewind the user to
 * where the server last heard about.
 */
internal fun MainActivity.importPlexUserState(
    items: List<PlexProvider.PlexItem>,
    includePlayed: Boolean = false
) {
    val stub = plexProviderStub(plexServerUrl())
    for (item in items) {
        // Plex has no favourite flag, so unlike the Jellyfin import there is nothing to
        // reconcile for a series - a show carries no per-user state worth importing.
        if (item.mediaType == "Series") continue
        val runtime = item.runtimeMs ?: continue
        when {
            // A full-duration entry is exactly what EpisodeAdapter reads as "watched"
            // (PlaybackPosition.isNearComplete) to dim the row and badge it, and what
            // getAllInProgress excludes from Continue Watching. Clearing it instead would
            // leave a watched episode looking untouched.
            //
            // Only imported where something renders it (an episode list): the position store
            // holds 500 entries and evicts the oldest, so seeding a watched mark for every
            // film in a large library would push out the resume points Continue Watching is
            // built from, to show a badge nothing displays.
            item.played && includePlayed -> PlaybackPositionStore.save(
                this,
                item.id,
                runtime,
                runtime,
                PlexProvider.toChannel(item, stub, prefixSeriesName = true)
            )
            item.resumePositionMs > 0 -> {
                val local = PlaybackPositionStore.get(this, item.id)
                if (local == null || item.resumePositionMs > local.positionMs) {
                    PlaybackPositionStore.save(
                        this,
                        item.id,
                        item.resumePositionMs,
                        runtime,
                        PlexProvider.toChannel(item, stub, prefixSeriesName = true)
                    )
                }
            }
        }
    }
}

/** The server's own On Deck, split into the Resume and Next Up halves the Home rows expect
 *  (see PlexProvider.getResumeItems). Also seeds resume positions for the items in them -
 *  these are the partly-watched titles, so they carry the positions worth having even when
 *  the catalog crawl didn't include them. */
internal suspend fun MainActivity.refreshPlexRows(plex: PlexProvider, stub: Provider) {
    // One On Deck fetch feeding both halves - they are two views of the same list, so asking
    // twice would be two round trips for one answer.
    val (resume, nextUp) = withContext(Dispatchers.IO) {
        plex.getResumeItems() to plex.getNextUp()
    }
    importPlexUserState(resume)
    plexResumeItems = resume.map { PlexProvider.toChannel(it, stub, prefixSeriesName = true) }
    plexNextUpItems = nextUp.map { PlexProvider.toChannel(it, stub, prefixSeriesName = true) }
}

// ── Plex playback ──────────────────────────────

internal fun MainActivity.externalSubtitlesForPlex(resolved: PlexProvider.ResolvedStream): List<PlayerManager.ExternalSubtitle> =
    resolved.subtitles.mapNotNull { stream ->
        val url = stream.url ?: return@mapNotNull null
        val mime = subtitleMimeType(stream.codec) ?: return@mapNotNull null
        PlayerManager.ExternalSubtitle(
            uri = url,
            mimeType = mime,
            language = stream.language,
            label = stream.title ?: stream.language,
            isDefault = stream.isDefault,
            isForced = stream.isForced
        )
    }

/** How a Plex audio track reads in the picker: the server's own displayTitle when it has one
 *  ("English (AC3 5.1)"), otherwise the language spelled out with the codec/channel detail
 *  that distinguishes two tracks in the same language. */
internal fun plexAudioLabel(stream: PlexProvider.AudioStream): String {
    stream.title?.takeIf { it.isNotBlank() }?.let { return it }
    val language = stream.language?.takeIf { it.isNotBlank() }?.let { tag ->
        runCatching { java.util.Locale.forLanguageTag(tag).displayLanguage }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: tag
    } ?: "Unknown"
    val detail = listOfNotNull(
        stream.codec?.uppercase(),
        stream.channels?.let { if (it == 6) "5.1" else if (it == 8) "7.1" else "${it}ch" }
    ).joinToString(" · ")
    return if (detail.isBlank()) language else "$language  ·  $detail"
}

/** The playable URL for a Plex channel when negotiation didn't produce one. The catalogue
 *  stores the part path without a token (it is written to an on-disk cache), so the token has
 *  to go on here - Media3 fetches through its own HTTP stack and never sees the app's
 *  interceptor. */
internal fun MainActivity.plexFallbackUrl(channel: Channel): String? {
    if (channel.url.isBlank()) return null
    val token = plexToken() ?: return null
    val separator = if (channel.url.contains('?')) "&" else "?"
    return "${channel.url}${separator}X-Plex-Token=$token"
}

/** Rebuilds the stream around a different audio track of the same item and picks playback up
 *  where it was. Needed because a transcoded source only carries the one track the server
 *  chose - the others are in the file but never reach the player, so there is nothing for a
 *  Media3 track override to select. */
internal fun MainActivity.switchPlexAudioStream(streamId: Long) {
    val itemId = plexPlayingItemId ?: return
    val channel = nowPlayingChannel ?: return
    val resumeMs = playerManager.currentPosition.coerceAtLeast(0L)
    binding.bufferingSpinner.visibility = View.VISIBLE
    scope.launch {
        val client = plexClientOrConnect()
        val resolved = if (client == null) null else withContext(Dispatchers.IO) {
            runCatching {
                client.resolveStream(itemId, resumeMs, forceAudioStreamId = streamId)
            }.getOrNull()
        }
        // Playback moved on while the server was negotiating - this answer is for a title
        // that is no longer on screen.
        if (nowPlayingChannel?.id != channel.id) return@launch
        if (resolved == null) {
            binding.bufferingSpinner.visibility = View.GONE
            Toast.makeText(this@switchPlexAudioStream, getString(R.string.plug_couldnt_switch_audio), Toast.LENGTH_SHORT).show()
            return@launch
        }
        playerManager.playUrl(
            resolved.url,
            channel.streamUserAgent,
            subtitles = externalSubtitlesForPlex(resolved),
            startPositionMs = resumeMs,
            // The track was chosen by hand; re-applying the language preference on top of it
            // would only fight the pick.
            preferAudioLanguage = false,
            maintainTokenQuery = resolved.tokenQuery
        )
        plexPlaySession = resolved
        reportPlexStart(itemId, resolved, resumeMs)
    }
}

/** Reports a Plex play as started, so the server opens a session for it (and knows not to
 *  reap the transcode it just set up). */
internal fun MainActivity.reportPlexStart(itemId: String, resolved: PlexProvider.ResolvedStream?, positionMs: Long) {
    val client = plexClient ?: return
    plexPlayingDurationMs = resolved?.runtimeMs ?: plexPlayingDurationMs
    val duration = plexPlayingDurationMs
    scope.launch(Dispatchers.IO) {
        runCatching { client.reportPlaybackStart(itemId, resolved?.playSessionId, positionMs, duration) }
    }
}

/** Progress heartbeat for the Plex item playing, if any. Called off the same 1s progress tick
 *  the local position save uses, throttled to ~10s. Paused reports carry a transcode keepalive
 *  ping alongside them: a timeline update on its own has historically not stopped the server
 *  reaping an idle transcoder, and a reaped session ends playback on resume. */
internal fun MainActivity.reportPlexProgress() {
    val itemId = plexPlayingItemId ?: return
    val client = plexClient ?: return
    val position = playerManager.currentPosition.takeIf { it >= 0 } ?: return
    val paused = !playerManager.isPlaying
    val session = plexPlaySession
    val duration = plexPlayingDurationMs ?: playerManager.duration.takeIf { it > 0 }
    scope.launch(Dispatchers.IO) {
        runCatching {
            client.reportPlaybackProgress(itemId, session?.playSessionId, position, paused, duration)
            if (paused) session?.transcodeSessionId?.let { client.pingTranscodeSession(it) }
        }
    }
}

/** End of a Plex play. The position reported here is what the server turns into a resume
 *  point, and what decides whether the title gets scrobbled as watched, so this runs before
 *  the player state is torn down. */
internal fun MainActivity.reportPlexStopped(): Boolean {
    val itemId = plexPlayingItemId ?: return false
    val client = plexClient
    val session = plexPlaySession
    val position = playerManager.currentPosition.takeIf { it >= 0 } ?: 0L
    val duration = plexPlayingDurationMs ?: playerManager.duration.takeIf { it > 0 }
    plexPlayingItemId = null
    plexPlaySession = null
    plexPlayingDurationMs = null
    // The chapter list is shared with the Jellyfin path, and only whichever server was
    // playing clears it - so this has to, or a Plex-only session leaves the chapter button
    // pointing into a film that is no longer on screen.
    playbackChapters = emptyList()
    updateChaptersButtonVisibility()
    if (client == null) return false
    scope.launch(Dispatchers.IO) {
        runCatching { client.reportPlaybackStopped(itemId, session?.playSessionId, position, duration) }
    }
    return true
}

/** Re-pulls On Deck after a Plex play ends, so finishing an episode advances the Next Up row
 *  instead of leaving it stale until the next catalog reload. Waits a beat first - the list is
 *  derived from the stop just reported, and asking before the server has recorded it hands
 *  back the pre-play state. */
internal fun MainActivity.refreshPlexRowsAfterPlayback() {
    val client = plexClient ?: return
    scope.launch {
        delay(1500)
        val stub = plexProviderStub(plexServerUrl())
        runCatching { refreshPlexRows(client, stub) }
        if (showingHome) homeShelfAdapter.submitList(buildHomeShelves())
    }
}

/** Chapter markers for the Plex item now playing. Per-item and only ever needed for the one
 *  title on screen, so fetched at play time rather than carried on every catalog entry. */
internal fun MainActivity.loadPlexPlaybackExtras(itemId: String) {
    val client = plexClient ?: return
    scope.launch {
        val chapters = withContext(Dispatchers.IO) {
            runCatching { client.getChapters(itemId) }.getOrDefault(emptyList())
        }
        if (plexPlayingItemId != itemId) return@launch
        playbackChapters = chapters.map { MediaChapter(it.name, it.positionMs, it.imageUrl) }
        updateChaptersButtonVisibility()
    }
}

/** One-shot direct-play recovery, matching the Jellyfin path: a source error (server read
 *  timeout, a part that moved after an on-disk change) is usually transient, so re-negotiate
 *  once from the current position before surfacing a playback error. */
internal fun MainActivity.retryPlexPlayback() {
    val channel = nowPlayingChannel ?: return
    if (!channel.isPlex || channel.id.isBlank()) return
    plexRetryAttempted = true
    val resumeMs = playerManager.currentPosition.coerceAtLeast(0L)
    binding.bufferingSpinner.visibility = View.VISIBLE
    scope.launch {
        val plex = plexClientOrConnect()
        val resolved = if (plex == null) null else withContext(Dispatchers.IO) {
            runCatching {
                plex.resolveStream(
                    channel.id,
                    resumeMs,
                    preferredAudioLanguage = prefs.getString(PREF_AUDIO_LANGUAGE, "en") ?: "en"
                )
            }.getOrNull()
        }
        if (nowPlayingChannel?.id != channel.id) return@launch
        val url = resolved?.url ?: plexFallbackUrl(channel)
        if (url == null) {
            binding.bufferingSpinner.visibility = View.GONE
            return@launch
        }
        playerManager.playUrl(
            url,
            channel.streamUserAgent,
            subtitles = resolved?.let(::externalSubtitlesForPlex) ?: emptyList(),
            startPositionMs = resumeMs,
            preferAudioLanguage = true,
            maintainTokenQuery = resolved?.tokenQuery
        )
        plexPlaySession = resolved
        plexPlayingItemId = channel.id
        plexPlayingDurationMs = resolved?.runtimeMs
        reportPlexStart(channel.id, resolved, resumeMs)
    }
}
