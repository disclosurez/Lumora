package com.lumora.plugin.js

import android.os.Handler
import android.os.Looper
import com.lumora.plugin.DiscoveredProvider
import com.lumora.plugin.DiscoveryResult
import com.lumora.plugin.PluginSubtitle
import com.lumora.plugin.ResolveResult
import com.lumora.plugin.SearchResult
import com.lumora.plugin.TorrentResult
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import com.lumora.BaseApplication
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/** The app-wide client, resolved lazily: the constructor default below is evaluated at the
 *  first engine built without an explicit client (production paths reach that only after
 *  Application.onCreate has set BaseApplication.instance), and every JVM unit test passes its
 *  own client - so nothing test-hostile ever touches this lookup. */
private val sharedHttpClient: OkHttpClient by lazy { BaseApplication.instance.okHttpClient }

/**
 * Runs a JS plugin script's `discover()`/`search()`/`resolve()` entry points, replacing
 * [com.lumora.plugin.PluginClient]/[com.lumora.plugin.StreamSearchClient]'s bind-a-Messenger-
 * service dance. Every call gets a fresh [QuickJSContext] and a fresh [JsHostImpl] - scripts
 * are short-lived (one discovery run, one search, one resolve) exactly like the old
 * per-operation Messenger bind, so there's no state to leak between runs and no pool to reason
 * about. See [JsPluginContract] for the script-facing contract this drives.
 */
