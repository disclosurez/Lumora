package com.lumora.plugin.js

import com.lumora.plugin.DiscoveredProvider
import com.lumora.plugin.DiscoveryResult
import com.lumora.plugin.ResolveResult
import com.lumora.plugin.SearchResult
import com.lumora.plugin.TorrentResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsPluginEngineTest {

    companion object {
        // wrapper-java ships no prebuilt desktop-JVM native lib (only wrapper-android bundles a
        // prebuilt .so, for Android ABIs), so these tests need a locally-built one to actually
        // execute:
        //   git clone --recursive https://github.com/HarlonWang/quickjs-wrapper.git
        //   cd quickjs-wrapper/wrapper-java
        //   cmake -DCMAKE_BUILD_TYPE=Debug -DCMAKE_MAKE_PROGRAM=ninja -G Ninja -S ./src/main -B ./build/cmake
        //   cmake --build ./build/cmake --target quickjs-java-wrapper -j 4
        // then run with:
        //   ./gradlew :app:testDebugUnitTest --tests "com.lumora.plugin.js.*" \
        //     -Dtest.quickjs.so=/path/to/quickjs-wrapper/wrapper-java/build/cmake/libquickjs-java-wrapper.so
        // Without the property, these tests fail with QuickJSException ("so library must be
        // initialized"). Real device/emulator runs (Android instrumented tests) need no setup
        // since wrapper-android bundles the .so.
        init {
            System.getProperty("test.quickjs.so")?.let { System.load(it) }
        }
    }

    @Test
    fun `discover reports progress and a valid candidate`() = runBlocking {
        val engine = JsPluginEngine(OkHttpClient())
        val script = """
            PLUGIN = { id: "test.discover", label: "Test", capabilities: ["provider_discovery"] };
            function discover(host) {
                host.reportProgress("scanning");
                host.reportCandidate({ type: "m3u", label: "Test provider", url: "https://example.com/list.m3u" });
            }
        """.trimIndent()

        val progress = mutableListOf<String>()
        val candidates = mutableListOf<DiscoveredProvider>()
        val result = engine.runDiscovery(
            script,
            onProgress = { progress.add(it) },
            onCandidate = { candidates.add(it) },
        )

        assertTrue(result is DiscoveryResult.Finished)
        assertEquals(listOf("scanning"), progress)
        assertEquals(1, candidates.size)
        assertEquals("m3u", candidates[0].type)
        assertEquals("https://example.com/list.m3u", candidates[0].url)
    }

    @Test
    fun `candidates with unsupported type or non-http url are dropped`() = runBlocking {
        val engine = JsPluginEngine(OkHttpClient())
        val script = """
            function discover(host) {
                host.reportCandidate({ type: "m3u", label: "bad", url: "ftp://not-http" });
                host.reportCandidate({ type: "not-a-type", label: "bad2", url: "https://ok.example" });
            }
        """.trimIndent()
        val candidates = mutableListOf<DiscoveredProvider>()
        engine.runDiscovery(script, onCandidate = { candidates.add(it) })
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `discover throwing maps to a Failed result`() = runBlocking {
        val engine = JsPluginEngine(OkHttpClient())
        val script = "function discover(host) { throw new Error(\"boom\"); }"
        val result = engine.runDiscovery(script)
        assertTrue(result is DiscoveryResult.Failed)
        assertEquals("boom", (result as DiscoveryResult.Failed).message)
    }

    @Test
    fun `discover propagates the script's own return value as the finished message`() = runBlocking {
        // Regression: runDiscovery used to hardcode DiscoveryResult.Finished(null), discarding
        // whatever specific reason discover() returned (e.g. "No credentials found in pastes")
        // and leaving the UI to always show a generic "Nothing found" instead.
        val engine = JsPluginEngine(OkHttpClient())
        val script = """function discover(host) { return "No paste links found"; }"""
        val result = engine.runDiscovery(script)
        assertTrue(result is DiscoveryResult.Finished)
        assertEquals("No paste links found", (result as DiscoveryResult.Finished).message)
    }

    @Test
    fun `a throwing onResult callback does not abort the rest of the search`() = runBlocking {
        // Regression: onResult/onCandidate/onProgress used to be called synchronously from the
        // JS engine's background executor thread straight into caller UI code that assumes the
        // main thread. A caller callback throwing (as real Android view code does off the main
        // thread) used to propagate back into the executor thread uncaught, aborting the script
        // mid-run - reliably losing every result after the first one that triggered it.
        val engine = JsPluginEngine(OkHttpClient())
        val script = """
            function search(host, query, year, season, episode) {
                host.reportResult({ title: "First", token: "tok-1" });
                host.reportResult({ title: "Second", token: "tok-2" });
                return "done";
            }
        """.trimIndent()
        val results = mutableListOf<TorrentResult>()
        val outcome = engine.runSearch(
            script, "q", null, null, null,
            onResult = {
                results.add(it)
                if (results.size == 1) error("simulated UI crash on the first result")
            },
        )
        assertTrue(outcome is SearchResult.Finished)
        assertEquals("done", (outcome as SearchResult.Finished).message)
        assertEquals(2, results.size)
    }

    @Test
    fun `httpGet on a malformed url reports status 0 instead of throwing`() = runBlocking {
        val engine = JsPluginEngine(OkHttpClient())
        val script = """
            function search(host, query, year, season, episode) {
                const resp = host.httpGet("not a valid url", {});
                return "status=" + resp.status;
            }
        """.trimIndent()
        val outcome = engine.runSearch(script, "q", null, null, null)
        assertTrue(outcome is SearchResult.Finished)
        assertEquals("status=0", (outcome as SearchResult.Finished).message)
    }

    @Test
    fun `httpGet on an unreachable host reports status 0 instead of throwing`() = runBlocking {
        // Port 1 on loopback: nothing listens there, so this fails fast with a real IOException
        // (connection refused) rather than a timeout - exercises the network-failure path of
        // JsHostImpl.httpGet distinctly from the malformed-URL path above.
        val engine = JsPluginEngine(OkHttpClient())
        val script = """
            function search(host, query, year, season, episode) {
                const resp = host.httpGet("http://127.0.0.1:1/", {});
                return "status=" + resp.status;
            }
        """.trimIndent()
        val outcome = engine.runSearch(script, "q", null, null, null)
        assertTrue(outcome is SearchResult.Finished)
        assertEquals("status=0", (outcome as SearchResult.Finished).message)
    }

    @Test
    fun `search calls host httpGet and reports a result`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        server.start()
        try {
            val engine = JsPluginEngine(OkHttpClient())
            val script = """
                function search(host, query, year, season, episode) {
                    const resp = host.httpGet("${server.url("/api")}", {});
                    const data = JSON.parse(resp.body);
                    if (data.ok) {
                        host.reportResult({ title: "Found " + query, token: "tok-1", seeders: 12 });
                    }
                    return "done";
                }
            """.trimIndent()
            val results = mutableListOf<TorrentResult>()
            val outcome = engine.runSearch(script, "Some Title", null, null, null, onResult = { results.add(it) })
            assertTrue(outcome is SearchResult.Finished)
            assertEquals("done", (outcome as SearchResult.Finished).message)
            assertEquals(1, results.size)
            assertEquals("Found Some Title", results[0].title)
            assertEquals(12, results[0].seeders)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `resolve returns a validated stream url`() = runBlocking {
        val engine = JsPluginEngine(OkHttpClient())
        val script = """
            function resolve(host, token, season, episode) {
                return "https://stream.example.com/" + token + ".m3u8";
            }
        """.trimIndent()
        val outcome = engine.resolve(script, "abc123", null, null)
        assertTrue(outcome is ResolveResult.Ready)
        assertEquals("https://stream.example.com/abc123.m3u8", (outcome as ResolveResult.Ready).url)
    }

    @Test
    fun `resolve rejects a non-http url`() = runBlocking {
        val engine = JsPluginEngine(OkHttpClient())
        val script = """function resolve(host, token, season, episode) { return "file:///etc/passwd"; }"""
        val outcome = engine.resolve(script, "abc123", null, null)
        assertTrue(outcome is ResolveResult.Failed)
    }

    @Test
    fun `probeManifest reads the PLUGIN object`() = runBlocking {
        val engine = JsPluginEngine(OkHttpClient())
        val script = """PLUGIN = { id: "anime.senshi", label: "Anime", capabilities: ["stream_search"] };"""
        val manifest = engine.probeManifest(script)
        assertEquals("anime.senshi", manifest?.get("id"))
        assertEquals("Anime", manifest?.get("label"))
    }
}
