package com.lumora.plugin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.EnumMap
import java.util.Locale

private const val TAG = "PluginInstall"
private const val SESSION_TIMEOUT_MS = 5 * 60 * 1000L
private const val QR_SIZE = 512
private const val MAX_BODY = 8 * 1024

/**
 * Small localhost HTTP server that lets a phone hand a plugin APK URL to the TV, so the user
 * doesn't have to type a long link with a D-pad. Deliberately generic: the page just asks for an
 * APK link - it knows nothing about any specific plugin.
 *
 * Flow (mirrors [com.lumora.pairing.QrPairingManager]):
 *   TV opens a ServerSocket → shows a QR for http://IP:PORT/install?t=TOKEN
 *   Phone scans it → opens the page → pastes an APK URL → POSTs to /submit
 *   TV validates the token, hands the URL to [onApkUrl], which downloads and installs it.
 *
 * The submitted URL is untrusted: only http/https is accepted here, and the actual install still
 * goes through the system package installer, which prompts the user before anything is installed.
 */
class PluginInstallServer(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var timeoutJob: Job? = null
    private var activeToken: String? = null
    private var activeExpiresAt: Long = 0L

    /** Called on a background thread with a validated http/https URL the phone submitted. */
    var onApkUrl: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var currentSession: Session? = null

    data class Session(val url: String, val qrBitmap: Bitmap, val expiresAtMs: Long)

    suspend fun start(): Session? = withContext(Dispatchers.IO) {
        stop()
        val host = resolveLanIp() ?: run {
            onError?.invoke("Could not detect LAN IP. Connect to Wi-Fi and try again.")
            stop()
            return@withContext null
        }
        val socket = try { ServerSocket(0, 10) } catch (e: Exception) {
            onError?.invoke("Server error: ${e.message}")
            stop()
            return@withContext null
        }
        val token = generateToken()
        val port = socket.localPort
        val url = "http://$host:$port/install?t=$token"
        val expiresAt = System.currentTimeMillis() + SESSION_TIMEOUT_MS

        serverSocket = socket
        activeToken = token
        activeExpiresAt = expiresAt
        acceptJob = scope.launch { acceptLoop(socket) }
        timeoutJob = scope.launch {
            delay(SESSION_TIMEOUT_MS)
            if (serverSocket != null) { stop(); onError?.invoke("Session expired. Try again.") }
        }
        Session(url, createQrBitmap(url), expiresAt).also { currentSession = it }
    }

    fun stop() {
        acceptJob?.cancel(); timeoutJob?.cancel()
        acceptJob = null; timeoutJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        activeToken = null
        recycleQrBitmap()
    }

    fun recycleQrBitmap() {
        currentSession?.qrBitmap?.recycle()
        currentSession = null
    }

    // ── HTTP ──

    private suspend fun acceptLoop(socket: ServerSocket) {
        try {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: Exception) { break }
                client.soTimeout = 30_000 // 30 second read timeout
                scope.launch {
                    try { handleClient(client) } catch (e: Exception) {
                        Log.w(TAG, "Client error: ${e.message}")
                        runCatching { client.close() }
                    }
                }
            }
        } catch (e: Exception) {
            if (!socket.isClosed) Log.w(TAG, "Accept loop error: ${e.message}")
        }
    }

    private fun handleClient(client: java.net.Socket) {
        client.use { c ->
            val reader = BufferedReader(InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase(Locale.US)
            val pathAndQuery = parts[1]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val key = line.substringBefore(':').trim().lowercase(Locale.US)
                if (key.isNotBlank()) headers[key] = line.substringAfter(':').trim()
            }

            when {
                method == "GET" && pathAndQuery.startsWith("/install") -> {
                    val token = getQueryParam(pathAndQuery, "t")
                    if (!isTokenValid(token)) {
                        writeHtml(c.getOutputStream(), 403, errorPage("Link expired. Start a new session on the TV."))
                    } else {
                        writeHtml(c.getOutputStream(), 200, formPage(token ?: ""))
                    }
                }
                method == "POST" && pathAndQuery.startsWith("/submit") -> {
                    val length = headers["content-length"]?.toIntOrNull()?.coerceAtMost(MAX_BODY) ?: 0
                    val body = CharArray(length)
                    var read = 0
                    while (read < length) { val n = reader.read(body, read, length - read); if (n <= 0) break; read += n }
                    val form = parseForm(String(body, 0, read))
                    if (!isTokenValid(form["token"])) {
                        writeHtml(c.getOutputStream(), 403, errorPage("Session expired. Start a new session."))
                        return
                    }
                    val url = form["url"]?.trim().orEmpty()
                    val scheme = url.substringBefore("://", "").lowercase(Locale.US)
                    if (url.isBlank() || (scheme != "http" && scheme != "https")) {
                        writeHtml(c.getOutputStream(), 200, errorPage("Enter a valid http(s) APK link."))
                        return
                    }
                    onApkUrl?.invoke(url)
                    writeHtml(c.getOutputStream(), 200, successPage())
                }
                else -> writeHtml(c.getOutputStream(), 404, "Not found")
            }
        }
    }

    private fun isTokenValid(token: String?): Boolean =
        token != null && token == activeToken && System.currentTimeMillis() < activeExpiresAt

    private fun generateToken(): String {
        val bytes = ByteArray(16); random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ── QR ──

    private fun createQrBitmap(value: String): Bitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.MARGIN, 2)
            put(EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M)
        }
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints)
        val pixels = IntArray(QR_SIZE * QR_SIZE)
        for (y in 0 until QR_SIZE) for (x in 0 until QR_SIZE) {
            pixels[y * QR_SIZE + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(QR_SIZE, QR_SIZE, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, QR_SIZE, 0, 0, QR_SIZE, QR_SIZE)
        }
    }

    // ── IP ──

    private fun resolveLanIp(): String? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi != null) {
            try {
                @Suppress("DEPRECATION")
                val ipInt = wifi.connectionInfo.ipAddress
                if (ipInt != 0) {
                    @Suppress("DEPRECATION")
                    return Formatter.formatIpAddress(ipInt)
                }
            } catch (e: Exception) { Log.w(TAG, "WiFi IP failed: ${e.message}") }
        }
        return try {
            NetworkInterface.getNetworkInterfaces().toList().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .firstOrNull { !it.startsWith("127.") }
        } catch (e: Exception) { null }
    }

    // ── Helpers ──

    private fun getQueryParam(pathAndQuery: String, key: String): String? {
        val query = pathAndQuery.substringAfter('?', "").takeIf { it.isNotBlank() } ?: return null
        return parseForm(query)[key]
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            decode(part.substringBefore('=', "")) to decode(part.substringAfter('=', ""))
        }.toMap()

    private fun decode(value: String): String =
        try { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) } catch (e: Exception) { value }

    private fun writeHtml(output: OutputStream, status: Int, html: String) {
        val statusText = when (status) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "OK" }
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $status $statusText\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        try { output.write(header.toByteArray(StandardCharsets.UTF_8)); output.write(bytes); output.flush() }
        catch (e: Exception) { Log.w(TAG, "Write failed: ${e.message}") }
    }

    private fun formPage(token: String) = """
<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Install Lumora Plugin</title>
<style>*{box-sizing:border-box}body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;background:#0d0d0d;color:#eee;margin:0;padding:16px}
main{max-width:520px;margin:20px auto;background:#1a1a1a;border:1px solid #333;border-radius:16px;padding:24px}
h1{margin:0 0 4px;font-size:22px;color:#fff}p{color:#9e9e9e;margin:4px 0 16px;line-height:1.4}
label{display:block;margin-top:16px;font-size:14px;font-weight:600;color:#ccc}
input{width:100%;margin-top:6px;padding:14px;border-radius:10px;border:1px solid #444;background:#0d0d0d;color:#fff;font-size:16px}
input:focus{outline:none;border-color:#2979ff;box-shadow:0 0 0 2px rgba(41,121,255,0.3)}
button{width:100%;margin-top:24px;padding:16px;border:0;border-radius:12px;background:#2979ff;color:#fff;font-weight:700;font-size:17px}
button:active{background:#1565c0}.hint{font-size:13px;color:#777;margin-top:6px}</style></head>
<body><main><h1>Install a plugin</h1>
<p>Paste the download link (.apk) for the plugin you want to add. It's sent to your TV over your local network and installed there.</p>
<form method="post" action="/submit">
<input type="hidden" name="token" value="${token.escapeHtml()}">
<label>Plugin APK link</label>
<input name="url" type="url" inputmode="url" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="https://example.com/plugin.apk">
<div class="hint">Only install plugins from sources you trust. Your TV will still ask you to confirm.</div>
<button type="submit">Send to TV</button></form></main></body></html>
""".trimIndent()

    private fun successPage() = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:-apple-system,sans-serif;background:#0d0d0d;color:#eee;padding:28px}
main{max-width:520px;margin:auto;background:#1a1a1a;border-radius:16px;padding:24px}h1{color:#4caf50}</style></head>
<body><main><h1>Sent to TV</h1><p>Finish the install on your TV screen.</p></main></body></html>"""

    private fun errorPage(msg: String) = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:-apple-system,sans-serif;background:#0d0d0d;color:#eee;padding:28px}
main{max-width:520px;margin:auto;background:#221111;border-radius:16px;padding:24px}h1{color:#ff5252}</style></head>
<body><main><h1>Error</h1><p>${msg.escapeHtml()}</p></main></body></html>"""

    private fun String.escapeHtml(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
