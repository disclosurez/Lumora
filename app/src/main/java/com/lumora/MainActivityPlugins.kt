package com.lumora

import android.app.AlertDialog
import android.app.Dialog
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.model.Provider
import com.lumora.model.IptvProviderConfig
import com.lumora.data.IptvProviderStore
import com.lumora.plugin.DiscoveredProvider
import com.lumora.util.cleanVodTitle
import com.lumora.util.extractYearFromName
import com.lumora.plugin.DiscoveryResult
import com.lumora.plugin.ResolveResult
import com.lumora.plugin.SearchResult
import com.lumora.plugin.js.JsPluginContract
import com.lumora.plugin.js.PluginScript
import com.lumora.plugin.js.PluginScriptManager
import com.lumora.plugin.js.PluginStore
import com.lumora.plugin.js.PluginStoreManager
import com.lumora.plugin.js.StoreScript
import com.lumora.torrent.TorrentEngine
import com.lumora.torrent.TorrentForegroundService
import com.lumora.plugin.TorrentResult
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

// ── Plugins & stream-search ──
//
// Extracted from MainActivity.kt; see that file's header.

private const val PREF_PUBLIC_CONTENT_DISCLAIMER_ACCEPTED = "public_content_disclaimer_accepted"

/** Gate in front of installing or enabling any stream_search/scraper_sites plugin - those
 *  search public torrent/streaming sites, which Lumora neither hosts nor controls. Accepted
 *  once, remembered in prefs; every later call runs [onAccepted] straight away. [onAccepted]
 *  is simply never called if the user declines. */
internal fun MainActivity.ensurePublicContentDisclaimerAccepted(onAccepted: () -> Unit) {
    if (prefs.getBoolean(PREF_PUBLIC_CONTENT_DISCLAIMER_ACCEPTED, false)) {
        onAccepted()
        return
    }
    AlertDialog.Builder(this)
        .setTitle("Public Streaming Content")
        .setMessage(
            "These plugins search public torrent and streaming sites for content. Lumora " +
                "does not host, control, or verify anything they find - what they return is " +
                "entirely outside this app's control.\n\n" +
                "For educational and personal use only. You are responsible for complying " +
                "with the laws that apply to you."
        )
        .setCancelable(false)
        .setPositiveButton("I Agree") { _, _ ->
            prefs.edit().putBoolean(PREF_PUBLIC_CONTENT_DISCLAIMER_ACCEPTED, true).apply()
            onAccepted()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

/** Parses a Discover [Channel.id] of the form "tmdb:movie:123" / "tmdb:tv:123". */
internal fun MainActivity.tmdbTypeAndId(id: String): Pair<String, Int>? {
    val parts = id.split(":")
    if (parts.size != 3 || parts[0] != "tmdb") return null
    return parts[1] to (parts[2].toIntOrNull() ?: return null)
}

/** The panel-supplied TMDB id as a `(mediaType, id)` pair, or null when it sent none. Live
 *  items are excluded - a channel is not a TMDB title, whatever id happens to be attached. */
internal fun Channel.tmdbTypeAndId(): Pair<String, Int>? {
    if (mediaType == MediaType.LIVE) return null
    val id = tmdbId?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    return (if (mediaType == MediaType.SERIES) "tv" else "movie") to id
}

/** Release year for TMDB matching. Panels routinely omit the `year` field on the bulk lists
 *  while still putting "(2026)" in the name, and a year is what separates the right "Run" or
 *  "Fearless" from the popular one - so fall back to the release date, then to the name. */
internal fun Channel.tmdbYear(): String? =
    year?.takeIf { it.isNotBlank() }
        ?: releaseDate?.take(4)?.takeIf { it.length == 4 }
        ?: extractYearFromName(name)

/** Looks up and plays a TMDB trailer for a Discover item (id already carries the TMDB id). */
internal fun MainActivity.showTrailerForDiscoverItem(item: Channel) {
    val (type, id) = tmdbTypeAndId(item.id) ?: run {
        android.util.Log.d("TrailerPlayer", "showTrailerForDiscoverItem: '${item.id}' not a tmdb id")
        return
    }
    scope.launch {
        val key = try {
            tmdbClient.trailerKey(type, id)
        } catch (e: Exception) {
            android.util.Log.e("TrailerPlayer", "trailerKey($type,$id) threw", e)
            null
        }
        android.util.Log.d("TrailerPlayer", "trailerKey($type,$id) = $key")
        if (key == null) {
            Toast.makeText(this@showTrailerForDiscoverItem, "No trailer found.", Toast.LENGTH_SHORT).show()
        } else {
            showTrailerPlayer(key)
        }
    }
}

/** Shows/hides the detail screen's Trailer button, resolving a catalog item to a TMDB id
 *  by title/year search since provider/Jellyfin content carries no TMDB id of its own. */
internal fun MainActivity.wireTrailerButton(item: Channel) {
    val button = binding.detailTrailerButton
    button.visibility = View.GONE
    button.setOnClickListener(null)
    if (!tmdbClient.hasKey()) {
        android.util.Log.d("TrailerPlayer", "wireTrailerButton: no TMDB key configured")
        return
    }
    // The panel's own trailer key, when it sent one: no network at all, and it covers plenty
    // of titles TMDB itself has no video for.
    item.trailerKey?.takeIf { it.isNotBlank() }?.let { key ->
        button.visibility = View.VISIBLE
        button.setOnClickListener { showTrailerPlayer(key) }
        return
    }
    scope.launch {
        try {
            // The panel's TMDB id beats any title search - see Channel.tmdbId.
            val direct = tmdbTypeAndId(item.id) ?: item.tmdbTypeAndId()
            // cleanVodTitle first, same as the detail screen's TMDB metadata fill - this
            // path used the raw catalog name, so a title the plot/backdrop lookup resolved
            // fine could still fail here and silently drop the button.
            val resolved = direct
                ?: tmdbClient.resolveId(cleanVodTitle(item.name), item.tmdbYear(), item.mediaType == MediaType.SERIES)
            android.util.Log.d("TrailerPlayer", "wireTrailerButton('${item.name}', year=${item.year}): resolved=$resolved (direct=${direct != null})")
            val (type, id) = resolved ?: return@launch
            val key = tmdbClient.trailerKey(type, id)
            android.util.Log.d("TrailerPlayer", "wireTrailerButton('${item.name}'): trailerKey=$key")
            if (key == null) return@launch
            button.visibility = View.VISIBLE
            button.setOnClickListener { showTrailerPlayer(key) }
        } catch (e: Exception) {
            android.util.Log.e("TrailerPlayer", "wireTrailerButton('${item.name}') threw", e)
        }
    }
}

/** Plays a YouTube trailer in-app, fullscreen, via the standard /embed player - loaded
 *  directly (no hand-built HTML wrapper: that rendered blank with no logged error). */
internal fun MainActivity.showTrailerPlayer(youtubeKey: String) {
    val density = resources.displayMetrics.density
    val webView = WebView(this).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true // YouTube's iframe player needs this or it stays blank with no error
        settings.mediaPlaybackRequiresUserGesture = false
        webViewClient = object : WebViewClient() {
            // YouTube's watch/embed page top-navigates to plain youtube.com/ as a fallback
            // when an internal resource (e.g. the doubleclick ad request) fails to load -
            // seen on networks that block ad domains. Refuse every main-frame navigation
            // outright: this player never legitimately needs to leave the embed URL.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame && !request.url.toString().contains("/embed/")) {
                    android.util.Log.d("TrailerPlayer", "blocked main-frame navigation to ${request.url}")
                    return true
                }
                return false
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                android.util.Log.e(
                    "TrailerPlayer",
                    "onReceivedError url=${request.url} code=${error.errorCode} desc=${error.description}"
                )
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                android.util.Log.e(
                    "TrailerPlayer",
                    "onReceivedHttpError url=${request.url} status=${response.statusCode}"
                )
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                android.util.Log.d("TrailerPlayer", "onPageStarted url=$url")
            }
            override fun onPageFinished(view: WebView, url: String?) {
                android.util.Log.d("TrailerPlayer", "onPageFinished url=$url")
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                android.util.Log.d("TrailerPlayer", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                return true
            }
        }
    }
    val closeButton = Button(this).apply {
        text = "Close"
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            topMargin = (16 * density).toInt()
            rightMargin = (16 * density).toInt()
        }
    }
    val root = FrameLayout(this).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(webView)
        addView(closeButton)
    }
    val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    dialog.setContentView(root)
    closeButton.setOnClickListener { dialog.dismiss() }
    dialog.setOnDismissListener { webView.destroy() }
    // A raw loadUrl only sends the Referer header on the very first request, not on the
    // player's own follow-up calls - got as far as fixing error 153 but still hit 152.
    // Giving the WebView's document itself a youtube-nocookie.com origin (via
    // loadDataWithBaseURL) plus an explicit iframe referrerpolicy covers those too.
    val html = """
        <html><body style="margin:0;padding:0;background:#000;">
        <iframe width="100%" height="100%"
            src="https://www.youtube-nocookie.com/embed/$youtubeKey?autoplay=1&playsinline=1"
            frameborder="0" referrerpolicy="strict-origin-when-cross-origin"
            allow="autoplay; encrypted-media" allowfullscreen></iframe>
        </body></html>
    """.trimIndent()
    webView.loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "utf-8", null)
    dialog.show()
    closeButton.requestFocus()
}

