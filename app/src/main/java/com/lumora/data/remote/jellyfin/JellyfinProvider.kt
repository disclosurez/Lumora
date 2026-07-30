package com.lumora.data.remote.jellyfin

import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.model.Provider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Jellyfin expresses every time value in 100-nanosecond ticks. */
private const val TICKS_PER_MS = 10_000L
private const val PAGE_SIZE = 500
/** Backstop for the paging loop - a server that keeps returning full pages can't spin us
 *  forever. Well past any realistic personal library. */
private const val MAX_ITEMS = 50_000

/**
 * Jellyfin media server provider integration.
 * Fetches live TV, movies, series, and episodes from a Jellyfin server via its REST API.
 * Supports password-based auth and Quick Connect.
 */
class JellyfinProvider(private val client: OkHttpClient) {

    private val mutex = Mutex()
    private var accessToken: String? = null
    private var userId: String? = null
    private var serverBase: String? = null

    val currentAccessToken: String? get() = accessToken
    val currentUserId: String? get() = userId

    /** Set by startQuickConnect() on failure so callers can show *why* instead of a generic
     *  message - e.g. distinguishing "server unreachable" from "Quick Connect disabled on
     *  server", which look identical from the Pair<String,String>? return alone. */
    var lastQuickConnectError: String? = null
        private set

    data class AuthResult(
        val success: Boolean = false,
        val token: String? = null,
        val userId: String? = null,
        val serverName: String? = null
    )

    data class JellyfinItem(
        val id: String,
        val name: String,
        val path: String,
        val mediaType: String, // LiveTV, Movie, Series, Episode
        val imageUrl: String? = null,
        val backdropUrl: String? = null,
        val overview: String? = null,
        val year: Int? = null,
        val genres: List<String> = emptyList(),
        val channelNumber: String? = null,
        val channelType: String? = null, // TV, Radio
        val rating: Double? = null,
        val releaseDate: String? = null, // ISO "YYYY-MM-DD"
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val runtimeMs: Long? = null,
        // Server-side per-user state (UserData). This is the whole point of talking to a
        // personal media server rather than a catalogue: resume points and watched marks
        // made in any other Jellyfin client belong here too.
        val resumePositionMs: Long = 0L,
        val played: Boolean = false,
        val favorite: Boolean = false,
        // Episodes only - a Next Up row is meaningless without saying which show it's from.
        val seriesId: String? = null,
        val seriesName: String? = null
    )

    /** One season of a series as the server names it - "Specials" (index 0) included, which
     *  grouping episodes by their own ParentIndexNumber alone can't produce. */
    data class JellyfinSeason(
        val id: String,
        val name: String,
        val indexNumber: Int?
    )

    /** A chapter marker, for chapter skip in the player. */
    data class Chapter(
        val name: String,
        val positionMs: Long,
        val imageUrl: String?
    )

    /** Trickplay tile-sheet geometry for seek-preview thumbnails (Jellyfin 10.9+). Tiles are
     *  sprite sheets of [tileWidth]x[tileHeight] thumbnails, one thumbnail per [intervalMs]. */
    data class TrickplayInfo(
        val width: Int,
        val height: Int,
        val tileWidth: Int,
        val tileHeight: Int,
        val thumbnailCount: Int,
        val intervalMs: Int
    ) {
        val perTile: Int get() = (tileWidth * tileHeight).coerceAtLeast(1)
    }

    /** One subtitle track offered by a resolved media source. [url] is set for anything the
     *  server can deliver as a sidecar file (external files, and embedded text tracks it can
     *  extract) - those get sideloaded into the MediaItem so Media3 renders them itself. */
    data class SubtitleStream(
        val index: Int,
        val url: String?,
        val language: String?,
        val title: String?,
        val codec: String?,
        val isExternal: Boolean,
        val isForced: Boolean,
        val isDefault: Boolean
    )

    /**
     * The outcome of a PlaybackInfo negotiation: the URL to actually play, plus the session
     * identity that playback reporting has to quote back so the server can tie progress to
     * this play (and tear down a transcode when it ends).
     */
    data class ResolvedStream(
        val url: String,
        val playSessionId: String?,
        val mediaSourceId: String?,
        // "DirectPlay", "DirectStream" or "Transcode" - reported as-is to /Sessions/Playing.
        val playMethod: String,
        val runtimeMs: Long?,
        val subtitles: List<SubtitleStream> = emptyList()
    )

