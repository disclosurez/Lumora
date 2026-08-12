package com.lumora.scraper.utils

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response as OkHttpResponse
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Drop-in for the Cronet-backed client one provider was written against, reimplemented on
 * OkHttp.
 *
 * Upstream used `org.chromium.net:cronet-embedded` here specifically for its TLS and HTTP/2
 * fingerprint: Cronet negotiates exactly as desktop Chrome does, which gets past some bot
 * checks that a JVM TLS stack does not. That is a real advantage, and it costs roughly 20MB of
 * bundled Chromium per build - on the same order as the FFmpeg decoders this project already
 * declined for the same reason (see the playback section of CLAUDE.md). One provider does not
 * justify it.
 *
 * So this keeps the call shape and gives up the fingerprint. When the site does challenge us,
 * the provider's existing fallback is the right answer anyway: it catches its own
 * `ChallengeRequiredException` and re-runs the request through [WebViewResolver], which is a
 * real Chromium instance and therefore has the fingerprint this class no longer does.
 *
 * The one Cronet behaviour worth keeping verbatim is writing every `Set-Cookie` into the WebView
 * [CookieManager] - that shared cookie store is how a `cf_clearance` earned in the bypass
 * WebView becomes usable by plain HTTP requests, and vice versa (see [NetworkClient.cookieJar]).
 */
object AnimeOnlineNinjaCronetClient {

    private const val CACHE_SIZE_BYTES = 20L * 1024L * 1024L

    data class Response(
        val statusCode: Int,
        val finalUrl: String,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
    ) {
        val isSuccessful: Boolean get() = statusCode in 200..299

        fun bodyAsString(): String = body.toString(Charsets.UTF_8)

        // Data class equality over a ByteArray compares references, which is never what a caller
        // means. Overridden so two identical responses compare equal.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Response) return false
            return statusCode == other.statusCode &&
                    finalUrl == other.finalUrl &&
                    headers == other.headers &&
                    body.contentEquals(other.body)
        }

        override fun hashCode(): Int {
            var result = statusCode
            result = 31 * result + finalUrl.hashCode()
            result = 31 * result + headers.hashCode()
            result = 31 * result + body.contentHashCode()
            return result
        }
    }

    fun interface Callback {
        fun onComplete(result: Result<Response>)
    }

    class Call internal constructor() {
        @Volatile
        private var call: okhttp3.Call? = null
        private val cancelled = AtomicBoolean(false)

        internal fun attach(call: okhttp3.Call) {
            this.call = call
            if (cancelled.get()) call.cancel()
        }

        fun cancel() {
            cancelled.set(true)
            call?.cancel()
        }

        internal fun isCancelled(): Boolean = cancelled.get()
    }

    @Volatile
    private var client: OkHttpClient? = null

    /** Warms the client so the first real request does not pay for building it. */
    fun init(context: Context) {
        client(context)
    }

    suspend fun get(
        context: Context,
        url: String,
        headers: Map<String, String>,
        useCache: Boolean = true,
    ): Response = suspendCancellableCoroutine { continuation ->
        val call = get(context, url, headers, useCache) { result ->
            if (!continuation.isActive) return@get
            result.fold(continuation::resume, continuation::resumeWithException)
        }
        continuation.invokeOnCancellation { call.cancel() }
    }

    fun get(
        context: Context,
        url: String,
        headers: Map<String, String>,
        useCache: Boolean = true,
        callback: Callback,
    ): Call {
        val handle = Call()
        val completed = AtomicBoolean(false)

        fun complete(result: Result<Response>) {
            if (!handle.isCancelled() && completed.compareAndSet(false, true)) {
                callback.onComplete(result)
            }
        }

        val request = Request.Builder()
            .url(url)
            .get()
            .apply {
                headers.forEach { (name, value) -> if (value.isNotBlank()) header(name, value) }
                if (!useCache) cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
            }
            .build()

        val call = client(context).newCall(request)
        handle.attach(call)
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                complete(Result.failure(e))
            }

            override fun onResponse(call: okhttp3.Call, response: OkHttpResponse) {
                complete(runCatching { response.use { it.toResponse() } })
            }
        })
        return handle
    }

    private fun OkHttpResponse.toResponse(): Response {
        persistCookies(this)
        return Response(
            statusCode = code,
            // request.url, not the originally requested one - redirects are followed and callers
            // resolve relative links against whatever we actually landed on.
            finalUrl = request.url.toString(),
            headers = headers.toMultimap(),
            body = body?.bytes() ?: ByteArray(0),
        )
    }

    private fun client(context: Context): OkHttpClient {
        client?.let { return it }
        return synchronized(this) {
            client ?: NetworkClient.default.newBuilder()
                .cache(
                    Cache(
                        File(context.applicationContext.cacheDir, "scraper-anime-http").apply { mkdirs() },
                        CACHE_SIZE_BYTES,
                    )
                )
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
                .also { client = it }
        }
    }

    private fun persistCookies(response: OkHttpResponse) {
        val setCookies = response.headers.values("Set-Cookie")
        if (setCookies.isEmpty()) return
        val url = response.request.url.toString()
        CookieManager.getInstance().apply {
            setCookies.forEach { cookie -> setCookie(url, cookie) }
            flush()
        }
    }
}
