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
/** Per-provider EPG clock-shift estimate (seconds), keyed by server URL - survives the
 *  per-call XtreamClient instances so the guide stays consistent across channels. */
private val epgShiftSecondsCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

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
                val auth = if (userInfo.has("auth")) {
                    val raw = userInfo.get("auth")
                    when (raw) {
                        is Boolean -> if (raw) "1" else "0"
                        else -> raw.toString()
                    }
                } else "0"
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
            val listings = (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = decodeEpgText(obj.optString("title", "")) ?: return@mapNotNull null
                val start = obj.optString("start_timestamp", "0").toLongOrNull() ?: return@mapNotNull null
                val stop = obj.optString("stop_timestamp", "0").toLongOrNull() ?: return@mapNotNull null
                EpgProgram(title, start, stop)
            }
            // Some panels store their local wall-clock as the UTC epoch, so their listings
            // read hours in the future on a UTC-reckoning device. Shift them so the guide
            // lines up with the device clock. Computed once per provider and cached.
            val shift = epgEpochShiftSeconds(provider.serverUrl, listings)
            if (shift == 0L) listings
            else listings.map { it.copy(startTimestamp = it.startTimestamp - shift, stopTimestamp = it.stopTimestamp - shift) }
        }

    /**
     * The channel's whole stored guide (`get_simple_data_table`), not just the next few
     * entries - Catch Up needs a full day's listings for a day that has already passed,
     * which get_short_epg (forward-looking, limited) can't answer.
     *
     * Same panel quirks as [getShortEpg]: base64 titles and the local-clock-as-UTC epoch
     * offset, corrected through the same shared shift so a catch-up listing lines up with
     * the guide the user saw live.
     */
    suspend fun getEpgTable(provider: Provider, streamId: String): List<EpgProgram> =
        withContext(Dispatchers.IO) {
            val url = buildApiUrl(provider, "action=get_simple_data_table&stream_id=$streamId")
            val json = fetchJson(url) ?: return@withContext emptyList()
            val arr = json.optJSONArray("epg_listings") ?: json.optJSONArray("items")
                ?: return@withContext emptyList()
            val listings = (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = decodeEpgText(obj.optString("title", "")) ?: return@mapNotNull null
                val start = obj.optString("start_timestamp", "0").toLongOrNull() ?: return@mapNotNull null
                val stop = obj.optString("stop_timestamp", "0").toLongOrNull() ?: return@mapNotNull null
                if (start <= 0L || stop <= start) return@mapNotNull null
                EpgProgram(title, start, stop)
            }.sortedBy { it.startTimestamp }
            val shift = epgEpochShiftSeconds(provider.serverUrl, listings)
            if (shift == 0L) listings
            else listings.map { it.copy(startTimestamp = it.startTimestamp - shift, stopTimestamp = it.stopTimestamp - shift) }
        }

    /** Estimated per-provider EPG timezone shift, seconds. 0 = panel is spec-compliant. */
    private fun epgEpochShiftSeconds(serverUrl: String?, listings: List<EpgProgram>): Long {
        if (serverUrl == null) return 0L
        epgShiftSecondsCache[serverUrl]?.let { return it }
        val firstStart = listings.minOfOrNull { it.startTimestamp } ?: return 0L
        val now = System.currentTimeMillis() / 1000
        val gap = firstStart - now
        // A spec-compliant panel lists the current programme first (it starts at or before
        // now); at worst the first entry is the next one, still within the hour. A gap over
        // an hour means the panel is clock-shifted - estimate the shift to the nearest
        // half-hour and cache it, so every channel's guide agrees with the device clock.
        val shift = if (gap > 3600L) {
            (Math.round(gap / 1800.0).toLong() * 1800L).coerceIn(3600L, 14 * 3600L)
        } else 0L
        epgShiftSecondsCache[serverUrl] = shift
        return shift
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
        val url = buildApiUrl(provider, "action=get_vod_info&vod_id=${URLEncoder.encode(vodId, "UTF-8")}")
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
            val url = buildApiUrl(provider, "action=get_series_info&series_id=${URLEncoder.encode(seriesId, "UTF-8")}")
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
                // Some panels pad category names with non-breaking spaces (U+00A0) that render as
                // odd gaps and break keyword grouping; fold them to plain spaces and collapse runs.
                val name = obj.optString("category_name", "")
                    .replace(' ', ' ')
                    .replace(Regex("\\s+"), " ")
                    .trim()
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

    /**
     * A stream's category id. Prefers the singular `category_id`, but some panels leave it null and
     * only populate a plural `category_ids` array; fall back to the first entry there. Only the
     * first is used because across the live providers surveyed no stream ever belonged to more than
     * one category - the plural field is redundant, not true multi-membership.
     */
    private fun resolveCategoryId(obj: JSONObject): String {
        val single = obj.optString("category_id", "")
        if (single.isNotBlank() && single != "null") return single
        val arr = obj.optJSONArray("category_ids") ?: return ""
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "")
            if (v.isNotBlank() && v != "null") return v
        }
        return ""
    }

    private fun parseStream(obj: JSONObject, mediaType: MediaType, provider: Provider): Channel? {
        val streamId = obj.optString("stream_id", "")
        if (streamId.isBlank()) return null
        val name = obj.optString("name", "Unknown")
        val streamIcon = obj.optString("stream_icon", "")
        val categoryId = resolveCategoryId(obj)
        // optString returns the literal "null" for a JSON null value on Android, not "" - treat
        // that as absent so it doesn't become a category row named "null".
        val categoryName = obj.optString("category_name", "").let { if (it == "null") "" else it }
        val rating = obj.optString("rating", "")
        val year = obj.optString("year", "")
        val container = obj.optString("container_extension", if (mediaType == MediaType.LIVE) "m3u8" else "mp4")
        val base = provider.serverUrl?.let { normalizeServerUrl(it) }
        val streamUrl = when (mediaType) {
            MediaType.MOVIE -> "$base/movie/${provider.username}/${provider.password}/$streamId.$container"
            MediaType.LIVE -> "$base/live/${provider.username}/${provider.password}/$streamId.$container"
            else -> "$base/${provider.username}/${provider.password}/$streamId.$container"
        }
        // Archive/catch-up availability, live only. Panels are inconsistent about the JSON
        // type here - some send tv_archive as a number, some as the string "1" - and
        // optInt returns 0 for a string value, so read both shapes.
        val archiveFlag = obj.opt("tv_archive")?.toString()?.trim()
        val hasArchive = mediaType == MediaType.LIVE && (archiveFlag == "1" || archiveFlag == "true")
        val archiveDays = if (hasArchive) {
            obj.opt("tv_archive_duration")?.toString()?.trim()?.toIntOrNull() ?: 0
        } else 0
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
            year = year.ifBlank { null },
            // A panel that advertises the archive but reports 0 days kept has nothing to
            // play back, so it doesn't count as catch-up capable.
            tvArchive = hasArchive && archiveDays > 0,
            tvArchiveDays = archiveDays
        )
    }

    private fun parseSeriesItem(obj: JSONObject, provider: Provider): Channel? {
        val seriesId = obj.optString("series_id", "")
        if (seriesId.isBlank()) return null
        val name = obj.optString("name", "Unknown")
        val cover = obj.optString("cover", "")
        val categoryId = resolveCategoryId(obj)
        // optString returns the literal "null" for a JSON null value on Android, not "" - treat
        // that as absent so it doesn't become a category row named "null".
        val categoryName = obj.optString("category_name", "").let { if (it == "null") "" else it }
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
            episodeNum = episodeNum.takeIf { it > 0 },
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