    /** A device id that's stable for a given server+account (so the server's device list
     *  doesn't collapse every Lumora install on the same phone model into one entry) but
     *  distinct per connection - only knowable once we have both, so this is per-call
     *  rather than a fixed field. */
    private fun deviceId(serverUrl: String, username: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$serverUrl|$username".toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun authHeader(serverUrl: String, username: String, token: String? = null): String {
        val id = deviceId(serverUrl, username)
        return buildString {
            // Device was Build.MODEL - unescaped, and some devices/boxes report a model
            // string with a stray quote or comma in it, which corrupts this header's
            // quoted-comma-separated format and gets the whole request rejected with a
            // 400 (Jellyfin's Initiate endpoint throws if any of Client/Device/DeviceId/
            // Version comes back empty from a header it couldn't parse). A fixed literal
            // sidesteps that risk entirely - confirmed against another working Jellyfin
            // client's implementation, which does the same.
            append("MediaBrowser Client=\"Lumora\", Device=\"Lumora\", DeviceId=\"$id\", Version=\"1.0.0\"")
            token?.takeIf { it.isNotBlank() }?.let { append(", Token=\"$it\"") }
        }
    }

    /**
     * Authenticate with a Jellyfin server using username/password.
     */
    suspend fun authenticate(serverUrl: String, username: String, password: String): Result<AuthResult> {
        return try {
            val base = serverUrl.trimEnd('/')
            val url = "$base/Users/AuthenticateByName"

            val payload = JSONObject().apply {
                put("Username", username)
                put("Pw", password)
            }

            val request = Request.Builder().url(url)
                .header("Content-Type", "application/json")
                .header("Authorization", authHeader(base, username))
                .header("User-Agent", "Lumora/1.0")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val detail = response.body?.string()?.take(160)?.takeIf { it.isNotBlank() }
                return Result.failure(Exception("Auth failed: HTTP ${response.code}" + (detail?.let { ": $it" } ?: "")))
            }

            val body = response.body?.string() ?: return Result.failure(Exception("Empty response"))
            val json = JSONObject(body)

            val token = json.optString("AccessToken", null)
            val uid = json.optJSONObject("User")?.optString("Id", null)
            val serverName = json.optJSONObject("Server")?.optString("Name", null)

            if (token != null && uid != null) {
                mutex.withLock {
                    accessToken = token
                    userId = uid
                    serverBase = base
                }
                JellyfinSession.update(base, token)
                Result.success(AuthResult(success = true, token = token, userId = uid, serverName = serverName))
            } else {
                Result.failure(Exception("Invalid auth response"))
            }
        } catch (e: Exception) {
            // Bare exception messages from OkHttp ("Unable to resolve host", a TLS
            // handshake failure, connection refused) are exactly what's needed to tell a
            // wrong-scheme/unreachable-server problem apart from a real auth rejection -
            // surfaced as-is instead of swallowed, since there's nowhere else (no log
            // access on a TV) a user could see this otherwise.
            Result.failure(Exception(e.message ?: e.toString(), e))
        }
    }

    /** Reconnects using a previously-saved session token (e.g. from Quick Connect, which
     *  never yields a password to re-authenticate with) - no network call, just restores
     *  state so getLiveTvChannels()/getMovies()/etc. work. */
    fun restoreSession(serverUrl: String, token: String, userId: String) = kotlinx.coroutines.runBlocking {
        val base = serverUrl.trimEnd('/')
        mutex.withLock {
            this@JellyfinProvider.accessToken = token
            this@JellyfinProvider.userId = userId
            this@JellyfinProvider.serverBase = base
        }
        JellyfinSession.update(base, token)
    }

    /**
     * Quick Connect: start a quick connect session. Returns the code to show the user
     * and the secret to poll with.
     */
    suspend fun startQuickConnect(serverUrl: String): Pair<String, String>? {
        lastQuickConnectError = null
        return try {
            val base = serverUrl.trimEnd('/')
            val request = Request.Builder()
                .url("$base/QuickConnect/Initiate")
                .header("Authorization", authHeader(base, "quickconnect"))
                .header("Accept", "application/json")
                .header("User-Agent", "Lumora/1.0")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val detail = response.body?.string()?.take(160)?.takeIf { it.isNotBlank() }
                lastQuickConnectError = "Server returned HTTP ${response.code}" + (detail?.let { ": $it" } ?: "")
                return null
            }
            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                lastQuickConnectError = "Server returned an empty response"
                return null
            }
            val json = JSONObject(body)

            val secret = json.optString("Secret").takeIf { it.isNotBlank() }
            val code = json.optString("Code").takeIf { it.isNotBlank() }
            if (secret == null || code == null) {
                lastQuickConnectError = "Quick Connect isn't enabled on this server"
                return null
            }
            code to secret
        } catch (e: Exception) {
            lastQuickConnectError = e.message ?: "Couldn't reach server"
            null
        }
    }

    /** True once the user has approved the code on the server (via Settings > Quick Connect). */
    suspend fun isQuickConnectApproved(serverUrl: String, secret: String): Boolean {
        return try {
            val base = serverUrl.trimEnd('/')
            val url = "$base/QuickConnect/Connect?secret=${URLEncoder.encode(secret, "UTF-8")}"
            val request = Request.Builder().url(url)
                .header("User-Agent", "Lumora/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return false
            val body = response.body?.string() ?: return false
            JSONObject(body).optBoolean("Authenticated", false)
        } catch (e: Exception) {
            false
        }
    }

    /** Exchanges an *already-approved* Quick Connect secret (from the same startQuickConnect()
     *  call the UI polled isQuickConnectApproved() against) for a real session - the step
     *  the old implementation was missing entirely; it only ever checked approval, never
     *  actually logged in with it. */
    suspend fun completeQuickConnect(serverUrl: String, secret: String): Result<AuthResult> {
        return try {
            val base = serverUrl.trimEnd('/')
            val url = "$base/Users/AuthenticateWithQuickConnect"
            val payload = JSONObject().apply { put("Secret", secret) }
            val request = Request.Builder().url(url)
                .header("Content-Type", "application/json")
                .header("Authorization", authHeader(base, "quickconnect"))
                .header("User-Agent", "Lumora/1.0")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val detail = response.body?.string()?.take(160)?.takeIf { it.isNotBlank() }
                return Result.failure(Exception("Quick Connect login failed: HTTP ${response.code}" + (detail?.let { ": $it" } ?: "")))
            }
            val body = response.body?.string() ?: return Result.failure(Exception("Empty response"))
            val json = JSONObject(body)

            val token = json.optString("AccessToken", null)
            val uid = json.optJSONObject("User")?.optString("Id", null)
            val serverName = json.optJSONObject("Server")?.optString("Name", null)

            if (token != null && uid != null) {
                mutex.withLock {
                    accessToken = token
                    userId = uid
                    serverBase = base
                }
                JellyfinSession.update(base, token)
                Result.success(AuthResult(success = true, token = token, userId = uid, serverName = serverName))
            } else {
                Result.failure(Exception("Invalid Quick Connect auth response"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch live TV channels from Jellyfin.
     */
    suspend fun getLiveTvChannels(): List<JellyfinItem> {
        val token = accessToken ?: return emptyList()
        val base = serverBase ?: return emptyList()

        return try {
            val url = "$base/LiveTv/Channels?userId=$userId&ImageTypeLimit=1&EnableImageTypes=Primary"
            fetchAllItems(url, token).mapNotNull { parseLiveTvItem(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Fetch media items (movies) from Jellyfin.
     */
    suspend fun getMovies(): List<JellyfinItem> {
        return fetchMediaItems("Movie")
    }

    /**
     * Fetch series from Jellyfin.
     */
    suspend fun getSeries(): List<JellyfinItem> {
        return fetchMediaItems("Series")
    }

    /** Episodes for a series, in order - Lumora's Xtream path has no equivalent for
     *  Jellyfin providers, so without this a Jellyfin series has no way to be played
     *  past its own detail page. */
    suspend fun getEpisodes(seriesId: String): List<JellyfinItem> {
        val token = accessToken ?: return emptyList()
        val base = serverBase ?: return emptyList()

        return try {
            val url = "$base/Shows/$seriesId/Episodes?userId=$userId" +
                    "&Fields=$mediaItemFields,ParentIndexNumber,IndexNumber" +
                    "&ImageTypeLimit=1&EnableImageTypes=Primary"
            fetchAllItems(url, token).mapNotNull { parseMediaItem(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The series' seasons as the server names them. Grouping episodes by ParentIndexNumber
     *  alone can only ever produce "Season 0" for specials, and loses any season the server
     *  has a real name for. */
    suspend fun getSeasons(seriesId: String): List<JellyfinSeason> {
        val token = accessToken ?: return emptyList()
        val base = serverBase ?: return emptyList()

        return try {
            val url = "$base/Shows/$seriesId/Seasons?userId=$userId&Fields=ItemCounts"
            fetchAllItems(url, token).mapNotNull { json ->
                val id = json.optString("Id", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                JellyfinSeason(
                    id = id,
                    name = json.optString("Name", "").takeIf { it.isNotBlank() } ?: "Season",
                    indexNumber = json.optInt("IndexNumber", -1).takeIf { it >= 0 }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Partly-watched movies/episodes, newest first - the server's own "Continue Watching",
     *  which knows about playback that happened in any other client. */
    suspend fun getResumeItems(limit: Int = 24): List<JellyfinItem> {
        val token = accessToken ?: return emptyList()
        val base = serverBase ?: return emptyList()

        return try {
            val url = "$base/Users/$userId/Items/Resume?includeItemTypes=Movie,Episode" +
                    "&Fields=$mediaItemFields,ParentIndexNumber,IndexNumber&Recursive=true" +
                    "&MediaTypes=Video&Limit=$limit&ImageTypeLimit=1"
            fetchItems(url, token).first.mapNotNull { parseMediaItem(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The next unwatched episode of each series the user is partway through. */
    suspend fun getNextUp(limit: Int = 24): List<JellyfinItem> {
        val token = accessToken ?: return emptyList()
        val base = serverBase ?: return emptyList()

        return try {
            val url = "$base/Shows/NextUp?userId=$userId" +
                    "&Fields=$mediaItemFields,ParentIndexNumber,IndexNumber" +
                    "&Limit=$limit&ImageTypeLimit=1"
            fetchItems(url, token).first.mapNotNull { parseMediaItem(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Chapter markers for one item, for chapter skip. Fetched per item rather than as a
     *  catalog field - Chapters on a few thousand items is a large payload for something
     *  only ever needed for the one title being played. */
    suspend fun getChapters(itemId: String): List<Chapter> {
        val base = serverBase ?: return emptyList()
        val json = getJson("$base/Users/$userId/Items/$itemId?Fields=Chapters") ?: return emptyList()
        val chapters = json.optJSONArray("Chapters") ?: return emptyList()
        return (0 until chapters.length()).mapNotNull { i ->
            val chapter = chapters.optJSONObject(i) ?: return@mapNotNull null
            val ticks = chapter.optLong("StartPositionTicks", -1L).takeIf { it >= 0 } ?: return@mapNotNull null
            val imageTag = chapter.optString("ImageTag", "").takeIf { it.isNotBlank() }
            Chapter(
                name = chapter.optString("Name", "").takeIf { it.isNotBlank() } ?: "Chapter ${i + 1}",
                positionMs = ticks / TICKS_PER_MS,
                imageUrl = imageTag?.let {
                    "$base/Items/$itemId/Images/Chapter/$i?tag=${URLEncoder.encode(it, "UTF-8")}"
                }
            )
        }
    }

    /** Trickplay tile geometry for [itemId], or null on a server/library without it. Picks the
     *  smallest available tile width - these are thumbnails behind a seek bar, and a larger
     *  sheet is just more to download and crop on a device that has no spare bandwidth. */
    suspend fun getTrickplay(itemId: String): TrickplayInfo? {
        val base = serverBase ?: return null
        val json = getJson("$base/Users/$userId/Items/$itemId?Fields=Trickplay") ?: return null
        val trickplay = json.optJSONObject("Trickplay") ?: return null
        // Shape is { mediaSourceId: { width: {...} } } - any source will do, the geometry is
        // per-width and the URL only takes a width.
        val bySource = trickplay.keys().asSequence().firstOrNull()?.let { trickplay.optJSONObject(it) } ?: return null
        val widthKey = bySource.keys().asSequence().mapNotNull { it.toIntOrNull() }.minOrNull() ?: return null
        val info = bySource.optJSONObject(widthKey.toString()) ?: return null
        val interval = info.optInt("Interval", 0).takeIf { it > 0 } ?: return null
        return TrickplayInfo(
            width = info.optInt("Width", widthKey),
            height = info.optInt("Height", 0),
            tileWidth = info.optInt("TileWidth", 1),
            tileHeight = info.optInt("TileHeight", 1),
            thumbnailCount = info.optInt("ThumbnailCount", 0),
            intervalMs = interval
        )
    }

    /** URL of one trickplay sprite sheet. [tileIndex] is the sheet, not the thumbnail -
     *  each sheet holds TrickplayInfo.perTile thumbnails. */
    fun trickplayTileUrl(itemId: String, info: TrickplayInfo, tileIndex: Int): String? {
        val base = serverBase ?: return null
        val token = accessToken ?: return null
        return "$base/Videos/$itemId/Trickplay/${info.width}/$tileIndex.jpg?api_key=$token"
    }

    /** Adds or removes [itemId] from the server's favourites, so a star set here shows up in
     *  every other Jellyfin client (and survives a reinstall of this one). */
    suspend fun setFavorite(itemId: String, favorite: Boolean): Boolean {
        val base = serverBase ?: return false
        val token = accessToken ?: return false
        val url = "$base/Users/$userId/FavoriteItems/$itemId"
        return runCatching {
            val builder = Request.Builder().url(url)
                .header("X-Emby-Token", token)
                .header("User-Agent", "Lumora/1.0")
            val request = if (favorite) {
                builder.post("{}".toRequestBody("application/json".toMediaType())).build()
            } else {
                builder.delete().build()
            }
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private val mediaItemFields =
        "Overview,Genres,ProductionYear,PremiereDate,CommunityRating,BackdropImageTags,ImageTags,UserData,RunTimeTicks"

    private suspend fun fetchMediaItems(type: String): List<JellyfinItem> {
        val token = accessToken ?: return emptyList()
        val base = serverBase ?: return emptyList()

        return try {
            val url = "$base/Items?userId=$userId" +
                    "&includeItemTypes=$type" +
                    "&recursive=true&fields=$mediaItemFields" +
                    "&ImageTypeLimit=1"
            fetchAllItems(url, token).mapNotNull { parseMediaItem(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Every item [url] matches, a page at a time. The single fixed `Limit=500` this replaced
     * silently truncated any library past 500 titles - the items simply weren't there, with
     * nothing to indicate why. Stops on a short page (the server has nothing more) or once
     * TotalRecordCount is reached, with [MAX_ITEMS] as a backstop against a server that
     * keeps handing back full pages forever.
     */
    private suspend fun fetchAllItems(url: String, token: String): List<JSONObject> {
        val all = mutableListOf<JSONObject>()
        var startIndex = 0
        while (startIndex < MAX_ITEMS) {
            val separator = if (url.contains('?')) "&" else "?"
            val paged = "$url${separator}StartIndex=$startIndex&Limit=$PAGE_SIZE"
            val (items, total) = fetchItems(paged, token)
            all += items
            if (items.size < PAGE_SIZE) break
            startIndex += items.size
            if (total != null && all.size >= total) break
        }
        return all
    }

    /** One page: the items, plus TotalRecordCount when the endpoint reports one. */
    private suspend fun fetchItems(url: String, token: String): Pair<List<JSONObject>, Int?> {
        val request = Request.Builder().url(url)
            .header("X-Emby-Token", token)
            .header("User-Agent", "Lumora/1.0")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return emptyList<JSONObject>() to null

        val body = response.body?.string() ?: return emptyList<JSONObject>() to null
        val json = JSONObject(body)
        val items = json.optJSONArray("Items") ?: return emptyList<JSONObject>() to null
        val total = json.optInt("TotalRecordCount", -1).takeIf { it >= 0 }

        return (0 until items.length()).map { items.getJSONObject(it) } to total
    }

    /** GET one JSON object (an item, not a list). */
    private fun getJson(url: String): JSONObject? {
        val token = accessToken ?: return null
        return runCatching {
            val request = Request.Builder().url(url)
                .header("X-Emby-Token", token)
                .header("User-Agent", "Lumora/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
            }
        }.getOrNull()
    }

    /** POST a JSON body, ignoring the response beyond success/failure - what every
     *  fire-and-forget reporting call needs. */
    private fun postJson(url: String, payload: JSONObject): Boolean {
        val token = accessToken ?: return false
        return runCatching {
            val request = Request.Builder().url(url)
                .header("X-Emby-Token", token)
                .header("Content-Type", "application/json")
                .header("User-Agent", "Lumora/1.0")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun parseLiveTvItem(json: JSONObject): JellyfinItem? {
        val id = json.optString("Id", "") ?: return null
        if (id.isBlank()) return null

        return JellyfinItem(
            id = id,
            name = json.optString("Name", "Unknown"),
            path = json.optString("Path", ""),
            mediaType = "LiveTV",
            imageUrl = buildImageUrl(id, "Primary", json),
            backdropUrl = buildImageUrl(id, "Backdrop", json, backdrop = true),
            channelNumber = json.optString("ChannelNumber", null),
            channelType = json.optString("ChannelType", null)
        )
    }

    private fun parseMediaItem(json: JSONObject): JellyfinItem? {
        val id = json.optString("Id", "") ?: return null
        if (id.isBlank()) return null

        val type = json.optString("Type", "")
        val rating = json.optDouble("CommunityRating", Double.NaN).takeIf { !it.isNaN() }
        val premiere = json.optString("PremiereDate", "").takeIf { it.isNotBlank() }?.take(10)
        val season = json.optInt("ParentIndexNumber", -1).takeIf { it >= 0 }
        val episode = json.optInt("IndexNumber", -1).takeIf { it >= 0 }
        val name = if (type == "Episode" && season != null && episode != null) {
            "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')} · ${json.optString("Name", "Episode")}"
        } else {
            json.optString("Name", "Unknown")
        }

        val userData = json.optJSONObject("UserData")
        return JellyfinItem(
            id = id,
            name = name,
            path = json.optString("Path", ""),
            mediaType = when (type) {
                "Movie" -> "Movie"
                "Series" -> "Series"
                "Episode" -> "Episode"
                else -> type
            },
            imageUrl = buildImageUrl(id, "Primary", json),
            backdropUrl = buildImageUrl(id, "Backdrop", json, backdrop = true),
            overview = json.optString("Overview", null),
            year = json.optInt("ProductionYear", 0).takeIf { it > 0 },
            genres = json.optJSONArray("Genres")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            rating = rating,
            releaseDate = premiere,
            seasonNumber = season,
            episodeNumber = episode,
            runtimeMs = json.optLong("RunTimeTicks", 0L).takeIf { it > 0 }?.div(TICKS_PER_MS),
            resumePositionMs = (userData?.optLong("PlaybackPositionTicks", 0L) ?: 0L) / TICKS_PER_MS,
            played = userData?.optBoolean("Played", false) ?: false,
            favorite = userData?.optBoolean("IsFavorite", false) ?: false,
            seriesId = json.optString("SeriesId", "").takeIf { it.isNotBlank() },
            seriesName = json.optString("SeriesName", "").takeIf { it.isNotBlank() }
        )
    }

    // ── Playback reporting ─────────────────────────

    /**
     * Tells the server a play has started. Without this trio of calls, nothing watched in
     * Lumora ever reaches the server: watched marks, resume points, "Continue Watching" and
     * "Next Up" all stay as they were, and a transcode started by PlaybackInfo is left
     * running with no session to attribute it to.
     */
    suspend fun reportPlaybackStart(
        itemId: String,
        playSessionId: String?,
        positionMs: Long,
        playMethod: String
    ): Boolean {
        val base = serverBase ?: return false
        return postJson("$base/Sessions/Playing", playStatePayload(itemId, playSessionId, positionMs, playMethod))
    }

    /** Progress heartbeat. Jellyfin expects these every ~10s while playing; it's also what
     *  keeps a live transcode from being reaped as abandoned. */
    suspend fun reportPlaybackProgress(
        itemId: String,
        playSessionId: String?,
        positionMs: Long,
        isPaused: Boolean,
        playMethod: String
    ): Boolean {
        val base = serverBase ?: return false
        val payload = playStatePayload(itemId, playSessionId, positionMs, playMethod)
            .put("IsPaused", isPaused)
            .put("EventName", "timeupdate")
        return postJson("$base/Sessions/Playing/Progress", payload)
    }

    /** End of play. The server decides watched-vs-resume from the position reported here, so
     *  this is what actually marks a finished title as watched. */
    suspend fun reportPlaybackStopped(
        itemId: String,
        playSessionId: String?,
        positionMs: Long
    ): Boolean {
        val base = serverBase ?: return false
        val payload = JSONObject()
            .put("ItemId", itemId)
            .put("PositionTicks", positionMs * TICKS_PER_MS)
        playSessionId?.let { payload.put("PlaySessionId", it) }
        return postJson("$base/Sessions/Playing/Stopped", payload)
    }

    private fun playStatePayload(
        itemId: String,
        playSessionId: String?,
        positionMs: Long,
        playMethod: String
    ): JSONObject = JSONObject()
        .put("ItemId", itemId)
        .put("PositionTicks", positionMs * TICKS_PER_MS)
        .put("PlayMethod", playMethod)
        .put("CanSeek", true)
        .apply { playSessionId?.let { put("PlaySessionId", it) } }

    // ── Playback negotiation (PlaybackInfo) ────────

    /**
     * Asks the server how this item should actually be played, given what this device can
     * decode ([JellyfinDeviceProfile]). Returns the URL to hand ExoPlayer - a direct stream
     * where the file is playable as-is, an HLS transcode where it isn't - plus the subtitle
     * tracks that come with it and the PlaySessionId that reporting must quote.
     *
     * Falls back to null on any failure; callers keep using the plain `?static=true` URL,
     * which is what the app did unconditionally before.
     */
    suspend fun resolveStream(
        itemId: String,
        startPositionMs: Long = 0L,
        maxBitrate: Int = JellyfinDeviceProfile.DEFAULT_MAX_BITRATE
    ): ResolvedStream? {
        val base = serverBase ?: return null
        val token = accessToken ?: return null
        val payload = JSONObject()
            .put("DeviceProfile", JellyfinDeviceProfile.build(maxBitrate))
            .put("MaxStreamingBitrate", maxBitrate)
            .put("StartTimeTicks", startPositionMs * TICKS_PER_MS)

        val url = "$base/Items/$itemId/PlaybackInfo?userId=$userId" +
            "&startTimeTicks=${startPositionMs * TICKS_PER_MS}" +
            "&isPlayback=true&autoOpenLiveStream=true&maxStreamingBitrate=$maxBitrate"

        val json = runCatching {
            val request = Request.Builder().url(url)
                .header("X-Emby-Token", token)
                .header("Content-Type", "application/json")
                .header("User-Agent", "Lumora/1.0")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
            }
        }.getOrNull() ?: return null

        val playSessionId = json.optString("PlaySessionId", "").takeIf { it.isNotBlank() }
        val sources = json.optJSONArray("MediaSources") ?: return null
        val source = (0 until sources.length()).mapNotNull { sources.optJSONObject(it) }
            .firstOrNull() ?: return null

        val mediaSourceId = source.optString("Id", "").takeIf { it.isNotBlank() }
        val transcodingUrl = source.optString("TranscodingUrl", "").takeIf { it.isNotBlank() }
        val directPlay = source.optBoolean("SupportsDirectPlay", false)
        val directStream = source.optBoolean("SupportsDirectStream", false)

        // Direct play beats a transcode whenever the server says the file is playable as-is:
        // no re-encode on the server, no quality loss, and seeking stays instant. Only when
        // it isn't do we take the TranscodingUrl (already carries its own api_key and
        // PlaySessionId, so it's used verbatim).
        val (streamUrl, playMethod) = when {
            directPlay || directStream -> {
                val query = buildString {
                    append("static=true")
                    mediaSourceId?.let { append("&mediaSourceId=$it") }
                    playSessionId?.let { append("&playSessionId=$it") }
                    append("&api_key=$token")
                }
                "$base/Videos/$itemId/stream?$query" to if (directPlay) "DirectPlay" else "DirectStream"
            }
            transcodingUrl != null -> "$base$transcodingUrl" to "Transcode"
            else -> return null
        }

        return ResolvedStream(
            url = streamUrl,
            playSessionId = playSessionId,
            mediaSourceId = mediaSourceId,
            playMethod = playMethod,
            runtimeMs = source.optLong("RunTimeTicks", 0L).takeIf { it > 0 }?.div(TICKS_PER_MS),
            subtitles = parseSubtitleStreams(source, itemId, mediaSourceId, token, base)
        )
    }

    /** Subtitle tracks of a resolved source. Anything the server can hand over as a sidecar
     *  file gets a URL here so it can be sideloaded into the MediaItem and rendered by Media3;
     *  image-based tracks (PGS/VOBSUB) have no URL and are left to the server to burn in. */
    private fun parseSubtitleStreams(
        source: JSONObject,
        itemId: String,
        mediaSourceId: String?,
        token: String,
        base: String
    ): List<SubtitleStream> {
        val streams = source.optJSONArray("MediaStreams") ?: return emptyList()
        return (0 until streams.length()).mapNotNull { i ->
            val stream = streams.optJSONObject(i) ?: return@mapNotNull null
            if (!stream.optString("Type", "").equals("Subtitle", ignoreCase = true)) return@mapNotNull null
            val index = stream.optInt("Index", -1).takeIf { it >= 0 } ?: return@mapNotNull null
            val codec = stream.optString("Codec", "").takeIf { it.isNotBlank() }?.lowercase()
            val deliveryUrl = stream.optString("DeliveryUrl", "").takeIf { it.isNotBlank() }
            val supportsExternal = stream.optBoolean("SupportsExternalStream", false)
            val url = when {
                deliveryUrl != null -> if (deliveryUrl.startsWith("http")) deliveryUrl else "$base$deliveryUrl"
                supportsExternal && mediaSourceId != null && isTextSubtitle(codec) ->
                    "$base/Videos/$itemId/$mediaSourceId/Subtitles/$index/0/Stream.vtt?api_key=$token"
                else -> null
            }
            SubtitleStream(
                index = index,
                url = url,
                language = stream.optString("Language", "").takeIf { it.isNotBlank() },
                title = stream.optString("DisplayTitle", "").takeIf { it.isNotBlank() },
                codec = codec,
                isExternal = stream.optBoolean("IsExternal", false),
                isForced = stream.optBoolean("IsForced", false),
                isDefault = stream.optBoolean("IsDefault", false)
            )
        }
    }

    private fun isTextSubtitle(codec: String?): Boolean =
        codec in setOf("subrip", "srt", "webvtt", "vtt", "ass", "ssa", "ttml", "mov_text", "text")

    /** Includes the image's own tag as a cache-buster/existence check where Jellyfin
     *  provides one - without it a stale or entirely absent image can get cached as if
     *  it were valid. Backdrops in particular don't exist for every item; skip building
     *  a URL at all rather than guess one that 404s. */
    private fun buildImageUrl(itemId: String, imageType: String, json: JSONObject, backdrop: Boolean = false): String? {
        val base = serverBase ?: return null
        val tag = if (backdrop) {
            json.optJSONArray("BackdropImageTags")?.takeIf { it.length() > 0 }?.optString(0) ?: return null
        } else {
            json.optJSONObject("ImageTags")?.optString(imageType, null)
        }
        val query = tag?.let { "?tag=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
        return "$base/Items/$itemId/Images/$imageType$query"
    }

    companion object {
        /** [prefixSeriesName] labels an episode with the show it belongs to - needed for the
         *  Next Up / Continue Watching rows, where a bare "S02E04 · Title" says nothing about
         *  which series is being offered. Off inside a series' own episode list, which is
         *  already scoped to one show. */
        fun toChannel(item: JellyfinItem, provider: Provider, prefixSeriesName: Boolean = false): Channel {
            val mediaType = when (item.mediaType) {
                "LiveTV" -> MediaType.LIVE
                "Movie" -> MediaType.MOVIE
                "Series" -> MediaType.SERIES
                "Episode" -> MediaType.SERIES
                else -> MediaType.LIVE
            }
            val serverBase = provider.serverUrl?.trimEnd('/') ?: ""
            val streamUrl = "$serverBase/Videos/${item.id}/stream?static=true"

            val displayName = if (prefixSeriesName && !item.seriesName.isNullOrBlank()) {
                "${item.seriesName} · ${item.name}"
            } else {
                item.name
            }

            return Channel(
                id = item.id,
                name = displayName,
                url = streamUrl,
                logoUrl = item.imageUrl,
                posterUrl = item.imageUrl,
                backdropUrl = item.backdropUrl,
                description = item.overview,
                year = item.year?.toString(),
                categoryName = item.genres.firstOrNull(),
                episodeNum = item.episodeNumber,
                mediaType = mediaType,
                rating = item.rating?.toString(),
                releaseDate = item.releaseDate,
                isJellyfin = true
            )
        }
    }
}
