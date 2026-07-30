package com.lumora.plugin.js

import android.content.SharedPreferences
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Tracks which plugin stores are configured (the default one plus any the user added) and can
 * fetch a store's catalog / download one of its scripts. Doesn't touch script storage itself -
 * installing a fetched script goes through [PluginScriptManager.installScript].
 *
 * Catalog schema (a small static JSON file, e.g. `scripts/index.json` in a plugin repo):
 * ```json
 * {
 *   "name": "Lumora Plugins",
 *   "scripts": [
 *     {
 *       "id": "anime.senshi",
 *       "label": "Anime (Senshi)",
 *       "description": "...",
 *       "capabilities": ["stream_search"],
 *       "file": "anime-senshi.js"
 *     }
 *   ]
 * }
 * ```
 * `file` may be a bare filename (resolved relative to the catalog URL's own directory - the
 * common case, since a store is usually just this JSON file sitting next to its scripts) or an
 * absolute `http(s)://` URL.
 */
class PluginStoreManager(
    private val prefs: SharedPreferences,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    fun storeUrls(): List<PluginStore> {
        val custom = customStoreUrls()
        val stores = mutableListOf(PluginStore(url = DEFAULT_STORE_URL, name = "Lumora Plugins", removable = false))
        custom.filterNot { it == DEFAULT_STORE_URL }.forEach { stores.add(PluginStore(url = it, name = null, removable = true)) }
        return stores
    }

    fun addStore(url: String) {
        if (url == DEFAULT_STORE_URL) return
        val current = customStoreUrls().toMutableSet()
        current.add(url)
        prefs.edit().putStringSet(PREF_STORE_URLS, current).apply()
    }

    fun removeStore(url: String) {
        if (url == DEFAULT_STORE_URL) return
        val current = customStoreUrls().toMutableSet()
        current.remove(url)
        prefs.edit().putStringSet(PREF_STORE_URLS, current).apply()
    }

    private fun customStoreUrls(): Set<String> = prefs.getStringSet(PREF_STORE_URLS, emptySet()) ?: emptySet()

    /**
     * Fetches and parses a store's catalog. Never throws - failures come back as [Result.failure].
     *
     * Uses Gson rather than `org.json` deliberately: `org.json` classes resolve to the Android
     * SDK's unmocked stub jar on a desktop JVM (throws `RuntimeException: ... not mocked` outside
     * Robolectric), so parsing logic built on it can't run under this project's plain JVM unit
     * tests. Gson is a real, pure-Java library (already a project dependency) with identical
     * behavior on-device and under test.
     */
    suspend fun fetchCatalog(storeUrl: String): Result<List<StoreScript>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = fetchText(storeUrl) ?: error("Couldn't reach that store")
            val json = JsonParser.parseString(body).asJsonObject
            val scriptsArray = json.getAsJsonArray("scripts")
            val baseUrl = storeUrl.substringBeforeLast('/', "") + "/"
            val result = mutableListOf<StoreScript>()
            scriptsArray?.forEach { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val id = item.optString("id")?.takeIf { it.isNotBlank() } ?: return@forEach
                val file = item.optString("file")?.takeIf { it.isNotBlank() } ?: return@forEach
                val capabilities = item.get("capabilities")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { it.asString?.takeIf(String::isNotBlank) }
                    ?.toSet()
                    .orEmpty()
                result.add(
                    StoreScript(
                        id = id,
                        label = item.optString("label")?.takeIf { it.isNotBlank() } ?: id,
                        description = item.optString("description")?.takeIf { it.isNotBlank() },
                        capabilities = capabilities,
                        fileUrl = if (file.startsWith("http://") || file.startsWith("https://")) file else baseUrl + file,
                    )
                )
            }
            result
        }
    }

    /** The store's self-declared name, if its catalog has been fetched successfully. */
    suspend fun fetchStoreName(storeUrl: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = fetchText(storeUrl) ?: return@withContext null
            JsonParser.parseString(body).asJsonObject.optString("name")?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun com.google.gson.JsonObject.optString(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

    suspend fun fetchScriptText(fileUrl: String): String? = withContext(Dispatchers.IO) { fetchText(fileUrl) }

    private fun fetchText(url: String): String? = try {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val PREF_STORE_URLS = "plugin_store_urls"
        const val DEFAULT_STORE_URL = "https://raw.githubusercontent.com/disclosurez/Lumora-Plugins/master/scripts/index.json"
    }
}
