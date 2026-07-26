package com.lumora.pairing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.lumora.util.normalizeServerUrl
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.*

private const val TAG = "QrPairing"
private const val PAIRING_TIMEOUT_MS = 5 * 60 * 1000L
private const val QR_SIZE = 512
private const val MAX_BODY = 24 * 1024

/**
 * HTTP server + QR code generator for phone-based IPTV provider setup.
 *
 * Flow:
 *   TV starts ServerSocket → generates QR with http://IP:PORT/pair?t=TOKEN
 *   Phone scans QR → opens browser → fills form → POSTs to /submit
 *   TV receives form → calls onProviderReceived callback
 */
class QrPairingManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var timeoutJob: Job? = null
    private var activeToken: String? = null
    private var activeExpiresAt: Long = 0L

    var onProviderReceived: ((type: String, formData: Map<String, String>) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onServerReady: ((url: String, port: Int) -> Unit)? = null

    /** Starts a Jellyfin Quick Connect session against the submitted server URL - wired by
     *  MainActivity (which owns the actual Jellyfin network client) so this class stays
     *  provider-agnostic. Called synchronously while handling the phone's POST /submit, so
     *  the resulting code/secret can be shown in the response *and* handed to the TV -
     *  both sides need the same pair, not two independently-started ones. */
    var onJellyfinQuickConnect: (suspend (serverUrl: String) -> QuickConnectStart)? = null

    data class QuickConnectStart(val code: String?, val secret: String?, val error: String?)

    data class PairingResult(
        val url: String,
        val qrBitmap: Bitmap,
        val expiresAtMs: Long,
        val ipAddress: String,
        val port: Int
    )

    private var _result: PairingResult? = null
    val result: PairingResult? get() = _result

    /** Start the pairing server with an optional provider type preset. */
    suspend fun start(providerType: String? = null): PairingResult? = withContext(Dispatchers.IO) {
        stop()
        Log.d(TAG, "Starting QR pairing server...")

        val host = resolveLanIp()
        if (host == null) {
            val err = "Could not detect LAN IP. Connect to Wi-Fi and try again."
            Log.w(TAG, err)
            onError?.invoke(err)
            return@withContext null
        }
        Log.d(TAG, "LAN IP resolved: $host")

        val socket = try {
            ServerSocket(0, 10)
        } catch (e: Exception) {
            val err = "Server error: ${e.message}"
            Log.w(TAG, err)
            onError?.invoke(err)
            return@withContext null
        }

        val token = generateToken()
        val port = socket.localPort
        val typeParam = if (providerType != null) "&p=$providerType" else ""
        val url = "http://$host:$port/pair?t=$token$typeParam"
        val expiresAt = System.currentTimeMillis() + PAIRING_TIMEOUT_MS
        Log.d(TAG, "Server listening on $host:$port, token=$token type=$providerType")

        serverSocket = socket
        activeToken = token
        activeExpiresAt = expiresAt

        val qrBitmap = createQrBitmap(url)
        val pairingResult = PairingResult(url, qrBitmap, expiresAt, host, port)
        _result = pairingResult

        // Accept connections in background
        acceptJob = scope.launch { acceptLoop(socket) }

        // Auto-stop after timeout
        timeoutJob = scope.launch {
            delay(PAIRING_TIMEOUT_MS)
            if (_result != null) {
                Log.d(TAG, "Pairing timed out")
                stop()
                onError?.invoke("Pairing session expired. Try again.")
            }
        }

        onServerReady?.invoke(url, port)
        return@withContext pairingResult
    }

    /** Stop the server and clean up. */
    fun stop() {
        acceptJob?.cancel()
        timeoutJob?.cancel()
        acceptJob = null
        timeoutJob = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        activeToken = null
        _result = null
        Log.d(TAG, "Pairing server stopped")
    }

    // ── HTTP Server ──────────────────────────────────────────

    private suspend fun acceptLoop(socket: ServerSocket) {
        try {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: Exception) { break }
                scope.launch {
                    try { handleClient(client) } catch (e: Exception) {
                        Log.w(TAG, "Client error: ${e.message}")
                        try { client.close() } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            if (!socket.isClosed) Log.w(TAG, "Accept loop error: ${e.message}")
        }
    }

    private suspend fun handleClient(client: java.net.Socket) {
        client.use { c ->
            val reader = BufferedReader(InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return

            val method = parts[0].uppercase(Locale.US)
            val pathAndQuery = parts[1]

            // Read headers
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val key = line.substringBefore(':').trim().lowercase(Locale.US)
                val value = line.substringAfter(':').trim()
                if (key.isNotBlank()) headers[key] = value
            }

            when {
                method == "GET" && pathAndQuery.startsWith("/pair") -> {
                    val token = getQueryParam(pathAndQuery, "t")
                    val presetType = getQueryParam(pathAndQuery, "p") ?: "m3u"
                    if (!isTokenValid(token)) {
                        writeHtml(c.getOutputStream(), 403, errorPage("Link expired or invalid. Start a new QR session on the TV."))
                    } else {
                        writeHtml(c.getOutputStream(), 200, formPage(token ?: "", presetType))
                    }
                }
                method == "POST" && pathAndQuery.startsWith("/submit") -> {
                    val length = headers["content-length"]?.toIntOrNull()?.coerceAtMost(MAX_BODY) ?: 0
                    val body = CharArray(length)
                    var read = 0
                    while (read < length) { val n = reader.read(body, read, length - read); if (n <= 0) break; read += n }
                    val form = parseForm(String(body, 0, read))
                    val token = form["token"]
                    if (!isTokenValid(token)) {
                        writeHtml(c.getOutputStream(), 403, errorPage("Session expired. Start a new QR session."))
                        return
                    }
                    val type = form["type"] ?: "m3u"
                    Log.d(TAG, "Provider submitted: type=$type name=${form["name"]}")
                    if (type == "jellyfin" && form["authMethod"] == "quickconnect") {
                        // Quick Connect's code has to match on both screens - start it here,
                        // synchronously, so the same code/secret pair can be shown on the
                        // phone (this response) and handed to the TV to poll/complete,
                        // instead of each side minting its own separate code.
                        val serverUrl = form["jellyfinServerUrl"]?.let { normalizeServerUrl(it) } ?: ""
                        val starter = onJellyfinQuickConnect
                        val result = starter?.invoke(serverUrl)
                        if (result?.code == null || result.secret == null) {
                            writeHtml(c.getOutputStream(), 200, errorPage(result?.error ?: "Couldn't start Quick Connect - check the server URL"))
                        } else {
                            onProviderReceived?.invoke(
                                "jellyfin_quickconnect",
                                form + mapOf("serverUrl" to serverUrl, "code" to result.code, "secret" to result.secret)
                            )
                            writeHtml(c.getOutputStream(), 200, quickConnectPage(result.code))
                        }
                    } else {
                        onProviderReceived?.invoke(type, form)
                        writeHtml(c.getOutputStream(), 200, successPage("Provider details received! Check your TV."))
                    }
                }
                else -> writeHtml(c.getOutputStream(), 404, "Not found")
            }
        }
    }

    // ── Token ────────────────────────────────────────────────

    private fun isTokenValid(token: String?): Boolean =
        token != null && token == activeToken && System.currentTimeMillis() < activeExpiresAt

    private fun generateToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ── QR Code ──────────────────────────────────────────────

    private fun createQrBitmap(value: String): Bitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.MARGIN, 2)
            put(EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M)
        }
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints)
        val pixels = IntArray(QR_SIZE * QR_SIZE)
        for (y in 0 until QR_SIZE) {
            for (x in 0 until QR_SIZE) {
                pixels[y * QR_SIZE + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(QR_SIZE, QR_SIZE, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, QR_SIZE, 0, 0, QR_SIZE, QR_SIZE)
        }
    }

    // ── IP Resolution ────────────────────────────────────────

    private fun resolveLanIp(): String? {
        // Try Wi-Fi first
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi != null) {
            try {
                @Suppress("DEPRECATION")
                val ipInt = wifi.connectionInfo.ipAddress
                if (ipInt != 0) {
                    @Suppress("DEPRECATION")
                    return Formatter.formatIpAddress(ipInt)
                }
            } catch (e: Exception) {
                Log.w(TAG, "WiFi IP failed: ${e.message}")
            }
        }

        // Fallback: iterate network interfaces
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .firstOrNull { !it.startsWith("127.") }
        } catch (e: Exception) {
            Log.w(TAG, "Network interface scan failed: ${e.message}")
            null
        }
    }

    // ── HTTP Helpers ─────────────────────────────────────────

    private fun getQueryParam(pathAndQuery: String, key: String): String? {
        val query = pathAndQuery.substringAfter('?', "").takeIf { it.isNotBlank() } ?: return null
        return parseForm(query)[key]
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val k = decode(part.substringBefore('=', ""))
            val v = decode(part.substringAfter('=', ""))
            k to v
        }.toMap()

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    } catch (e: Exception) { value }

    private fun writeHtml(output: OutputStream, status: Int, html: String) =
        writeResponse(output, status, "text/html; charset=utf-8", html)

    private fun writeResponse(output: OutputStream, status: Int, contentType: String, body: String) {
        val statusText = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "OK" }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $status $statusText\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n"
        try {
            output.write(header.toByteArray(StandardCharsets.UTF_8))
            output.write(bytes)
            output.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Write response failed: ${e.message}")
        }
    }

    // ── HTML Pages ───────────────────────────────────────────

    private fun formPage(token: String, presetType: String = "m3u"): String = """
<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Lumora Pairing</title>
<style>
*{box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;background:#0d0d0d;color:#eee;margin:0;padding:16px}
main{max-width:520px;margin:20px auto;background:#1a1a1a;border:1px solid #333;border-radius:16px;padding:24px}
h1{margin:0 0 4px;font-size:22px;color:#fff} p{color:#9e9e9e;margin:4px 0 16px;line-height:1.4}
label{display:block;margin-top:16px;font-size:14px;font-weight:600;color:#ccc}
input,select{width:100%;margin-top:6px;padding:14px;border-radius:10px;border:1px solid #444;background:#0d0d0d;color:#fff;font-size:16px}
input:focus,select:focus{outline:none;border-color:#2979ff;box-shadow:0 0 0 2px rgba(41,121,255,0.3)}
button{width:100%;margin-top:24px;padding:16px;border:0;border-radius:12px;background:#2979ff;color:#fff;font-weight:700;font-size:17px}
button:active{background:#1565c0}
.hint{font-size:13px;color:#777;margin-top:4px}.field-group{display:none}.field-group.active{display:block}
</style></head>
<body>
<main>
<h1>Add IPTV Provider</h1>
<p>Enter your provider details. Sent directly to your TV over your local network.</p>
<form method="post" action="/submit">
<input type="hidden" name="token" value="${token.escapeHtml()}">
<label>Provider type</label>
<select name="type" id="type" onchange="updateType()">
  <option value="m3u" ${if (presetType == "m3u") "selected" else ""}>M3U Playlist URL</option>
  <option value="xtream" ${if (presetType == "xtream") "selected" else ""}>Xtream Codes</option>
  <option value="jellyfin" ${if (presetType == "jellyfin") "selected" else ""}>Jellyfin</option>
</select>
<label>Name (optional)</label>
<input name="name" placeholder="My Provider">
<div id="m3uFields" class="field-group ${if (presetType == "m3u") "active" else ""}">
  <label>M3U Playlist URL</label>
  <input name="m3uUrl" placeholder="https://example.com/get.php?...">
  <div class="hint">Paste your full M3U playlist URL here.</div>
</div>
<div id="xtreamFields" class="field-group ${if (presetType == "xtream") "active" else ""}">
  <label>Server URL</label>
  <input name="serverUrl" placeholder="http://example.com:8080">
  <label>Username</label>
  <input name="username">
  <label>Password</label>
  <input name="password" type="password">
</div>
<div id="jellyfinFields" class="field-group ${if (presetType == "jellyfin") "active" else ""}">
  <label>Server URL</label>
  <input name="jellyfinServerUrl" placeholder="http://192.168.1.100:8096">
  <label>Sign in with</label>
  <select name="authMethod" id="authMethod" onchange="updateAuthMethod()">
    <option value="password">Username &amp; Password</option>
    <option value="quickconnect">Quick Connect</option>
  </select>
  <div id="jellyfinPasswordFields" class="field-group active">
    <label>Username</label>
    <input name="jellyfinUsername">
    <label>Password</label>
    <input name="jellyfinPassword" type="password">
  </div>
  <div id="jellyfinQuickConnectHint" class="field-group">
    <div class="hint">After sending, a code appears here and on your TV - enter it on your Jellyfin server's Quick Connect page to finish signing in.</div>
  </div>
</div>
<button type="submit">Send to TV</button>
</form>
</main>
<script>
function updateType(){const t=document.getElementById('type').value;
document.getElementById('m3uFields').classList.toggle('active',t==='m3u');
document.getElementById('xtreamFields').classList.toggle('active',t==='xtream');
document.getElementById('jellyfinFields').classList.toggle('active',t==='jellyfin');}
function updateAuthMethod(){const m=document.getElementById('authMethod').value;
document.getElementById('jellyfinPasswordFields').classList.toggle('active',m==='password');
document.getElementById('jellyfinQuickConnectHint').classList.toggle('active',m==='quickconnect');}
</script>
</body></html>
""".trimIndent()

    private fun successPage(msg: String) = """<!doctype html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:-apple-system,sans-serif;background:#0d0d0d;color:#eee;padding:28px}
main{max-width:520px;margin:auto;background:#1a1a1a;border-radius:16px;padding:24px}
h1{color:#4caf50}</style>
</head><body><main><h1>Sent to TV</h1><p>${msg.escapeHtml()}</p></main></body></html>"""

    private fun quickConnectPage(code: String) = """<!doctype html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Lumora Pairing</title>
<style>body{font-family:-apple-system,sans-serif;background:#0d0d0d;color:#eee;padding:28px;text-align:center}
main{max-width:520px;margin:auto;background:#1a1a1a;border-radius:16px;padding:28px}
h1{color:#fff;font-size:20px;margin:0 0 4px}
p{color:#9e9e9e;line-height:1.4}
.code{font-size:48px;font-weight:700;letter-spacing:6px;color:#2979ff;background:#0d0d0d;border-radius:12px;padding:20px;margin:20px 0}
</style></head><body><main>
<h1>Quick Connect</h1>
<p>Enter this code on your Jellyfin server to finish signing in on your TV.</p>
<div class="code">${code.escapeHtml()}</div>
<p>This page can be closed once you've entered it.</p>
</main></body></html>"""

    private fun errorPage(msg: String) = """<!doctype html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:-apple-system,sans-serif;background:#0d0d0d;color:#eee;padding:28px}
main{max-width:520px;margin:auto;background:#221111;border-radius:16px;padding:24px}
h1{color:#ff5252}</style>
</head><body><main><h1>Error</h1><p>${msg.escapeHtml()}</p></main></body></html>"""

    private fun String.escapeHtml(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
