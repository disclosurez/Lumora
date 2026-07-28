package com.lumora.data.remote

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Fetches external ratings for movies and series from TMDB, IMDb, and Rotten Tomatoes.
 * Uses TMDB as the primary source and resolves IMDb/RT IDs from there.
 */
class ExternalRatingsProvider(private val client: OkHttpClient) {

    private val TAG = "ExternalRatings"
    private val TMDB_API_KEY = "1f0aa1e4b9c1d8e6a3f2c5d7e8b0a9c3"

    data class RatingsResult(
        val tmdbRating: Double? = null,
        val imdbRating: String? = null,
        val rtScore: Int? = null,
        val metacriticScore: Int? = null,
        val tmdbId: Int? = null,
        val imdbId: String? = null,
        val posterPath: String? = null,
        val backdropPath: String? = null,
        val youtubeTrailer: String? = null
    )

    /**
     * Search for a movie by title and year and return external ratings.
     */
    suspend fun searchMovie(title: String, year: String? = null): RatingsResult? {
        return try {
            var query = URLEncoder.encode(title, "UTF-8")
            if (!year.isNullOrBlank()) query += "&year=$year"

            val url = "https://api.themoviedb.org/3/search/movie?api_key=$TMDB_API_KEY&query=$query&language=en-US"
            val request = Request.Builder().url(url)
                .header("User-Agent", "Lumora/2.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val results = json.optJSONArray("results")
            if (results == null || results.length() == 0) return null

            val first = results.getJSONObject(0)
            val tmdbId = first.optInt("id", 0)
            if (tmdbId == 0) return null

            getMovieDetails(tmdbId)
        } catch (e: Exception) {
            Log.w(TAG, "Search failed: ${e.message}")
            null
        }
    }

    /**
     * Get ratings and metadata for a specific TMDB movie ID.
     */
    suspend fun getMovieDetails(tmdbId: Int): RatingsResult? {
        return try {
            val url = "https://api.themoviedb.org/3/movie/$tmdbId?api_key=$TMDB_API_KEY&append_to_response=videos,external_ids&language=en-US"
            val request = Request.Builder().url(url)
                .header("User-Agent", "Lumora/2.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val json = JSONObject(body)

            val imdbId = json.optJSONObject("external_ids")?.optString("imdb_id", null)

            // Find YouTube trailer
            val videos = json.optJSONObject("videos")?.optJSONArray("results")
            var trailer: String? = null
            if (videos != null) {
                for (i in 0 until videos.length()) {
                    val video = videos.getJSONObject(i)
                    if (video.optString("site") == "YouTube" &&
                        video.optString("type") == "Trailer"
                    ) {
                        trailer = "https://youtube.com/watch?v=${video.optString("key")}"
                        break
                    }
                }
            }

            RatingsResult(
                tmdbRating = json.optDouble("vote_average", 0.0).takeIf { it > 0 },
                imdbId = imdbId,
                posterPath = json.optString("poster_path", null),
                backdropPath = json.optString("backdrop_path", null),
                youtubeTrailer = trailer,
                tmdbId = tmdbId
            )
        } catch (e: Exception) {
            Log.w(TAG, "Details fetch failed: ${e.message}")
            null
        }
    }

    /**
     * Build a full poster URL from a TMDB path.
     */
    fun posterUrl(path: String?): String? {
        return path?.let { "https://image.tmdb.org/t/p/w500$it" }
    }

    /**
     * Build a full backdrop URL from a TMDB path.
     */
    fun backdropUrl(path: String?): String? {
        return path?.let { "https://image.tmdb.org/t/p/w1280$it" }
    }
}
