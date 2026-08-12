package com.lumora.scraper.bridge

import android.content.Context
import android.util.Log
import com.lumora.scraper.utils.UserPreferences
import org.json.JSONObject

/**
 * Which scraper sites are allowed to run, supplied entirely by the `scraper_sites` plugin script
 * (`scraper-sites.js`).
 *
 * The engine - every per-site parser, all 86 extractors, the Cloudflare bypass - is compiled into
 * the app, because none of it can be expressed as data: it needs a real WebView, the WebView's
 * cookie store shared with OkHttp, and typed HTTP clients. But a compiled-in parser is inert on
 * its own. Nothing decides to *use* it except this list, and the list ships as a plugin.
 *
 * **No manifest means no sites.** Not "all sites" - none. This is the gate, so a build with no
 * `scraper_sites` script installed and enabled has no scraper sources at all: Find Stream falls
 * back to whatever `stream_search` plugin is installed, or is hidden. Nothing is bundled in
 * assets as a fallback, deliberately - a fallback would make the plugin cosmetic, since the sites
 * would already be running before it was installed.
 *
 * Within a loaded manifest the semantics are the ones the script documents: a site named here
 * that this build has no provider for is ignored (so the script can be updated ahead of an app
 * release), and `enabled: false` is a kill switch for something broken for everyone. That kill
 * switch is deliberately not symmetric with the user's own per-site toggles - [ScraperSiteStore]
 * treats a user's "off" as final, so an update here can never switch a site back on for someone
 * who turned it off.
 *
 * This class only *applies* a manifest; it does not go and get one. Fetching means running a JS
 * plugin, which belongs to the app's plugin layer - see `MainActivity.loadScraperSiteManifest()`.
 */
object ScraperSiteManifest {

    private const val TAG = "ScraperSiteManifest"

    /**
     * Provider names the current manifest permits. Null - not empty - until one has been applied,
     * so "no script installed" and "a script that allows nothing" stay distinguishable.
     */
    @Volatile
    private var allowed: Set<String>? = null

    /** Per-site facts the search uses to decide what is worth querying, and in what order. */
    data class SiteInfo(
        val language: String,
        /** Anime-only catalogue - cannot answer for a live-action title. */
        val anime: Boolean,
        /** Search goes through the Cloudflare WebView bypass; held back to a second wave. */
        val heavy: Boolean,
    )

    @Volatile
    private var info: Map<String, SiteInfo> = emptyMap()

    fun infoFor(name: String): SiteInfo? = info[name]

    /** True once a manifest has been applied. False means the scrapers are switched off entirely. */
    val isLoaded: Boolean
        get() = allowed != null

    /** Whether the manifest permits this provider. False for everything while none is loaded. */
    fun allows(providerName: String): Boolean = allowed?.contains(providerName) == true

    /** "https://host/path" -> "host". The settings-backed domains store a bare host. */
    private fun String.hostOnly(): String =
        substringAfter("://").substringBefore('/')

    /**
     * Forgets the current manifest, taking every scraper site out of service.
     *
     * Called when the `scraper_sites` script is disabled or removed. Without it the sites the
     * script authorised would keep running for the rest of the process, which would make
     * disabling the plugin look like it had done nothing.
     */
    fun clear() {
        allowed = null
        info = emptyMap()
        ScraperHosts.clear()
        Log.i(TAG, "Site list cleared - scraper sources are off")
    }

    /**
     * Applies a manifest produced by a `scraper_sites` script. Returns true if it parsed.
     *
     * Parsed in full before anything is changed, so a script that returns junk leaves the
     * previously applied list in place rather than wiping it.
     */
    fun applyJson(context: Context, json: String): Boolean {
        val manifest = runCatching { JSONObject(json) }.getOrNull()
        val sites = manifest?.optJSONArray("sites")
        if (sites == null) {
            Log.w(TAG, "Site list is not a manifest; keeping the previous one")
            return false
        }

        val permitted = mutableSetOf<String>()
        val hosts = mutableMapOf<String, String>()
        val details = mutableMapOf<String, SiteInfo>()
        var killed = 0
        for (i in 0 until sites.length()) {
            val site = sites.optJSONObject(i) ?: continue
            val name = site.optString("name").takeIf { it.isNotBlank() } ?: continue

            // The host is what makes a compiled-in parser usable, so a site without one is not
            // permitted however its `enabled` flag reads - it has nowhere to send a request.
            val baseUrl = site.optString("baseUrl").trimEnd('/').takeIf { it.isNotBlank() }
            if (baseUrl == null) {
                Log.w(TAG, "Skipping $name: the manifest gives it no baseUrl")
                killed++
                continue
            }
            if (!site.optBoolean("enabled", true)) {
                killed++
                continue
            }
            permitted += name
            hosts[name] = baseUrl
            details[name] = SiteInfo(
                language = site.optString("language").ifBlank { "en" },
                anime = site.optBoolean("anime", false),
                heavy = site.optBoolean("heavy", false),
            )

            // A few sites keep their host in settings as well, because their own provider code
            // resolves a live domain at runtime and writes it back. Seeding those from the
            // manifest keeps the two in step on a fresh install.
            when (site.optString("domainKey")) {
                "" -> Unit
                "streamingcommunity" -> UserPreferences.streamingcommunityDomain = baseUrl.hostOnly()
                "serienstream" -> UserPreferences.serienstreamDomain = baseUrl.hostOnly()
                "cuevana" -> UserPreferences.cuevanaDomain = baseUrl.hostOnly()
                "poseidon" -> UserPreferences.poseidonDomain = baseUrl.hostOnly()
                "moflix" -> UserPreferences.moflixDomain = baseUrl.hostOnly()
                else -> Log.w(TAG, "Unknown domainKey on $name; its baseUrl still applies")
            }
        }
        allowed = permitted
        info = details
        ScraperHosts.set(hosts)
        Log.i(
            TAG,
            "Site list v${manifest.optInt("version")} applied - ${permitted.size} on, $killed off"
        )
        return true
    }
}
