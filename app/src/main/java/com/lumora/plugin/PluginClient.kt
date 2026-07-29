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
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Runs one discovery job against one plugin: binds its service, streams progress and
 * candidates back to the caller, and guarantees the binding is torn down exactly once
 * however the job ends (plugin finished, plugin errored, plugin died, host cancelled,
 * or nothing happened for [PluginContract.DISCOVERY_TIMEOUT_MS]).
 *
 * Everything the plugin sends is treated as untrusted input from another app: messages for a
 * stale request id are ignored, text is length-capped, and a candidate with a type or URL the
 * host can't build a provider from is dropped rather than shown.
 *
 * One instance runs one job. Callbacks land on the main thread.
 */
class PluginClient(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var connection: ServiceConnection? = null
    private var outgoing: Messenger? = null
    private var incoming: Messenger? = null
    private var requestId: String = ""
    private var candidateCount = 0
    private val finished = AtomicBoolean(false)

    /**
     * Binds [plugin] and runs a discovery job to completion.
     *
     * [onProgress] gets the plugin's status lines and [onCandidate] each proposed provider,
     * both on the main thread as they arrive - a discovery run is slow and the UI is expected
     * to fill in progressively rather than wait for the terminal result.
     *
     * Cancelling the calling coroutine cancels the job: the plugin is told to stop and the
     * service is unbound.
     */
    suspend fun runDiscovery(
        plugin: InstalledPlugin,
        onProgress: (String) -> Unit,
        onCandidate: (DiscoveredProvider) -> Unit
    ): DiscoveryResult = suspendCancellableCoroutine { continuation ->
        requestId = UUID.randomUUID().toString()
        candidateCount = 0
        finished.set(false)

        fun complete(result: DiscoveryResult) {
            // Every path out of this function funnels through here, and only the first one
            // wins - a plugin that sends MSG_ERROR and then dies must not resume twice.
            if (!finished.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            teardown()
            if (continuation.isActive) continuation.resume(result)
        }

        val timeout = Runnable {
            complete(DiscoveryResult.Failed("${plugin.label} stopped responding"))
        }

        incoming = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                val data = msg.data ?: Bundle.EMPTY
                // A late reply from a previous job (or a plugin talking to the wrong host)
                // must not be mistaken for this run's output.
                if (data.getString(PluginContract.KEY_REQUEST_ID) != requestId) return
                // Any traffic means the plugin is alive; the timeout is for silence, not for
                // total runtime - a real scan legitimately takes minutes.
                handler.removeCallbacks(timeout)
                handler.postDelayed(timeout, PluginContract.DISCOVERY_TIMEOUT_MS)
                when (msg.what) {
                    PluginContract.MSG_PROGRESS ->
                        data.trimmed(PluginContract.KEY_MESSAGE)?.let(onProgress)
                    PluginContract.MSG_CANDIDATE -> {
                        if (candidateCount >= PluginContract.MAX_CANDIDATES) return
                        sanitizeCandidate(data)?.let {
                            candidateCount++
                            onCandidate(it)
                        }
                    }
                    PluginContract.MSG_FINISHED ->
                        complete(DiscoveryResult.Finished(data.trimmed(PluginContract.KEY_MESSAGE)))
                    PluginContract.MSG_ERROR ->
                        complete(
                            DiscoveryResult.Failed(
                                data.trimmed(PluginContract.KEY_MESSAGE) ?: "${plugin.label} reported an error"
                            )
                        )
                }
            }
        })

        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    complete(DiscoveryResult.Failed("Couldn't connect to ${plugin.label}"))
                    return
                }
                outgoing = Messenger(service)
                val start = Message.obtain(null, PluginContract.MSG_START_DISCOVERY).apply {
                    data = Bundle().apply { putString(PluginContract.KEY_REQUEST_ID, requestId) }
                    replyTo = incoming
                }
                try {
                    outgoing?.send(start)
                } catch (e: RemoteException) {
                    complete(DiscoveryResult.Failed("${plugin.label} isn't responding"))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // The plugin process died mid-job. Without this the run would just hang until
                // the silence timeout, with no explanation.
                outgoing = null
                complete(DiscoveryResult.Failed("${plugin.label} stopped unexpectedly"))
            }

            override fun onBindingDied(name: ComponentName?) {
                complete(DiscoveryResult.Failed("${plugin.label} stopped unexpectedly"))
            }

            override fun onNullBinding(name: ComponentName?) {
                complete(DiscoveryResult.Failed("${plugin.label} doesn't support discovery"))
            }
        }
        connection = serviceConnection

        // Explicit component, not just the action: an implicit service bind is illegal on
        // Android 5+, and naming the component also means a second app can't answer for this
        // plugin's action.
        val intent = Intent(PluginContract.ACTION_PLUGIN_SERVICE).setComponent(plugin.component)
        val bound = try {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            false
        }
        if (!bound) {
            connection = null
            complete(DiscoveryResult.Failed("Couldn't start ${plugin.label}"))
            return@suspendCancellableCoroutine
        }
        handler.postDelayed(timeout, PluginContract.DISCOVERY_TIMEOUT_MS)

        continuation.invokeOnCancellation {
            handler.post {
                sendCancel()
                if (finished.compareAndSet(false, true)) {
                    handler.removeCallbacksAndMessages(null)
                    teardown()
                }
            }
        }
    }

    private fun sendCancel() {
        val messenger = outgoing ?: return
        val cancel = Message.obtain(null, PluginContract.MSG_CANCEL).apply {
            data = Bundle().apply { putString(PluginContract.KEY_REQUEST_ID, requestId) }
        }
        runCatching { messenger.send(cancel) }
    }

    private fun teardown() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        outgoing = null
        incoming = null
    }

    /**
     * Turns a candidate bundle into a [DiscoveredProvider], or null if the host couldn't build
     * a working provider config out of it anyway. Rejecting here rather than at "Add" time
     * keeps unusable proposals off the screen entirely.
     */
    private fun sanitizeCandidate(data: Bundle): DiscoveredProvider? {
        val type = data.trimmed(PluginContract.KEY_TYPE)?.lowercase() ?: return null
        if (type !in PluginContract.SUPPORTED_PROVIDER_TYPES) return null
        val url = data.trimmed(PluginContract.KEY_URL) ?: return null
        // http/https only - a plugin must not be able to point the app at file:// or an
        // intent: URL, and every provider backend speaks HTTP anyway.
        val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        return DiscoveredProvider(
            type = type,
            label = data.trimmed(PluginContract.KEY_LABEL) ?: url.substringAfter("://").substringBefore('/'),
            url = url,
            username = data.trimmed(PluginContract.KEY_USERNAME),
            password = data.trimmed(PluginContract.KEY_PASSWORD),
            userAgent = data.trimmed(PluginContract.KEY_USER_AGENT),
            detail = data.trimmed(PluginContract.KEY_DETAIL),
            verified = data.getBoolean(PluginContract.KEY_VERIFIED, false)
        )
    }

    /** Non-blank, whitespace-trimmed, length-capped - or null. */
    private fun Bundle.trimmed(key: String): String? =
        runCatching { getString(key) }.getOrNull()
            ?.trim()
            ?.take(PluginContract.MAX_TEXT_LENGTH)
            ?.takeIf { it.isNotBlank() }

    companion object {
        private const val TAG = "PluginClient"
    }
}
