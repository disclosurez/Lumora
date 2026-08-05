package com.lumora

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.*
import com.lumora.adapter.EpisodeAdapter
import com.lumora.cache.FavoritesStore
import com.lumora.cache.PlaybackPositionStore
import com.lumora.model.Channel
import com.lumora.model.Provider
import com.lumora.model.ProviderType
import com.lumora.plugin.PluginSubtitle
import com.lumora.player.PlayerManager
import com.lumora.util.normalizeServerUrl
import com.lumora.data.remote.jellyfin.JellyfinProvider
import kotlinx.coroutines.*
import okhttp3.Request

// ── Jellyfin: connect, user state, playback reporting & extras ──
//
// Extracted from MainActivity.kt; see that file's header.
/** The configured Jellyfin server URL, normalized, or null when the slot is empty. */
internal fun MainActivity.jellyfinServerUrl(): String? =
    prefs.getString("jellyfin_url", null)?.takeIf { it.isNotBlank() }
        ?.let { normalizeServerUrl(it, defaultScheme = "https") }

/** toChannel() only reads serverUrl off a Provider - a minimal stand-in instead of the
 *  shared `provider` field, which now belongs solely to the IPTV slots. Passing that
 *  field here builds episode stream URLs against the *Xtream* host (or an empty one when
 *  Jellyfin is the only provider configured), which is unplayable. */
internal fun MainActivity.jellyfinProviderStub(url: String?): Provider =
    Provider(name = "Jellyfin", type = ProviderType.M3U, serverUrl = url)

/** Authenticates (or restores) a Jellyfin session against [url]. Failure message is
 *  already user-facing. */
internal suspend fun MainActivity.connectJellyfin(url: String): Result<JellyfinProvider> {
    val jellyfin = JellyfinProvider(BaseApplication.instance.okHttpClient)
    val savedToken = prefs.getString("jellyfin_token", null)
    val savedUserId = prefs.getString("jellyfin_userid", null)
    if (!savedToken.isNullOrBlank() && !savedUserId.isNullOrBlank()) {
        // Quick Connect never yields a password to re-authenticate with later -
        // reuse the session it already gave us instead.
        jellyfin.restoreSession(url, savedToken, savedUserId)
    } else {
        val username = prefs.getString("jellyfin_user", null)
            ?: return Result.failure(Exception("Jellyfin: no username"))
        val password = prefs.getString("jellyfin_pass", null).orEmpty()
        val authResult = withContext(Dispatchers.IO) { jellyfin.authenticate(url, username, password) }
        if (authResult.isFailure) {
            return Result.failure(Exception("Jellyfin: ${authResult.exceptionOrNull()?.message?.take(60)}"))
        }
    }
    return Result.success(jellyfin)
}

/** The live Jellyfin session, reconnecting on demand. A cold start that hits the channel
 *  cache returns from loadAllConfiguredProviders() before any Jellyfin fetch runs, so
 *  jellyfinClient is null while Jellyfin series are already on screen - without this a
 *  series detail page silently showed "No episodes found" on every cached launch. */
internal suspend fun MainActivity.jellyfinClientOrConnect(): JellyfinProvider? {
    jellyfinClient?.let { return it }
    val url = jellyfinServerUrl() ?: return null
    return connectJellyfin(url).getOrNull()?.also { jellyfinClient = it }
}

/** Kept alive post-load for fetching a Jellyfin series' episodes when its detail page
 *  opens - that has no Xtream equivalent path to fall back to. */
