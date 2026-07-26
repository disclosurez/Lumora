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

/**
 * Jellyfin media server provider integration.
 * Fetches live TV, movies, series, and episodes from a Jellyfin server via its REST API.
 * Supports password-based auth and Quick Connect.
 */
class JellyfinProvider(private val client: OkHttpClient) {

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
        val episodeNumber: Int? = null
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
                accessToken = token
                userId = uid
                serverBase = base
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
    fun restoreSession(serverUrl: String, token: String, userId: String) {
        val base = serverUrl.trimEnd('/')
        this.accessToken = token
        this.userId = userId
        this.serverBase = base
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
                accessToken = token
                userId = uid
                serverBase = base
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
            val url = "$base/LiveTv/Channels?userId=$userId" +
                    "&Limit=500&ImageTypeLimit=1&EnableImageTypes=Primary"

            val items = fetchItems(url, token)
            items.mapNotNull { parseLiveTvItem(it) }
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
                    "&Fields=Overview,Genres,ProductionYear,PremiereDate,CommunityRating,ParentIndexNumber,IndexNumber,BackdropImageTags,ImageTags" +
                    "&ImageTypeLimit=1&EnableImageTypes=Primary"

            val items = fetchItems(url, token)
            items.mapNotNull { parseMediaItem(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private val mediaItemFields =
        "Overview,Genres,ProductionYear,PremiereDate,CommunityRating,BackdropImageTags,ImageTags"

    private suspend fun fetchMediaItems(type: String): List<JellyfinItem> {
        val token = accessToken ?: return emptyList()
        val base = serverBase ?: return emptyList()

        return try {
            val url = "$base/Items?userId=$userId" +
                    "&includeItemTypes=$type" +
                    "&recursive=true&fields=$mediaItemFields" +
                    "&Limit=500&ImageTypeLimit=1"

            val items = fetchItems(url, token)
            items.mapNotNull { parseMediaItem(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchItems(url: String, token: String): List<JSONObject> {
        val request = Request.Builder().url(url)
            .header("X-Emby-Token", token)
            .header("User-Agent", "Lumora/1.0")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return emptyList()

        val body = response.body?.string() ?: return emptyList()
        val json = JSONObject(body)
        val items = json.optJSONArray("Items") ?: return emptyList()

        return (0 until items.length()).map { items.getJSONObject(it) }
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
            episodeNumber = episode
        )
    }

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
        fun toChannel(item: JellyfinItem, provider: Provider): Channel {
            val mediaType = when (item.mediaType) {
                "LiveTV" -> MediaType.LIVE
                "Movie" -> MediaType.MOVIE
                "Series" -> MediaType.SERIES
                "Episode" -> MediaType.SERIES
                else -> MediaType.LIVE
            }
            val serverBase = provider.serverUrl?.trimEnd('/') ?: ""
            val streamUrl = "$serverBase/Videos/${item.id}/stream?static=true"

            return Channel(
                id = item.id,
                name = item.name,
                url = streamUrl,
                logoUrl = item.imageUrl,
                posterUrl = item.imageUrl,
                backdropUrl = item.backdropUrl,
                description = item.overview,
                year = item.year?.toString(),
                categoryName = item.genres.firstOrNull(),
                mediaType = mediaType,
                rating = item.rating?.toString(),
                releaseDate = item.releaseDate,
                isJellyfin = true
            )
        }
    }
}
