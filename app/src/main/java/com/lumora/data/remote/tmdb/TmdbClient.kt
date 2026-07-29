package com.lumora.data.remote.tmdb

import com.lumora.model.Channel
import com.lumora.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal TMDB (The Movie Database) client for the Discover tab: browse trending titles and
 * search movies/series that aren't in any configured provider's catalog. Results are mapped to
 * [Channel]s (with no playback URL) purely so the existing poster grid can render them; playing
 * one is handled separately by a stream-search plugin.
 *
 * Only public read endpoints are used, with a v3 API key. Set [API_KEY] to a real key (TMDB ->
 * Settings -> API -> API Read Access is the v4 token; the shorter "API Key (v3 auth)" is what
 * goes here). With no key the calls no-op and Discover shows an empty state.
 */
class TmdbClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun hasKey(): Boolean = KEYS.isNotEmpty()

    /** Trending movies + shows this week - the default Discover grid before any search. */
    suspend fun trending(): List<Channel> =
        get("/trending/all/week", "language=en-US")

    /** Multi-search across movies and TV; people/other media_types are dropped. */
    suspend fun search(query: String): List<Channel> {
        if (query.isBlank()) return emptyList()
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return get("/search/multi", "include_adult=false&language=en-US&query=$q")
    }

    /**
     * Best YouTube trailer key for a movie/tv title, or null if TMDB has none. Prefers an
     * official "Trailer" over a "Teaser" over whatever else is listed.
     */
    suspend fun trailerKey(mediaType: String, id: Int): String? = withContext(Dispatchers.IO) {
        val path = if (mediaType == "tv") "/tv/$id/videos" else "/movie/$id/videos"
        val body = fetchBody(path, "language=en-US") ?: return@withContext null
        val results = JSONObject(body).optJSONArray("results") ?: return@withContext null
        val youtube = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
            .filter { it.optString("site") == "YouTube" && it.optString("key").isNotBlank() }
        (youtube.firstOrNull { it.optString("type") == "Trailer" && it.optBoolean("official") }
            ?: youtube.firstOrNull { it.optString("type") == "Trailer" }
            ?: youtube.firstOrNull { it.optString("type") == "Teaser" }
            ?: youtube.firstOrNull())?.optString("key")
    }

    /**
     * Resolves a catalog title (no TMDB id of its own) to a `(mediaType, id)` pair via search,
     * so provider/Jellyfin content can still look up a trailer. Matches on year when known.
     */
    suspend fun resolveId(title: String, year: String?, isSeries: Boolean): Pair<String, Int>? {
        // IPTV/Jellyfin titles often carry quality/language/source tags ("Movie Name 4K
        // [MULTI]", "Movie Name (2026) HDR") that TMDB's search tolerates poorly - strip
        // anything in brackets/parens and trailing quality/audio tags before searching.
        val cleaned = title
            .replace(Regex("^[A-Z0-9+]{2,6}\\s*-\\s*"), "") // strip provider/channel prefix, e.g. "TOP - ", "DSC+ - "
            .replace(Regex("[\\[({][^\\])}]*[\\])}]"), " ")
            .replace(Regex("(?i)\\b(4k|2160p|1080p|720p|hdr|multi|dual audio|dubbed|subbed|remux|web[- ]?dl|bluray)\\b"), " ")
            .replace("_", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { title }
        val all = search(cleaned)
        android.util.Log.d("TmdbClient", "resolveId('$title' -> '$cleaned'): ${all.size} results")
        val byTypeAndYear = all.filter { (it.mediaType == MediaType.SERIES) == isSeries && (year == null || it.year == year) }
        val byTypeOnly = all.filter { (it.mediaType == MediaType.SERIES) == isSeries }
        val id = (byTypeAndYear.firstOrNull() ?: byTypeOnly.firstOrNull())?.id
        if (id == null) {
            android.util.Log.d("TmdbClient", "resolveId('$cleaned'): no type/year match among ${all.size} results")
            return null
        }
        val parts = id.split(":")
        if (parts.size != 3) return null
        val tmdbId = parts[2].toIntOrNull() ?: return null
        return parts[1] to tmdbId
    }

    /** Seasons of a TV show (season 0 / specials dropped), for the episode picker. */
    suspend fun tvSeasons(tvId: Int): List<TvSeason> = withContext(Dispatchers.IO) {
        val body = fetchBody("/tv/$tvId", "language=en-US") ?: return@withContext emptyList()
        val arr = JSONObject(body).optJSONArray("seasons") ?: return@withContext emptyList()
        val out = ArrayList<TvSeason>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val number = o.optInt("season_number", -1)
            val count = o.optInt("episode_count", 0)
            if (number < 1 || count < 1) continue // skip specials (season 0) and empty seasons
            out.add(TvSeason(number, count, o.optString("name").ifBlank { "Season $number" }))
        }
        out
    }

    /** Tries each configured key in turn, so a dead/rate-limited key falls back to the next. */
    private suspend fun get(path: String, params: String): List<Channel> =
        fetchBody(path, params)?.let { parse(it) } ?: emptyList()

    private suspend fun fetchBody(path: String, params: String): String? = withContext(Dispatchers.IO) {
        for (key in KEYS) {
            val url = "$BASE$path?api_key=$key&$params"
            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (!body.isNullOrBlank()) return@withContext body
                    }
                    // Non-2xx (bad/limited key): fall through to the next key.
                }
            } catch (e: Exception) {
                // Network error: try the next key too.
            }
        }
        null
    }

    private fun parse(body: String): List<Channel> {
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Channel>(results.length())
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val type = o.optString("media_type")
            val mediaType = when (type) {
                "movie" -> MediaType.MOVIE
                "tv" -> MediaType.SERIES
                else -> continue // skip people and anything non-playable
            }
            val title = o.optString("title").ifBlank { o.optString("name") }
            if (title.isBlank()) continue
            val date = o.optString("release_date").ifBlank { o.optString("first_air_date") }
            val year = date.take(4).takeIf { it.length == 4 }
            val poster = o.optString("poster_path").takeIf { it.isNotBlank() }?.let { "$IMG$it" }
            val backdrop = o.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { "$BACKDROP$it" }
            val id = o.optInt("id")
            out.add(
                Channel(
                    id = "tmdb:$type:$id",
                    name = title,
                    url = "",
                    posterUrl = poster,
                    backdropUrl = backdrop,
                    mediaType = mediaType,
                    year = year,
                    description = o.optString("overview").takeIf { it.isNotBlank() },
                    rating = o.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { "%.1f".format(it) }
                )
            )
        }
        return out
    }

    data class TvSeason(val number: Int, val episodeCount: Int, val name: String)

    companion object {
        /** TMDB v3 API keys, tried in order (fallback on failure). Empty list = Discover disabled. */
        val KEYS = listOf(
            "c3515fdc674ea2bd7b514f4bc3616a4a",
            "1865f43a0549ca50d341dd9ab8b29f49",
            "f562845c2beca65e1028ff2e31ccaff1"
        ).filter { it.isNotBlank() }

        private const val BASE = "https://api.themoviedb.org/3"
        private const val IMG = "https://image.tmdb.org/t/p/w342"
        private const val BACKDROP = "https://image.tmdb.org/t/p/w780"
    }
}
