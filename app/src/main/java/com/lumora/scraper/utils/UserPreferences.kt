package com.lumora.scraper.utils

import android.content.Context
import android.content.SharedPreferences
import com.lumora.scraper.ScraperApp
import com.lumora.scraper.providers.Provider
import com.lumora.scraper.providers.TmdbProvider
import org.json.JSONObject

/**
 * Settings the ported scrapers read. Rewritten for Lumora rather than copied: upstream's version
 * was ~570 lines covering player, subtitles, theme, parental control and cloud sync - all of
 * which Lumora already owns elsewhere. Only the members the scrapers themselves touch are here,
 * under their original names so the ported files need no edits.
 *
 * Backed by its own SharedPreferences file, deliberately separate from Lumora's: these are
 * per-site scraper state (rotating domains, per-provider scratch cache) with a very different
 * lifetime from user settings, and clearing them should never risk taking a provider config
 * with it.
 *
 * Every accessor tolerates being read before [init] - a scraper coroutine can outlive the
 * Activity that started it, and returning the default beats throwing from inside a fetch.
 */
object UserPreferences {

    private const val PREFS_NAME = "lumora_scraper_prefs"

    // Rotating-domain defaults. These sites move hosts every few months; the stored value wins
    // once a provider's own onChangeUrl() has resolved a live one.
    private const val DEFAULT_SERIENSTREAM_DOMAIN = "s.to"
    private const val DEFAULT_MOFLIX_DOMAIN = "moflix-stream.xyz"
    private const val DEFAULT_STREAMINGCOMMUNITY_DOMAIN = "streamingunity.cc"
    private const val DEFAULT_CUEVANA_DOMAIN = "cuevana.gs"
    private const val DEFAULT_POSEIDON_DOMAIN = "www.poseidonhd2.co"

    private const val DEFAULT_DOH_PROVIDER_URL = "https://cloudflare-dns.com/dns-query"

    /** Keys a provider may stash under its own name via [setProviderCache]. */
    const val PROVIDER_URL = "URL"
    const val PROVIDER_LOGO = "LOGO"
    const val PROVIDER_PORTAL_URL = "PORTAL_URL"
    const val PROVIDER_AUTOUPDATE = "AUTOUPDATE_URL"
    const val PROVIDER_NEW_INTERFACE = "NEW_INTERFACE"
    const val PROVIDER_PREFERRED_SERVER = "PREFERRED_SERVER"

    private const val KEY_CURRENT_PROVIDER = "current_provider"
    private const val KEY_PROVIDER_LANGUAGE = "provider_language"
    private const val KEY_PROVIDER_CACHE = "provider_cache"
    private const val KEY_DOH_PROVIDER_URL = "doh_provider_url"
    private const val KEY_TMDB_API_KEY = "tmdb_api_key"
    private const val KEY_ENABLE_TMDB = "enable_tmdb"
    private const val KEY_SERVER_AUTO_SUBTITLES_DISABLED = "server_auto_subtitles_disabled"
    private const val KEY_AUTO_UPDATE_PLUGINS = "auto_update_plugins"
    private const val KEY_BYPASS_WS_ADVERTISED_HOST = "bypass_ws_advertised_host"
    private const val KEY_STREAMINGCOMMUNITY_DOMAIN = "streamingcommunity_domain"
    private const val KEY_SERIENSTREAM_DOMAIN = "serienstream_domain"
    private const val KEY_CUEVANA_DOMAIN = "cuevana_domain"
    private const val KEY_POSEIDON_DOMAIN = "poseidon_domain"
    private const val KEY_MOFLIX_DOMAIN = "moflix_domain"

    @Volatile
    private var prefsOrNull: SharedPreferences? = null

    /**
     * Parsed form of [KEY_PROVIDER_CACHE]: `{ "<provider name>": { "<key>": "<value>" } }`. Held
     * in memory because providers read it on nearly every request (a rotating base URL is
     * consulted per call) and re-parsing the JSON each time showed up in fetch latency upstream.
     */
    private var providerCache: JSONObject = JSONObject()

    private val prefs: SharedPreferences?
        get() = prefsOrNull ?: synchronized(this) {
            prefsOrNull ?: run {
                if (!ScraperApp.isReady) return@run null
                ScraperApp.instance
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .also {
                        prefsOrNull = it
                        providerCache = runCatching {
                            JSONObject(it.getString(KEY_PROVIDER_CACHE, null) ?: "{}")
                        }.getOrDefault(JSONObject())
                    }
            }
        }

    private fun getString(key: String): String? = prefs?.getString(key, null)

    private fun setString(key: String, value: String?) {
        prefs?.edit()?.apply {
            if (value == null) remove(key) else putString(key, value)
        }?.apply()
    }

    private fun domain(key: String, default: String): String =
        getString(key)?.takeIf { it.isNotEmpty() } ?: default

