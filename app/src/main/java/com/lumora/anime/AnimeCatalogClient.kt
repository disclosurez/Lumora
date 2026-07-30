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
 * Catalog fetching is gated on a stream_search plugin being installed and enabled.
 */
class AnimeCatalogClient(private val client: OkHttpClient) {

    companion object {
        private const val TAG = "AnimeCatalog"
        private const val API_URL = "https://graphql.anilist.co"
        private const val CATEGORY_LABEL = "Anime"
        private const val MAX_ITEMS = 50
    }

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
        val format: String?
    )

    /**
     * Fetches trending anime + current season's popular anime.
     * Returns merged list (trending first, season second, deduplicated by id).
     */
    fun fetchCatalog(): List<Channel> {
        val trending = fetchPage("trending: Page(page: 1, perPage: $MAX_ITEMS) { media(sort: TRENDING_DESC, type: ANIME) { ... fields ... } }")
        val season = fetchCurrentSeason()

        // Merge: trending first, then season items not already present
        val seen = trending.map { it.id }.toSet()
        val merged = trending + season.filter { it.id !in seen }
        return merged.mapNotNull { it.toChannel() }
    }

    private fun fetchCurrentSeason(): List<AnimeItem> {
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val season = when (cal.get(Calendar.MONTH)) {
            Calendar.JANUARY, Calendar.FEBRUARY, Calendar.MARCH -> "WINTER"
            Calendar.APRIL, Calendar.MAY, Calendar.JUNE -> "SPRING"
            Calendar.JULY, Calendar.AUGUST, Calendar.SEPTEMBER -> "SUMMER"
            else -> "FALL"
        }
        val query = "season: Page(page: 1, perPage: $MAX_ITEMS) { media(season: $season, seasonYear: $year, sort: POPULARITY_DESC, type: ANIME) { ... fields ... } }"
        return fetchPage(query)
    }

    private fun fetchPage(queryFragment: String): List<AnimeItem> {
        val query = """
            query {
              $queryFragment
            }
        """.trimIndent()

        // Replace the ...fields... placeholder with actual field list
        val fullQuery = query.replace("... fields ...", FIELDS)

        val body = JSONObject()
            .put("query", fullQuery)

        val request = Request.Builder()
            .url(API_URL)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return emptyList()
            if (!response.isSuccessful) {
                Log.w(TAG, "API HTTP ${response.code}")
                return emptyList()
            }

            val root = JSONObject(responseBody)
            val pageKey = queryFragment.substringBefore(":").trim()
            val mediaArray = root.optJSONObject("data")
                ?.optJSONObject(pageKey)
                ?.optJSONArray("media") ?: return emptyList()

            val results = mutableListOf<AnimeItem>()
            for (i in 0 until mediaArray.length()) {
                val item = mediaArray.optJSONObject(i) ?: continue
                results.add(parseItem(item))
            }
            results
        } catch (e: Exception) {
            Log.w(TAG, "Fetch failed", e)
            emptyList()
        }
    }

    private fun parseItem(obj: JSONObject): AnimeItem {
        val title = obj.optJSONObject("title") ?: JSONObject()
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
            format = stringOrNull(obj, "format")
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
            id = "anime:$id",
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
            sourceProviderId = null
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
    """.trimIndent()
}
