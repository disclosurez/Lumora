package com.lumora

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.*
import com.lumora.adapter.EpisodeAdapter
import com.lumora.cache.FavoritesStore
import com.lumora.cache.PlaybackPositionStore
import com.lumora.data.MediaServerStore
import com.lumora.model.Channel
import com.lumora.model.MediaServerConfig
import com.lumora.model.Provider
import com.lumora.model.ProviderType
import com.lumora.plugin.PluginSubtitle
import com.lumora.player.PlayerManager
import com.lumora.util.normalizeServerUrl
import com.lumora.util.qualifiedMediaItemId
import com.lumora.data.remote.jellyfin.JellyfinProvider
import kotlinx.coroutines.*
import okhttp3.Request

// ── Jellyfin: connect, user state, playback reporting & extras ──
//
// Extracted from MainActivity.kt; see that file's header.
/** One configured Jellyfin account's server URL, normalized. */
internal fun MainActivity.jellyfinServerUrl(cfg: MediaServerConfig): String? =
    cfg.url?.takeIf { it.isNotBlank() }?.let { normalizeServerUrl(it, defaultScheme = "https") }

/** The Jellyfin account a channel came from, or null when it isn't a Jellyfin item (or its
 *  account has since been removed). */
internal fun MainActivity.jellyfinConfigFor(channel: Channel): MediaServerConfig? =
    if (!channel.isJellyfin) null
    else MediaServerStore.get(prefs, channel.sourceProviderId)?.takeIf { it.isJellyfin }
        // A catalogue written before media servers became a list carries no source id; with
        // exactly one Jellyfin account configured there is no ambiguity about which it is,
        // so it still plays instead of failing until the next refresh re-stamps it.
        ?: jellyfinServers().singleOrNull()

/** toChannel() only reads serverUrl off a Provider - a minimal stand-in instead of the
 *  shared `provider` field, which now belongs solely to the IPTV slots. Passing that
 *  field here builds episode stream URLs against the *Xtream* host (or an empty one when
 *  Jellyfin is the only provider configured), which is unplayable. */
internal fun MainActivity.jellyfinProviderStub(url: String?): Provider =
    Provider(name = "Jellyfin", type = ProviderType.M3U, serverUrl = url)

/** Authenticates (or restores) a session for one configured Jellyfin account. Failure
 *  message is already user-facing. */
internal suspend fun MainActivity.connectJellyfin(cfg: MediaServerConfig): Result<JellyfinProvider> {
    val url = jellyfinServerUrl(cfg) ?: return Result.failure(Exception("Jellyfin: no server URL"))
    val jellyfin = JellyfinProvider(BaseApplication.instance.okHttpClient)
    if (!cfg.token.isNullOrBlank() && !cfg.userId.isNullOrBlank()) {
        // Quick Connect never yields a password to re-authenticate with later -
        // reuse the session it already gave us instead.
        jellyfin.restoreSession(url, cfg.token, cfg.userId)
    } else {
        val username = cfg.username ?: return Result.failure(Exception("Jellyfin: no username"))
        val password = cfg.password.orEmpty()
        val authResult = withContext(Dispatchers.IO) { jellyfin.authenticate(url, username, password) }
        if (authResult.isFailure) {
            return Result.failure(Exception("Jellyfin: ${authResult.exceptionOrNull()?.message?.take(60)}"))
        }
    }
    return Result.success(jellyfin)
}

/** The live session for one Jellyfin account, reconnecting on demand. A cold start that hits
 *  the channel cache returns from loadAllConfiguredProviders() before any Jellyfin fetch runs,
 *  so the client map is empty while Jellyfin series are already on screen - without this a
 *  series detail page silently showed "No episodes found" on every cached launch. */
internal suspend fun MainActivity.jellyfinClientOrConnect(cfg: MediaServerConfig?): JellyfinProvider? {
    if (cfg == null) return null
    jellyfinClients[cfg.id]?.let { return it }
    return connectJellyfin(cfg).getOrNull()?.also { jellyfinClients[cfg.id] = it }
}

