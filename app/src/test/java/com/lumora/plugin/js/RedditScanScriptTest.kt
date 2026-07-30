package com.lumora.plugin.js

import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises redditscan.js against fixture HTTP responses. The paste.sh decrypt path is the
 * highest-risk part of this port (see JsHostImpl's binary-safe primitives) - its test builds a
 * ciphertext independently with raw javax.crypto calls (not reusing any JsHostImpl code) using
 * the exact layout/KDF the old Kotlin PasteShDecryptor.kt used, so a match proves the JS port +
 * host crypto primitives reproduce that algorithm byte-for-byte, not just "some algorithm".
 *
 * Requires a locally-built quickjs-wrapper native lib - see JsPluginEngineTest's companion
 * object comment for build steps and how to point -Dtest.quickjs.so at it.
 */
class RedditScanScriptTest {

    companion object {
        init {
            System.getProperty("test.quickjs.so")?.let { System.load(it) }
        }
    }

    private val script = File("src/test/resources/plugins/redditscan.js").readText()

    @Test
    fun `probeManifest reads the bundled script's PLUGIN header`() = runBlocking {
        val engine = JsPluginEngine(OkHttpClient())
        val manifest = engine.probeManifest(script)
        assertEquals("reddit.iptvscan", manifest?.get("id"))
        assertEquals(listOf("provider_discovery"), manifest?.get("capabilities"))
    }

    @Test
    fun `pasteShDecrypt reproduces the old Kotlin PasteShDecryptor's algorithm`() = runBlocking {
        // Build a ciphertext the same way the real paste.sh service (and the old Kotlin
        // PasteShDecryptor) would, using independent javax.crypto calls - no shared code with
        // JsHostImpl's implementation.
        val pasteId = "abc123"
        val serverKey = "serverKeyXYZ789"
        val clientKey = "clientKeyABC456"
        val plaintext = "xtream://example.com user=demo pass=demo123"

        val passwordBytes = "$pasteId$serverKey${clientKey}https://paste.sh".toByteArray(Charsets.UTF_8)
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val ignoredPrefix = ByteArray(8) { 'S'.code.toByte() } // paste.sh's on-wire format has 8 unused leading bytes

        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(passwordBytes, "HmacSHA512"))
        val keyIv = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1)) // one PBKDF2 round, block index 1
        val key = keyIv.copyOfRange(0, 32)
        val iv = keyIv.copyOfRange(32, 48)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val onWireBlob = java.util.Base64.getEncoder().encodeToString(ignoredPrefix + salt + ciphertext)

        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("$serverKey\n$onWireBlob"))
        server.start()
        try {
            val baseUrl = server.url("/$pasteId").toString()
            val pasteUrl = "$baseUrl#$clientKey"

            // redditscan.js only exposes discover() - reuse its internal pasteShDecrypt via a
            // thin test-only search() wrapper so we can call it through the normal engine API.
            val wrapperScript = "$script\nfunction search(host, q, y, s, e) { return pasteShDecrypt(q); }"

            val engine = JsPluginEngine(OkHttpClient())
            val outcome = engine.runSearch(wrapperScript, query = pasteUrl, year = null, season = null, episode = null)
            val decrypted = (outcome as com.lumora.plugin.SearchResult.Finished).message
            assertEquals(plaintext, decrypted)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `parseCredentials extracts an xtream url-query-param link`() = runBlocking {
        val pasteBody = "Check this out: http://example.com:8080/get.php?username=demoUser&password=demoPass123&type=m3u_plus"
        val wrapperScript = "$script\nfunction search(host, q, y, s, e) { return JSON.stringify(parseCredentials(q)); }"
        val engine = JsPluginEngine(OkHttpClient())
        val outcome = engine.runSearch(wrapperScript, query = pasteBody, year = null, season = null, episode = null)
        val json = (outcome as com.lumora.plugin.SearchResult.Finished).message!!
        assertTrue(json.contains("\"type\":\"xtream\""))
        assertTrue(json.contains("\"url\":\"http://example.com:8080\""))
        assertTrue(json.contains("\"username\":\"demoUser\""))
        assertTrue(json.contains("\"password\":\"demoPass123\""))
    }

    @Test
    fun `parseCredentials extracts a structured-line block with an inline host colon port`() = runBlocking {
        // Host and port must be glued together (host:port) to be captured together - a separate
        // "Port:" line is a dead branch in the original Kotlin CredentialParser too (its
        // takeIf-based fallback never actually triggers), so the JS port replicates that
        // (buggy) behavior rather than "fixing" it into a different-from-production shape.
        val pasteBody = "Host: 203.0.113.5:8080\nUsername: demoUser\nPassword: demoPass123"
        val wrapperScript = "$script\nfunction search(host, q, y, s, e) { return JSON.stringify(parseCredentials(q)); }"
        val engine = JsPluginEngine(OkHttpClient())
        val outcome = engine.runSearch(wrapperScript, query = pasteBody, year = null, season = null, episode = null)
        val json = (outcome as com.lumora.plugin.SearchResult.Finished).message!!
        assertTrue(json.contains("\"type\":\"xtream\""))
        assertTrue(json.contains("\"url\":\"http://203.0.113.5:8080\""))
        assertTrue(json.contains("\"username\":\"demoUser\""))
        assertTrue(json.contains("\"password\":\"demoPass123\""))
    }

    @Test
    fun `parseCredentials extracts a stalker portal block`() = runBlocking {
        val pasteBody = "Portal: http://203.0.113.5/c/\nMAC: 00:1A:79:AA:BB:CC\nExpiry: 2027-01-01"
        val wrapperScript = "$script\nfunction search(host, q, y, s, e) { return JSON.stringify(parseCredentials(q)); }"
        val engine = JsPluginEngine(OkHttpClient())
        val outcome = engine.runSearch(wrapperScript, query = pasteBody, year = null, season = null, episode = null)
        val json = (outcome as com.lumora.plugin.SearchResult.Finished).message!!
        assertTrue(json.contains("\"type\":\"stalker\""))
        assertTrue(json.contains("\"url\":\"http://203.0.113.5/c\""))
        assertTrue(json.contains("\"macAddress\":\"00:1A:79:AA:BB:CC\""))
    }

    @Test
    fun `testCredential reports a working xtream m3u endpoint online`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:-1,Test\nhttp://example/1"))
        server.start()
        try {
            val cred = """{"type":"xtream","url":"${server.url("").toString().trimEnd('/')}","username":"u","password":"p"}"""
            val wrapperScript = "$script\nfunction search(host, q, y, s, e) { return JSON.stringify(testCredential(JSON.parse(q))); }"
            val engine = JsPluginEngine(OkHttpClient())
            val outcome = engine.runSearch(wrapperScript, query = cred, year = null, season = null, episode = null)
            val json = (outcome as com.lumora.plugin.SearchResult.Finished).message!!
            assertTrue(json.contains("\"online\":true"))
        } finally {
            server.shutdown()
        }
    }
}