    /**
     * The scraper the user is browsing. Upstream this was the app's single global mode; here it
     * is which provider the scraper browse surface is currently pointed at, which is still a
     * single value because a scraper catalog is paginated and cannot be merged the way Lumora's
     * IPTV providers are.
     *
     * `TmdbProvider` is reconstructed from its name because it is the one provider that is
     * parameterised by language rather than being a singleton in [Provider.providers].
     */
    var currentProvider: Provider?
        get() {
            val providerName = getString(KEY_CURRENT_PROVIDER)
            if (providerName?.startsWith("TMDb (") == true && providerName.endsWith(")")) {
                return TmdbProvider(providerName.substringAfter("TMDb (").substringBefore(")"))
            }
            return Provider.providers.keys.find { it.name == providerName }
        }
        set(value) {
            setString(KEY_CURRENT_PROVIDER, value?.name)
            ProviderChangeNotifier.notifyProviderChanged()
        }

    /** Language filter some multi-language providers apply to their own catalog. */
    var providerLanguage: String?
        get() = getString(KEY_PROVIDER_LANGUAGE)
        set(value) = setString(KEY_PROVIDER_LANGUAGE, value)

    fun getProviderCache(provider: Provider, key: String): String {
        prefs ?: return ""
        return providerCache.optJSONObject(provider.name)?.optString(key).orEmpty()
    }

    fun setProviderCache(provider: Provider?, key: String, value: String) {
        prefs ?: return
        val providerName = provider?.name ?: currentProvider?.name ?: return
        val inner = providerCache.optJSONObject(providerName)
            ?: JSONObject().also { providerCache.put(providerName, it) }
        inner.put(key, value)
        setString(KEY_PROVIDER_CACHE, providerCache.toString())
    }

    fun clearProviderCache(providerName: String) {
        prefs ?: return
        if (providerCache.has(providerName)) {
            providerCache.remove(providerName)
            setString(KEY_PROVIDER_CACHE, providerCache.toString())
        }
    }

    /**
     * DNS-over-HTTPS endpoint the scraper OkHttp stack resolves through. Empty string means
     * system DNS - see [DnsResolver], which falls back to system per-lookup regardless.
     */
    var dohProviderUrl: String
        get() = getString(KEY_DOH_PROVIDER_URL) ?: DEFAULT_DOH_PROVIDER_URL
        set(value) = setString(KEY_DOH_PROVIDER_URL, value)

    /** Overrides Lumora's own TMDB key for scraper metadata lookups when the user sets one. */
    var tmdbApiKey: String
        get() = getString(KEY_TMDB_API_KEY) ?: ""
        set(value) = setString(KEY_TMDB_API_KEY, value.trim())

    /** Whether scrapers may enrich their results with TMDB artwork and overviews. */
    var enableTmdb: Boolean
        get() = prefs?.getBoolean(KEY_ENABLE_TMDB, true) ?: true
        set(value) {
            prefs?.edit()?.putBoolean(KEY_ENABLE_TMDB, value)?.apply()
        }

    /**
     * When true, a subtitle track a host marks as its own default is not auto-enabled. Several
     * hosts default to a burned-in-language track the user did not ask for.
     */
    var serverAutoSubtitlesDisabled: Boolean
        get() = prefs?.getBoolean(KEY_SERVER_AUTO_SUBTITLES_DISABLED, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_SERVER_AUTO_SUBTITLES_DISABLED, value)?.apply()
        }

    /**
     * Whether every installed plugin script is silently re-fetched from its store and
     * reinstalled in place on launch. Off by default - a plugin is arbitrary JS the user chose
     * to run, so picking up a new version of it without being asked is an opt-in, not a default.
     */
    var autoUpdatePlugins: Boolean
        get() = prefs?.getBoolean(KEY_AUTO_UPDATE_PLUGINS, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_AUTO_UPDATE_PLUGINS, value)?.apply()
        }

    /**
     * Host/IP this device advertises in the QR code for the phone-assisted challenge bypass.
     * Empty means "work it out from the active network interface" - only needed when the TV has
     * several interfaces and picks the wrong one (see [BypassWebSocketEndpointHelper]).
     */
    var bypassWsAdvertisedHost: String
        get() = getString(KEY_BYPASS_WS_ADVERTISED_HOST) ?: ""
        set(value) = setString(KEY_BYPASS_WS_ADVERTISED_HOST, value.trim())

    var streamingcommunityDomain: String
        get() = domain(KEY_STREAMINGCOMMUNITY_DOMAIN, DEFAULT_STREAMINGCOMMUNITY_DOMAIN)
        set(value) = setString(KEY_STREAMINGCOMMUNITY_DOMAIN, value)

    var serienstreamDomain: String
        get() = domain(KEY_SERIENSTREAM_DOMAIN, DEFAULT_SERIENSTREAM_DOMAIN)
        set(value) = setString(KEY_SERIENSTREAM_DOMAIN, value)

    var cuevanaDomain: String
        get() = domain(KEY_CUEVANA_DOMAIN, DEFAULT_CUEVANA_DOMAIN)
        set(value) = setString(KEY_CUEVANA_DOMAIN, value)

    var poseidonDomain: String
        get() = domain(KEY_POSEIDON_DOMAIN, DEFAULT_POSEIDON_DOMAIN)
        set(value) = setString(KEY_POSEIDON_DOMAIN, value)

    var moflixDomain: String
        get() = domain(KEY_MOFLIX_DOMAIN, DEFAULT_MOFLIX_DOMAIN)
        set(value) = setString(KEY_MOFLIX_DOMAIN, value)
}
