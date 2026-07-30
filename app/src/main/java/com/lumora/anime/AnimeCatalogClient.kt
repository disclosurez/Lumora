package com.lumora.anime

import android.util.Log
import com.lumora.model.Channel
import com.lumora.model.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Calendar

/**
 * Fetches anime metadata from a public GraphQL anime database (AniList).
 * Maps results to Lumora [Channel] objects for catalog display.
 * The returned channels use id format "anime:{animeId}" and carry
 * mediaType=SERIES so they appear in the Series section.
 *
 * The catalog is fetched as *sections* (Trending, this season, per-genre, ...) rather than one
 * flat list, because the Series sidebar shows Anime as an expandable parent whose children are
 * those sections. A title legitimately belongs to several of them (Trending *and* Action), so a
 * section holds channel *ids* and the channel list itself is deduplicated - one card per title,
 * referenced by however many section rows contain it. Duplicating the Channel per section would
 * instead give the same title several entries in the tab's list, each with its own watch state.
 *
 * Catalog fetching is gated on a stream_search plugin being installed and enabled.
 */
class AnimeCatalogClient(private val client: OkHttpClient) {

    companion object {
        private const val TAG = "AnimeCatalog"
        private const val API_URL = "https://graphql.anilist.co"
        private const val CATEGORY_LABEL = "Anime"
        /** Items per section. */
        private const val PER_SECTION = 30
        /** Aliased Page queries per HTTP request - AniList rejects very large queries, and one
         *  request per section would burn the (30/min) rate limit on a single catalog load. */
        private const val SECTIONS_PER_REQUEST = 5
        /** Channel id prefix, also the marker MainActivity uses for "this title is anime". */
        const val ID_PREFIX = "anime:"

        /** Genre sections, in AniList's own genre spelling. Deliberately excludes Ecchi/Hentai -
         *  the catalog query filters adult titles out, so those rows would come back empty. */
        private val GENRES = listOf(
            "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Mecha", "Music",
            "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports",
            "Supernatural", "Thriller"
        )
    }

    /** One sidebar child row: a label and the anime channel ids that belong under it. */
    data class Section(val label: String, val channelIds: List<String>)

    /** Deduplicated channels plus the section membership that groups them in the sidebar. */
    data class Catalog(val channels: List<Channel>, val sections: List<Section>)

    data class AnimeItem(
        val id: Int,
        val malId: Int?,
        val titleRomaji: String,
        val titleEnglish: String?,
        val description: String?,
        val coverImage: String?,
        val bannerImage: String?,
        val averageScore: Int?,
        val episodes: Int?,
        val seasonYear: Int?,
        val status: String?,
        val format: String?,
        val genres: List<String>
    )

    /** A section as requested: its label and the `media(...)` arguments that select it. */
    private data class SectionQuery(val label: String, val mediaArgs: String)

    /**
     * Fetches every section in as few requests as possible and returns them in display order,
     * dropping any that came back empty (a genre with no non-adult results, a next season not
     * yet announced) so the sidebar never shows a child row that filters to nothing.
     */
    fun fetchCatalog(): Catalog {
        val queries = sectionQueries()
        val channelsById = LinkedHashMap<String, Channel>()
        val sections = mutableListOf<Section>()

        for (batch in queries.chunked(SECTIONS_PER_REQUEST)) {
            val byLabel = fetchBatch(batch)
            for (query in batch) {
                val items = byLabel[query.label] ?: continue
                val ids = mutableListOf<String>()
                for (item in items) {
                    val channel = item.toChannel() ?: continue
                    channelsById.putIfAbsent(channel.id, channel)
                    if (channel.id !in ids) ids.add(channel.id)
                }
                if (ids.isNotEmpty()) sections.add(Section(query.label, ids))
            }
        }
        return Catalog(channelsById.values.toList(), sections)
    }

    /**
     * The curated sections first (what a browse screen leads with), then one row per genre.
     * "Popular This Season"/"Upcoming Next Season" are computed from today's date rather than
     * hardcoded, so the catalog stays current without a release.
     */
    private fun sectionQueries(): List<SectionQuery> {
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val season = seasonOf(cal.get(Calendar.MONTH))
        val (nextSeason, nextSeasonYear) = when (season) {
            "WINTER" -> "SPRING" to year
            "SPRING" -> "SUMMER" to year
            "SUMMER" -> "FALL" to year
            else -> "WINTER" to year + 1
        }
        val curated = listOf(
            SectionQuery("Trending Now", "sort: TRENDING_DESC"),
            SectionQuery("Popular This Season", "season: $season, seasonYear: $year, sort: POPULARITY_DESC"),
            SectionQuery("Upcoming Next Season", "season: $nextSeason, seasonYear: $nextSeasonYear, sort: POPULARITY_DESC"),
            SectionQuery("All Time Popular", "sort: POPULARITY_DESC"),
            SectionQuery("Top Rated", "sort: SCORE_DESC"),
            // Airing now, so "what can I catch up on this week" is one row rather than a filter.
            SectionQuery("Currently Airing", "status: RELEASING, sort: POPULARITY_DESC")
        )
        return curated + GENRES.map { SectionQuery(it, "genre: \"$it\", sort: POPULARITY_DESC") }
    }