/** The client for whichever Jellyfin account a channel belongs to. */
internal suspend fun MainActivity.jellyfinClientFor(channel: Channel): JellyfinProvider? =
    jellyfinClientOrConnect(jellyfinConfigFor(channel))

/** Kept alive post-load for fetching a Jellyfin series' episodes when its detail page
 *  opens - that has no Xtream equivalent path to fall back to. */
internal suspend fun MainActivity.fetchJellyfinChannels(cfg: MediaServerConfig): FetchResult {
    val url = jellyfinServerUrl(cfg) ?: return FetchResult.Failure("Jellyfin: no server URL")
    return try {
        val jellyfin = connectJellyfin(cfg).getOrElse {
            return FetchResult.Failure(it.message ?: "Jellyfin: auth failed")
        }
        val stub = jellyfinProviderStub(url)
        val items: List<Channel> = withContext(Dispatchers.IO) {
            // The three crawls run together rather than one after another. Each is a
            // paginated walk of a whole library (500 items a page, sequential within
            // itself), so serially they added up to the sum of all three - and the live
            // one is the unpredictable member of the set: /LiveTv/Channels on a server
            // whose tuner is unreachable can sit there long past the point the movie and
            // series libraries have both answered.
            //
            // Each gate is still checked before anything is started: an off type is not
            // crawled at all, and never was.
            coroutineScope {
                val liveDeferred = if (jellyfinAllowsLive(cfg)) async { jellyfin.getLiveTvChannels() } else null
                val moviesDeferred = if (jellyfinAllowsMovies(cfg)) async { jellyfin.getMovies() } else null
                val seriesDeferred = if (jellyfinAllowsSeries(cfg)) async { jellyfin.getSeries() } else null
                val liveItems = liveDeferred?.await().orEmpty()
                val movies = moviesDeferred?.await().orEmpty()
                val series = seriesDeferred?.await().orEmpty()
                // No-op on empty, so the gate check the old shape needed here is gone.
                importJellyfinUserState(movies + series, cfg)
                liveItems.map { JellyfinProvider.toChannel(it, stub, sourceId = cfg.id) } +
                    movies.map { JellyfinProvider.toChannel(it, stub, sourceId = cfg.id) } +
                    series.map { JellyfinProvider.toChannel(it, stub, sourceId = cfg.id) }
            }
        }
        jellyfinClients[cfg.id] = jellyfin
        refreshJellyfinRows(jellyfin, cfg, stub)
        FetchResult.Success(items)
    } catch (e: CancellationException) {
        // Not a fetch failure - the loader was cancelled (provider toggled, a newer load
        // started, the timeout fired). Swallowing it here would both report "Jellyfin: Job
        // was cancelled" as a provider error and leave this coroutine looking like it
        // completed normally while its parent is cancelled.
        throw e
    } catch (e: Exception) {
        FetchResult.Failure("Jellyfin: ${e.message?.take(60)}")
    }
}

// ── Jellyfin server-side state sync ────────────

/**
 * Pulls the server's per-user state (UserData) into the local stores, so watched marks
 * and resume points made in *any* Jellyfin client show up here. Without this the app
 * treated a personal media server like a plain catalogue: every title looked unwatched
 * no matter what had already been seen elsewhere.
 *
 * Local progress is only overwritten when the server is *ahead* (or when nothing local
 * exists). A resume point written here and not yet reported - the app was offline, or the
 * report failed - is still the more recent truth, and clobbering it would rewind the
 * user to where the server last heard about.
 */