/**
 * Two things can source a stream for a title that has no URL of its own: an installed
 * `stream_search` plugin, or the built-in site scrapers (see MainActivityScraper).
 *
 * A plugin wins when both are available. The user went out of their way to install it, and it
 * is the more specific answer - an anime plugin knows things about an anime title that a
 * general-purpose site search does not.
 */
internal fun MainActivity.wireFindStreamButton(item: Channel) {
    val button = binding.detailFindStreamButton
    val available = canFindStream(item)
    button.visibility = if (available) View.VISIBLE else View.GONE
    // One entry point for both kinds of source - an installed stream_search plugin and the
    // built-in site scrapers are searched together and ranked into one queue. They used to be
    // mutually exclusive, which meant the sites were dead weight whenever a plugin was on.
    button.setOnClickListener(
        if (!available) null
        else View.OnClickListener {
            // A series has to say which episode before anything can be searched for - without
            // this it silently searched S01E01, which is right roughly never. The picker is
            // TMDB-backed, so it works for a title the library does not have at all (the
            // "No episodes found" case on a Discover series).
            if (item.mediaType == MediaType.SERIES) {
                showSeriesEpisodePicker(item) { season, episode ->
                    showFindStreamDialog(item, season, episode)
                }
            } else {
                showFindStreamDialog(item)
            }
        }
    )
}

/**
 * Resolves a magnet token via the native [TorrentEngine] instead of a JS plugin's own
 * `resolve()` - see [PluginScript.resolvesNatively]. Unlike a JS-resolved plain http(s) URL,
 * this one is served by a local HTTP server this engine instance owns, so it's kept alive in
 * [activeTorrentSession] for the life of playback (see [hidePlayer]/`onDestroy`).
 *
 * [activeTorrentSession] is set *before* the blocking [TorrentEngine.start] call, not after -
 * `start()` can take minutes (metadata fetch + buffering), and [TorrentEngine] only stops
 * that wait when its own `cancelled` flag is set by [TorrentEngine.stop] (coroutine
 * cancellation alone doesn't interrupt it - see [TorrentEngine]'s kdoc). Setting the field
 * early lets a caller that cancels mid-resolve (e.g. the Find Stream dialog's cancel
 * listener) actually reach and stop this engine instead of it finishing unattended minutes
 * later and popping up playback for a stream the user already backed out of.
 */
