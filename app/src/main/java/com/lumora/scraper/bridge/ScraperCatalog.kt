package com.lumora.scraper.bridge

import com.lumora.scraper.extractors.Extractor
import com.lumora.scraper.models.Movie
import com.lumora.scraper.models.TvShow
import com.lumora.scraper.models.Video
import com.lumora.scraper.providers.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

/**
 * The seam between the ported site scrapers and the rest of Lumora.
 *
 * **The scrapers are stream sources, not a catalogue.** Lumora already has a catalogue for this
 * kind of content - Discover, backed by TMDB - and browsing 70 sites individually would mean 70
 * inconsistent, paginated, unsearchable libraries sitting next to it. So none of the sites'
 * own browse or home endpoints are used. TMDB says what exists; the scrapers are asked only
 * "given this title, who can stream it", at the moment the user asks to play it.
 *
 * That also settles the awkward part of the model mismatch. There is no mapping of a site's
 * `Movie`/`TvShow` into [com.lumora.model.Channel] to maintain, no scraper item in
 * `allChannels`, nothing to keep out of [com.lumora.cache.ChannelCache]. A scraper's models
 * exist only inside [findSources], long enough to get from a title to a list of embed hosts.
 *
 * Everything here is suspending and hits the network. All of it is dispatched to IO, because the
 * ported providers block on Retrofit and, for a Cloudflare-guarded site, may end up waiting on a
 * WebView challenge for a good while.
 */
object ScraperCatalog {

    /**
     * How many sites are queried at once. The work is almost entirely network wait, but each
     * site can involve several round trips (search, then title page, then episode list, then the
     * server list), and a TV stick on domestic wifi does not get faster by having 70 of those in
     * flight - it gets slower and starts timing out. Eight keeps the slowest sites from holding
     * up the rest without flooding the link.
     */
    private const val MAX_CONCURRENT_SITES = 8

    /**
     * Once this many sources are in hand the second wave is skipped entirely.
     *
     * The point of a wave is that the cheap sites answer in about a second; if several of them
     * carried the title, spending a Chromium page load per remaining site to find more is work
     * nobody asked for. Verified on device: a title one site carried resolved in 1.4s while the
     * fan-out went on to query 49 sites and 38 DNS names for nothing.
     */
    private const val ENOUGH_SOURCES = 4

    /** One playable option: an embed host, and which site vouched for it. */
    data class Source(
        val providerName: String,
        val server: Video.Server,
        /** The title as that site named it, so an obvious mismatch is visible before playing. */
        val matchedTitle: String,
    )

    /** Every site the port carries, in registry order. */
    fun allProviders(): List<Provider> = Provider.providers.keys.toList()

    fun providerByName(name: String): Provider? = Provider.findByName(name)

    /**
     * Searches [providers] for [title] and reports each site's embed hosts through [onSources]
     * as it finds them, rather than returning one list at the end.
     *
     * Progressive by design: sites fail and hang constantly, and the useful behaviour is that
     * the first site to answer gives the user something to click while the rest are still
     * running. A site that throws is dropped silently - with this many sources, one being down
     * is not an error worth showing.
     *
     * [onSources] is called on the IO dispatcher; callers touching views must hop threads.
     * Returns when every site has finished or failed.
     */
    suspend fun findSources(
        providers: List<Provider>,
        title: String,
        year: String?,
        isSeries: Boolean,
        season: Int? = null,
        episode: Int? = null,
        /** True when the title is anime, which is the only case anime-only sites can answer. */
        isAnime: Boolean = false,
        onProgress: (searched: Int, total: Int) -> Unit = { _, _ -> },
        onSources: (List<Source>) -> Unit,
    ) = withContext(Dispatchers.IO) {
        // An anime-only catalogue cannot carry a live-action title, so querying one for a film is
        // a guaranteed miss - and about half the list is anime-only. A site the manifest says
        // nothing about is kept: unknown is not a reason to skip.
        val candidates = providers.filter { provider ->
            val info = ScraperSiteManifest.infoFor(provider.name) ?: return@filter true
            !info.anime || isAnime
        }

        // Two waves. The cheap sites are plain HTTP and answer in about a second; the heavy ones
        // run their search through the Cloudflare WebView bypass, which is a full Chromium page
        // load each. Doing those only when the cheap wave came up short is the difference between
        // one second and a minute of churn on a TV stick.
        val (heavy, cheap) = candidates.partition {
            ScraperSiteManifest.infoFor(it.name)?.heavy == true
        }

        var found = 0
        var done = 0
        val lock = Any()
        val total = candidates.size

        suspend fun runWave(wave: List<Provider>) {
            if (wave.isEmpty()) return
            val gate = Semaphore(MAX_CONCURRENT_SITES)
            coroutineScope {
                wave.forEach { provider ->
                    launch {
                        gate.withPermit {
                            // Checked inside the permit, not before the launch: by the time a
                            // queued site gets its turn, earlier ones may already have found
                            // plenty, and there is no reason to spend a request confirming it.
                            if (synchronized(lock) { found } >= ENOUGH_SOURCES) {
                                synchronized(lock) { done++; onProgress(done, total) }
                                return@withPermit
                            }
                            val sources = runCatching {
                                sourcesFrom(provider, title, year, isSeries, season, episode)
                            }.getOrElse { e ->
                                // A cancelled scope must not be swallowed as "this site failed" -
                                // that would leave the remaining sites running after the user has
                                // closed the picker.
                                if (e is CancellationException) throw e
                                emptyList()
                            }
                            synchronized(lock) {
                                done++
                                found += sources.size
                                onProgress(done, total)
                                if (sources.isNotEmpty()) onSources(sources)
                            }
                        }
                    }
                }
            }
        }

        runWave(cheap)
        if (synchronized(lock) { found } < ENOUGH_SOURCES) runWave(heavy)
    }