internal fun MainActivity.importJellyfinUserState(
    items: List<JellyfinProvider.JellyfinItem>,
    cfg: MediaServerConfig,
    includePlayed: Boolean = false
) {
    val stub = jellyfinProviderStub(jellyfinServerUrl(cfg))
    for (item in items) {
        if (item.mediaType == "Series") {
            // Favourites are reconciled to the server both ways for Jellyfin items:
            // un-favouriting on the server has to be able to clear the local star too,
            // which a toggle-only import could never do. Keyed by the *qualified* id, the
            // same one the catalogue's channels carry.
            FavoritesStore.setFavoriteSeries(this, qualifiedMediaItemId(cfg.id, item.id), item.favorite)
            continue
        }
        val runtime = item.runtimeMs ?: continue
        when {
            // Watched, not cleared: a full-duration entry is exactly what EpisodeAdapter
            // reads as "watched" (PlaybackPosition.isNearComplete) to dim the row and show
            // its badge, and getAllInProgress excludes it from Continue Watching for the
            // same reason. Clearing it instead would leave a watched episode looking
            // untouched.
            //
            // Only imported where something actually renders it (an episode list), because
            // the position store holds 500 entries and evicts the oldest: seeding a watched
            // mark for every film in a large library would push out the resume points that
            // Continue Watching is built from, to show a badge nothing displays.
            item.played && includePlayed -> PlaybackPositionStore.save(
                this,
                qualifiedMediaItemId(cfg.id, item.id),
                runtime,
                runtime,
                JellyfinProvider.toChannel(item, stub, prefixSeriesName = true, sourceId = cfg.id)
            )
            item.resumePositionMs > 0 -> {
                val key = qualifiedMediaItemId(cfg.id, item.id)
                val local = PlaybackPositionStore.get(this, key)
                if (local == null || item.resumePositionMs > local.positionMs) {
                    PlaybackPositionStore.save(
                        this,
                        key,
                        item.resumePositionMs,
                        runtime,
                        JellyfinProvider.toChannel(item, stub, prefixSeriesName = true, sourceId = cfg.id)
                    )
                }
            }
        }
    }
}

/** The server's own Resume and Next Up lists, for the Home rows. Also seeds resume
 *  positions for the items in them - these are the partly-watched titles, so they carry
 *  the positions worth having even when the catalog fetch didn't include them. */
internal suspend fun MainActivity.refreshJellyfinRows(
    jellyfin: JellyfinProvider,
    cfg: MediaServerConfig,
    stub: Provider
) {
    // `a to b` builds the pair by evaluating a then b - two serial round trips, for two
    // independent endpoints. Run together instead.
    val (resume, nextUp) = withContext(Dispatchers.IO) {
        coroutineScope {
            val resumeDeferred = async { jellyfin.getResumeItems() }
            val nextUpDeferred = async { jellyfin.getNextUp() }
            resumeDeferred.await() to nextUpDeferred.await()
        }
    }
    importJellyfinUserState(resume, cfg)
    jellyfinResumeByServer[cfg.id] = resume.map {
        JellyfinProvider.toChannel(it, stub, prefixSeriesName = true, sourceId = cfg.id)
    }
    jellyfinNextUpByServer[cfg.id] = nextUp.map {
        JellyfinProvider.toChannel(it, stub, prefixSeriesName = true, sourceId = cfg.id)
    }
}

// ── Jellyfin playback reporting ────────────────

/** Media3 mime type for a Jellyfin subtitle codec, or null for the image-based formats
 *  (PGS/VOBSUB) that have no Media3 renderer - those are left to the server to burn in,
 *  never sideloaded as a track that would silently render nothing. */
internal fun MainActivity.subtitleMimeType(codec: String?): String? = when (codec?.lowercase()) {
    "vtt", "webvtt" -> androidx.media3.common.MimeTypes.TEXT_VTT
    "srt", "subrip" -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
    "ass", "ssa" -> androidx.media3.common.MimeTypes.TEXT_SSA
    "ttml" -> androidx.media3.common.MimeTypes.APPLICATION_TTML
    // The server hands extracted text tracks over as WebVTT regardless of their original
    // codec, so anything else that came back with a URL is treated as VTT.
    else -> androidx.media3.common.MimeTypes.TEXT_VTT
}

