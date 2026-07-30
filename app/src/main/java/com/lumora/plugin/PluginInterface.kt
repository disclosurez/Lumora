package com.lumora.plugin

/**
 * Data models shared by the plugin system - transport-agnostic, so they survived the migration
 * from companion-APK/Messenger plugins to in-process JS plugins unchanged (see
 * [com.lumora.plugin.js.JsPluginContract] for the current contract and
 * [com.lumora.plugin.js.PluginScript] for the current "plugin found" model, which replaced
 * the old package/Messenger-based `InstalledPlugin`).
 */

/**
 * One source option a stream-search plugin returned for a title. [token] is opaque to the
 * host - it identifies the source to the plugin and is handed straight back to resolve it.
 * Everything else is display-only and untrusted (length-capped before it reaches the UI).
 */
data class TorrentResult(
    val title: String,
    /** Opaque plugin-side handle (a magnet, in practice) - never parsed or shown by the host. */
    val token: String,
    val seeders: Int?,
    val size: String?,
    val quality: String?,
    val source: String?
)

/** Terminal outcome of the search phase. */
sealed class SearchResult {
    data class Finished(val message: String?) : SearchResult()
    data class Failed(val message: String) : SearchResult()
}

/** Outcome of resolving one [TorrentResult] to something playable. */
sealed class ResolveResult {
    /** [url] is a validated http/https URL for the host's player. [headers] are extra request
     *  headers the stream needs (e.g. a Referer for a hotlink-protected CDN), empty if none. */
    data class Ready(val url: String, val headers: Map<String, String> = emptyMap()) : ResolveResult()
    data class Failed(val message: String) : ResolveResult()
}

/**
 * A provider a plugin proposes adding. Nothing here is trusted: the type is checked against
 * [com.lumora.plugin.js.JsPluginContract.SUPPORTED_PROVIDER_TYPES], the URL against http/https,
 * and every string is length-capped before any of it reaches the UI (see
 * [com.lumora.plugin.js.JsHostImpl]). It becomes a real IptvProviderConfig only when the user
 * confirms it.
 */
data class DiscoveredProvider(
    /** "m3u", "xtream" or "stalker". */
    val type: String,
    val label: String,
    val url: String,
    val username: String? = null,
    val password: String? = null,
    /** Stalker MAC / M3U custom User-Agent. */
    val userAgent: String? = null,
    /** Free-text line under the label - expiry, channel count, whatever the plugin knows. */
    val detail: String? = null,
    /** The plugin claims it tested these credentials. Displayed as its claim, not as fact. */
    val verified: Boolean = false
)

/** Terminal outcome of one discovery run. */
sealed class DiscoveryResult {
    /** The plugin finished on its own. [message] is its summary line, if it sent one. */
    data class Finished(val message: String?) : DiscoveryResult()
    /** The plugin reported a failure, or the host gave up on it (timeout, bind failure, death). */
    data class Failed(val message: String) : DiscoveryResult()
}
