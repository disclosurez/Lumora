package com.lumora.parser

import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.util.normalizeServerUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Fast, streaming M3U playlist parser.
 * Handles standard IPTV M3U format with EXTINF tags.
 * Classifies entries as LIVE, MOVIE, or SERIES based on group-title heuristics.
 */
object M3uParser {

    data class ParseResult(
        val channels: List<Channel>,
        val header: String? = null
    )

    private val MOVIE_GROUP_KEYWORDS = setOf(
        "movie", "movies", "film", "films", "vod", "cinema", "pelicula", "peliculas"
    )
    /** Tested *before* the movie keywords, because the two overlap constantly: a provider that
     *  files its shows under "TV VOD", "Series VOD" or "VOD - TV Shows" matches "vod" as well,
     *  and whichever set is checked first wins. Series is the more specific claim of the two -
     *  "vod" only says "not live" - so it gets the first look. "tv" is deliberately absent:
     *  as a substring it would swallow every live group called "UK TV", "TV Sports" and so on. */
    private val SERIES_GROUP_KEYWORDS = setOf(
        "series", "serie", "tv show", "tv shows", "episode", "episodio", "episodes",
        "season", "seasons", "tv vod"
    )
    /** Explicit `tvg-type` values, which say outright what the entry is. */
    private val MOVIE_TVG_TYPES = setOf("movie", "movies", "vod", "film")
    private val SERIES_TVG_TYPES = setOf("series", "serie", "tv", "show", "tvshow", "tv-show")
    private val LIVE_TVG_TYPES = setOf("live", "channel", "tv channel")

