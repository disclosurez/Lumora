package com.lumora.parser

import android.util.Log
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.model.Provider
import com.lumora.util.normalizeServerUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder

private const val TAG = "XtreamClient"

/**
 * Minimal Xtream Codes API client using OkHttp.
 * Fetches live TV, VOD, and series from an Xtream server.
 */
class XtreamClient(private val client: OkHttpClient) {

    // fetchJson() swallows exceptions into a null return so most callers can just treat
    // "no data" as empty - but that turned real errors (bad URL, DNS failure, timeout)
    // into a useless generic "Empty response" message for authenticate(). Stash the real
    // cause here so authenticate() can surface it instead.
    private var lastFetchError: String? = null

    data class ServerInfo(
        val version: String? = null,
        val url: String? = null,
        val port: String? = null,
        val httpsPort: String? = null,
        val serverProtocol: String? = null,
        val valid: Boolean = false,
        val expDateSeconds: Long? = null,
        val isTrial: Boolean = false
    )

    data class EpgProgram(
        val title: String,
        val startTimestamp: Long,
        val stopTimestamp: Long
    ) {
        fun isNowAiring(nowSeconds: Long): Boolean = nowSeconds in startTimestamp until stopTimestamp
    }

    data class ContentDetails(
        val plot: String? = null,
        val cast: String? = null,
        val director: String? = null,
        val genre: String? = null,
        val backdropUrl: String? = null,
        val rating: String? = null,
        val releaseDate: String? = null
    )

