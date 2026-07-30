package com.lumora.plugin.js

import com.whl.quickjs.wrapper.JSArray
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.jsoup.Jsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The `host` object bound into every JS plugin script's globalThis. Replaces the Bundle
 * validation/capping [com.lumora.plugin.PluginClient]/[com.lumora.plugin.StreamSearchClient]
 * used to do on the Messenger boundary - a script's calls are untrusted input the same way a
 * plugin APK's Bundle fields were, so every reported candidate/result is validated and capped
 * here before it reaches the app.
 *
 * Every call here runs synchronously on whatever thread [install]'s [QuickJSContext] lives on
 * (QuickJS requires all calls against one context to happen on its creation thread - see
 * [JsPluginEngine]) - there is no async/await in the script-facing API, `host.httpGet(...)`
 * just blocks and returns.
 *
 * One instance is created per script invocation (discovery run / search / resolve).
 */
class JsHostImpl(
    private val client: OkHttpClient,
    private val query: String? = null,
    private val year: Int? = null,
    private val season: Int? = null,
    private val episode: Int? = null,
    private val token: String? = null,
    private val onProgress: (String) -> Unit = {},
    private val onCandidate: (Map<String, Any?>) -> Unit = {},
    private val onResult: (Map<String, Any?>) -> Unit = {},
    private val onLog: (String) -> Unit = {},
) {
    private var candidateCount = 0
    private var resultCount = 0

    /** Must be called on [context]'s owning thread. */
    fun install(context: QuickJSContext) {
        val host = context.createNewJSObject()
        query?.let { host.setProperty("query", it) }
        year?.let { host.setProperty("year", it) }
        season?.let { host.setProperty("season", it) }
        episode?.let { host.setProperty("episode", it) }
        token?.let { host.setProperty("token", it) }

        host.setProperty("httpGet", JSCallFunction { args -> httpGet(context, args) })
        host.setProperty("httpPost", JSCallFunction { args -> httpPost(context, args) })
        host.setProperty("reportProgress", JSCallFunction { args -> reportProgress(args); null })
        host.setProperty("reportCandidate", JSCallFunction { args -> reportCandidate(args); null })
        host.setProperty("reportResult", JSCallFunction { args -> reportResult(args); null })
        host.setProperty("log", JSCallFunction { args -> log(args); null })
        host.setProperty("aesCbcDecrypt", JSCallFunction { args -> aesCbcDecrypt(args) })
        host.setProperty("pbkdf2Sha512", JSCallFunction { args -> pbkdf2Sha512(args) })
        host.setProperty("md5", JSCallFunction { args -> md5(args) })
        host.setProperty("base64Decode", JSCallFunction { args -> base64Decode(args) })
        host.setProperty("base64Encode", JSCallFunction { args -> base64Encode(args) })
        host.setProperty("base64Slice", JSCallFunction { args -> base64Slice(args) })
        host.setProperty("base64Concat", JSCallFunction { args -> base64Concat(args) })
        host.setProperty("md5Bytes", JSCallFunction { args -> md5Bytes(args) })
        host.setProperty("hmacSha512", JSCallFunction { args -> hmacSha512(args) })

        host.setProperty("selectAll", JSCallFunction { args -> selectAll(context, args) })
        host.setProperty("selectFirst", JSCallFunction { args -> selectFirst(args) })
        host.setProperty("selectText", JSCallFunction { args -> selectText(args) })
        host.setProperty("selectAttr", JSCallFunction { args -> selectAttr(args) })
        host.setProperty("selectTextAt", JSCallFunction { args -> selectTextAt(args) })
        host.setProperty("selectAttrAt", JSCallFunction { args -> selectAttrAt(args) })
        host.setProperty("textOf", JSCallFunction { args -> textOf(args) })

        context.getGlobalObject().setProperty("host", host)
        host.release()
    }

    // ── HTTP ──

    /**
     * Never throws, all the way from building the request through executing it: a malformed URL
     * (`Request.Builder().url()` throwing `IllegalArgumentException`) or a network-level failure
     * (timeout, connection refused, DNS) both come back as `{status: 0, body: ""}` instead of a
     * Kotlin exception. A Kotlin exception thrown inside a JSCallFunction does not unwind through
     * a script's own `try/catch` - it propagates straight past every JS stack frame back into
     * whatever Kotlin code called [QuickJSContext.evaluate] (verified empirically; not documented
     * behavior of the underlying binding) - so every script here is written to check
     * `resp.status` rather than wrap `host.httpGet` in a try/catch it expects to actually catch
     * failures. status=0 sorts under every script's existing `status < 200` success check, so no
     * script-side handling was missed.
     */
    private fun httpGet(context: QuickJSContext, args: Array<out Any?>): JSObject = runCatching {
        val url = args[0] as String
        val headers = (args.getOrNull(1) as? JSObject)?.toMap().orEmpty()
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v.toString()) }
        }.get().build()
        execute(context, request)
    }.getOrElse { failedResponse(context, "GET", url = args.getOrNull(0) as? String, it) }

    private fun httpPost(context: QuickJSContext, args: Array<out Any?>): JSObject = runCatching {
        val url = args[0] as String
        val body = (args.getOrNull(1) as? String).orEmpty()
        val headers = (args.getOrNull(2) as? JSObject)?.toMap().orEmpty()
        val contentType = headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.toString()
            ?: "application/json; charset=utf-8"
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v.toString()) }
        }.post(body.toRequestBody(contentType.toMediaTypeOrNull())).build()
        execute(context, request)
    }.getOrElse { failedResponse(context, "POST", url = args.getOrNull(0) as? String, it) }

    /** The returned [JSObject] is handed back to JS by the caller - do not release it here. */
    private fun execute(context: QuickJSContext, request: Request): JSObject {
        client.newCall(request).execute().use { response ->
            PluginLog.d(TAG, "${request.method} ${request.url} -> ${response.code} (${response.body?.contentLength() ?: -1} bytes)")
            val obj = context.createNewJSObject()
            obj.setProperty("status", response.code)
            obj.setProperty("body", response.body?.string().orEmpty())
            return obj
        }
    }

    private fun failedResponse(context: QuickJSContext, method: String, url: String?, error: Throwable): JSObject {
        PluginLog.w(TAG, "$method $url failed: ${error.message}")
        val obj = context.createNewJSObject()
        obj.setProperty("status", 0)
        obj.setProperty("body", "")
        return obj
    }

    // ── Reporting (validated/capped the same way the old Messenger candidates were) ──

    private fun reportProgress(args: Array<out Any?>) {
        onProgress(cap(args.getOrNull(0) as? String).orEmpty())
    }

    private fun reportCandidate(args: Array<out Any?>) {
        if (candidateCount >= JsPluginContract.MAX_CANDIDATES) return
        val obj = (args.getOrNull(0) as? JSObject)?.toMap() ?: return
        val type = (obj[JsPluginContract.KEY_TYPE] as? String)?.lowercase() ?: return
        if (type !in JsPluginContract.SUPPORTED_PROVIDER_TYPES) return
        val url = obj[JsPluginContract.KEY_URL] as? String ?: return
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        candidateCount++
        onCandidate(
            mapOf(
                JsPluginContract.KEY_TYPE to type,
                JsPluginContract.KEY_LABEL to cap(obj[JsPluginContract.KEY_LABEL] as? String),
                JsPluginContract.KEY_URL to url,
                JsPluginContract.KEY_USERNAME to cap(obj[JsPluginContract.KEY_USERNAME] as? String),
                JsPluginContract.KEY_PASSWORD to cap(obj[JsPluginContract.KEY_PASSWORD] as? String),
                JsPluginContract.KEY_USER_AGENT to cap(obj[JsPluginContract.KEY_USER_AGENT] as? String),
                JsPluginContract.KEY_DETAIL to cap(obj[JsPluginContract.KEY_DETAIL] as? String),
                JsPluginContract.KEY_VERIFIED to (obj[JsPluginContract.KEY_VERIFIED] as? Boolean ?: false),
            )
        )
    }

    private fun reportResult(args: Array<out Any?>) {
        if (resultCount >= JsPluginContract.MAX_RESULTS) return
        val obj = (args.getOrNull(0) as? JSObject)?.toMap() ?: return
        val token = obj[JsPluginContract.KEY_TOKEN] as? String ?: return
        resultCount++
        onResult(
            mapOf(
                JsPluginContract.KEY_TITLE to cap(obj[JsPluginContract.KEY_TITLE] as? String),
                JsPluginContract.KEY_TOKEN to token,
                JsPluginContract.KEY_SEEDERS to (obj[JsPluginContract.KEY_SEEDERS] as? Number)?.toInt(),
                JsPluginContract.KEY_SIZE to cap(obj[JsPluginContract.KEY_SIZE] as? String),
                JsPluginContract.KEY_QUALITY to cap(obj[JsPluginContract.KEY_QUALITY] as? String),
                JsPluginContract.KEY_SOURCE to cap(obj[JsPluginContract.KEY_SOURCE] as? String),
            )
        )
    }

    private fun log(args: Array<out Any?>) {
        onLog(cap(args.getOrNull(0) as? String).orEmpty())
    }

    private fun cap(s: String?): String? = s?.take(JsPluginContract.MAX_TEXT_LENGTH)

    // ── Crypto (relocated from the old redditscan plugin's PasteShDecryptor - a script never
    //    implements crypto itself, it only orchestrates calls into these primitives). ──

    // The crypto/base64 primitives all return null on any failure rather than throwing. A raw
    // Kotlin exception thrown out of a host function (e.g. javax.crypto's BadPaddingException on a
    // wrong AES key/padding, or an IllegalArgumentException on malformed base64) does NOT surface
    // as a JS-catchable error through the QuickJS bridge - it aborts the whole script run with the
    // native error message (verified: a single un-decryptable paste.sh paste killed an entire
    // reddit scan with "OPENSSL_internal:BAD_DECRYPT"). Returning null instead lets the script's
    // own try/catch and null checks handle a failed operation, the same contract httpGet uses.

    private fun aesCbcDecrypt(args: Array<out Any?>): String? = runCatching {
        val cipherBytes = Base64.getDecoder().decode(args[0] as String)
        val keyBytes = Base64.getDecoder().decode(args[1] as String)
        val ivBytes = Base64.getDecoder().decode(args[2] as String)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }.getOrNull()

    /** Returns the derived key, base64-encoded. */
    private fun pbkdf2Sha512(args: Array<out Any?>): String? = runCatching {
        val password = args[0] as String
        val salt = Base64.getDecoder().decode(args[1] as String)
        val iterations = (args[2] as Number).toInt()
        val keyLenBytes = (args[3] as Number).toInt()
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyLenBytes * 8)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec)
        Base64.getEncoder().encodeToString(key.encoded)
    }.getOrNull()

    /** Hex-encoded MD5 of a UTF-8 string - used by the EVP_BytesToKey fallback path. */
    private fun md5(args: Array<out Any?>): String? = runCatching {
        val digest = MessageDigest.getInstance("MD5").digest((args[0] as String).toByteArray(Charsets.UTF_8))
        digest.joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun base64Decode(args: Array<out Any?>): String? = runCatching {
        String(Base64.getDecoder().decode(args[0] as String), Charsets.UTF_8)
    }.getOrNull()

    private fun base64Encode(args: Array<out Any?>): String? = runCatching {
        Base64.getEncoder().encodeToString((args[0] as String).toByteArray(Charsets.UTF_8))
    }.getOrNull()

    // ── Binary-safe primitives (base64 in/out) for scripts that need to manipulate raw bytes -
    //    e.g. redditscan.js's paste.sh key derivation - without ever round-tripping binary data
    //    through a JS string (which is lossy: JS strings are UTF-16, not byte arrays). ──

    /** [start, end) byte slice of base64-decoded [args][0], re-encoded. `end` omitted/null = to the end. */
    private fun base64Slice(args: Array<out Any?>): String? = runCatching {
        val bytes = Base64.getDecoder().decode(args[0] as String)
        val start = (args[1] as Number).toInt()
        val end = (args.getOrNull(2) as? Number)?.toInt() ?: bytes.size
        Base64.getEncoder().encodeToString(bytes.copyOfRange(start, end))
    }.getOrNull()

    private fun base64Concat(args: Array<out Any?>): String? = runCatching {
        val a = Base64.getDecoder().decode(args[0] as String)
        val b = Base64.getDecoder().decode(args[1] as String)
        Base64.getEncoder().encodeToString(a + b)
    }.getOrNull()

    /** Raw MD5 digest bytes (base64), unlike [md5] which hex-encodes a digest of UTF-8 text. */
    private fun md5Bytes(args: Array<out Any?>): String? = runCatching {
        val bytes = Base64.getDecoder().decode(args[0] as String)
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes))
    }.getOrNull()

    private fun hmacSha512(args: Array<out Any?>): String? = runCatching {
        val key = Base64.getDecoder().decode(args[0] as String)
        val message = Base64.getDecoder().decode(args[1] as String)
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        Base64.getEncoder().encodeToString(mac.doFinal(message))
    }.getOrNull()

    // ── HTML parsing (Jsoup) for scraper scripts like torrent-search.js. A script re-parses a
    //    row's own outerHTML as its own mini-document for sub-selection rather than round-
    //    tripping opaque DOM element handles across the JS/Kotlin boundary. ──

    /**
     * Jsoup's HTML5 parser applies table foster-parenting rules: a `<tr>`/`<td>`/`<th>` fragment
     * with no enclosing `<table>` gets its cells silently dropped or relocated instead of parsed
     * as-is (verified empirically - this is what broke the first version of these primitives).
     * A row/cell extracted via [selectAll] always starts with one of those tags, so give it back
     * its table context before parsing; a real top-level document (which always starts with
     * something like `<!doctype`/`<html`, never a bare `<tr>`) parses unwrapped as normal.
     */
    private fun parseFragment(html: String) = if (Regex("^\\s*<(tr|td|th)\\b", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
        Jsoup.parse("<table><tbody><tr>$html</tr></tbody></table>")
    } else {
        Jsoup.parse(html)
    }

    /** Outer HTML of every element matching [selector], as a JS array of strings. */
    private fun selectAll(context: QuickJSContext, args: Array<out Any?>): JSArray {
        val html = args[0] as String
        val selector = args[1] as String
        val elements = parseFragment(html).select(selector)
        val array = context.createNewJSArray()
        elements.forEachIndexed { i, el -> array.set(el.outerHtml(), i) }
        return array
    }

    private fun selectFirst(args: Array<out Any?>): String? {
        val html = args[0] as String
        val selector = args[1] as String
        return parseFragment(html).select(selector).firstOrNull()?.outerHtml()
    }

    private fun selectText(args: Array<out Any?>): String? {
        val html = args[0] as String
        val selector = args[1] as String
        return parseFragment(html).select(selector).firstOrNull()?.text()?.trim()
    }

    private fun selectAttr(args: Array<out Any?>): String? {
        val html = args[0] as String
        val selector = args[1] as String
        val attr = args[2] as String
        return parseFragment(html).select(selector).firstOrNull()?.attr(attr)?.takeIf { it.isNotBlank() }
    }

    private fun selectTextAt(args: Array<out Any?>): String? {
        val html = args[0] as String
        val selector = args[1] as String
        val index = (args[2] as Number).toInt()
        return parseFragment(html).select(selector).getOrNull(index)?.text()?.trim()
    }

    private fun selectAttrAt(args: Array<out Any?>): String? {
        val html = args[0] as String
        val selector = args[1] as String
        val index = (args[2] as Number).toInt()
        val attr = args[3] as String
        return parseFragment(html).select(selector).getOrNull(index)?.attr(attr)?.takeIf { it.isNotBlank() }
    }

    /** Text content of a whole HTML fragment (e.g. a single `<td>...</td>` pulled out via selectAll). */
    private fun textOf(args: Array<out Any?>): String = Jsoup.parse(args[0] as String).text().trim()

    companion object {
        private const val TAG = "PluginEngine"
    }
}
