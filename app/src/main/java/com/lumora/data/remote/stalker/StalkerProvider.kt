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
                    logoUrl = e.logo, posterUrl = e.poster ?: e.logo,
                    categoryId = e.categoryId, categoryName = e.categoryName,
                    description = e.description, year = e.releaseDate?.take(4), releaseDate = e.releaseDate,
                    rating = e.rating, stalkerCmd = e.rawCmd, mediaType = MediaType.MOVIE
                )
            }
            val series = seriesEntries.map { e ->
                Channel(
                    id = e.id, name = e.name, url = e.url,
                    logoUrl = e.logo, posterUrl = e.poster ?: e.logo,
                    categoryId = e.categoryId, categoryName = e.categoryName,
                    description = e.description, year = e.releaseDate?.take(4), releaseDate = e.releaseDate,
                    rating = e.rating, stalkerCmd = e.rawCmd, mediaType = MediaType.SERIES
                )
            }

            Result.success(StalkerContent(live, films, series))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Resolves a VOD/series base64 play command into a real stream URL: authenticates a
     *  fresh session against the portal, then create_links the cmd. Returns null if either
     *  step fails (expired portal, dead link). [isSeries] switches the create_link type. */
    suspend fun resolvePlayUrl(provider: Provider, cmd: String, episode: Int? = null): String? {
        val serverUrl = provider.serverUrl ?: return null
        val mac = provider.userAgent ?: return null
        if (!api.connect(serverUrl, mac)) return null
        return api.createLink(cmd, episode)
    }

    /** Seasons + episodes for a Stalker series, as (seasonLabel, episodes) pairs matching the
     *  detail screen's shape. Each episode Channel carries the season's play [cmd] in
     *  stalkerCmd and its number in episodeNum, so playback resolves via create_link&series=N.
     *  [seriesId] is the list item's id ("16471:16471"); the portal wants the part before ':'. */
    suspend fun getEpisodes(provider: Provider, seriesId: String, categoryId: String?): List<Pair<String, List<Channel>>> {
        val serverUrl = provider.serverUrl ?: return emptyList()
        val mac = provider.userAgent ?: return emptyList()
        if (!api.connect(serverUrl, mac)) return emptyList()
        val movieId = seriesId.substringBefore(':')
        val seasons = api.getSeriesEpisodes(movieId, categoryId, mac)
            // The portal returns seasons newest-first ("Season 28" before "Season 1"). Sort by
            // the number in the label so the picker reads 1,2,3… - fall back to original order
            // for any label without a parseable number.
            .sortedBy { Regex("\\d+").find(it.label)?.value?.toIntOrNull() ?: Int.MAX_VALUE }
        return seasons.map { season ->
            season.label to season.numbers.sorted().map { ep ->
                Channel(
                    id = "$seriesId:${season.label}:$ep",
                    name = "Episode $ep",
                    url = "",
                    episodeNum = ep,
                    stalkerCmd = season.cmd,
                    mediaType = MediaType.SERIES
                )
            }
        }
    }

    companion object {
        const val TYPE = "stalker"
    }
}
