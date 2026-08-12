package com.lumora

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.plugin.PluginSubtitle
import com.lumora.util.cleanVodTitle
import com.lumora.scraper.bridge.ScraperCatalog
import com.lumora.scraper.bridge.ScraperSiteManifest
import com.lumora.scraper.bridge.ScraperSiteStore
import com.lumora.scraper.models.Video
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Streaming from the built-in site scrapers (`com.lumora.scraper`).
 *
 * The scrapers are not a catalogue - see [ScraperCatalog]. Lumora's catalogue for this content is
 * Discover/TMDB, and this file turns a title the user is already looking at there into something
 * playing.
 *
 * **It plays; it does not ask.** An embed host is not a meaningful choice - the names are
 * interchangeable CDNs, nobody can tell a working one from a dead one by looking at it, and a
 * list of thirty is a worse experience than the app simply trying them. So this starts the first
 * source it gets and, when one fails to resolve, moves to the next without comment. This is how
 * the upstream scraper app behaves too: it plays `preferredServer ?: servers.first()` immediately
 * and, on a failed extraction, silently advances to `servers[i + 1]` until the list runs out.
 *
 * Two properties make it work. Sources are tried *as they arrive* rather than after every site
 * has answered, so playback starts at the speed of the fastest site rather than the slowest. And
 * whichever host played last is floated to the front next time, so a setup that works keeps
 * working instead of re-walking the same dead hosts on every launch.
 *
 * A manual list is still one press away, for the case the automatic order gets wrong.
 */

private const val SCRAPER_PREFS = "lumora_scraper_prefs"
private const val KEY_LAST_GOOD_SOURCE = "last_good_source"

/** True when the scrapers can be offered for [item] at all. */
internal fun MainActivity.scraperCanSource(item: Channel): Boolean =
    (item.mediaType == MediaType.MOVIE || item.mediaType == MediaType.SERIES) && scrapersUsable()

/** True when at least one site is switched on and the master switch is up. */
internal fun MainActivity.scrapersUsable(): Boolean =
    ScraperSiteStore.activeProviders(this).isNotEmpty()

/**
 * True when there is *some* way to find something to play without an IPTV provider - an installed
 * `stream_search` plugin, or the built-in site scrapers.
 *
 * This is the question the app's empty-state and chrome gates are really asking. They used to ask
 * only about plugins, from when a plugin was the only provider-less source; a scraper-only setup
 * therefore got bounced straight to "Add a Provider" with Discover, search and the tab bar all
 * hidden, and no way to reach content it was perfectly able to play.
 */
internal fun MainActivity.hasProviderlessSource(): Boolean =
    enabledStreamSearchPlugin() != null || scrapersUsable()

/** "<site> · <host>" - the key the last-good-source memory is stored under. */
private fun sourceKey(source: ScraperCatalog.Source) =
    source.providerName + " · " + source.server.name

/**
 * Finds a stream for [item] across the enabled sites and starts playing the first one that
 * resolves.
 *
 * [season]/[episode] pick the episode of a series; without them the sites are asked for S01E01,
 * the only sane default when the caller does not know.
 */