internal fun MainActivity.externalSubtitlesFor(resolved: JellyfinProvider.ResolvedStream): List<PlayerManager.ExternalSubtitle> =
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

/** A plugin's sidecar subtitle URL carries no codec metadata the way a Jellyfin MediaStream
 *  does, so the format is taken from the file extension (query string stripped - these URLs
 *  are often signed). WebVTT is the fallback: it's what every source seen so far serves. */
internal fun MainActivity.externalSubtitleFor(subtitle: PluginSubtitle): PlayerManager.ExternalSubtitle {
    val path = subtitle.url.substringBefore('?').substringBefore('#')
    val mime = when {
        path.endsWith(".srt", ignoreCase = true) -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
        path.endsWith(".ass", ignoreCase = true) ||
            path.endsWith(".ssa", ignoreCase = true) -> androidx.media3.common.MimeTypes.TEXT_SSA
        path.endsWith(".ttml", ignoreCase = true) ||
            path.endsWith(".xml", ignoreCase = true) -> androidx.media3.common.MimeTypes.APPLICATION_TTML
        else -> androidx.media3.common.MimeTypes.TEXT_VTT
    }
    return PlayerManager.ExternalSubtitle(
        uri = subtitle.url,
        mimeType = mime,
        language = subtitle.language,
        label = subtitle.label ?: subtitle.language,
        isDefault = subtitle.isDefault
    )
}

/** How an audio track reads in the picker: the server's own DisplayTitle when it has one
 *  ("English - Dolby Digital - 5.1 - Default"), otherwise the language spelled out with the
 *  codec/channel detail that distinguishes two tracks in the same language. */
