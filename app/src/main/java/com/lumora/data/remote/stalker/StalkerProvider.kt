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

    /** Authenticates against the portal, if a prior [loadLiveChannels]/[loadVodAndSeries] call
     *  on this instance hasn't already done so. */
    private suspend fun ensureAuthenticated(serverUrl: String, mac: String): Result<Unit> {
        if (authenticated) return Result.success(Unit)
        val auth = api.authenticate(serverUrl, mac)
        if (auth.isFailure) return Result.failure(auth.exceptionOrNull() ?: Exception("Auth failed"))
        if (!auth.getOrThrow().success) return Result.failure(Exception("Invalid MAC or server"))
        authenticated = true
        return Result.success(Unit)
    }

    /** Live channels only - split out from VOD/series so a caller can show the part people
     *  open the app for as soon as it lands, instead of waiting on the whole catalogue. A
     *  portal with a huge live lineup (tens of thousands of channels across hundreds of
     *  genres) plus a large VOD/series library held all three fully in memory at once before
     *  anything was shown, which is what ran a low-RAM box out of heap and read as the app
     *  freezing. */
    suspend fun loadLiveChannels(provider: Provider): Result<List<Channel>> {
        val serverUrl = provider.serverUrl ?: return Result.failure(Exception("No server URL"))
        val mac = provider.userAgent ?: return Result.failure(Exception("No MAC address"))
        ensureAuthenticated(serverUrl, mac).onFailure { return Result.failure(it) }
        return try {
            Result.success(api.getLiveChannels(serverUrl, mac).map { StalkerApiService.toChannel(it) })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** VOD + series, fetched after live so the live list's own memory (raw entries, the
     *  intermediate map) is already reclaimable by the time this starts. */
    suspend fun loadVodAndSeries(provider: Provider): Result<Pair<List<Channel>, List<Channel>>> {
        val serverUrl = provider.serverUrl ?: return Result.failure(Exception("No server URL"))
        val mac = provider.userAgent ?: return Result.failure(Exception("No MAC address"))
        ensureAuthenticated(serverUrl, mac).onFailure { return Result.failure(it) }
        return try {
            val vodEntries = api.getVodList(mac)
            val seriesEntries = api.getSeriesList(mac)
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
            Result.success(films to series)
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
}
