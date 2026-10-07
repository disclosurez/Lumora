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
    // The app-wide client (engines/managers share it), not a private one per manager: a
    // store fetch otherwise gets its own connection pool that is never reused or closed.
    // Resolved lazily rather than as a constructor default - that would touch
    // BaseApplication.instance at construction, which a plain JVM test (no Application)
    // cannot provide even when it never fetches. Unit tests pass their own client.
    private val explicitHttpClient: OkHttpClient? = null,
) {
    private val httpClient: OkHttpClient
        get() = explicitHttpClient ?: com.lumora.BaseApplication.instance.okHttpClient
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
            // Resolve relative `file` names against the store URL's own directory, via URI
            // so a store hosted at the bare origin (no path at all) doesn't turn "a.js"
            // into "https://a.js". Falls back to the old string split if the URL isn't a
            // parseable URI.
            val baseUrl = runCatching { java.net.URI(storeUrl).resolve(".").toString() }
                .getOrElse { storeUrl.substringBeforeLast('/', "") + "/" }
            val result = mutableListOf<StoreScript>()
            scriptsArray?.forEach { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val id = item.optString("id")?.takeIf { it.isNotBlank() } ?: return@forEach
                val file = item.optString("file")?.takeIf { it.isNotBlank() } ?: return@forEach
                // Each element is checked before asString: JsonNull/non-primitive elements
                // throw UnsupportedOperationException, and that used to fail the whole
                // catalog fetch because it sits inside the outer runCatching.
                val capabilities = item.get("capabilities")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { cap ->
                        cap.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString?.takeIf(String::isNotBlank)
                    }
                    ?.toSet()
                    .orEmpty()
                result.add(
                    StoreScript(
                        id = id,
                        label = item.optString("label")?.takeIf { it.isNotBlank() } ?: id,
                        description = item.optString("description")?.takeIf { it.isNotBlank() },
                        capabilities = capabilities,
                        fileUrl = if (file.startsWith("http://") || file.startsWith("https://")) file
                        else runCatching { java.net.URI(baseUrl).resolve(file).toString() }
                            .getOrElse { baseUrl + file },
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
            if (!response.isSuccessful) return null
            // Bounded, like the JS host's own reads (see JsHostImpl.MAX_RESPONSE_BYTES):
            // a store catalog or script is user-supplied input, and an unbounded
            // body.string() on the TV sticks this app targets can OOM the process.
            var total = 0L
            val buffer = java.io.ByteArrayOutputStream()
            response.body?.byteStream()?.use { input ->
                val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) return null
                    buffer.write(chunk, 0, read)
                }
            }
            buffer.toString("UTF-8")
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val PREF_STORE_URLS = "plugin_store_urls"
        const val DEFAULT_STORE_URL = "https://raw.githubusercontent.com/disclosurez/Lumora-Plugins/master/scripts/index.json"
        /** Same per-response cap the JS host applies to script-visible HTTP (JsHostImpl). */
        private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    }
}