internal suspend fun MainActivity.resolveTorrentStream(
    magnet: String,
    season: Int?,
    episode: Int?,
    onProgress: (String) -> Unit
): ResolveResult {
    activeTorrentSession?.let { old -> Thread { runCatching { old.stop() } }.start() }
    TorrentForegroundService.start(this)
    val engine = TorrentEngine(this)
    activeTorrentSession = engine
    return try {
        val url = withContext(Dispatchers.IO) { engine.start(magnet, season, episode, onProgress) }
        ResolveResult.Ready(url)
    } catch (e: Exception) {
        if (activeTorrentSession === engine) activeTorrentSession = null
        withContext(Dispatchers.IO) { runCatching { engine.stop() } }
        TorrentForegroundService.stop(this)
        ResolveResult.Failed(e.message ?: "Could not resolve stream")
    }
}

/**
 * The enabled `stream_search` plugin to use for [item], if any. With more than one enabled
 * (e.g. an anime plugin and a general torrent plugin) this picks by declared
 * [PluginScript.contentTypes] instead of an arbitrary one - without [item] (existence-only
 * checks: is *any* stream_search plugin enabled, at all, for gating tabs/chrome) it just
 * returns the first. Anime catalog items carry the "anime:" id prefix set by
 * [fetchAnimeChannels] - the only signal Lumora itself has for "this title is anime",
 * entirely independent of which plugin (if any) declares itself able to handle that.
 */
/** Stable identity for a plugin-resolved stream. Everything that keys off a channel id -
 *  the saved playback position above all - needs this to come out the same for the same
 *  episode on a later launch, so it's derived from the plugin + token + episode rather than
 *  anything about the particular resolve that produced the URL. */
internal fun MainActivity.pluginChannelId(plugin: PluginScript, token: String, episode: Int?): String =
    "plugin:${plugin.id}:$token" + (episode?.let { ":e$it" } ?: "")

/**
 * The enabled plugin that actually declares itself an anime source, if any.
 *
 * The anime catalog (the AniList shelves and the Anime sidebar row) used to be gated on *any*
 * enabled `stream_search` plugin, on the reasoning that any of them could play the titles. In
 * practice that meant switching on the torrent plugin made a whole Anime section appear that the
 * user never asked for and could not obviously connect to what they had just enabled. Gated on
 * the declared content type instead, so the section tracks the thing it is named after.
 */
internal fun MainActivity.enabledAnimePlugin(): PluginScript? =
    pluginScriptManager.getDiscoveredScripts()
        .firstOrNull { it.enabled && it.supportsStreamSearch && it.contentTypes.contains("anime") }

internal fun MainActivity.enabledStreamSearchPlugin(item: Channel? = null): PluginScript? {
    val candidates = pluginScriptManager.getDiscoveredScripts().filter { it.enabled && it.supportsStreamSearch }
    if (item == null) return candidates.firstOrNull()
    val isAnime = item.id.startsWith("anime:")
    return candidates.firstOrNull { isAnime == it.contentTypes.contains("anime") } ?: candidates.firstOrNull()
}

/**
 * Runs a plugin stream search for [item], lists what comes back, and on a pick resolves it
 * to a playable URL and starts the player. Unlike the old Messenger plugins, a JS script has
 * no process of its own to keep bound during playback - `resolve()` just returns a plain
 * http(s) URL the player hits directly, so there's nothing to hold open past the pick.
 */
internal fun MainActivity.showStreamSearchDialog(
    plugin: PluginScript,
    item: Channel,
    season: Int? = null,
    episode: Int? = null
) {
    val epTag = if (season != null && episode != null)
        " S%02dE%02d".format(season, episode) else ""

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
    }
    val status = TextView(this).apply {
        text = "Searching…"
        setTextColor(ContextCompat.getColor(this@showStreamSearchDialog, R.color.text_secondary))
    }
    val resultsHost = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    val scroll = ScrollView(this).apply {
        isFillViewport = true
        addView(resultsHost)
    }
    container.addView(status)
    container.addView(scroll, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
    ))

    val dialog = AlertDialog.Builder(this)
        .setTitle("Find Stream — ${item.name}$epTag")
        .setView(container)
        .setNegativeButton("Cancel", null)
        .create()

    val source = pluginScriptManager.readSource(plugin)
    val results = mutableListOf<TorrentResult>()

    fun playResult(result: TorrentResult) {
        status.text = "Loading ${result.title}…"
        resultsHost.removeAllViews()
        scope.launch {
            val resolved = if (plugin.resolvesNatively) {
                // TorrentEngine.start calls onProgress from its IO thread, so the TextView
                // update has to hop to the main thread.
                resolveTorrentStream(result.token, season, episode) { line ->
                    runOnUiThread { status.text = line }
                }
            } else {
                jsPluginEngine.resolve(source, result.token, season, episode)
            }
            when (resolved) {
                is ResolveResult.Ready -> {
                    dialog.dismiss()
                    hideContentDetail()
                    showPlayerFor(
                        Channel(
                            // Derived from the token and episode rather than a hash of the
                            // moment: the saved-position key has to be the same string the
                            // next time this episode is played, or nothing ever resumes.
                            id = pluginChannelId(plugin, result.token, episode),
                            name = item.name + epTag,
                            url = resolved.url,
                            // Carried so the Continue Watching tile isn't a blank card, and
                            // so isAdultHomeItem has the same signals every other entry has.
                            posterUrl = item.posterUrl,
                            logoUrl = item.logoUrl,
                            group = item.group,
                            categoryName = item.categoryName,
                            mediaType = MediaType.MOVIE,
                            episodeNum = episode,
                            // Headers the CDN needs (e.g. a Referer) so the player doesn't 403.
                            streamHeaders = resolved.headers.ifEmpty { null },
                            // What lets a resume re-resolve this instead of replaying a URL
                            // that has since expired (see showPlayerFor's plugin branch).
                            pluginToken = result.token,
                            pluginId = plugin.id
                        ),
                        externalSubtitles = resolved.subtitles.map(::externalSubtitleFor),
                        pluginStreamAlreadyResolved = true,
                        audio = result.audio
                    )
                    // Back out of a plugin-played episode to the title it was picked from,
                    // the same as any other VOD item (see hidePlayer).
                    detailReturnItem = item
                }
                is ResolveResult.Failed -> {
                    Toast.makeText(this@showStreamSearchDialog, resolved.message, Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                }
            }
        }
    }

    fun addResultRow(result: TorrentResult, atFront: Boolean) {
        val row = layoutInflater.inflate(R.layout.item_stream_result, resultsHost, false)
        row.findViewById<TextView>(R.id.streamTitle).text = result.title
        row.findViewById<TextView>(R.id.streamMeta).text = listOfNotNull(
            result.quality,
            result.seeders?.let { "$it seeders" },
            result.size,
            result.source
        ).joinToString("  ·  ")
        row.setOnClickListener { playResult(result) }
        if (atFront) resultsHost.addView(row, 0) else resultsHost.addView(row)
        // The first result to arrive takes focus, so the common case - the top result is
        // the one you want - is one press away instead of a hunt down the list. Results
        // stream in one at a time, so this is the first one reported, not a re-focus on
        // every addition: taking focus again mid-search would yank it back off whatever
        // the user had already moved to.
        if (resultsHost.childCount == 1) {
            row.post { row.requestFocus() }
        }
    }

    val searchJob = scope.launch {
        val query = item.name
        val year = item.year?.toIntOrNull()
        val outcome = jsPluginEngine.runSearch(
            source = source, query = query, year = year, season = season, episode = episode,
            onProgress = { if (results.isEmpty()) status.text = it },
            onResult = { result ->
                // With "Prefer dubbed audio" on, a known-dub source jumps the queue so the
                // most likely pick surfaces first instead of being buried under the subs.
                val atFront = prefs.getBoolean(PREF_PREFER_DUB_AUDIO, false) && result.audio == "dub"
                if (atFront) results.add(0, result) else results.add(result)
                status.text = "${results.size} result(s)"
                addResultRow(result, atFront)
            }
        )
        if (results.isEmpty()) {
            status.text = when (outcome) {
                is SearchResult.Finished -> outcome.message ?: "No streams found"
                is SearchResult.Failed -> outcome.message
            }
        }
    }
    dialog.setOnCancelListener {
        searchJob.cancel()
        // A native-torrent resolve in progress won't stop on its own past this point (see
        // resolveTorrentStream's kdoc) - only reachable while it hasn't succeeded yet, since
        // a successful resolve already dismissed this dialog before the user could cancel it.
        if (plugin.resolvesNatively) {
            activeTorrentSession?.let { engine -> Thread { runCatching { engine.stop() } }.start() }
            activeTorrentSession = null
            TorrentForegroundService.stop(this)
        }
    }
    dialog.show()
}

