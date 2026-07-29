package com.lumora.plugin

import android.content.ComponentName

/**
 * Models for the plugin system. The contract itself - what a plugin declares and how the host
 * talks to it - lives in [PluginContract].
 *
 * There is deliberately no cross-APK Kotlin interface here any more: a plugin runs in its own
 * process with its own class loader, so an interface the host declares is something a plugin
 * can never actually implement. The Messenger protocol is the whole API surface.
 */

/** A plugin service found on the device by [PluginManager.discoverPlugins]. */
data class InstalledPlugin(
    val packageName: String,
    /** The Service to bind - a package may in principle export more than one. */
    val component: ComponentName,
    /** Self-declared id from meta-data, falling back to the package name. */
    val pluginId: String,
    val label: String,
    val description: String?,
    val versionName: String,
    val versionCode: Int,
    /** Declared capabilities, filtered to the ones this host understands. */
    val capabilities: Set<String>,
    /** Plugins are opt-in: nothing is bound or run until the user enables it. */
    val enabled: Boolean
) {
    val supportsDiscovery: Boolean
        get() = PluginContract.CAPABILITY_PROVIDER_DISCOVERY in capabilities

    val supportsStreamSearch: Boolean
        get() = PluginContract.CAPABILITY_STREAM_SEARCH in capabilities
}

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
    /** [url] is a validated http/https URL for the host's player. */
    data class Ready(val url: String) : ResolveResult()
    data class Failed(val message: String) : ResolveResult()
}

/**
 * A provider a plugin proposes adding. Nothing here is trusted: the type is checked against
 * [PluginContract.SUPPORTED_PROVIDER_TYPES], the URL against http/https, and every string is
 * length-capped before any of it reaches the UI (see PluginClient). It becomes a real
 * IptvProviderConfig only when the user confirms it.
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
