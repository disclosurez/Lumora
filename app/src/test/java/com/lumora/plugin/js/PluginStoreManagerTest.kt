package com.lumora.plugin.js

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Minimal in-memory SharedPreferences - this project has no Robolectric, and PluginStoreManager
 *  only needs getStringSet/edit/putStringSet/apply, so a hand-rolled fake is simplest. */
private class FakeSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (data[key] as? MutableSet<String>) ?: defValues

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
            pending[key] = values?.toMutableSet(); return this
        }
        override fun apply() { data.putAll(pending) }
        override fun commit(): Boolean { data.putAll(pending); return true }
        override fun putString(key: String, value: String?) = this.also { pending[key] = value }
        override fun putInt(key: String, value: Int) = this.also { pending[key] = value }
        override fun putLong(key: String, value: Long) = this.also { pending[key] = value }
        override fun putFloat(key: String, value: Float) = this.also { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = this.also { pending[key] = value }
        override fun remove(key: String) = this.also { pending[key] = null }
        override fun clear() = this.also { data.clear() }
    }

    override fun getAll(): MutableMap<String, *> = data
    override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
    override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = data.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

class PluginStoreManagerTest {

    @Test
    fun `default store is always present and not removable`() {
        val manager = PluginStoreManager(FakeSharedPreferences())
        val stores = manager.storeUrls()
        assertEquals(1, stores.size)
        assertEquals(PluginStoreManager.DEFAULT_STORE_URL, stores[0].url)
        assertFalse(stores[0].removable)
    }

    @Test
    fun `added stores are listed and removable, default cannot be removed`() {
        val manager = PluginStoreManager(FakeSharedPreferences())
        manager.addStore("https://example.com/plugins/index.json")
        var stores = manager.storeUrls()
        assertEquals(2, stores.size)
        assertTrue(stores.any { it.url == "https://example.com/plugins/index.json" && it.removable })

        manager.removeStore(PluginStoreManager.DEFAULT_STORE_URL)
        assertEquals(2, manager.storeUrls().size) // no-op, default survives

        manager.removeStore("https://example.com/plugins/index.json")
        stores = manager.storeUrls()
        assertEquals(1, stores.size)
    }

    @Test
    fun `fetchCatalog parses a catalog and resolves relative file urls`() = runBlocking {
        val server = MockWebServer()
        val catalogJson = """
            {
              "name": "Test Store",
              "scripts": [
                {"id": "a.b", "label": "A B", "description": "desc", "capabilities": ["stream_search"], "file": "a.js"},
                {"id": "c.d", "file": "https://elsewhere.example/c.js"}
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(catalogJson))
        server.start()
        try {
            val manager = PluginStoreManager(FakeSharedPreferences(), OkHttpClient())
            val indexUrl = server.url("/plugins/index.json").toString()
            val result = manager.fetchCatalog(indexUrl)
            val scripts = result.getOrNull()!!
            assertEquals(2, scripts.size)
            assertEquals("A B", scripts[0].label)
            assertEquals(setOf("stream_search"), scripts[0].capabilities)
            assertEquals(server.url("/plugins/a.js").toString(), scripts[0].fileUrl)
            assertEquals("https://elsewhere.example/c.js", scripts[1].fileUrl)
            assertEquals("c.d", scripts[1].label) // falls back to id when label absent
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `fetchCatalog fails gracefully on a 404`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()
        try {
            val manager = PluginStoreManager(FakeSharedPreferences(), OkHttpClient())
            val result = manager.fetchCatalog(server.url("/missing.json").toString())
            assertTrue(result.isFailure)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `fetchCatalog parses the real Lumora-Plugins index catalog`() = runBlocking {
        // Mirrors Lumora-Plugins/scripts/index.json (the default store's actual catalog) - not
        // read from that sibling repo directly, since this repo should build standalone. Keep
        // this in sync if that file's script list changes; it's a regression guard against a
        // schema-breaking edit there going unnoticed (see that repo's README).
        val indexJson = """
            {
              "name": "Lumora Plugins",
              "scripts": [
                {
                  "id": "anime.senshi",
                  "label": "Anime (Senshi)",
                  "description": "Searches AniList for anime, resolves streams from senshi.live.",
                  "capabilities": ["stream_search"],
                  "file": "anime-senshi.js"
                },
                {
                  "id": "reddit.iptvscan",
                  "label": "Reddit IPTV Scanner",
                  "description": "Scans r/IPTV_ZONENEW for public IPTV credential pastes and proposes working ones.",
                  "capabilities": ["provider_discovery"],
                  "file": "redditscan.js"
                },
                {
                  "id": "torrent.search",
                  "label": "Torrent Search",
                  "description": "Searches public torrent indexers for a title and streams the result via Lumora's built-in torrent engine.",
                  "capabilities": ["stream_search"],
                  "file": "torrent-search.js"
                }
              ]
            }
        """.trimIndent()
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(indexJson))
        server.start()
        try {
            val manager = PluginStoreManager(FakeSharedPreferences(), OkHttpClient())
            val indexUrl = server.url("/scripts/index.json").toString()
            val scripts = manager.fetchCatalog(indexUrl).getOrNull()!!
            assertEquals(setOf("anime.senshi", "reddit.iptvscan", "torrent.search"), scripts.map { it.id }.toSet())
            val torrentSearch = scripts.first { it.id == "torrent.search" }
            assertEquals(server.url("/scripts/torrent-search.js").toString(), torrentSearch.fileUrl)
            assertEquals(setOf("stream_search"), torrentSearch.capabilities)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `fetchScriptText returns the body`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("PLUGIN = { id: \"x\" };"))
        server.start()
        try {
            val manager = PluginStoreManager(FakeSharedPreferences(), OkHttpClient())
            val text = manager.fetchScriptText(server.url("/x.js").toString())
            assertEquals("PLUGIN = { id: \"x\" };", text)
        } finally {
            server.shutdown()
        }
    }
}
