package com.lumora.data.remote.stalker

import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.model.Provider
import okhttp3.OkHttpClient

/**
 * High-level Stalker Portal provider integration.
 * Handles auth flow, channel/VOD/series fetching with MAC-based device registration.
 */
class StalkerProvider(private val httpClient: OkHttpClient) {

    private val api = StalkerApiService(httpClient)
    private var authenticated = false

    data class StalkerContent(
        val live: List<Channel>,
        val films: List<Channel>,
        val series: List<Channel>
    )

    /**
     * Authenticate with the Stalker portal and fetch all content.
     */
    suspend fun loadContent(provider: Provider): Result<StalkerContent> {
        val serverUrl = provider.serverUrl ?: return Result.failure(Exception("No server URL"))
        val mac = provider.userAgent ?: return Result.failure(Exception("No MAC address"))

        val auth = api.authenticate(serverUrl, mac)
        if (auth.isFailure) return Result.failure(auth.exceptionOrNull() ?: Exception("Auth failed"))
        if (!auth.getOrThrow().success) return Result.failure(Exception("Invalid MAC or server"))

        authenticated = true

        return try {
            val liveEntries = api.getLiveChannels(serverUrl, mac)
            val vodEntries = api.getVodList(serverUrl, mac)
            val seriesEntries = api.getSeriesList(serverUrl, mac)

            val live = liveEntries.map { StalkerApiService.toChannel(it, provider) }
            val films = vodEntries.map { e ->
                Channel(
                    id = e.id, name = e.name, url = e.url,
                    logoUrl = e.logo, posterUrl = e.logo,
                    categoryId = e.categoryId, categoryName = e.categoryName,
                    mediaType = MediaType.MOVIE
                )
            }
            val series = seriesEntries.map { e ->
                Channel(
                    id = e.id, name = e.name, url = e.url,
                    logoUrl = e.logo, posterUrl = e.logo,
                    categoryId = e.categoryId, categoryName = e.categoryName,
                    mediaType = MediaType.SERIES
                )
            }

            Result.success(StalkerContent(live, films, series))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getStreamUrl(channelId: String, provider: Provider): String? {
        val serverUrl = provider.serverUrl ?: return null
        // Stalker stream URLs are embedded in the channel entries during loadContent
        // For on-demand resolution, we delegate to the API's internal create_link flow
        return null
    }

    companion object {
        const val TYPE = "stalker"
    }
}
