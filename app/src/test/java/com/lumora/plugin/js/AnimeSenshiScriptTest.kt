package com.lumora.plugin.js

import com.lumora.plugin.ResolveResult
import com.lumora.plugin.SearchResult
import com.lumora.plugin.TorrentResult
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the anime-senshi.js port against fixture HTTP responses shaped like the real
 * AniList/Senshi APIs, verifying it reproduces the old Kotlin animeplugin's behavior (see
 * AniListClient.kt/SenshiProvider.kt/StreamService.kt in the Lumora-Plugins repo, pre-port).
 *
 * Requires a locally-built quickjs-wrapper native lib - see JsPluginEngineTest's companion
 * object comment for build steps and how to point -Dtest.quickjs.so at it.
 */
class AnimeSenshiScriptTest {

    companion object {
        init {
            System.getProperty("test.quickjs.so")?.let { System.load(it) }
        }
    }

    private val script = File("src/test/resources/plugins/anime-senshi.js").readText()

    private val aniListResponse = """
        {"data":{"Page":{"media":[{
            "id":1,"idMal":5114,
            "title":{"romaji":"Hagane no Renkinjutsushi","english":"Fullmetal Alchemist: Brotherhood","native":"..."},
            "episodes":64,"status":"FINISHED","format":"TV","seasonYear":2009,
            "coverImage":{"large":"https://img.example/x.jpg"},
            "studios":{"nodes":[{"name":"Bones"}]}
        }]}}}
    """.trimIndent()

    private val episodesResponse = """[{"ep_id":1,"ep_title":"Ep 1","ep_filler":false}]"""

    private val embedsResponseSubAndDub = """
        [
            {"url":"https://cdn.example/ep1-sub.m3u8","status":"Sub"},
            {"url":"https://cdn.example/ep1-dub.m3u8","status":"Dub"}
        ]
    """.trimIndent()

    @Test
    fun `search returns sub and dub results for an available episode`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(aniListResponse))       // AniList search
        server.enqueue(MockResponse().setBody(episodesResponse))      // Senshi episode catalog
        server.enqueue(MockResponse().setBody(embedsResponseSubAndDub)) // Senshi availability probe
        server.start()
        try {
            val engine = JsPluginEngine(OkHttpClient())
            val results = mutableListOf<TorrentResult>()
            val outcome = engine.runSearch(
                source = script.replace("https://graphql.anilist.co", server.url("/anilist").toString())
                    .replace("https://senshi.live", server.url("/senshi").toString()),
                query = "Fullmetal Alchemist",
                year = null,
                season = null,
                episode = 1,
                onResult = { results.add(it) },
            )
            assertTrue(outcome is SearchResult.Finished)
            assertEquals(2, results.size)
            assertTrue(results.any { it.token == "senshi:5114:sub" && it.quality == "Sub" })
            assertTrue(results.any { it.token == "senshi:5114:dub" && it.quality == "Dub" })
            assertTrue(results.all { it.source == "Senshi" })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `resolve returns the sub HLS url for a sub token`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(embedsResponseSubAndDub))
        server.start()
        try {
            val engine = JsPluginEngine(OkHttpClient())
            val patchedScript = script.replace("https://senshi.live", server.url("/senshi").toString())
            val outcome = engine.resolve(patchedScript, "senshi:5114:sub", season = null, episode = 1)
            assertTrue(outcome is ResolveResult.Ready)
            assertEquals("https://cdn.example/ep1-sub.m3u8", (outcome as ResolveResult.Ready).url)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `resolve returns the dub HLS url for a dub token`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(embedsResponseSubAndDub))
        server.start()
        try {
            val engine = JsPluginEngine(OkHttpClient())
            val patchedScript = script.replace("https://senshi.live", server.url("/senshi").toString())
            val outcome = engine.resolve(patchedScript, "senshi:5114:dub", season = null, episode = 1)
            assertTrue(outcome is ResolveResult.Ready)
            assertEquals("https://cdn.example/ep1-dub.m3u8", (outcome as ResolveResult.Ready).url)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `resolve fails cleanly for an unknown token format`() = runBlocking {
        val engine = JsPluginEngine(OkHttpClient())
        val outcome = engine.resolve(script, "torrent:magnet:xyz", season = null, episode = 1)
        assertTrue(outcome is ResolveResult.Failed)
    }

    @Test
    fun `probeManifest reads the bundled script's PLUGIN header`() = runBlocking {
        val engine = JsPluginEngine(OkHttpClient())
        val manifest = engine.probeManifest(script)
        assertEquals("anime.senshi", manifest?.get("id"))
        assertEquals(listOf("stream_search"), manifest?.get("capabilities"))
        assertEquals(listOf("anime"), manifest?.get("contentTypes"))
    }
}