    /** Authenticate and get server info. */
    suspend fun authenticate(provider: Provider): Result<ServerInfo> = withContext(Dispatchers.IO) {
        try {
            val url = buildApiUrl(provider, "")
            Log.d(TAG, "Auth URL: ${url.take(80)}...")
            lastFetchError = null
            val json = fetchJson(url)
                ?: return@withContext Result.failure(Exception(lastFetchError ?: "Empty response from server"))

            val userInfo = json.optJSONObject("user_info")
            if (userInfo != null) {
                val auth = userInfo.optString("auth", "0")
                Result.success(ServerInfo(
                    version = json.optString("server_info", ""),
                    url = userInfo.optString("url"),
                    port = userInfo.optString("port"),
                    httpsPort = userInfo.optString("https_port"),
                    serverProtocol = userInfo.optString("server_protocol"),
                    valid = auth == "1",
                    expDateSeconds = userInfo.optString("exp_date", "").toLongOrNull(),
                    isTrial = userInfo.optString("is_trial", "0") == "1"
                ))
            } else {
                Result.failure(Exception("Invalid server response - check URL and credentials"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Auth failed: ${e.message}")
            Result.failure(e)
        }
    }

    /** Fetch live categories. Returns list of (id, name) pairs. */
    suspend fun getLiveCategories(provider: Provider): List<Pair<String, String>> =
        fetchCategoryList(provider, "live")

    /** Fetch VOD categories. */
    suspend fun getVodCategories(provider: Provider): List<Pair<String, String>> =
        fetchCategoryList(provider, "vod")

    /** Fetch series categories. */
    suspend fun getSeriesCategories(provider: Provider): List<Pair<String, String>> =
        fetchCategoryList(provider, "series")

    /** Fetch live streams. */
    suspend fun getLiveStreams(provider: Provider, categoryId: String? = null): List<Channel> =
        fetchStreamList(provider, "get_live_streams", categoryId, MediaType.LIVE)

    /** Fetch VOD streams. */
    suspend fun getVodStreams(provider: Provider, categoryId: String? = null): List<Channel> =
        fetchStreamList(provider, "get_vod_streams", categoryId, MediaType.MOVIE)

    /** Fetch series list. */
    suspend fun getSeries(provider: Provider, categoryId: String? = null): List<Channel> =
        fetchSeriesList(provider, categoryId)

    /** Fetch the next few EPG entries for a live channel. Not every channel has EPG data. */
    suspend fun getShortEpg(provider: Provider, streamId: String, limit: Int = 2): List<EpgProgram> =
        withContext(Dispatchers.IO) {
            val url = buildApiUrl(provider, "action=get_short_epg&stream_id=$streamId&limit=$limit")
            val json = fetchJson(url) ?: return@withContext emptyList()
            val arr = json.optJSONArray("epg_listings") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = decodeEpgText(obj.optString("title", "")) ?: return@mapNotNull null
                val start = obj.optString("start_timestamp", "0").toLongOrNull() ?: return@mapNotNull null
                val stop = obj.optString("stop_timestamp", "0").toLongOrNull() ?: return@mapNotNull null
                EpgProgram(title, start, stop)
            }
        }

    private fun decodeEpgText(value: String): String? {
        if (value.isBlank()) return null
        val decoded = runCatching {
            String(android.util.Base64.decode(value, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
        return decoded?.ifBlank { null } ?: value
    }

    /** Fetch a movie's plot/cast/director/genre for the detail screen. */
    suspend fun getVodInfo(provider: Provider, vodId: String): ContentDetails? = withContext(Dispatchers.IO) {
        val url = buildApiUrl(provider, "action=get_vod_info&vod_id=$vodId")
        val json = fetchJson(url) ?: return@withContext null
        json.optJSONObject("info")?.let { parseDetails(it) }
    }

    data class SeriesFullInfo(
        val details: ContentDetails?,
        val seasons: List<Pair<String, List<Channel>>>
    )

    /** Fetch a series' details plus its episodes grouped by season, in one call. */
    suspend fun getSeriesFull(provider: Provider, seriesId: String): SeriesFullInfo =
        withContext(Dispatchers.IO) {
            val url = buildApiUrl(provider, "action=get_series_info&series_id=$seriesId")
            val json = fetchJson(url) ?: return@withContext SeriesFullInfo(null, emptyList())
            val details = json.optJSONObject("info")?.let { parseDetails(it) }

            val episodes = json.optJSONObject("episodes")
            val seasons = mutableListOf<Pair<String, List<Channel>>>()
            if (episodes != null) {
                val seasonKeys = episodes.keys().asSequence().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                for (seasonKey in seasonKeys) {
                    val seasonArr = episodes.optJSONArray(seasonKey) ?: continue
                    val eps = (0 until seasonArr.length()).map { i ->
                        parseEpisode(seasonArr.getJSONObject(i), seriesId, seasonKey, provider)
                    }
                    if (eps.isNotEmpty()) seasons.add("Season $seasonKey" to eps)
                }
            }
            SeriesFullInfo(details, seasons)
        }

    private fun parseDetails(info: JSONObject): ContentDetails {
        val backdrop = info.optJSONArray("backdrop_path")?.takeIf { it.length() > 0 }?.optString(0)
        return ContentDetails(
            plot = info.optString("plot", info.optString("description", "")).ifBlank { null },
            cast = info.optString("cast", "").ifBlank { null },
            director = info.optString("director", "").ifBlank { null },
            genre = info.optString("genre", "").ifBlank { null },
            backdropUrl = backdrop?.ifBlank { null },
            rating = info.optString("rating", "").ifBlank { null },
            // Confirmed against a live provider: movies actually use "releasedate" (all
            // lowercase, no separator) - "release_date"/"releaseDate" never matched
            // anything there. Kept as fallbacks in case another provider spells it
            // differently.
            releaseDate = info.optString("releasedate", info.optString("release_date", info.optString("releaseDate", ""))).ifBlank { null }
        )
    }

    // ── Internal helpers ──────────────────────────

    private suspend fun fetchCategoryList(provider: Provider, type: String): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val url = buildApiUrl(provider, "action=get_${type}_categories")
            val json = fetchJson(url) ?: return@withContext emptyList()
            val arr = json.optJSONArray("categories")
                ?: json.optJSONArray("")  // Some servers return array as root
                ?: json.optJSONArray("items")  // Wrapped bare array
                ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("category_id", "")
                val name = obj.optString("category_name", "")
                if (id.isNotBlank()) id to name else null
            }
        }

    private suspend fun fetchStreamList(
        provider: Provider,
        action: String,
        categoryId: String?,
        mediaType: MediaType
    ): List<Channel> = withContext(Dispatchers.IO) {
        val params = StringBuilder("action=$action")
        if (!categoryId.isNullOrBlank()) params.append("&category_id=$categoryId")
        val url = buildApiUrl(provider, params.toString())
        val json = fetchJson(url) ?: return@withContext emptyList()
        val key = when (action) {
            "get_live_streams" -> "live_streams"
            "get_vod_streams" -> "vod_streams"
            else -> ""
        }
        // Many Xtream servers return a bare JSON array at the root instead of
        // {"live_streams": [...]}; fetchJson() wraps that case under "items".
        val arr = json.optJSONArray(key) ?: json.optJSONArray("items") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            parseStream(obj, mediaType, provider)
        }
    }

    private suspend fun fetchSeriesList(provider: Provider, categoryId: String?): List<Channel> =
        withContext(Dispatchers.IO) {
            val params = StringBuilder("action=get_series")
            if (!categoryId.isNullOrBlank()) params.append("&category_id=$categoryId")
            val url = buildApiUrl(provider, params.toString())
            val json = fetchJson(url) ?: return@withContext emptyList()
            val arr = json.optJSONArray("series") ?: json.optJSONArray("items") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                parseSeriesItem(obj, provider)
            }
        }

    private fun parseStream(obj: JSONObject, mediaType: MediaType, provider: Provider): Channel? {
        val streamId = obj.optString("stream_id", "")
        if (streamId.isBlank()) return null
        val name = obj.optString("name", "Unknown")
        val streamIcon = obj.optString("stream_icon", "")
        val categoryId = obj.optString("category_id", "")
        val categoryName = obj.optString("category_name", "")
        val rating = obj.optString("rating", "")
        val year = obj.optString("year", "")
        val container = obj.optString("container_extension", if (mediaType == MediaType.LIVE) "m3u8" else "mp4")
        val base = provider.serverUrl?.let { normalizeServerUrl(it) }
        val streamUrl = when (mediaType) {
            MediaType.MOVIE -> "$base/movie/${provider.username}/${provider.password}/$streamId.$container"
            MediaType.LIVE -> "$base/live/${provider.username}/${provider.password}/$streamId.$container"
            else -> "$base/${provider.username}/${provider.password}/$streamId.$container"
        }
        return Channel(
            id = streamId,
            name = name,
            url = streamUrl,
            logoUrl = streamIcon.ifBlank { null },
            posterUrl = if (mediaType != MediaType.LIVE) streamIcon.ifBlank { null } else null,
            categoryId = categoryId,
            categoryName = categoryName,
            mediaType = mediaType,
            rating = rating.ifBlank { null },
            year = year.ifBlank { null }
        )
    }

    private fun parseSeriesItem(obj: JSONObject, provider: Provider): Channel? {
        val seriesId = obj.optString("series_id", "")
        if (seriesId.isBlank()) return null
        val name = obj.optString("name", "Unknown")
        val cover = obj.optString("cover", "")
        val categoryId = obj.optString("category_id", "")
        val categoryName = obj.optString("category_name", "")
        val rating = obj.optString("rating", "")
        val year = obj.optString("year", "")
        // Bulk get_series actually carries a real release date (unlike movies, which
        // only expose one per-item) - confirmed against a live provider.
        val releaseDate = obj.optString("releaseDate", obj.optString("release_date", ""))
        return Channel(
            id = seriesId,
            name = name,
            url = "",
            logoUrl = cover.ifBlank { null },
            posterUrl = cover.ifBlank { null },
            categoryId = categoryId,
            categoryName = categoryName,
            mediaType = MediaType.SERIES,
            rating = rating.ifBlank { null },
            year = year.ifBlank { null },
            releaseDate = releaseDate.ifBlank { null }
        )
    }

    private fun parseEpisode(obj: JSONObject, seriesId: String, seasonKey: String, provider: Provider): Channel {
        val id = obj.optString("id", "")
        val episodeNum = obj.optInt("episode_num", 0)
        val title = obj.optString("title", "Episode")
        val info = obj.optJSONObject("info")
        val container = obj.optString("container_extension", "mp4")
        // get_series_info episodes carry no "url" field - the stream must be built manually.
        val base = provider.serverUrl?.let { normalizeServerUrl(it) }
        val streamUrl = "$base/series/${provider.username}/${provider.password}/$id.$container"
        return Channel(
            id = id,
            name = "S${seasonKey}E${episodeNum.toString().padStart(2, '0')} · $title",
            url = streamUrl,
            posterUrl = info?.optString("movie_image", null),
            description = info?.optString("plot", null),
            mediaType = MediaType.SERIES,
            categoryId = seriesId
        )
    }

    private fun buildApiUrl(provider: Provider, params: String): String {
        val base = provider.serverUrl?.let { normalizeServerUrl(it) } ?: ""
        val user = URLEncoder.encode(provider.username.orEmpty(), "UTF-8")
        val pass = URLEncoder.encode(provider.password.orEmpty(), "UTF-8")
        val sep = if (params.isNotBlank()) "&$params" else ""
        return "$base/player_api.php?username=$user&password=$pass$sep"
    }

    /** Fetch JSON from URL using OkHttp. Returns null on failure. */
    private fun fetchJson(url: String): JSONObject? {
        return try {
            val request = Request.Builder().url(url)
                .header("User-Agent", "Lumora/1.0")
                .header("Accept", "application/json, text/plain, */*")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                lastFetchError = "Server returned HTTP ${response.code}"
                Log.w(TAG, "HTTP ${response.code} for $url")
                return null
            }
            val body = response.body?.string() ?: return null
            if (body.isBlank()) {
                lastFetchError = "Server returned an empty response"
                Log.w(TAG, "Empty response body")
                return null
            }
            // Xtream sometimes wraps arrays at the root level
            return try {
                JSONObject(body)
            } catch (e: JSONException) {
                // Maybe it's a JSONArray wrapped in an object
                try {
                    val arr = JSONArray(body)
                    JSONObject().apply { put("items", arr) }
                } catch (e2: JSONException) {
                    Log.w(TAG, "Invalid JSON response: ${body.take(200)}")
                    null
                }
            }
        } catch (e: Exception) {
            lastFetchError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "Network error fetching $url: ${e.message}")
            null
        }
    }
}
