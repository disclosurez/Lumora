package com.lumora.plugin.js

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
 * Exercises torrent-search.js's scraper/filter/aggregation logic against fixture HTML shaped
 * like the real ThePirateBay/Knaben pages. Resolve is intentionally not part of this script (see
 * PLUGIN.resolvesNatively) - the native com.lumora.torrent.TorrentEngine handles that, which
 * can't be meaningfully unit tested without a real libtorrent session and peers.
 *
 * Requires a locally-built quickjs-wrapper native lib - see JsPluginEngineTest's companion
 * object comment for build steps and how to point -Dtest.quickjs.so at it.
 */
class TorrentSearchScriptTest {

    companion object {
        init {
            System.getProperty("test.quickjs.so")?.let { System.load(it) }
        }
    }

    private val script = File("src/test/resources/plugins/torrent-search.js").readText()

    private val tpbHtml = """
        <table>
          <tr><th>Type</th><th>Name</th><th>Uploaded</th><th>Uploader</th><th>Size</th><th>SE</th></tr>
          <tr>
            <td>Video</td>
            <td><a class="detLink" href="/torrent/1">Movie Title 2020 1080p BluRay x264</a>
                <a href="magnet:?xt=urn:btih:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA">magnet</a></td>
            <td>2020-01-01</td>
            <td>uploader</td>
            <td>2.1 GiB</td>
            <td>150</td>
          </tr>
        </table>
    """.trimIndent()

    private val knabenHtml = """
        <table><tbody>
          <tr>
            <td>1</td>
            <td class="text-wrap"><a href="magnet:?xt=urn:btih:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB" title="Another Movie 2021 720p WEB-DL">Another Movie 2021 720p WEB-DL</a></td>
            <td>500 MB</td>
            <td>Movies</td>
            <td>10</td>
            <td>2</td>
            <td>2 days ago</td>
          </tr>
        </tbody></table>
    """.trimIndent()

    @Test
    fun `probeManifest reads the bundled script's PLUGIN header incl resolvesNatively`() = runBlocking {
        val engine = JsPluginEngine(OkHttpClient())
        val manifest = engine.probeManifest(script)
        assertEquals("torrent.search", manifest?.get("id"))
        assertEquals(listOf("stream_search"), manifest?.get("capabilities"))
        assertEquals(true, manifest?.get("resolvesNatively"))
    }

    @Test
    fun `search aggregates both scrapers, filters by title and size, sorts by seeders`() = runBlocking {
        val knabenServer = MockWebServer()
        val tpbServer = MockWebServer()
        knabenServer.enqueue(MockResponse().setBody(knabenHtml))
        tpbServer.enqueue(MockResponse().setBody(tpbHtml))
        tpbServer.enqueue(MockResponse().setBody("<table></table>")) // page 2 - empty, stops pagination
        knabenServer.start()
        tpbServer.start()
        try {
            val patchedScript = script
                .replace("https://knaben.org", knabenServer.url("").toString().trimEnd('/'))
                .replace("https://1.piratebays.to", tpbServer.url("").toString().trimEnd('/'))

            val engine = JsPluginEngine(OkHttpClient())
            val results = mutableListOf<TorrentResult>()
            val outcome = engine.runSearch(patchedScript, query = "Movie", year = null, season = null, episode = null, onResult = { results.add(it) })

            assertTrue(outcome is SearchResult.Finished)
            assertEquals(2, results.size)
            // Sorted by seeders desc: TPB (150) before Knaben (10).
            assertEquals("ThePirateBay", results[0].source)
            assertEquals(150, results[0].seeders)
            assertEquals("1080p", results[0].quality)
            assertTrue(results[0].token.startsWith("magnet:"))
            assertEquals("Knaben", results[1].source)
            assertEquals(10, results[1].seeders)
            assertEquals("720p", results[1].quality)
        } finally {
            knabenServer.shutdown()
            tpbServer.shutdown()
        }
    }

    @Test
    fun `search filters out results not matching the title`() = runBlocking {
        val knabenServer = MockWebServer()
        val tpbServer = MockWebServer()
        knabenServer.enqueue(MockResponse().setBody(knabenHtml))
        tpbServer.enqueue(MockResponse().setBody(tpbHtml))
        tpbServer.enqueue(MockResponse().setBody("<table></table>")) // page 2 - empty, stops pagination
        knabenServer.start()
        tpbServer.start()
        try {
            val patchedScript = script
                .replace("https://knaben.org", knabenServer.url("").toString().trimEnd('/'))
                .replace("https://1.piratebays.to", tpbServer.url("").toString().trimEnd('/'))

            val engine = JsPluginEngine(OkHttpClient())
            val results = mutableListOf<TorrentResult>()
            engine.runSearch(patchedScript, query = "Completely Unrelated Title", year = null, season = null, episode = null, onResult = { results.add(it) })

            assertTrue(results.isEmpty())
        } finally {
            knabenServer.shutdown()
            tpbServer.shutdown()
        }
    }

    @Test
    fun `search appends season episode tag and filters by it`() = runBlocking {
        val knabenServer = MockWebServer()
        val tpbServer = MockWebServer()
        val episodeHtml = """
            <table><tbody>
              <tr>
                <td>1</td>
                <td class="text-wrap"><a href="magnet:?xt=urn:btih:CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" title="Show Name S02E05 1080p WEB-DL">Show Name S02E05 1080p WEB-DL</a></td>
                <td>1.0 GB</td>
                <td>TV</td>
                <td>20</td>
                <td>1</td>
                <td>1 day ago</td>
              </tr>
            </tbody></table>
        """.trimIndent()
        knabenServer.enqueue(MockResponse().setBody(episodeHtml))
        tpbServer.enqueue(MockResponse().setBody("<table></table>"))
        knabenServer.start()
        tpbServer.start()
        try {
            val patchedScript = script
                .replace("https://knaben.org", knabenServer.url("").toString().trimEnd('/'))
                .replace("https://1.piratebays.to", tpbServer.url("").toString().trimEnd('/'))

            val engine = JsPluginEngine(OkHttpClient())
            val results = mutableListOf<TorrentResult>()
            engine.runSearch(patchedScript, query = "Show Name", year = null, season = 2, episode = 5, onResult = { results.add(it) })

            assertEquals(1, results.size)
            assertEquals("magnet:?xt=urn:btih:CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC", results[0].token)
        } finally {
            knabenServer.shutdown()
            tpbServer.shutdown()
        }
    }
}