    private fun seasonOf(month: Int): String = when (month) {
        Calendar.JANUARY, Calendar.FEBRUARY, Calendar.MARCH -> "WINTER"
        Calendar.APRIL, Calendar.MAY, Calendar.JUNE -> "SPRING"
        Calendar.JULY, Calendar.AUGUST, Calendar.SEPTEMBER -> "SUMMER"
        else -> "FALL"
    }

    /**
     * Runs one request holding an aliased `Page` query per section. Aliases are `s0`, `s1`, ...
     * rather than the section label because a GraphQL alias can't contain spaces or punctuation.
     */
    private fun fetchBatch(batch: List<SectionQuery>): Map<String, List<AnimeItem>> {
        val body = batch.mapIndexed { index, section ->
            """
            s$index: Page(page: 1, perPage: $PER_SECTION) {
              media(type: ANIME, isAdult: false, ${section.mediaArgs}) {
                $FIELDS
              }
            }
            """.trimIndent()
        }.joinToString("\n")
        val query = "query {\n$body\n}"

        val request = Request.Builder()
            .url(API_URL)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(JSONObject().put("query", query).toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            if (!response.isSuccessful || responseBody == null) {
                Log.w(TAG, "API HTTP ${response.code}")
                return emptyMap()
            }
            val data = JSONObject(responseBody).optJSONObject("data") ?: return emptyMap()
            batch.mapIndexedNotNull { index, section ->
                val media = data.optJSONObject("s$index")?.optJSONArray("media") ?: return@mapIndexedNotNull null
                val items = (0 until media.length()).mapNotNull { i ->
                    media.optJSONObject(i)?.let(::parseItem)
                }
                section.label to items
            }.toMap()
        } catch (e: Exception) {
            Log.w(TAG, "Fetch failed", e)
            emptyMap()
        }
    }

    private fun parseItem(obj: JSONObject): AnimeItem {
        val title = obj.optJSONObject("title") ?: JSONObject()
        val genresArray = obj.optJSONArray("genres")
        return AnimeItem(
            id = obj.optInt("id", 0),
            malId = intOrNull(obj, "idMal"),
            titleRomaji = title.optString("romaji", ""),
            titleEnglish = stringOrNull(title, "english"),
            description = stripHtml(stringOrNull(obj, "description")),
            coverImage = stringOrNull(obj.optJSONObject("coverImage"), "large"),
            bannerImage = stringOrNull(obj, "bannerImage"),
            averageScore = intOrNull(obj, "averageScore"),
            episodes = intOrNull(obj, "episodes"),
            seasonYear = intOrNull(obj, "seasonYear"),
            status = stringOrNull(obj, "status"),
            format = stringOrNull(obj, "format"),
            genres = if (genresArray == null) emptyList()
            else (0 until genresArray.length()).mapNotNull { genresArray.optString(it).takeIf { g -> g.isNotBlank() } }
        )
    }

    /** Safe nullable string from JSONObject. Returns null when key absent, null value, or blank. */
    private fun stringOrNull(obj: JSONObject?, key: String): String? {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return null
        return obj.optString(key, "").takeIf { it.isNotBlank() }
    }

    /** Safe nullable int from JSONObject. Returns null when key absent or null value. */
    private fun intOrNull(obj: JSONObject, key: String): Int? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return obj.optInt(key, -1).takeIf { it >= 0 }
    }

    private fun AnimeItem.toChannel(): Channel? {
        if (id == 0) return null
        val name = titleEnglish?.takeIf { it.isNotBlank() } ?: titleRomaji.takeIf { it.isNotBlank() } ?: return null
        val score = averageScore?.let { if (it > 0) "$it" else null }
        return Channel(
            id = "$ID_PREFIX$id",
            name = name,
            url = "",  // No direct URL — resolved via plugin at play time
            logoUrl = coverImage,
            posterUrl = coverImage,
            backdropUrl = bannerImage ?: coverImage,
            group = CATEGORY_LABEL,
            mediaType = MediaType.SERIES,
            categoryName = CATEGORY_LABEL,
            description = description ?: "",
            year = seasonYear?.toString(),
            rating = score,
            episodeNum = episodes,
            sourceProviderId = null,
            // Store MAL ID so downstream anime-aware code can pick it off the item
            // without re-running an AniList search. The host passes this to the
            // stream_search plugin's search()/resolve() as query metadata.
            tvgId = malId?.takeIf { it > 0 }?.toString()
        )
    }

    private fun stripHtml(html: String?): String? {
        if (html == null) return null
        return html
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private val FIELDS = """
        id
        idMal
        title { romaji english }
        description
        coverImage { large }
        bannerImage
        averageScore
        episodes
        seasonYear
        status
        format
        genres
    """.trimIndent()
}