class JsPluginEngine(
    // Shared client instead of a bare OkHttpClient() per engine: engines are constructed per
    // manager/feature, and each private one meant its own connection pool whose connections
    // were never reused by anything else.
    private val httpClient: OkHttpClient = sharedHttpClient,
) {

    /**
     * `onProgress`/`onCandidate`/`onResult` are called from [runScript]'s dedicated background
     * executor thread, not the caller's thread - but every real caller is Android UI code
     * (`status.text = ...`, inflating and adding a result row, ...) that assumes it's on the
     * main thread. Calling it straight from the executor thread is a real `CalledFromWrongThreadException`
     * risk once a callback does anything beyond a plain field write (verified against a real
     * device: a torrent search that reported one result crashed there, aborting the rest of the
     * script's execution and leaving one half-added, unclickable row behind - "1 result,
     * nothing selectable"). Every UI-facing callback this class exposes is hopped onto the main
     * thread before the caller ever sees it, so callers don't each have to remember to do this
     * themselves.
     */
    // Lazy: touching Looper.getMainLooper() eagerly would break every JVM unit test (no
    // Android framework Looper exists there) even for paths like probeManifest/resolve that
    // never need this at all - only construct it once a runDiscovery/runSearch actually does.
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Posting decouples the executor thread from the callback's completion - it no longer
     * blocks waiting for the UI update, so a caller's callback throwing must never escape
     * uncaught here: an exception from a `Handler.post` runnable is fatal to the whole app
     * (there's nothing upstream to catch it, unlike the old synchronous-call behavior where
     * [JsPluginEngine]'s own `runScript` would catch it and just fail that one plugin run).
     *
     * [finished] is the run's completion flag (see `runScript`): once a run has completed or
     * timed out, its executor thread may still be executing a wedged script, and any report
     * callbacks it makes after that must be dropped - otherwise "Found N" keeps appending
     * after the dialog has already shown "Plugin timed out".
     */
    private fun <T> onMain(
        callback: (T) -> Unit,
        finished: AtomicBoolean = AtomicBoolean(false),
    ): (T) -> Unit = { value ->
        // Check before posting so a late executor-thread report never even reaches the queue.
        if (!finished.get()) {
            fun dispatch() {
                // Re-check at dispatch time: the flag can be set between the post and now.
                if (finished.get()) return
                // CancellationException must not be swallowed: rethrowing keeps coroutine
                // cancellation observable to the caller instead of being logged away.
                try {
                    callback(value)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PluginLog.w(TAG, "UI callback threw: ${e.message}")
                }
            }
            // Looper itself is an unmocked Android stub under this project's plain JVM unit tests
            // (no Robolectric) - treat that as "just call it directly" (which is what a test wants
            // anyway: a synchronous, same-thread callback) rather than let it blow up runDiscovery/
            // runSearch outright.
            val isMainThread = runCatching { Looper.myLooper() == Looper.getMainLooper() }.getOrDefault(true)
            if (isMainThread) dispatch() else mainHandler.post { dispatch() }
        }
    }

    suspend fun runDiscovery(
        source: String,
        onProgress: (String) -> Unit = {},
        onCandidate: (DiscoveredProvider) -> Unit = {},
    ): DiscoveryResult {
        PluginLog.i(TAG, "discover() start")
        // Set once the run has completed or timed out; onMain drops any report that arrives
        // after it, since a timed-out script keeps executing on its executor thread.
        val finished = AtomicBoolean(false)
        val mainProgress = onMain(onProgress, finished)
        val mainCandidate = onMain(onCandidate, finished)
        // Discovery diagnostics are logged at INFO, not DEBUG: the reddit scanner's own
        // host.log() lines (oauth status, post/paste counts, per-paste results, token state) are
        // the whole story when a scan finds nothing, and the tested Fire TV drops DEBUG from its
        // logcat ring buffer - so at .d they never survive to be read back.
        val host = JsHostImpl(
            client = httpClient,
            onProgress = { PluginLog.i(TAG, "discover progress: $it"); mainProgress(it) },
            onCandidate = {
                PluginLog.i(TAG, "discover candidate: type=${it[JsPluginContract.KEY_TYPE]} url=${it[JsPluginContract.KEY_URL]}")
                mainCandidate(it.toDiscoveredProvider())
            },
            onLog = { PluginLog.i(TAG, "script: $it") },
        )
        val result = when (val outcome = runScript(JsPluginContract.DISCOVERY_TIMEOUT_MS, host, finished) { context ->
            context.evaluate("$source\ndiscover(host);")
        }) {
            // discover()'s return value is its specific reason ("No credentials found in
            // pastes", "Tested N, none responded", ...) - discarding it and always showing
            // null left the UI falling back to a generic "Nothing found" no matter why.
            is ScriptOutcome.Success -> DiscoveryResult.Finished(outcome.result as? String)
            is ScriptOutcome.Failure -> DiscoveryResult.Failed(outcome.message)
            ScriptOutcome.TimedOut -> DiscoveryResult.Failed("Plugin timed out")
        }
        PluginLog.i(TAG, "discover() finished: $result")
        return result
    }

    suspend fun runSearch(
        source: String,
        query: String,
        year: Int?,
        season: Int?,
        episode: Int?,
        onProgress: (String) -> Unit = {},
        onResult: (TorrentResult) -> Unit = {},
    ): SearchResult {
        PluginLog.i(TAG, "search() start: query=\"$query\" year=$year season=$season episode=$episode")
        val finished = AtomicBoolean(false)
        val mainProgress = onMain(onProgress, finished)
        val mainResult = onMain(onResult, finished)
        val host = JsHostImpl(
            client = httpClient,
            query = query,
            year = year,
            season = season,
            episode = episode,
            onProgress = { PluginLog.d(TAG, "search progress: $it"); mainProgress(it) },
            onResult = {
                PluginLog.d(TAG, "search result: title=${it[JsPluginContract.KEY_TITLE]} source=${it[JsPluginContract.KEY_SOURCE]}")
                mainResult(it.toTorrentResult())
            },
            onLog = { PluginLog.d(TAG, "script: $it") },
        )
        val result = when (val outcome = runScript(JsPluginContract.SEARCH_TIMEOUT_MS, host, finished) { context ->
            context.evaluate("$source\nsearch(host, host.query, host.year, host.season, host.episode);")
        }) {
            is ScriptOutcome.Success -> SearchResult.Finished(outcome.result as? String)
            is ScriptOutcome.Failure -> SearchResult.Failed(outcome.message)
            ScriptOutcome.TimedOut -> SearchResult.Failed("Plugin timed out")
        }
        PluginLog.i(TAG, "search() finished: $result")
        return result
    }

    suspend fun resolve(source: String, token: String, season: Int?, episode: Int?): ResolveResult {
        PluginLog.i(TAG, "resolve() start: season=$season episode=$episode")
        val host = JsHostImpl(
            client = httpClient,
            token = token,
            season = season,
            episode = episode,
            onLog = { PluginLog.d(TAG, "script: $it") },
        )
        // resolve() may return either a bare URL string, or an object
        // { url, headers: {...}, subtitles: [...] } when the stream needs extra request headers
        // (e.g. a Referer for a hotlink-protected CDN) or carries sidecar subtitle tracks. The
        // JSObject must be flattened here, on the context's own thread, before runScript destroys
        // the context - reading a live JS reference after destroy() throws (see probeManifest).
        val result = when (val outcome = runScript(JsPluginContract.RESOLVE_TIMEOUT_MS, host) { context ->
            when (val r = context.evaluate("$source\nresolve(host, host.token, host.season, host.episode);")) {
                is String -> ResolvedStream(r, emptyMap(), emptyList())
                is JSObject -> {
                    val m = r.toMap()
                    val url = m["url"] as? String ?: return@runScript null
                    // Headers come across as a JSON string (`headers: JSON.stringify({...})`), which
                    // survives toMap deterministically - a nested object property can be dropped or
                    // returned as an already-freed handle depending on the bridge. A raw map/object
                    // is still accepted as a fallback for any script that sends one.
                    val headers: Map<String, String> = when (val h = m["headers"]) {
                        is String -> runCatching {
                            val jo = org.json.JSONObject(h)
                            jo.keys().asSequence().associateWith { k -> jo.getString(k) }
                        }.getOrDefault(emptyMap())
                        is JSObject -> h.toMap().entries.associate { it.key to it.value.toString() }
                        is Map<*, *> -> h.entries.associate { it.key.toString() to it.value.toString() }
                        else -> emptyMap()
                    }
                    ResolvedStream(url, headers, parseSubtitles(m["subtitles"]), audioOf(m["audio"]))
                }
                else -> null
            }
        }) {
            is ScriptOutcome.Success -> {
                val resolved = outcome.result as? ResolvedStream
                val url = resolved?.url
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    ResolveResult.Ready(url, resolved.headers, resolved.subtitles, resolved.audio)
                } else {
                    ResolveResult.Failed("Plugin returned an invalid stream URL")
                }
            }
            is ScriptOutcome.Failure -> ResolveResult.Failed(outcome.message)
            ScriptOutcome.TimedOut -> ResolveResult.Failed("Plugin timed out")
        }
        PluginLog.i(TAG, "resolve() finished: $result")
        return result
    }

    /**
     * Runs a `scraper_sites` script's `sites(host)` and returns the JSON string it produced, or
     * null if it failed, timed out or returned something else.
     *
     * A string rather than a parsed structure on purpose: it crosses the QuickJS bridge exactly
     * once, deterministically, and is parsed on the Kotlin side by whoever actually understands
     * the schema ([com.lumora.scraper.bridge.ScraperSiteManifest]). Handing back a JSObject would
     * mean flattening a nested array of objects through `toMap`, which is the one shape that
     * bridge does not round-trip reliably.
     */
    suspend fun scraperSites(source: String): String? {
        val host = JsHostImpl(client = httpClient, onLog = { PluginLog.d(TAG, "script: $it") })
        val outcome = runScript(JsPluginContract.SITES_TIMEOUT_MS, host) { context ->
            context.evaluate("$source\nsites(host);") as? String
        }
        return when (outcome) {
            is ScriptOutcome.Success -> (outcome.result as? String)?.takeIf { it.isNotBlank() }
            is ScriptOutcome.Failure -> {
                PluginLog.w(TAG, "sites() failed: ${outcome.message}")
                null
            }
            ScriptOutcome.TimedOut -> {
                PluginLog.w(TAG, "sites() timed out")
                null
            }
        }
    }

    /** Evaluates just enough of [source] to read its `PLUGIN` manifest object, if any. */
    suspend fun probeManifest(source: String): Map<String, Any?>? {
        val host = JsHostImpl(client = httpClient)
        // toMap() must happen here, on the context's own thread, before runScript's `finally`
        // destroys the context - a JSObject is a live reference into the JS heap and reading it
        // after destroy() throws (refcount already zero).
        val outcome = runScript(MANIFEST_PROBE_TIMEOUT_MS, host) { context ->
            (context.evaluate("$source\nPLUGIN;") as? JSObject)?.toMap()
        }
        @Suppress("UNCHECKED_CAST")
        return (outcome as? ScriptOutcome.Success)?.result as? Map<String, Any?>
    }

    /**
     * Runs [body] on a dedicated single-thread executor, since [QuickJSContext] requires every
     * call against one instance (creation, evaluation, destruction) to happen on the thread that
     * created it.
     *
     * The timeout is soft: [withTimeoutOrNull] only ever gets to abandon *waiting* for the
     * background thread - this QuickJS binding has no exposed way to interrupt a script that's
     * stuck in a synchronous, non-I/O loop (unlike the old Messenger protocol, where a wedged
     * plugin ran in its own OS process and the host just stopped listening to it). A script
     * busy-looping with no host calls leaks that one thread for as long as it runs; every I/O
     * call it makes (`host.httpGet`, etc.) is still bounded by OkHttp's own timeouts, which
     * covers the realistic slow-plugin case. This is a deliberate, documented trade-off for
     * running plugins in-process instead of as separate installable apps.
     */
    private suspend fun runScript(
        timeoutMs: Long,
        host: JsHostImpl,
        finished: AtomicBoolean = AtomicBoolean(false),
        body: (QuickJSContext) -> Any?,
    ): ScriptOutcome {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    executor.execute {
                        val outcome: ScriptOutcome = try {
                            val context = QuickJSContext.create()
                            try {
                                host.install(context)
                                ScriptOutcome.Success(body(context))
                            } finally {
                                context.destroy()
                            }
                        } catch (e: Exception) {
                            ScriptOutcome.Failure(shortMessage(e))
                        }
                        runCatching { cont.resume(outcome) }
                        executor.shutdown()
                    }
                }
            } ?: ScriptOutcome.TimedOut
        } finally {
            // Whether the script finished or timed out, the run is over from the caller's
            // perspective: flag it so onMain drops any callback the (still-running, in the
            // timeout case) executor thread reports afterwards.
            finished.set(true)
        }
    }

    /** QuickJSException appends a JS stack trace after the message on its own lines - keep only the first. */
    private fun shortMessage(e: Throwable): String =
        (e.message ?: e::class.simpleName ?: "Plugin error")
            .lineSequence().first()
            .take(JsPluginContract.MAX_TEXT_LENGTH)

    private fun Map<String, Any?>.toDiscoveredProvider(): DiscoveredProvider = DiscoveredProvider(
        type = this[JsPluginContract.KEY_TYPE] as? String ?: "",
        label = this[JsPluginContract.KEY_LABEL] as? String ?: "",
        url = this[JsPluginContract.KEY_URL] as? String ?: "",
        username = this[JsPluginContract.KEY_USERNAME] as? String,
        password = this[JsPluginContract.KEY_PASSWORD] as? String,
        userAgent = this[JsPluginContract.KEY_USER_AGENT] as? String,
        detail = this[JsPluginContract.KEY_DETAIL] as? String,
        verified = this[JsPluginContract.KEY_VERIFIED] as? Boolean ?: false,
    )

    private fun Map<String, Any?>.toTorrentResult(): TorrentResult = TorrentResult(
        title = this[JsPluginContract.KEY_TITLE] as? String ?: "",
        token = this[JsPluginContract.KEY_TOKEN] as? String ?: "",
        seeders = (this[JsPluginContract.KEY_SEEDERS] as? Number)?.toInt(),
        size = this[JsPluginContract.KEY_SIZE] as? String,
        quality = this[JsPluginContract.KEY_QUALITY] as? String,
        source = this[JsPluginContract.KEY_SOURCE] as? String,
        audio = audioOf(this[JsPluginContract.KEY_AUDIO]),
    )

    /** Audio category hint from a script value, normalised to "sub"/"dub"/null. */
    private fun audioOf(raw: Any?): String? = when (val s = raw as? String) {
        null -> null
        else -> s.lowercase().takeIf { it == "sub" || it == "dub" }
    }

    private sealed class ScriptOutcome {
        data class Success(val result: Any?) : ScriptOutcome()
        data class Failure(val message: String) : ScriptOutcome()
        data object TimedOut : ScriptOutcome()
    }

    /** A resolve() result flattened off the JS heap: the stream URL, any request headers, any
     *  sidecar subtitle tracks, and the audio category hint ("sub"/"dub") if the script sent one. */
    private data class ResolvedStream(
        val url: String,
        val headers: Map<String, String>,
        val subtitles: List<PluginSubtitle>,
        val audio: String? = null
    )

    /**
     * Flattens a resolve()'s `subtitles` property. Like `headers`, the reliable wire form is a
     * JSON string (`subtitles: JSON.stringify([...])`) - a nested JS array survives `toMap` even
     * less predictably than a nested object does. Entries missing an http(s) `url` are dropped
     * rather than handed to the player as an unopenable MediaItem.
     */
    private fun parseSubtitles(raw: Any?): List<PluginSubtitle> {
        val json = raw as? String ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(json)
            (0 until minOf(arr.length(), MAX_SUBTITLES)).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url").takeIf {
                    it.startsWith("http://") || it.startsWith("https://")
                } ?: return@mapNotNull null
                PluginSubtitle(
                    url = url,
                    label = o.optString("label").takeIf { it.isNotBlank() }
                        ?.take(JsPluginContract.MAX_TEXT_LENGTH),
                    language = o.optString("language").takeIf { it.isNotBlank() }?.take(16),
                    isDefault = o.optBoolean("default")
                )
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val TAG = "PluginEngine"
        private const val MANIFEST_PROBE_TIMEOUT_MS = 5_000L
        /** Cap on sideloaded tracks, same idea as the report caps in [JsPluginContract]. */
        private const val MAX_SUBTITLES = 20
    }
}