internal fun MainActivity.showScraperSourceDialog(
    item: Channel,
    season: Int? = null,
    episode: Int? = null,
) {
    val isSeries = item.mediaType == MediaType.SERIES
    val epTag = if (season != null && episode != null) " S%02dE%02d".format(season, episode) else ""
    val scraperPrefs = getSharedPreferences(SCRAPER_PREFS, Context.MODE_PRIVATE)
    val lastGood = scraperPrefs.getString(KEY_LAST_GOOD_SOURCE, null)

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
    }
    val status = TextView(this).apply {
        text = getString(R.string.scraper_finding_sources)
        setTextColor(ContextCompat.getColor(this@showScraperSourceDialog, R.color.text_primary))
    }
    val detail = TextView(this).apply {
        setTextColor(ContextCompat.getColor(this@showScraperSourceDialog, R.color.text_secondary))
        textSize = 12f
        visibility = View.GONE
    }
    container.addView(status)
    container.addView(detail)

    // Everything found so far, in the order it should be tried. Appended to as sites answer.
    val queue = mutableListOf<ScraperCatalog.Source>()
    var nextToTry = 0
    var trying = false
    var searchFinished = false
    var attempts = 0
    var playbackStarted = false
    lateinit var searchJob: Job

    val dialog = AlertDialog.Builder(this)
        .setTitle(getString(R.string.scraper_dialog_title, item.name + epTag))
        .setView(container)
        .setNegativeButton(android.R.string.cancel, null)
        // Sites keep working after the dialog closes unless the search is actually cancelled, and
        // a fan-out across dozens of sites is not something to leave running unattended.
        .setOnDismissListener { if (!playbackStarted) searchJob.cancel() }
        .create()
    // Escape hatch from "it is choosing for me" to "I will choose", for when the automatic order
    // gets it wrong - a specific host known to carry the right audio track, say.
    dialog.setButton(
        AlertDialog.BUTTON_NEUTRAL,
        getString(R.string.scraper_pick_manually)
    ) { _, _ ->
        searchJob.cancel()
        showScraperSourcePicker(item, epTag, queue.toList(), episode)
    }

    fun startPlayback(source: ScraperCatalog.Source, video: Video) {
        playbackStarted = true
        // Remembered before the player is even up: this records "this host answered", which is
        // exactly what should be tried first next time.
        scraperPrefs.edit().putString(KEY_LAST_GOOD_SOURCE, sourceKey(source)).apply()
        searchJob.cancel()
        dialog.dismiss()
        playScraperVideo(item, epTag, episode, video)
    }

    /**
     * Resolves the next queued source, and on failure the one after that. Re-entrancy is guarded
     * by [trying]: a newly-arrived batch and a just-failed attempt both call this, and only one
     * resolve should ever be in flight.
     */
    fun tryNext() {
        if (trying || playbackStarted) return
        if (nextToTry >= queue.size) {
            // Nothing left to try *yet*. If sites are still answering, the next batch restarts
            // this; if they have all finished, there is genuinely nothing.
            if (searchFinished) status.text = getString(R.string.scraper_no_sources)
            return
        }
        trying = true
        val source = queue[nextToTry++]
        attempts++
        status.text = getString(R.string.scraper_opening_source, source.server.name)
        detail.visibility = View.VISIBLE
        detail.text = getString(R.string.scraper_attempt_detail, source.providerName, attempts)

        scope.launch {
            val video = ScraperCatalog.resolve(source)
            trying = false
            if (playbackStarted) return@launch
            if (video != null && video.source.isNotBlank()) startPlayback(source, video)
            // Silent on failure: a dead host is the normal case, not something to interrupt for.
            else tryNext()
        }
    }

    dialog.show()

    val providers = ScraperSiteStore.activeProviders(this)
    searchJob = scope.launch {
        ScraperCatalog.findSources(
            providers = providers,
            title = cleanVodTitle(item.name),
            year = item.year,
            isSeries = isSeries,
            season = season,
            episode = episode,
            // The same signal enabledStreamSearchPlugin() uses to pick between plugins: the
            // anime catalog stamps this prefix on its ids, and it is the only thing Lumora knows
            // about a title's genre without another lookup.
            isAnime = item.id.startsWith("anime:"),
            onProgress = { searched, total ->
                runOnUiThread {
                    // Only while nothing is being tried yet - once a host is being opened, its
                    // name is the more useful thing to have on screen.
                    if (queue.isEmpty()) {
                        status.text = getString(R.string.scraper_searching_sites, searched, total)
                    }
                }
            },
            onSources = { sources ->
                runOnUiThread {
                    if (playbackStarted) return@runOnUiThread
                    // A host that worked last time jumps ahead of everything still queued. It
                    // cannot overtake one already in flight, hence the insert at nextToTry.
                    val (preferred, rest) = sources.partition { sourceKey(it) == lastGood }
                    queue.addAll(nextToTry.coerceAtMost(queue.size), preferred)
                    queue.addAll(rest)
                    tryNext()
                }
            },
        )
        searchFinished = true
        // The search ending is not the end of the attempt - a resolve may still be in flight, and
        // tryNext() reports the empty case itself once it runs dry.
        if (!playbackStarted && !trying && nextToTry >= queue.size) {
            status.text = getString(R.string.scraper_no_sources)
        }
    }
}

/**
 * The manual list, reached from the auto-play dialog's "Pick a source" button.
 *
 * Deliberately not the default path - see this file's header. Unlike the automatic path, a
 * failure here is reported rather than skipped past: the user chose this host, so quietly playing
 * a different one would be the wrong answer.
 */