internal fun jellyfinAudioLabel(stream: JellyfinProvider.AudioStream): String {
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

/** Rebuilds the stream around a different audio track of the same item and picks playback
 *  up where it was. Needed because a transcoded source only carries the one track the
 *  server chose - the others are in the file but never reach the player, so there is
 *  nothing for a Media3 track override to select. */
internal fun MainActivity.switchJellyfinAudioStream(streamIndex: Int) {
    val itemId = jellyfinPlayingItemId ?: return
    val channel = nowPlayingChannel ?: return
    val resumeMs = playerManager.currentPosition.coerceAtLeast(0L)
    binding.bufferingSpinner.visibility = View.VISIBLE
    scope.launch {
        val client = jellyfinClientFor(channel)
        val resolved = if (client == null) null else withContext(Dispatchers.IO) {
            runCatching {
                client.resolveStream(itemId, resumeMs, forceAudioStreamIndex = streamIndex)
            }.getOrNull()
        }
        // Playback moved on while the server was negotiating - this answer is for a title
        // that is no longer on screen.
        if (nowPlayingChannel?.id != channel.id) return@launch
        if (resolved == null) {
            binding.bufferingSpinner.visibility = View.GONE
            Toast.makeText(this@switchJellyfinAudioStream, getString(R.string.plug_couldnt_switch_audio), Toast.LENGTH_SHORT).show()
            return@launch
        }
        playerManager.playUrl(
            resolved.url,
            channel.streamUserAgent,
            subtitles = externalSubtitlesFor(resolved),
            startPositionMs = resumeMs,
            // The track was chosen by hand; re-applying the language preference on top of
            // it would only fight the pick.
            preferAudioLanguage = false
        )
        jellyfinPlaySession = resolved
        reportJellyfinStart(itemId, resolved, resumeMs)
    }
}

/** The client for the Jellyfin account whose item is playing, if any. Playback reports must
 *  reach that server specifically - with several accounts configured, any other one would
 *  either 404 on the item id or attribute the play to the wrong library. */
internal fun MainActivity.jellyfinPlayingClient(): JellyfinProvider? =
    jellyfinPlayingServerId?.let { jellyfinClients[it] }

/** Reports a Jellyfin play as started, so the server opens a session for it (and knows
 *  not to reap the transcode it just set up). */
internal fun MainActivity.reportJellyfinStart(itemId: String, resolved: JellyfinProvider.ResolvedStream?, positionMs: Long) {
    val client = jellyfinPlayingClient() ?: return
    scope.launch(Dispatchers.IO) {
        runCatching {
            client.reportPlaybackStart(
                itemId,
                resolved?.playSessionId,
                positionMs,
                resolved?.playMethod ?: "DirectPlay"
            )
        }
    }
}

/** Progress heartbeat for the Jellyfin item playing, if any. Called off the same 1s
 *  progress tick the local position save uses, throttled to ~10s. */
internal fun MainActivity.reportJellyfinProgress() {
    val itemId = jellyfinPlayingItemId ?: return
    val client = jellyfinPlayingClient() ?: return
    val position = playerManager.currentPosition.takeIf { it >= 0 } ?: return
    val paused = !playerManager.isPlaying
    val session = jellyfinPlaySession
    scope.launch(Dispatchers.IO) {
        runCatching {
            client.reportPlaybackProgress(itemId, session?.playSessionId, position, paused, session?.playMethod ?: "DirectPlay")
        }
    }
}

/** End of a Jellyfin play. The position reported here is what the server turns into a
 *  watched mark or a resume point, so this runs before the player state is torn down. */
internal fun MainActivity.reportJellyfinStopped(): Boolean {
    val itemId = jellyfinPlayingItemId ?: return false
    val client = jellyfinPlayingClient()
    val session = jellyfinPlaySession
    val position = playerManager.currentPosition.takeIf { it >= 0 } ?: 0L
    jellyfinPlayingItemId = null
    jellyfinPlaySession = null
    playbackChapters = emptyList()
    jellyfinTrickplay = null
    trickplayTileCache = null
    trickplayLoadJob?.cancel()
    if (client == null) return false
    scope.launch(Dispatchers.IO) {
        runCatching { client.reportPlaybackStopped(itemId, session?.playSessionId, position) }
    }
    return true
}

/** Re-pulls Resume/Next Up after a Jellyfin play ends, so finishing an episode advances
 *  the Next Up row instead of leaving it stale until the next catalog reload. Waits a
 *  beat first - the lists are derived from the stop we just reported, and asking before
 *  the server has recorded it hands back the pre-play state. */
internal fun MainActivity.refreshJellyfinRowsAfterPlayback() {
    // Read now, not inside the coroutine: reportJellyfinStopped() has already run, and
    // starting another title resets the playing-server field while this is still waiting out
    // its delay - which would refresh the wrong account's rows, or none at all.
    val serverId = jellyfinPlayingServerId ?: return
    val cfg = MediaServerStore.get(prefs, serverId) ?: return
    val client = jellyfinClients[serverId] ?: return
    scope.launch {
        delay(1500)
        val stub = jellyfinProviderStub(jellyfinServerUrl(cfg))
        runCatching { refreshJellyfinRows(client, cfg, stub) }
        if (showingHome) homeShelfAdapter.submitList(buildHomeShelves())
    }
}

/** Chapter markers and trickplay tiles for the Jellyfin item now playing - both are
 *  per-item and only ever needed for the one title on screen, so they're fetched at play
 *  time rather than carried on every catalog entry. */
internal fun MainActivity.loadJellyfinPlaybackExtras(itemId: String) {
    val client = jellyfinPlayingClient() ?: return
    scope.launch {
        val (chapters, trickplay) = withContext(Dispatchers.IO) {
            runCatching { client.getChapters(itemId) }.getOrDefault(emptyList()) to
                runCatching { client.getTrickplay(itemId) }.getOrNull()
        }
        if (jellyfinPlayingItemId != itemId) return@launch
        playbackChapters = chapters.map {
            com.lumora.model.MediaChapter(it.name, it.positionMs, it.imageUrl)
        }
        jellyfinTrickplay = trickplay
        updateChaptersButtonVisibility()
    }
}

internal fun MainActivity.updateChaptersButtonVisibility() {
    binding.btnChapters.visibility = if (playbackChapters.size > 1) View.VISIBLE else View.GONE
    // Row's focus chain must skip it while it's hidden - see relinkPlayerButtonRowFocus.
    relinkPlayerButtonRowFocus()
}

/** Chapter picker - jumps straight to a chapter's start. Only reachable when the item
 *  actually has chapters (see updateChaptersButtonVisibility). */
internal fun MainActivity.showChapterPicker() {
    val chapters = playbackChapters
    if (chapters.isEmpty()) return
    val position = playerManager.currentPosition
    val currentIdx = chapters.indexOfLast { it.positionMs <= position }.coerceAtLeast(0)
    val labels = chapters.mapIndexed { index, chapter ->
        val marker = if (index == currentIdx) "▶ " else ""
        "$marker${chapter.name}  ·  ${formatTime(chapter.positionMs)}"
    }.toTypedArray()
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.chapters))
        .setItems(labels) { _, which ->
            playerManager.seekTo(chapters[which].positionMs)
            showControls()
        }
        .show()
}