// ── Plugins ────────────────────────────────────

/**
 * Settings > Plugins. Lists the user's installed JS plugin scripts, lets them switch one on,
 * run its discovery job, and add whatever it proposes.
 *
 * A deliberate gate, because a script's output is still untrusted input proposing servers
 * and credentials to point this app at: no proposal is written to the provider list without
 * a per-item confirmation naming which plugin it came from. [com.lumora.plugin.js.JsHostImpl]
 * does the field validation before any of this sees a candidate.
 */
internal fun MainActivity.wirePluginsPane(dialogView: View, onProviderAdded: () -> Unit = {}) {
    val listContainer = dialogView.findViewById<LinearLayout>(R.id.settingsPluginList)
    val listEmpty = dialogView.findViewById<View>(R.id.settingsPluginListEmpty)
    val manager = pluginScriptManager

    val detailPane = dialogView.findViewById<View>(R.id.panePluginDetail)
    val listPane = dialogView.findViewById<View>(R.id.panePlugins)
    val detailBack = dialogView.findViewById<View>(R.id.pluginDetailBack)
    val detailTitle = dialogView.findViewById<TextView>(R.id.pluginDetailTitle)
    val detailDescription = dialogView.findViewById<TextView>(R.id.pluginDetailDescription)
    val detailMeta = dialogView.findViewById<TextView>(R.id.pluginDetailMeta)
    val detailEnabledRow = dialogView.findViewById<View>(R.id.pluginDetailEnabledRow)
    val detailEnabledBox = dialogView.findViewById<CheckBox>(R.id.pluginDetailEnabled)
    val detailRunButton = dialogView.findViewById<View>(R.id.pluginDetailRunButton)
    val detailRunLabel = dialogView.findViewById<TextView>(R.id.pluginDetailRunLabel)
    val detailUpdateButton = dialogView.findViewById<View>(R.id.pluginDetailUpdateButton)
    val detailUpdateLabel = dialogView.findViewById<TextView>(R.id.pluginDetailUpdateLabel)
    val detailRemoveButton = dialogView.findViewById<View>(R.id.pluginDetailRemoveButton)
    val detailResults = dialogView.findViewById<View>(R.id.pluginDetailResults)
    val detailProgress = dialogView.findViewById<View>(R.id.pluginDetailProgress)
    val detailStatus = dialogView.findViewById<TextView>(R.id.pluginDetailStatus)
    val detailCandidateList = dialogView.findViewById<LinearLayout>(R.id.pluginDetailCandidateList)

    lateinit var renderPluginList: () -> Unit
    lateinit var renderPluginDetail: () -> Unit

    fun openPluginPage(id: String) {
        openPluginId = id
        // Reachable straight from the nav rail's plugin dropdown, bypassing selectSection() -
        // so whichever section pane (e.g. EPG) was showing before has to be hidden here too,
        // or it stays visible underneath this page.
        listOf(
            R.id.paneProviders, R.id.panePlayback, R.id.paneFilters, R.id.panePrivacy,
            R.id.paneBackup, R.id.paneEpg, R.id.paneDownloads, R.id.paneGeneral, R.id.paneAbout
        ).forEach { dialogView.findViewById<View>(it)?.visibility = View.GONE }
        listPane.visibility = View.GONE
        detailPane.visibility = View.VISIBLE
        // Landing on Back rather than nowhere: the page is rebuilt asynchronously, so
        // without this the D-pad has no starting point until the render lands.
        detailBack.requestFocus()
        renderPluginDetail()
    }

    fun closePluginPage() {
        openPluginId = null
        detailPane.visibility = View.GONE
        listPane.visibility = View.VISIBLE
        liveDiscoveryStatusView = null
        liveDiscoveryCandidateList = null
        renderPluginList()
    }
    // Settings always opens on the list, never on whichever plugin was last looked at.
    openPluginId = null
    detailPane.visibility = View.GONE

    fun fetchAndAddPluginScript(url: String) {
        val scheme = url.substringBefore("://", "").lowercase(Locale.US)
        if (url.isBlank() || (scheme != "http" && scheme != "https")) {
            Toast.makeText(this, "Enter a valid http(s) link", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val text: String? = try {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(url).build()
                    OkHttpClient().newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) resp.body?.string() else null
                    }
                }
            } catch (e: Exception) {
                null
            }
            if (text.isNullOrBlank()) {
                Toast.makeText(this@wirePluginsPane, "Couldn't fetch that script", Toast.LENGTH_SHORT).show()
                return@launch
            }
            when (val result = manager.installScript(text)) {
                is PluginScriptManager.InstallResult.Installed -> {
                    // Says so explicitly, because installing no longer switches it on and a
                    // plugin that is installed but does nothing is otherwise a puzzle.
                    val message = if (result.script.enabled) "Added ${result.script.label}"
                        else "Added ${result.script.label} - enable it to use it"
                    Toast.makeText(this@wirePluginsPane, message, Toast.LENGTH_LONG).show()
                    renderPluginList()
                }
                is PluginScriptManager.InstallResult.Rejected ->
                    Toast.makeText(this@wirePluginsPane, result.reason, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun showAddPluginScriptFromUrlDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com/my-plugin.js"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Add plugin script from URL")
            .setMessage("Enter the link to a Lumora plugin script (.js).")
            .setView(container)
            .setPositiveButton("Add") { _, _ -> fetchAndAddPluginScript(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    dialogView.findViewById<View>(R.id.settingsPluginInstallUrl)?.setOnClickListener {
        showAddPluginScriptFromUrlDialog()
    }
    wirePluginStoresSection(dialogView, manager) { renderPluginList() }

    fun addCandidateRow(
        candidateList: LinearLayout,
        plugin: PluginScript,
        candidate: DiscoveredProvider
    ) {
        val row = layoutInflater.inflate(R.layout.item_plugin_candidate_row, candidateList, false)
        val typeLabel = when (candidate.type) {
            "xtream" -> "Xtream"
            "stalker" -> "Stalker Portal"
            else -> "M3U/M3U8"
        }
        row.findViewById<TextView>(R.id.candidateName).text = candidate.label
        row.findViewById<TextView>(R.id.candidateDetail).text =
            listOfNotNull("$typeLabel · ${candidate.url}", candidate.detail).joinToString("\n")
        // The plugin's own claim that it tested this, labelled as such - the host hasn't
        // verified anything at this point.
        row.findViewById<View>(R.id.candidateVerified).visibility =
            if (candidate.verified) View.VISIBLE else View.GONE
        val addButton = row.findViewById<View>(R.id.candidateAddButton)
        val addLabel = row.findViewById<TextView>(R.id.candidateAddLabel)
        // Survives the re-render that follows every discovery progress line - the button is
        // a fresh view each time, but the fact it was already used is not.
        if (candidate.url in pluginDiscoveryAdded) {
            addLabel.text = "Added"
            addButton.isEnabled = false
            addButton.isFocusable = false
        }
        addButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Add ${candidate.label}?")
                .setMessage(
                    "${plugin.label} found this $typeLabel provider:\n\n${candidate.url}\n\n" +
                        "Adding it saves those details as a provider in Lumora."
                )
                .setPositiveButton("Add") { _, _ ->
                    IptvProviderStore.upsert(
                        prefs,
                        IptvProviderConfig(
                            id = IptvProviderStore.newId(),
                            type = candidate.type,
                            name = candidate.label,
                            enabled = true,
                            url = candidate.url,
                            username = candidate.username,
                            password = candidate.password,
                            // Stalker's MAC and M3U's custom UA share this slot everywhere
                            // else in the app (see loadAllConfiguredProviders).
                            userAgent = candidate.userAgent
                        )
                    )
                    pluginDiscoveryAdded.add(candidate.url)
                    addLabel.text = "Added"
                    addButton.isEnabled = false
                    addButton.isFocusable = false
                    // Rebuild the provider list in the same settings screen so the newly
                    // added provider shows up immediately instead of only after reopening.
                    refreshIptvProviderList.invoke()
                    try {
                        loadAllConfiguredProviders(forceRefresh = true)
                    } catch (_: Exception) {
                        // A malformed candidate (blank URL, missing credentials) can crash
                        // the provider load. The upsert already succeeded; don't let the
                        // crash abort the UI navigation that shows the user where it landed.
                    }
                    // The user was on this plugin's page when they tapped Add; the providers
                    // list they actually want to see is in the Providers pane, so jump there
                    // rather than leaving them staring at the now-empty "Added" button.
                    onProviderAdded()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        candidateList.addView(row)
    }

    fun runDiscovery(plugin: PluginScript) {
        pluginDiscoveryJob?.cancel()
        // A run owns the results area, so anything the previous plugin left there goes -
        // two plugins' candidates in one list would be unattributable.
        pluginDiscoveryPluginId = plugin.id
        pluginDiscoveryCandidates.clear()
        pluginDiscoveryAdded.clear()
        pluginDiscoveryStatus = "Starting ${plugin.label}…"
        liveDiscoveryStatusView = null
        liveDiscoveryCandidateList = null
        liveDiscoveryPlugin = null
        // Run is only reachable from the plugin's own page, and that page is where the
        // results render - so it is already open. Redraw it to show the run starting.
        renderPluginDetail()
        pluginDiscoveryJob = scope.launch {
            val source = manager.readSource(plugin)
            val result = jsPluginEngine.runDiscovery(
                source,
                onProgress = { line ->
                    pluginDiscoveryStatus = line
                    liveDiscoveryStatusView?.text = line
                },
                onCandidate = { candidate ->
                    pluginDiscoveryCandidates.add(candidate)
                    // Appended to the live list where one exists; otherwise it's still held
                    // in the list above and the render at the end of the run puts it there.
                    liveDiscoveryCandidateList?.let { list ->
                        addCandidateRow(list, liveDiscoveryPlugin ?: plugin, candidate)
                    }
                }
            )
            val found = pluginDiscoveryCandidates.size
            pluginDiscoveryStatus = when (result) {
                is DiscoveryResult.Finished ->
                    result.message ?: if (found == 0) "Nothing found" else "Found $found"
                is DiscoveryResult.Failed -> result.message
            }
            pluginDiscoveryJob = null
            liveDiscoveryStatusView = null
            liveDiscoveryCandidateList = null
            liveDiscoveryPlugin = null
            // The page shows the run; the list behind it shows its outcome in the summary
            // line, so both are redrawn.
            renderPluginDetail()
            renderPluginList()
        }
    }

    // ── The plugin list, and one plugin's own page ──

    fun openPluginDetail(id: String) {
        openPluginPage(id)
    }

    renderPluginList = {
        scope.launch {
            val plugins = manager.discoverScripts()
            listContainer.removeAllViews()
            listEmpty.visibility = if (plugins.isEmpty()) View.VISIBLE else View.GONE
            for (plugin in plugins) {
                val row = layoutInflater.inflate(R.layout.item_plugin_row, listContainer, false)
                row.findViewById<TextView>(R.id.pluginName).text = plugin.label
                row.findViewById<TextView>(R.id.pluginSummary).text = listOfNotNull(
                    if (plugin.enabled) "Enabled" else "Disabled",
                    pluginDiscoveryStatus.takeIf { plugin.id == pluginDiscoveryPluginId }
                ).joinToString("  ·  ")
                row.setOnClickListener { openPluginDetail(plugin.id) }
                listContainer.addView(row)

                if (plugin.id == pluginFocusRequestId) {
                    pluginFocusRequestId = null
                    pluginFocusRequestViewId = View.NO_ID
                    row.post { row.requestFocus() }
                }
            }
        }
        Unit
    }

    // Wires the dedicated plugin page against whichever plugin is currently open. Rebuilt
    // rather than bound once: enabling, updating and running all change what it should say,
    // and a discovery run rewrites its results as it goes.
    renderPluginDetail = {
        val id = openPluginId
        if (id != null) scope.launch {
            val plugin = manager.discoverScripts().firstOrNull { it.id == id }
            if (plugin == null) {
                // Removed from under us - the list is the only sensible place to land.
                closePluginPage()
            } else {
                val running = pluginDiscoveryJob?.isActive == true
                val isRunningPlugin = plugin.id == pluginDiscoveryPluginId

                detailTitle.text = plugin.label
                detailDescription.text = plugin.description.orEmpty()
                detailDescription.visibility =
                    if (plugin.description.isNullOrBlank()) View.GONE else View.VISIBLE
                detailMeta.text = buildList {
                    if (plugin.supportsDiscovery) add("Provider discovery")
                    if (plugin.supportsStreamSearch) add("Stream search")
                    addAll(plugin.contentTypes)
                }.joinToString("  ·  ").uppercase(Locale.US)

                detailEnabledBox.isChecked = plugin.enabled
                fun applyEnabledToggle() {
                    manager.setEnabled(plugin.id, !plugin.enabled)
                    // A scraper_sites script is the gate on every built-in scraper site, so
                    // toggling it has to take effect now rather than at the next launch.
                    if (plugin.supportsScraperSites) loadScraperSiteManifest()
                    pluginFocusRequestViewId = R.id.pluginDetailEnabledRow
                    renderPluginDetail()
                    renderPluginList()
                    refreshPluginNavRows?.invoke()
                    if (plugin.supportsStreamSearch) loadAllConfiguredProviders(forceRefresh = true)
                }
                detailEnabledRow.setOnClickListener {
                    // Only the OFF -> ON transition needs the disclaimer - switching one off
                    // never reaches out to a public site.
                    if (!plugin.enabled && (plugin.supportsStreamSearch || plugin.supportsScraperSites)) {
                        ensurePublicContentDisclaimerAccepted { applyEnabledToggle() }
                    } else {
                        applyEnabledToggle()
                    }
                }

                // Run only applies to discovery plugins; a stream_search plugin is driven
                // from a title's "Find stream" instead.
                if (plugin.supportsDiscovery) {
                    detailRunButton.visibility = View.VISIBLE
                    detailRunLabel.text = if (running && isRunningPlugin) "Running…" else "Run"
                    // Dimmed but still focusable when it can't be used: setEnabled(false)
                    // takes a View out of focus search entirely, and Run is exactly what the
                    // user is heading for after enabling a plugin, so it has to stay on the
                    // path. The click explains itself instead.
                    detailRunButton.alpha = if (plugin.enabled && !running) 1f else 0.4f
                    detailRunButton.setOnClickListener {
                        when {
                            running -> Toast.makeText(
                                this@wirePluginsPane, "A plugin is already running", Toast.LENGTH_SHORT
                            ).show()
                            !plugin.enabled -> Toast.makeText(
                                this@wirePluginsPane, "Enable ${plugin.label} first", Toast.LENGTH_SHORT
                            ).show()
                            else -> runDiscovery(plugin)
                        }
                    }
                } else {
                    detailRunButton.visibility = View.GONE
                    detailRunButton.setOnClickListener(null)
                }

                detailUpdateLabel.text = getString(R.string.update)
                detailUpdateButton.setOnClickListener {
                    detailUpdateLabel.text = "Updating…"
                    scope.launch {
                        val message = updatePluginFromStore(plugin)
                        Toast.makeText(this@wirePluginsPane, message, Toast.LENGTH_LONG).show()
                        pluginFocusRequestViewId = R.id.pluginDetailUpdateButton
                        renderPluginDetail()
                        renderPluginList()
                        refreshPluginNavRows?.invoke()
                    }
                }

                detailRemoveButton.setOnClickListener {
                    AlertDialog.Builder(this@wirePluginsPane)
                        .setTitle("Remove ${plugin.label}?")
                        .setMessage("This deletes the installed script. You can reinstall it later from a plugin store or its URL.")
                        .setPositiveButton("Remove") { _, _ ->
                            manager.setEnabled(plugin.id, false)
                            manager.removeUserScript(plugin.fileName)
                            if (plugin.supportsScraperSites) loadScraperSiteManifest()
                            closePluginPage()
                            renderPluginList()
                            refreshPluginNavRows?.invoke()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }

                // Results are this plugin's own, rebuilt from the state rather than from
                // whatever views survived - this runs again on every interaction, and a run
                // may still be in flight while it does.
                if (isRunningPlugin && pluginDiscoveryStatus != null) {
                    detailResults.visibility = View.VISIBLE
                    detailProgress.visibility = if (running) View.VISIBLE else View.GONE
                    detailStatus.text = pluginDiscoveryStatus
                    detailCandidateList.removeAllViews()
                    for (candidate in pluginDiscoveryCandidates) {
                        addCandidateRow(detailCandidateList, plugin, candidate)
                    }
                    // While a run is live these are what each progress line and candidate is
                    // written into directly - re-rendering the page per line would rebuild
                    // every focusable view under the user.
                    if (running) {
                        liveDiscoveryStatusView = detailStatus
                        liveDiscoveryCandidateList = detailCandidateList
                        liveDiscoveryPlugin = plugin
                    }
                } else {
                    detailResults.visibility = View.GONE
                }

                if (pluginFocusRequestViewId != View.NO_ID) {
                    val target = dialogView.findViewById<View>(pluginFocusRequestViewId)
                    pluginFocusRequestViewId = View.NO_ID
                    target?.post { target.requestFocus() }
                }
            }
        }
        Unit
    }

    detailBack.setOnClickListener { closePluginPage() }
    closeOpenPluginPage = { closePluginPage() }

    // Lets the nav rail's plugin rows open a plugin's page - see wirePluginNavRows.
    revealPluginInPane = { id -> openPluginDetail(id) }
    renderPluginList()
}

/**
 * Re-installs [plugin] from whichever configured store lists its id, and reports what
 * happened as a message for the caller to show.
 *
 * Matched on the manifest id rather than the file name: a store is free to rename its file,
 * and the id is what [PluginScriptManager.installScript] overwrites on, so those two have to
 * agree or an "update" would install a second copy alongside the old one.
 */
internal suspend fun MainActivity.updatePluginFromStore(plugin: PluginScript): String {
    val stores = pluginStoreManager.storeUrls()
    for (store in stores) {
        val catalog = pluginStoreManager.fetchCatalog(store.url).getOrNull() ?: continue
        val entry = catalog.firstOrNull { it.id == plugin.id } ?: continue
        val text = pluginStoreManager.fetchScriptText(entry.fileUrl)
            ?: return "Couldn't download ${plugin.label}"
        // installScript() preserves the stored enabled state, so an update can't switch a
        // plugin the user had turned off back on.
        return when (val result = pluginScriptManager.installScript(text)) {
            is PluginScriptManager.InstallResult.Installed -> "Updated ${result.script.label}"
            is PluginScriptManager.InstallResult.Rejected -> "Update rejected: ${result.reason}"
        }
    }
    return "${plugin.label} isn't in any configured plugin store"
}

/**
 * Makes the nav rail's Plugins row a dropdown over the installed plugins. Each child opens
 * the Plugins pane with that plugin's section already expanded and focused, which is where
 * it can be updated or enabled/disabled - the rail itself is navigation, so a child row only
 * reports the enabled state rather than being another place that changes it.
 *
 * This is the reason a discovery plugin is reachable at all on a long list: the Reddit
 * scanner sits near the bottom of the installed plugins, which is several screens down a
 * pane that also holds the install-from-URL card and the store list above it.
 */
internal fun MainActivity.wirePluginNavRows(dialogView: View, openPluginsPane: () -> Unit) {
    val parentRow = dialogView.findViewById<View>(R.id.navPlugins)
    val caret = dialogView.findViewById<TextView>(R.id.navPluginsCaret)
    val children = dialogView.findViewById<LinearLayout>(R.id.navPluginChildren)

    fun render() {
        scope.launch {
            val plugins = pluginScriptManager.discoverScripts()
            children.removeAllViews()
            for (plugin in plugins) {
                val row = layoutInflater.inflate(R.layout.item_plugin_nav_row, children, false)
                row.findViewById<TextView>(R.id.pluginNavLabel).text = plugin.label
                row.findViewById<TextView>(R.id.pluginNavState).text =
                    if (plugin.enabled) "✓" else "○"
                row.setOnClickListener {
                    openPluginsPane()
                    revealPluginInPane?.invoke(plugin.id)
                }
                children.addView(row)
            }
            val hasPlugins = plugins.isNotEmpty()
            children.visibility = if (navPluginsExpanded && hasPlugins) View.VISIBLE else View.GONE
            caret.visibility = if (hasPlugins) View.VISIBLE else View.GONE
            caret.text = if (navPluginsExpanded) "▾" else "▸"
        }
        Unit
    }
    refreshPluginNavRows = { render() }

    // Selecting the parent does both jobs: it opens the pane (what every other rail row
    // does, so the row doesn't behave differently from its neighbours) and expands the list.
    parentRow.setOnClickListener {
        openPluginsPane()
        navPluginsExpanded = !navPluginsExpanded
        render()
    }
    render()
}

/**
 * Settings > Plugins > Plugin Stores. A store is a small JSON catalog listing scripts a user
 * can install with one tap - see [PluginStoreManager]'s kdoc for the schema. The default
 * store (Lumora's own plugin repo) is always present; users can add more (a community repo,
 * their own fork, ...) and remove any they added. [onInstalled] refreshes the plain
 * installed-plugin list above once something new lands.
 */
internal fun MainActivity.wirePluginStoresSection(dialogView: View, manager: PluginScriptManager, onInstalled: () -> Unit) {
    val listContainer = dialogView.findViewById<LinearLayout>(R.id.settingsPluginStoreList)
    val listEmpty = dialogView.findViewById<View>(R.id.settingsPluginStoreListEmpty)

    fun installFromStore(storeScript: StoreScript, onDone: (PluginScriptManager.InstallResult) -> Unit) {
        scope.launch {
            val text = pluginStoreManager.fetchScriptText(storeScript.fileUrl)
            if (text.isNullOrBlank()) {
                onDone(PluginScriptManager.InstallResult.Rejected("Couldn't download that script"))
                return@launch
            }
            // No enabled-state juggling here: installScript() leaves it alone, so an update
            // keeps whatever the user had chosen and a first install lands switched off.
            onDone(manager.installScript(text))
        }
    }

    fun showBrowseStoreDialog(store: PluginStore) {
        val status = TextView(this).apply {
            text = "Loading…"
            setTextColor(ContextCompat.getColor(this@wirePluginStoresSection, R.color.text_secondary))
        }
        val resultsHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(status)
            addView(resultsHost)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(store.name ?: store.url)
            .setView(ScrollView(this).apply { addView(container) })
            .setNegativeButton("Close", null)
            .create()
        dialog.show()

        scope.launch {
            val installedIds = manager.discoverScripts().map { it.id }.toSet()
            val result = pluginStoreManager.fetchCatalog(store.url)
            val catalog = result.getOrNull()
            if (catalog == null) {
                status.text = "Couldn't load this store"
                return@launch
            }
            if (catalog.isEmpty()) {
                status.text = "No scripts listed"
                return@launch
            }
            status.text = "${catalog.size} script${if (catalog.size == 1) "" else "s"}"
            for (storeScript in catalog) {
                val row = layoutInflater.inflate(R.layout.item_plugin_candidate_row, resultsHost, false)
                row.findViewById<TextView>(R.id.candidateName).text = storeScript.label
                row.findViewById<TextView>(R.id.candidateDetail).text = listOfNotNull(
                    storeScript.capabilities.joinToString(", ").takeIf { it.isNotBlank() },
                    storeScript.description
                ).joinToString("\n")
                row.findViewById<View>(R.id.candidateVerified).visibility = View.GONE
                val installButton = row.findViewById<View>(R.id.candidateAddButton)
                val installLabel = row.findViewById<TextView>(R.id.candidateAddLabel)
                // Already installed doesn't mean "nothing to do" - re-installing overwrites
                // in place (see PluginScriptManager.installScript), which is exactly how you
                // pick up a store update. Stays clickable either way, just relabeled.
                val alreadyInstalled = storeScript.id in installedIds
                val idleLabel = if (alreadyInstalled) "Update" else "Install"
                installLabel.text = idleLabel
                fun doInstall() {
                    installButton.isEnabled = false
                    installLabel.text = if (alreadyInstalled) "Updating…" else "Installing…"
                    installFromStore(storeScript) { outcome ->
                        when (outcome) {
                            is PluginScriptManager.InstallResult.Installed -> {
                                installLabel.text = if (alreadyInstalled) "Updated" else "Installed"
                                installButton.isEnabled = true
                                // A first install switches itself on (see
                                // PluginScriptManager.installScript); a re-install/update
                                // leaves whatever the user had chosen alone.
                                Toast.makeText(
                                    this@wirePluginStoresSection,
                                    if (outcome.script.enabled) "${storeScript.label} installed"
                                    else "${storeScript.label} installed - enable it to use it",
                                    Toast.LENGTH_LONG
                                ).show()
                                onInstalled()
                            }
                            is PluginScriptManager.InstallResult.Rejected -> {
                                installLabel.text = idleLabel
                                installButton.isEnabled = true
                                Toast.makeText(this@wirePluginStoresSection, outcome.reason, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                installButton.setOnClickListener {
                    // A first install switches itself on, which for one of these capabilities
                    // means it starts reaching out to public sites the moment it lands - the
                    // disclaimer has to clear before that happens, not after.
                    val isPublicContent = !alreadyInstalled && (
                        JsPluginContract.CAPABILITY_STREAM_SEARCH in storeScript.capabilities ||
                            JsPluginContract.CAPABILITY_SCRAPER_SITES in storeScript.capabilities
                        )
                    if (isPublicContent) ensurePublicContentDisclaimerAccepted { doInstall() } else doInstall()
                }
                resultsHost.addView(row)
            }
        }
    }

    lateinit var renderStoreList: () -> Unit

    fun showAddStoreDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com/plugins/index.json"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Add plugin store")
            .setMessage("Enter the link to a plugin store's catalog (a small JSON file listing its scripts).")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val url = input.text.toString().trim()
                val scheme = url.substringBefore("://", "").lowercase(Locale.US)
                if (url.isBlank() || (scheme != "http" && scheme != "https")) {
                    Toast.makeText(this, "Enter a valid http(s) link", Toast.LENGTH_SHORT).show()
                } else {
                    pluginStoreManager.addStore(url)
                    renderStoreList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    renderStoreList = {
        listContainer.removeAllViews()
        val stores = pluginStoreManager.storeUrls()
        listEmpty.visibility = if (stores.isEmpty()) View.VISIBLE else View.GONE
        for (store in stores) {
            val row = layoutInflater.inflate(R.layout.item_plugin_store_row, listContainer, false)
            row.findViewById<TextView>(R.id.storeName).text = store.name ?: store.url
            row.findViewById<TextView>(R.id.storeUrl).text = store.url
            row.findViewById<View>(R.id.storeBrowseButton).setOnClickListener { showBrowseStoreDialog(store) }
            val removeButton = row.findViewById<View>(R.id.storeRemoveButton)
            if (store.removable) {
                removeButton.visibility = View.VISIBLE
                removeButton.setOnClickListener {
                    pluginStoreManager.removeStore(store.url)
                    renderStoreList()
                }
            } else {
                removeButton.visibility = View.GONE
            }
            listContainer.addView(row)
            // Fetch the store's self-declared name in the background and fill it in once
            // known - showing the URL immediately means the row isn't empty while loading.
            if (store.name == null) {
                scope.launch {
                    pluginStoreManager.fetchStoreName(store.url)?.let { name ->
                        row.findViewById<TextView>(R.id.storeName).text = name
                    }
                }
            }
        }
    }
    dialogView.findViewById<View>(R.id.settingsPluginAddStore)?.setOnClickListener { showAddStoreDialog() }
    renderStoreList()
}
