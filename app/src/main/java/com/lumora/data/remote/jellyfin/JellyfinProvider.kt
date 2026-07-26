package com.lumora.data.remote.jellyfin

import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.model.Provider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Jellyfin media server provider integration.
 * Fetches live TV, movies, and series from a Jellyfin server via its REST API.
 * Supports password-based auth and Quick Connect.
 */
class JellyfinProvider(private val client: OkHttpClient) {

    private var accessToken: String? = null
    private var userId: String? = null
    private var serverBase: String? = null
    private val deviceId = "Lumora_${android.os.Build.MODEL}"

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
        val channelType: String? = null // TV, Radio
    )

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
                .header("X-Emby-Authorization", buildAuthHeader())
                .header("User-Agent", "Lumora/2.0")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return Result.failure(Exception("Auth failed: HTTP ${response.code}"))
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
                Result.success(AuthResult(success = true, token = token, userId = uid, serverName = serverName))
            } else {
                Result.failure(Exception("Invalid auth response"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Quick Connect: start a quick connect session.
     * Returns a URL to visit and a code to enter.
     */
    suspend fun startQuickConnect(serverUrl: String): Pair<String, String>? {
        return try {
            val base = serverUrl.trimEnd('/')
            val request = Request.Builder()
                .url("$base/QuickConnect/Initiate")
                .header("User-Agent", "Lumora/2.0")
                .post(okhttp3.RequestBody.create(null, ""))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)

            val secret = json.optString("Secret", null) ?: return null
            val code = json.optString("Code", null) ?: return null

            "$base/QuickConnect?code=$code" to secret
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Poll Quick Connect status.
     */
    suspend fun checkQuickConnect(serverUrl: String, secret: String): Boolean {
        return try {
            val base = serverUrl.trimEnd('/')
            val url = "$base/QuickConnect/Connect?secret=$secret"
            val request = Request.Builder().url(url)
                .header("User-Agent", "Lumora/2.0")
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
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

    private suspend fun fetchMediaItems(type: String): List<JellyfinItem> {
        val token = accessToken ?: return emptyList()
        val base = serverBase ?: return emptyList()

        return try {
            val url = "$base/Items?userId=$userId" +
                    "&includeItemTypes=$type" +
                    "&recursive=true&fields=Overview,Genres,ProductionYear" +
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
            .header("User-Agent", "Lumora/2.0")
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
            imageUrl = buildImageUrl(id, "Primary"),
            backdropUrl = buildImageUrl(id, "Backdrop"),
            channelNumber = json.optString("ChannelNumber", null),
            channelType = json.optString("ChannelType", null)
        )
    }

    private fun parseMediaItem(json: JSONObject): JellyfinItem? {
        val id = json.optString("Id", "") ?: return null
        if (id.isBlank()) return null

        val type = json.optString("Type", "")

        return JellyfinItem(
            id = id,
            name = json.optString("Name", "Unknown"),
            path = json.optString("Path", ""),
            mediaType = when (type) {
                "Movie" -> "Movie"
                "Series" -> "Series"
                "Episode" -> "Episode"
                else -> type
            },
            imageUrl = buildImageUrl(id, "Primary"),
            backdropUrl = buildImageUrl(id, "Backdrop"),
            overview = json.optString("Overview", null),
            year = json.optInt("ProductionYear", 0).takeIf { it > 0 },
            genres = json.optJSONArray("Genres")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
        )
    }

    private fun buildImageUrl(itemId: String, imageType: String): String? {
        val base = serverBase ?: return null
        return "$base/Items/$itemId/Images/$imageType"
    }

    private fun buildAuthHeader(): String {
        val token = accessToken ?: ""
        return "MediaBrowser Client=\"Lumora\", Device=\"${android.os.Build.MODEL}\", " +
                "DeviceId=\"$deviceId\", Version=\"2.0.0\", Token=\"$token\""
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
                mediaType = mediaType
            )
        }
    }
}