/** Seek-preview thumbnail from the trickplay sprite sheets, shown while scrubbing a
 *  Jellyfin item. Sheets are downloaded whole and cropped locally - one sheet covers
 *  ~100 thumbnails, so scrubbing within it costs nothing after the first fetch. */
internal fun MainActivity.showTrickplayPreview(targetMs: Long) {
    val info = jellyfinTrickplay ?: return
    val itemId = jellyfinPlayingItemId ?: return
    val client = jellyfinPlayingClient() ?: return
    val thumbIndex = (targetMs / info.intervalMs).toInt().coerceAtLeast(0)
    if (info.thumbnailCount > 0 && thumbIndex >= info.thumbnailCount) return
    val tileIndex = thumbIndex / info.perTile
    val withinTile = thumbIndex % info.perTile

    fun render(sheet: android.graphics.Bitmap) {
        val cellWidth = sheet.width / info.tileWidth.coerceAtLeast(1)
        val cellHeight = sheet.height / info.tileHeight.coerceAtLeast(1)
        if (cellWidth <= 0 || cellHeight <= 0) return
        val col = withinTile % info.tileWidth.coerceAtLeast(1)
        val row = withinTile / info.tileWidth.coerceAtLeast(1)
        val x = col * cellWidth
        val y = row * cellHeight
        if (x + cellWidth > sheet.width || y + cellHeight > sheet.height) return
        val crop = runCatching {
            android.graphics.Bitmap.createBitmap(sheet, x, y, cellWidth, cellHeight)
        }.getOrNull() ?: return
        binding.trickplayPreview.setImageBitmap(crop)
        binding.trickplayPreview.visibility = View.VISIBLE
    }

    trickplayTileCache?.takeIf { it.first == tileIndex }?.let { render(it.second); return }

    trickplayLoadJob?.cancel()
    trickplayLoadJob = scope.launch {
        val url = client.trickplayTileUrl(itemId, info, tileIndex) ?: return@launch
        val sheet = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).build()
                BaseApplication.instance.okHttpClient.newCall(request).execute()
                    .body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        } ?: return@launch
        if (jellyfinPlayingItemId != itemId) return@launch
        trickplayTileCache = tileIndex to sheet
        render(sheet)
    }
}

internal fun MainActivity.hideTrickplayPreview() {
    trickplayLoadJob?.cancel()
    binding.trickplayPreview.visibility = View.GONE
    binding.trickplayPreview.setImageDrawable(null)
}