    /** One site's contribution to [findSources]. Throws freely; the caller absorbs it. */
    private suspend fun sourcesFrom(
        provider: Provider,
        title: String,
        year: String?,
        isSeries: Boolean,
        season: Int?,
        episode: Int?,
    ): List<Source> {
        // Skip sites that have already said they do not carry this kind of content, rather than
        // spending a request finding out.
        if (isSeries && !Provider.supportsTvShows(provider)) return emptyList()
        if (!isSeries && !Provider.supportsMovies(provider)) return emptyList()

        val results = provider.search(title, 1)
        val match = bestMatch(results, title, year, isSeries) ?: return emptyList()

        return when (match) {
            is Movie -> {
                val type = Video.Type.Movie(
                    id = match.id,
                    title = match.title,
                    releaseDate = year.orEmpty(),
                    poster = match.poster.orEmpty(),
                    imdbId = match.imdbId,
                )
                provider.getServers(match.id, type)
                    .map { Source(provider.name, it, match.title) }
            }

            is TvShow -> {
                // A series needs three more hops before there is anything to play: the show page
                // (for its season list), the season's episode list, and then the episode itself.
                val wantedSeason = season ?: 1
                val wantedEpisode = episode ?: 1
                val show = provider.getTvShow(match.id)
                val seasonRef = show.seasons.firstOrNull { it.number == wantedSeason }
                    ?: show.seasons.firstOrNull()
                    ?: return emptyList()
                val episodes = provider.getEpisodesBySeason(seasonRef.id)
                val ep = episodes.firstOrNull { it.number == wantedEpisode }
                    ?: episodes.firstOrNull()
                    ?: return emptyList()
                val type = Video.Type.Episode(
                    id = ep.id,
                    number = ep.number,
                    title = ep.title,
                    poster = ep.poster ?: match.poster,
                    overview = ep.overview,
                    tvShow = Video.Type.Episode.TvShow(
                        id = match.id,
                        title = match.title,
                        poster = match.poster,
                        banner = match.banner,
                        releaseDate = year,
                        imdbId = match.imdbId,
                    ),
                    season = Video.Type.Episode.Season(
                        number = seasonRef.number,
                        title = seasonRef.title,
                    ),
                )
                provider.getServers(ep.id, type)
                    .map { Source(provider.name, it, "${match.title} S%02dE%02d".format(seasonRef.number, ep.number)) }
            }

            else -> emptyList()
        }
    }

    /**
     * Picks the site result that is actually the title we asked for.
     *
     * This matters more than it looks. A site's search is a substring match over its own
     * catalogue, so asking for "Up" returns everything with "up" in the name, and playing the
     * first hit means playing the wrong film. So: exact normalised title only, preferring a
     * matching year when the site reports one, and nothing at all rather than a near miss.
     */
    private fun bestMatch(
        results: List<com.lumora.scraper.adapters.AppAdapter.Item>,
        title: String,
        year: String?,
        isSeries: Boolean,
    ): Any? {
        val wanted = normalise(title)
        val candidates = results.filter { if (isSeries) it is TvShow else it is Movie }
        val exact = candidates.filter {
            val name = when (it) {
                is Movie -> it.title
                is TvShow -> it.title
                else -> return@filter false
            }
            normalise(name) == wanted
        }
        if (exact.isEmpty()) return null
        if (year.isNullOrBlank() || exact.size == 1) return exact.first()
        return exact.firstOrNull {
            val released = when (it) {
                is Movie -> it.released
                is TvShow -> it.released
                else -> null
            }
            released?.get(java.util.Calendar.YEAR)?.toString() == year
        } ?: exact.first()
    }

    /**
     * Casefolds, strips accents, and drops everything that is not a letter or digit - the same
     * title is written "Pokémon", "Pokemon" and "Pokemon:" across three sites, and none of those
     * should count as different films.
     */
    private fun normalise(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .filter { it.isLetterOrDigit() }

    /**
     * Turns one picked source into something playable.
     *
     * Two stages that fail independently: the site may resolve its server to an embed page URL
     * rather than a stream, in which case [Extractor] is what turns that host's page into a
     * media URL. A site that already returns a direct stream skips the extractor.
     */
    suspend fun resolve(source: Source): Video? = withContext(Dispatchers.IO) {
        val provider = providerByName(source.providerName) ?: return@withContext null
        val video = runCatching { provider.getVideo(source.server) }.getOrNull()
        if (video != null && video.source.isNotBlank()) return@withContext video
        // Most providers run the extractor inside getVideo() themselves, so a blank source here
        // usually means extraction already ran and failed - re-running it achieves nothing, and
        // with a blank server.src it re-ran against an empty URL, which is what put the
        // duplicate "[EXTRACTOR] -> Starting: X (URL: )" pairs in the log. Only worth a second
        // attempt when there is actually an embed URL the provider left unresolved.
        val embed = source.server.src
        if (embed.isBlank() || !embed.startsWith("http")) return@withContext null
        runCatching { Extractor.extract(embed, source.server) }.getOrNull()
    }
}