    /** Parse an M3U playlist from a URL using the provided OkHttp client. */
    suspend fun parseFromUrl(url: String, client: OkHttpClient): ParseResult =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(normalizeServerUrl(url))
                .header("User-Agent", "VLC/3.0.18 LibVLC/3.0.18")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                throw Exception("HTTP ${response.code} fetching playlist")
            }

            val body = response.body ?: run {
                response.close()
                throw Exception("Empty response body")
            }
            val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))
            reader.use { parse(it) }
        }

    /** Parse M3U content from a string. */
    fun parse(content: String): ParseResult =
        parse(content.lineSequence().iterator())

    /** Parse M3U content from a BufferedReader (streaming). */
    fun parse(reader: BufferedReader): ParseResult {
        return parse(object : Iterator<String> {
            private var nextLine: String? = reader.readLine()
            override fun hasNext(): Boolean = nextLine != null
            override fun next(): String {
                val current = nextLine ?: throw NoSuchElementException()
                nextLine = reader.readLine()
                return current
            }
        })
    }

    /**
     * What kind of thing an entry is, from the most reliable signal available down to a guess.
     *
     * `group-title` alone - all this used to read - is only a media type on playlists whose
     * author happened to use it as one. Plenty of providers put the *category* there (Action,
     * Comedy, Drama, Kids), which named nothing this could match and left an entire VOD library
     * sitting in Live TV. Worse, the two keyword sets overlap: "TV VOD" is a series group that
     * contains "vod", so with movies tested first every show in it landed in Films.
     *
     * So the order is: what the playlist states outright, then what the URL structure proves,
     * then the group name as a heuristic, then live as the historical default.
     */
    private fun classifyMediaType(tvgType: String?, group: String?, url: String): MediaType {
        // 1. The provider saying so directly. Nothing beats this.
        when (tvgType?.trim()?.lowercase().orEmpty()) {
            in SERIES_TVG_TYPES -> return MediaType.SERIES
            in MOVIE_TVG_TYPES -> return MediaType.MOVIE
            in LIVE_TVG_TYPES -> return MediaType.LIVE
        }

        // 2. Xtream-compatible URL layout - /live/, /movie/ and /series/ are structural, not a
        // naming convention, so they hold whatever the provider calls its categories.
        val lowerUrl = url.lowercase()
        when {
            "/series/" in lowerUrl -> return MediaType.SERIES
            "/movie/" in lowerUrl -> return MediaType.MOVIE
            "/live/" in lowerUrl -> return MediaType.LIVE
        }

        // 3. Group name keywords, series first - see SERIES_GROUP_KEYWORDS.
        val lower = group?.lowercase() ?: return MediaType.LIVE
        if (SERIES_GROUP_KEYWORDS.any { lower.contains(it) }) return MediaType.SERIES
        if (MOVIE_GROUP_KEYWORDS.any { lower.contains(it) }) return MediaType.MOVIE
        return MediaType.LIVE
    }

    private fun parse(lineIterator: Iterator<String>): ParseResult {
        var header: String? = null
        val channels = mutableListOf<Channel>()
        var currentExtInf: String? = null

        while (lineIterator.hasNext()) {
            val line = lineIterator.next().trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U") -> {
                    header = if (line.length > 7) line.substring(7).trim() else null
                }
                line.startsWith("#EXTINF:") -> {
                    currentExtInf = line.substring(8).trim()
                }
                line.startsWith("#") -> { /* skip other tags */ }
                else -> {
                    val channel = parseChannel(line, currentExtInf)
                    if (channel != null) channels.add(channel)
                    currentExtInf = null
                }
            }
        }

        return ParseResult(channels = channels, header = header)
    }

    private fun parseChannel(url: String, extInf: String?): Channel? {
        if (url.isBlank()) return null
        val cleanUrl = url.split(" ").firstOrNull {
            it.startsWith("http") || it.startsWith("rtmp") || it.startsWith("rtsp")
        } ?: url

        var name = "Channel ${url.hashCode()}"
        var logoUrl: String? = null
        var group: String? = null
        var tvgId: String? = null
        var tvgName: String? = null
        var tvgChno: String? = null
        var tvgType: String? = null
        var year: String? = null
        var rating: String? = null

        if (extInf != null) {
            val attrRegex = """(\w[\w-]*)\s*=\s*"([^"]*)"""".toRegex()
            var matchCount = 0
            for (match in attrRegex.findAll(extInf)) {
                if (++matchCount > 50) break // max 50 attributes per EXTINF
                when (match.groupValues[1].lowercase()) {
                    "tvg-id" -> tvgId = match.groupValues[2]
                    "tvg-name" -> tvgName = match.groupValues[2]
                    "tvg-logo" -> logoUrl = match.groupValues[2].ifBlank { null }
                    "tvg-chno" -> tvgChno = match.groupValues[2]
                    "tvg-type" -> tvgType = match.groupValues[2]
                    "group-title" -> group = match.groupValues[2]
                    "year" -> year = match.groupValues[2]
                    "rating" -> rating = match.groupValues[2]
                }
            }

            val commaIndex = titleSeparatorIndex(extInf)
            if (commaIndex >= 0) {
                val rawName = extInf.substring(commaIndex + 1).trim()
                if (rawName.isNotBlank()) name = rawName
            }
        }

        val mediaType = classifyMediaType(tvgType, group, cleanUrl)

        return Channel(
            // The stream url, because an M3U has no id of its own and Channel.id defaults to
            // "". Every M3U channel therefore shared one blank id, and anything keyed on id
            // collapsed them into a single entry - most visibly the quality-version map in
            // groupLiveQualityVersions(), whose every group overwrote the same "" key, so
            // playing or previewing any channel resolved to whichever group won and the
            // guide appeared stuck on one channel. Not tvg-id: playlists repeat that across
            // quality variants, which would reintroduce the collision on a smaller scale.
            id = cleanUrl,
            name = name,
            url = cleanUrl,
            logoUrl = logoUrl,
            posterUrl = if (mediaType != MediaType.LIVE) logoUrl else null,
            group = group,
            tvgId = tvgId,
            tvgName = tvgName,
            tvgChno = tvgChno,
            mediaType = mediaType,
            categoryName = group,
            description = null,
            year = year,
            rating = rating
        )
    }

    /** Index of the comma separating the EXTINF attribute list from the display title: the
     *  FIRST comma outside double quotes. Attribute values routinely contain commas
     *  (group-title="Films, Drama"), so lastIndexOf(',') turned every title that has a comma
     *  of its own into its tail ("Breaking Bad, Season 5" became "Season 5"); and the
     *  separator can sit before a quote inside the title itself ("Best "Of" Show"), which
     *  rules out scanning after the last quoted span instead. -1 when no such comma exists. */
    private fun titleSeparatorIndex(extInf: String): Int {
        var inQuotes = false
        for ((index, c) in extInf.withIndex()) {
            when (c) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return index
            }
        }
        return -1
    }
}
