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

    /** One page (20 titles) of TMDB's popularity ranking, English-language originals only -
     *  used by the Series/Films tabs' no-provider fallback, which wants far more than
     *  trending/week's ~10-per-type gives them. /movie/popular and /tv/popular are fixed
     *  lists with no filter params, so the discover endpoints are used instead - same
     *  ranking (sort_by=popularity.desc), with with_original_language as the one thing added.
     *  [page] is 1-based, same as TMDB's own paging. */
    suspend fun popularMovies(page: Int): List<Channel> = withContext(Dispatchers.IO) {
        val body = fetchBody("/discover/movie", "language=en-US&page=$page&sort_by=popularity.desc&with_original_language=en")
            ?: return@withContext emptyList()
        parse(body, forcedType = "movie")
    }

    suspend fun popularTv(page: Int): List<Channel> = withContext(Dispatchers.IO) {
        val body = fetchBody("/discover/tv", "language=en-US&page=$page&sort_by=popularity.desc&with_original_language=en")
            ?: return@withContext emptyList()
        parse(body, forcedType = "tv")
    }

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
        // TMDB's search does no fuzzy matching on trailing junk: a panel's per-season series
        // entry ("Breaking Bad S05", "Peaky Blinders - Season 1", "Dark 3rd Season") returns
        // literally zero results, which is why series resolved to nothing while films - which
        // carry no such suffix - matched fine. So try progressively shorter forms, and only
        // fall back to the next one when the previous found nothing.
        for (candidate in searchCandidates(cleaned, isSeries)) {
            // Multi-search first (one request, and what movies have always used), then the
            // typed endpoint: /search/tv finds shows that multi's cross-type popularity
            // ranking pushes off page one entirely.
            val hit = pickMatch(search(candidate), isSeries, year, candidate)
                ?: pickMatch(searchTyped(candidate, isSeries), isSeries, year, candidate)
            if (hit != null) {
                val parts = hit.id.split(":")
                if (parts.size != 3) return null
                val tmdbId = parts[2].toIntOrNull() ?: return null
                android.util.Log.d("TmdbClient", "resolveId('$title' -> '$candidate'): ${hit.name} = ${parts[1]}/$tmdbId")
                return parts[1] to tmdbId
            }
        }
        android.util.Log.d("TmdbClient", "resolveId('$title' -> '$cleaned', series=$isSeries): no match")
        return null
    }

    /** Search terms to try for [cleaned], longest first: as given, then (for a series) without
     *  a trailing season/complete marker, then without a trailing year. Each step is only
     *  reached when the fuller form found nothing, so "1923" or "Yellowstone 2018" still match
     *  themselves before anything is trimmed off them. */
    private fun searchCandidates(cleaned: String, isSeries: Boolean): List<String> {
        val out = ArrayList<String>(3)
        out.add(cleaned)
        if (isSeries) {
            // Repeated because both markers can stack: "Vikings S06 Complete".
            var stripped = cleaned
            while (true) {
                val next = SEASON_SUFFIX_REGEX.replace(stripped, "").trim(' ', '-', ':', '_')
                if (next == stripped || next.isBlank()) break
                stripped = next
            }
            if (stripped != cleaned) out.add(stripped)
        }
        val noYear = TRAILING_YEAR_REGEX.replace(out.last(), "").trim()
        if (noYear.isNotBlank() && noYear != out.last()) out.add(noYear)
        return out
    }

    /** Best result of the wanted type: an exact title match (with the year too, when known)
     *  beats a merely popular one - "Alone Season 10" otherwise resolves to "Alone Together". */
    private fun pickMatch(results: List<Channel>, isSeries: Boolean, year: String?, title: String): Channel? {
        val typed = results.filter { (it.mediaType == MediaType.SERIES) == isSeries }
        if (typed.isEmpty()) return null
        val wanted = normalizeTitle(title)
        return typed.firstOrNull { normalizeTitle(it.name) == wanted && year != null && it.year == year }
            ?: typed.firstOrNull { normalizeTitle(it.name) == wanted }
            ?: typed.firstOrNull { year != null && it.year == year }
            ?: typed.first()
    }

    private fun normalizeTitle(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /** Type-specific search. These endpoints don't return a `media_type` field at all, so the
     *  type is supplied to the parser - without it every result is dropped as "non-playable". */
    private suspend fun searchTyped(query: String, isSeries: Boolean): List<Channel> =
        withContext(Dispatchers.IO) {
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            val path = if (isSeries) "/search/tv" else "/search/movie"
            val body = fetchBody(path, "include_adult=false&language=en-US&query=$q")
                ?: return@withContext emptyList()
            parse(body, forcedType = if (isSeries) "tv" else "movie")
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

    /**
     * Episodes of one season, keyed by episode number, for filling in what a provider left
     * blank on its own episode rows (title, plot, still image).
     *
     * Cached per (show, season) for the process: the detail screen re-reads a season every
     * time the user chips back and forth between them, and this answer never changes within
     * a session. A season that TMDB has nothing for caches as empty, so a miss is asked once.
     */
    suspend fun tvEpisodes(tvId: Int, seasonNumber: Int): Map<Int, TvEpisode> {
        val cacheKey = "$tvId:$seasonNumber"
        episodeCache[cacheKey]?.let { return it }
        val parsed = withContext(Dispatchers.IO) {
            val body = fetchBody("/tv/$tvId/season/$seasonNumber", "language=en-US")
                ?: return@withContext emptyMap<Int, TvEpisode>()
            val arr = JSONObject(body).optJSONArray("episodes") ?: return@withContext emptyMap()
            val out = LinkedHashMap<Int, TvEpisode>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val number = o.optInt("episode_number", -1)
                if (number < 0) continue
                out[number] = TvEpisode(
                    number = number,
                    name = o.optString("name").takeIf { it.isNotBlank() },
                    overview = o.optString("overview").takeIf { it.isNotBlank() },
                    // A 300px-wide still is what the episode row's thumbnail slot actually
                    // renders at - the poster sizes would be wasted bytes per row.
                    stillUrl = o.optString("still_path").takeIf { it.isNotBlank() }?.let { "$STILL$it" },
                    airDate = o.optString("air_date").takeIf { it.isNotBlank() }
                )
            }
            out
        }
        episodeCache[cacheKey] = parsed
        return parsed
    }

    /**
     * Full detail for one title - overview, backdrop, release date, genres and the credit
     * lines - for filling in what a provider left blank on the detail screen. Works for both
     * a film ([mediaType] "movie") and a show ("tv").
     *
     * One request, not three: `append_to_response=credits` returns the cast/crew in the same
     * body, which matters on a TV stick opening this on every detail screen. Cached per title
     * for the process, same reasoning as [tvEpisodes].
     */
    suspend fun titleDetails(mediaType: String, id: Int): TitleDetails? {
        val cacheKey = "$mediaType:$id"
        titleCache[cacheKey]?.let { return it.value }
        val parsed = withContext(Dispatchers.IO) {
            val body = fetchBody("/$mediaType/$id", "language=en-US&append_to_response=credits")
                ?: return@withContext null
            val o = JSONObject(body)
            val genres = o.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
            }.orEmpty()
            val credits = o.optJSONObject("credits")
            val director = credits?.optJSONArray("crew")?.let { crew ->
                (0 until crew.length()).mapNotNull { crew.optJSONObject(it) }
                    .firstOrNull { it.optString("job") == "Director" }
                    ?.optString("name")?.takeIf(String::isNotBlank)
            }
            // Top-billed only: the full cast list runs to dozens of names and the detail
            // screen gives this one line.
            val cast = credits?.optJSONArray("cast")?.let { arr ->
                (0 until minOf(arr.length(), CAST_LIMIT)).mapNotNull {
                    arr.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank)
                }
            }.orEmpty()
            TitleDetails(
                overview = o.optString("overview").takeIf { it.isNotBlank() },
                backdropUrl = o.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { "$BACKDROP$it" },
                posterUrl = o.optString("poster_path").takeIf { it.isNotBlank() }?.let { "$IMG$it" },
                releaseDate = o.optString("release_date").ifBlank { o.optString("first_air_date") }
                    .takeIf { it.isNotBlank() },
                genre = genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
                director = director,
                cast = cast.takeIf { it.isNotEmpty() }?.joinToString(", "),
                rating = o.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { "%.1f".format(it) }
            )
        }
        // Boxed so a title TMDB has nothing for is still a cache hit rather than a re-request
        // on every reopen of that detail screen.
        titleCache[cacheKey] = Boxed(parsed)
        return parsed
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
                    if (resp.code == 429) {
                        val retryAfter = resp.header("Retry-After")?.toLongOrNull() ?: 1
                        kotlinx.coroutines.delay(retryAfter * 1000)
                        // Loop naturally continues to next key after use{} block
                    }
                    // Non-2xx (bad/limited key): fall through to the next key.
                }
            } catch (e: Exception) {
                // Network error: try the next key too.
            }
        }
        null
    }

    private fun parse(body: String, forcedType: String? = null): List<Channel> {
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Channel>(results.length())
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            // /search/tv and /search/movie omit media_type entirely - the caller knows it.
            val type = o.optString("media_type").ifBlank { forcedType ?: "" }
            val mediaType = when (type) {
                "movie" -> MediaType.MOVIE
                "tv" -> MediaType.SERIES
                else -> continue // skip people and anything non-playable
            }
            // News shows (Tagesschau, nightly bulletins, ...) are "tv" to TMDB same as any
            // scripted series, and popular enough in some regions to rank alongside them -
            // not what "Series" means here, so drop anything carrying that genre.
            val genreIds = o.optJSONArray("genre_ids")
            if (genreIds != null) {
                var isNews = false
                for (g in 0 until genreIds.length()) {
                    if (genreIds.optInt(g) == GENRE_NEWS) { isNews = true; break }
                }
                if (isNews) continue
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

    /** One TMDB episode's fillable fields; every one is optional because TMDB itself
     *  routinely has a name but no still, or a still but no overview. */
    data class TvEpisode(
        val number: Int,
        val name: String?,
        val overview: String?,
        val stillUrl: String?,
        /** ISO "YYYY-MM-DD" - the air date providers routinely omit on episodes. */
        val airDate: String? = null
    )

    /** Everything the detail screen can fill in from TMDB; all optional. */
    data class TitleDetails(
        val overview: String?,
        val backdropUrl: String?,
        val posterUrl: String?,
        val releaseDate: String?,
        val genre: String?,
        val director: String?,
        val cast: String?,
        val rating: String?
    )

    /** ConcurrentHashMap rejects null values, so a "TMDB knows nothing about this" result
     *  has to be wrapped to be cacheable at all. */
    private class Boxed(val value: TitleDetails?)

    private val episodeCache = java.util.concurrent.ConcurrentHashMap<String, Map<Int, TvEpisode>>()
    private val titleCache = java.util.concurrent.ConcurrentHashMap<String, Boxed>()

    companion object {
        /** TMDB v3 API keys, tried in order (fallback on failure). Empty list = Discover disabled. */
        val KEYS = listOf(
            "c3515fdc674ea2bd7b514f4bc3616a4a",
            "1865f43a0549ca50d341dd9ab8b29f49",
            "f562845c2beca65e1028ff2e31ccaff1"
        ).filter { it.isNotBlank() }

        /** First usable key, or "" if none are configured. For callers outside this client that
         *  take a bare key string rather than going through [request] - notably the ported
         *  scraper stack's own TMDB layer (com.lumora.scraper.utils.TMDb3), which has no
         *  rotation of its own and would otherwise need a second key baked in. */
        fun firstKeyOrEmpty(): String = KEYS.firstOrNull() ?: ""

        /** Trailing "S05" / "Season 2" / "S01E03" / "3rd Season" / "Complete Series" - how
         *  panels name a series entry, and poison to TMDB's exact-ish search. */
        private val SEASON_SUFFIX_REGEX = Regex(
            """(?i)[\s\-:_]*\b(s(?:eason)?\s*\d{1,3}(?:\s*e\s*\d{1,3})?|\d{1,2}(?:st|nd|rd|th)\s+season|complete(?:\s+series)?)\s*$"""
        )
        private val TRAILING_YEAR_REGEX = Regex("""\s*\(?(?:19|20)\d{2}\)?\s*$""")

        private const val BASE = "https://api.themoviedb.org/3"
        private const val IMG = "https://image.tmdb.org/t/p/w342"
        private const val BACKDROP = "https://image.tmdb.org/t/p/w780"
        private const val STILL = "https://image.tmdb.org/t/p/w300"
        private const val CAST_LIMIT = 8
        // TMDB's own TV genre id - https://developer.themoviedb.org/reference/genre-tv-list
        private const val GENRE_NEWS = 10763
    }
}