internal suspend fun MainActivity.fetchJellyfinChannels(): FetchResult {
    val url = jellyfinServerUrl() ?: return FetchResult.Failure("Jellyfin: no server URL")
    return try {
        val jellyfin = connectJellyfin(url).getOrElse {
            return FetchResult.Failure(it.message ?: "Jellyfin: auth failed")
        }
        val stub = jellyfinProviderStub(url)
        val items: List<Channel> = withContext(Dispatchers.IO) {
            // Live is only crawled when its type is on - every other type's fetch skips
            // the network call entirely when gated, and live should too.
            val liveItems = if (jellyfinAllowsLive()) {
                jellyfin.getLiveTvChannels().map { JellyfinProvider.toChannel(it, stub) }
            } else emptyList()
            // Content-type gates: off types are not crawled at all - no movies/series
            // fetch, no user-state import to seed their resume rows.
            if (!jellyfinAllowsMovies() && !jellyfinAllowsSeries()) {
                liveItems
            } else {
                val movies = if (jellyfinAllowsMovies()) jellyfin.getMovies() else emptyList()
                val series = if (jellyfinAllowsSeries()) jellyfin.getSeries() else emptyList()
                importJellyfinUserState(movies + series)
                liveItems +
                    movies.map { JellyfinProvider.toChannel(it, stub) } +
                    series.map { JellyfinProvider.toChannel(it, stub) }
            }
        }
        jellyfinClient = jellyfin
        refreshJellyfinRows(jellyfin, stub)
        FetchResult.Success(items)
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
    includePlayed: Boolean = false
) {
    val stub = jellyfinProviderStub(jellyfinServerUrl())
    for (item in items) {
        if (item.mediaType == "Series") {
            // Favourites are reconciled to the server both ways for Jellyfin items:
            // un-favouriting on the server has to be able to clear the local star too,
            // which a toggle-only import could never do.
            FavoritesStore.setFavoriteSeries(this, item.id, item.favorite)
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
                item.id,
                runtime,
                runtime,
                JellyfinProvider.toChannel(item, stub, prefixSeriesName = true)
            )
            item.resumePositionMs > 0 -> {
                val local = PlaybackPositionStore.get(this, item.id)
                if (local == null || item.resumePositionMs > local.positionMs) {
                    PlaybackPositionStore.save(
                        this,
                        item.id,
                        item.resumePositionMs,
                        runtime,
                        JellyfinProvider.toChannel(item, stub, prefixSeriesName = true)
                    )
                }
            }
        }
    }
}

/** The server's own Resume and Next Up lists, for the Home rows. Also seeds resume
 *  positions for the items in them - these are the partly-watched titles, so they carry
 *  the positions worth having even when the catalog fetch didn't include them. */
internal suspend fun MainActivity.refreshJellyfinRows(jellyfin: JellyfinProvider, stub: Provider) {
    val (resume, nextUp) = withContext(Dispatchers.IO) {
        jellyfin.getResumeItems() to jellyfin.getNextUp()
    }
    importJellyfinUserState(resume)
    jellyfinResumeItems = resume.map { JellyfinProvider.toChannel(it, stub, prefixSeriesName = true) }
    jellyfinNextUpItems = nextUp.map { JellyfinProvider.toChannel(it, stub, prefixSeriesName = true) }
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

/** Reports a Jellyfin play as started, so the server opens a session for it (and knows
 *  not to reap the transcode it just set up). */
internal fun MainActivity.reportJellyfinStart(itemId: String, resolved: JellyfinProvider.ResolvedStream?, positionMs: Long) {
    val client = jellyfinClient ?: return
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
    val client = jellyfinClient ?: return
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
    val client = jellyfinClient
    val session = jellyfinPlaySession
    val position = playerManager.currentPosition.takeIf { it >= 0 } ?: 0L
    jellyfinPlayingItemId = null
    jellyfinPlaySession = null
    jellyfinChapters = emptyList()
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
    val client = jellyfinClient ?: return
    scope.launch {
        delay(1500)
        val stub = jellyfinProviderStub(jellyfinServerUrl())
        runCatching { refreshJellyfinRows(client, stub) }
        if (showingHome) homeShelfAdapter.submitList(buildHomeShelves())
    }
}

/** Chapter markers and trickplay tiles for the Jellyfin item now playing - both are
 *  per-item and only ever needed for the one title on screen, so they're fetched at play
 *  time rather than carried on every catalog entry. */
internal fun MainActivity.loadJellyfinPlaybackExtras(itemId: String) {
    val client = jellyfinClient ?: return
    scope.launch {
        val (chapters, trickplay) = withContext(Dispatchers.IO) {
            runCatching { client.getChapters(itemId) }.getOrDefault(emptyList()) to
                runCatching { client.getTrickplay(itemId) }.getOrNull()
        }
        if (jellyfinPlayingItemId != itemId) return@launch
        jellyfinChapters = chapters
        jellyfinTrickplay = trickplay
        updateChaptersButtonVisibility()
    }
}

internal fun MainActivity.updateChaptersButtonVisibility() {
    binding.btnChapters.visibility = if (jellyfinChapters.size > 1) View.VISIBLE else View.GONE
}

/** Chapter picker - jumps straight to a chapter's start. Only reachable when the item
 *  actually has chapters (see updateChaptersButtonVisibility). */
internal fun MainActivity.showChapterPicker() {
    val chapters = jellyfinChapters
    if (chapters.isEmpty()) return
    val position = playerManager.currentPosition
    val currentIdx = chapters.indexOfLast { it.positionMs <= position }.coerceAtLeast(0)
    val labels = chapters.mapIndexed { index, chapter ->
        val marker = if (index == currentIdx) "▶ " else ""
        "$marker${chapter.name}  ·  ${formatTime(chapter.positionMs)}"
    }.toTypedArray()
    AlertDialog.Builder(this)
        .setTitle("Chapters")
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
    val client = jellyfinClient ?: return
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