private fun MainActivity.showScraperSourcePicker(
    item: Channel,
    epTag: String,
    sources: List<ScraperCatalog.Source>,
    episode: Int?,
) {
    if (sources.isEmpty()) {
        scraperToast(getString(R.string.scraper_no_sources))
        return
    }
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
    }
    val status = TextView(this).apply {
        text = resources.getQuantityString(
            R.plurals.scraper_sources_found, sources.size, sources.size
        )
        setTextColor(ContextCompat.getColor(this@showScraperSourcePicker, R.color.text_secondary))
    }
    val resultsHost = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // The rows use @animator/focus_scale, which grows them past their own bounds - without
        // these the focus pop is visibly cropped top and bottom inside the scroller.
        clipChildren = false
        clipToPadding = false
    }
    val scroll = ScrollView(this).apply {
        isFillViewport = true
        clipChildren = false
        clipToPadding = false
        addView(resultsHost)
    }
    container.addView(status)
    container.addView(
        scroll,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    )

    val dialog = AlertDialog.Builder(this)
        .setTitle(getString(R.string.scraper_dialog_title, item.name + epTag))
        .setView(container)
        .setNegativeButton(android.R.string.cancel, null)
        .create()

    sources.forEach { source ->
        val row = layoutInflater.inflate(R.layout.item_stream_result, resultsHost, false)
        row.findViewById<TextView>(R.id.streamTitle).text = source.server.name
        val meta = row.findViewById<TextView>(R.id.streamMeta)
        // Which site vouched for it, the embed host's domain, and the title as that site named
        // it - between them enough to spot a wrong match before playing it.
        val hostName = runCatching { android.net.Uri.parse(source.server.src).host }.getOrNull()
        val parts = listOfNotNull(source.providerName, hostName, source.matchedTitle)
        if (parts.isEmpty()) {
            meta.visibility = View.GONE
        } else {
            meta.visibility = View.VISIBLE
            meta.text = parts.joinToString("  ·  ")
        }
        row.setOnClickListener {
            status.text = getString(R.string.scraper_opening_source, source.server.name)
            resultsHost.removeAllViews()
            scope.launch {
                val video = ScraperCatalog.resolve(source)
                if (video == null || video.source.isBlank()) {
                    status.text = getString(R.string.scraper_source_failed, source.server.name)
                    return@launch
                }
                getSharedPreferences(SCRAPER_PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_LAST_GOOD_SOURCE, sourceKey(source)).apply()
                dialog.dismiss()
                playScraperVideo(item, epTag, episode, video)
            }
        }
        resultsHost.addView(row)
    }

    dialog.show()
    resultsHost.getChildAt(0)?.requestFocus()
}

/** Hands a resolved stream to the player. Shared by the automatic and manual paths. */
private fun MainActivity.playScraperVideo(
    item: Channel,
    epTag: String,
    episode: Int?,
    video: Video,
) {
    hideContentDetail()
    showPlayerFor(
        item.copy(
            // Not item.id alone: the saved-position key has to be stable for this episode, and
            // the resolved URL is single-use. The id stays whatever Discover gave the title, plus
            // the episode, so a resume finds the same entry next time.
            id = item.id + epTag,
            name = item.name + epTag,
            url = video.source,
            // The host's own headers - a Referer, usually. Without these a hotlink-protected CDN
            // 403s the playlist.
            streamHeaders = video.headers?.ifEmpty { null },
            episodeNum = episode,
        ),
        externalSubtitles = video.subtitles.map { subtitle ->
            externalSubtitleFor(
                PluginSubtitle(
                    url = subtitle.file,
                    label = subtitle.label,
                    // The scrapers' Subtitle model carries a display label, not a code.
                    language = null,
                    isDefault = subtitle.default,
                )
            )
        },
        // Re-applies the playlist URL's token to segment requests the host writes without one.
        // Only set when the host asked for it.
        maintainTokenQuery = video.source
            .takeIf { video.maintainToken }
            ?.substringAfter('?', "")
            ?.takeIf { it.isNotBlank() },
        // Several extractors know the container even though the signed URL does not advertise
        // it. Without this Media3 guesses from the path, picks the progressive extractors for an
        // HLS playlist, and fails with UnrecognizedInputFormatException.
        mimeType = video.type?.takeIf { it.isNotBlank() } ?: hlsMimeIfLooksLikeHls(video.source),
    )
    detailReturnItem = item
}

/**
 * Applies the site list from an installed `scraper_sites` plugin script, if there is one.
 *
 * Lives here rather than in [ScraperSiteManifest] because getting a manifest means running the JS
 * plugin engine, which is the app's plugin layer - the scraper package only knows how to apply
 * one. A missing or broken script is not an error: the list bundled in assets was already applied
 * at startup and stays in force.
 */
internal fun MainActivity.loadScraperSiteManifest() {
    scope.launch {
        val script = pluginScriptManager.getDiscoveredScripts()
            .firstOrNull { it.enabled && it.supportsScraperSites }
        if (script == null) {
            // No script, or it was just switched off: the sites it authorised stop being
            // queryable immediately. Without this they would keep running for the rest of the
            // process and disabling the plugin would look like it had done nothing.
            ScraperSiteManifest.clear()
            return@launch
        }
        val json = jsPluginEngine.scraperSites(pluginScriptManager.readSource(script))
        if (json == null) {
            ScraperSiteManifest.clear()
            return@launch
        }
        ScraperSiteManifest.applyJson(this@loadScraperSiteManifest, json)
    }
}

/**
 * APPLICATION_M3U8 when the URL looks like a playlist, else null.
 *
 * Only 15 of the 86 extractors set a container type, and Media3's own inference reads the last
 * path segment's extension - so a signed URL that carries `m3u8` anywhere other than the end of
 * the path (a query parameter, a path segment mid-way) is inferred as progressive and dies on the
 * playlist. Guessing from the whole URL is cruder than a declared type and is only the fallback.
 */
internal fun hlsMimeIfLooksLikeHls(url: String): String? =
    if (url.contains("m3u8", ignoreCase = true)) androidx.media3.common.MimeTypes.APPLICATION_M3U8
    else null

/** Shows [message] as a toast. */
internal fun MainActivity.scraperToast(message: String) {
    android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
}
