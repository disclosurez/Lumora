package com.lumora.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Drives a stream-search plugin across a single, long-lived binding: search, then resolve the
 * chosen result to a playable URL, then stop. Unlike [PluginClient] (one fire-and-forget
 * discovery run) the connection is held open the whole time, because the URL a resolve returns
 * is served *by the plugin's own process* and dies with the binding.
 *
 * Lifecycle: [connect] once, then [search] and [resolve] as many times as needed, then [close]
 * (which also tells the plugin to tear down any active stream). Everything the plugin sends is
 * treated as untrusted - stale request ids ignored, text capped, resolved URL checked for
 * http/https before it's handed back for playback.
 *
 * Not thread-safe for concurrent operations: one search-or-resolve in flight at a time, which
 * is exactly how the UI uses it. All callbacks land on the main thread.
 */
class StreamSearchClient(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var connection: ServiceConnection? = null
    private var outgoing: Messenger? = null
    private val requestIds = AtomicInteger(0)

    // The one operation currently awaiting a reply. Only messages carrying this id are acted on.
    private var activeRequestId: String = ""
    private var onProgress: ((String) -> Unit)? = null
    private var onResult: ((TorrentResult) -> Unit)? = null
    private var searchCont: ((SearchResult) -> Unit)? = null
    private var resolveCont: ((ResolveResult) -> Unit)? = null
    private val timeout = Runnable { failActive("The plugin stopped responding") }

    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val data = msg.data ?: Bundle.EMPTY
            if (data.getString(PluginContract.KEY_REQUEST_ID) != activeRequestId) return
            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, PluginContract.RESOLVE_TIMEOUT_MS)
            when (msg.what) {
                PluginContract.MSG_PROGRESS -> data.trimmed(PluginContract.KEY_MESSAGE)?.let { onProgress?.invoke(it) }
                PluginContract.MSG_RESULT -> parseResult(data)?.let { onResult?.invoke(it) }
                PluginContract.MSG_FINISHED ->
                    finishSearch(SearchResult.Finished(data.trimmed(PluginContract.KEY_MESSAGE)))
                PluginContract.MSG_STREAM_READY -> {
                    val url = data.trimmed(PluginContract.KEY_STREAM_URL)
                    val scheme = url?.substringBefore("://", "")?.lowercase()
                    if (url != null && (scheme == "http" || scheme == "https")) {
                        finishResolve(ResolveResult.Ready(url))
                    } else {
                        finishResolve(ResolveResult.Failed("Plugin returned an unplayable link"))
                    }
                }
                PluginContract.MSG_ERROR -> {
                    val message = data.trimmed(PluginContract.KEY_MESSAGE) ?: "The plugin reported an error"
                    // Whichever phase is live gets the failure.
                    if (searchCont != null) finishSearch(SearchResult.Failed(message))
                    else finishResolve(ResolveResult.Failed(message))
                }
            }
        }
    })

    /** Binds the plugin. Returns false if it couldn't be reached. */
    suspend fun connect(plugin: InstalledPlugin): Boolean = withTimeout(30_000L) {
        suspendCancellableCoroutine { cont ->
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    context.unbindService(this)
                    if (cont.isActive) cont.resume(false)
                    return
                }
                outgoing = Messenger(service)
                if (cont.isActive) cont.resume(true)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                outgoing = null
                failActive("The plugin stopped unexpectedly")
            }
            override fun onBindingDied(name: ComponentName?) { failActive("The plugin stopped unexpectedly") }
            override fun onNullBinding(name: ComponentName?) {
                if (cont.isActive) cont.resume(false)
            }
        }
        connection = serviceConnection
        val intent = Intent(PluginContract.ACTION_PLUGIN_SERVICE).setComponent(plugin.component)
        val bound = try {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) { false }
        if (!bound) {
            connection = null
            if (cont.isActive) cont.resume(false)
        }
    }
    }

    suspend fun search(
        query: String,
        year: Int?,
        season: Int?,
        episode: Int?,
        onProgress: (String) -> Unit,
        onResult: (TorrentResult) -> Unit
    ): SearchResult = suspendCancellableCoroutine { cont ->
        if (isOperationActive()) { cont.resume(SearchResult.Failed("Another operation is already in progress")); return@suspendCancellableCoroutine }
        val messenger = outgoing ?: run { cont.resume(SearchResult.Failed("Plugin not connected")); return@suspendCancellableCoroutine }
        val id = beginOp()
        this.onProgress = onProgress
        this.onResult = onResult
        this.searchCont = { cont.resume(it) }
        val msg = Message.obtain(null, PluginContract.MSG_START_SEARCH).apply {
            data = Bundle().apply {
                putString(PluginContract.KEY_REQUEST_ID, id)
                putString(PluginContract.KEY_QUERY, query)
                year?.let { putInt(PluginContract.KEY_YEAR, it) }
                season?.let { putInt(PluginContract.KEY_SEASON, it) }
                episode?.let { putInt(PluginContract.KEY_EPISODE, it) }
            }
            replyTo = incoming
        }
        if (!trySend(messenger, msg)) finishSearch(SearchResult.Failed("The plugin isn't responding"))
        cont.invokeOnCancellation { handler.post { clearActive() } }
    }

    suspend fun resolve(
        token: String,
        season: Int?,
        episode: Int?,
        onProgress: (String) -> Unit
    ): ResolveResult = suspendCancellableCoroutine { cont ->
        if (isOperationActive()) { cont.resume(ResolveResult.Failed("Another operation is already in progress")); return@suspendCancellableCoroutine }
        val messenger = outgoing ?: run { cont.resume(ResolveResult.Failed("Plugin not connected")); return@suspendCancellableCoroutine }
        val id = beginOp()
        this.onProgress = onProgress
        this.resolveCont = { cont.resume(it) }
        val msg = Message.obtain(null, PluginContract.MSG_RESOLVE_STREAM).apply {
            data = Bundle().apply {
                putString(PluginContract.KEY_REQUEST_ID, id)
                putString(PluginContract.KEY_MAGNET, token)
                season?.let { putInt(PluginContract.KEY_SEASON, it) }
                episode?.let { putInt(PluginContract.KEY_EPISODE, it) }
            }
            replyTo = incoming
        }
        if (!trySend(messenger, msg)) finishResolve(ResolveResult.Failed("The plugin isn't responding"))
        cont.invokeOnCancellation { handler.post { clearActive() } }
    }

    /** Tells the plugin to stop any active stream and unbinds. Safe to call more than once. */
    fun close() {
        outgoing?.let { messenger ->
            val msg = Message.obtain(null, PluginContract.MSG_STOP_STREAM).apply {
                data = Bundle().apply { putString(PluginContract.KEY_REQUEST_ID, activeRequestId) }
            }
            runCatching { messenger.send(msg) }
        }
        handler.removeCallbacks(timeout)
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        outgoing = null
        clearActive()
    }

    private fun beginOp(): String {
        if (isOperationActive()) {
            throw IllegalStateException("Another operation is already in progress")
        }
        val id = "op-${requestIds.incrementAndGet()}"
        activeRequestId = id
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, PluginContract.SEARCH_TIMEOUT_MS)
        return id
    }

    private fun isOperationActive(): Boolean = activeRequestId.isNotBlank()

    private fun finishSearch(result: SearchResult) {
        val cont = searchCont ?: return
        clearActive()
        cont(result)
    }

    private fun finishResolve(result: ResolveResult) {
        val cont = resolveCont ?: return
        clearActive()
        cont(result)
    }

    private fun failActive(message: String) {
        when {
            searchCont != null -> finishSearch(SearchResult.Failed(message))
            resolveCont != null -> finishResolve(ResolveResult.Failed(message))
        }
    }

    private fun clearActive() {
        handler.removeCallbacks(timeout)
        activeRequestId = ""
        onProgress = null
        onResult = null
        searchCont = null
        resolveCont = null
    }

    private fun trySend(messenger: Messenger, msg: Message): Boolean =
        try { messenger.send(msg); true } catch (e: RemoteException) { false }

    private fun parseResult(data: Bundle): TorrentResult? {
        val name = data.trimmed(PluginContract.KEY_LABEL) ?: return null
        val token = runCatching { data.getString(PluginContract.KEY_MAGNET) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return TorrentResult(
            title = name,
            token = token,
            seeders = runCatching { data.getInt(PluginContract.KEY_SEEDERS, -1) }.getOrDefault(-1).takeIf { it >= 0 },
            size = data.trimmed(PluginContract.KEY_SIZE),
            quality = data.trimmed(PluginContract.KEY_QUALITY),
            source = data.trimmed(PluginContract.KEY_SOURCE)
        )
    }

    private fun Bundle.trimmed(key: String): String? =
        runCatching { getString(key) }.getOrNull()
            ?.trim()
            ?.take(PluginContract.MAX_TEXT_LENGTH)
            ?.takeIf { it.isNotBlank() }
}
