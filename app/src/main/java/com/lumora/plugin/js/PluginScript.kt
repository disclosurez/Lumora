package com.lumora.plugin.js

/**
 * A JS plugin script found by [PluginScriptManager.discoverScripts] - replaces
 * [com.lumora.plugin.InstalledPlugin]. Everything downstream (dialogs, candidate/result
 * rendering) keeps using the same [com.lumora.plugin.DiscoveredProvider]/
 * [com.lumora.plugin.TorrentResult]/etc. shapes; only this and the manager/engine classes are
 * new.
 *
 * Nothing ships built into Lumora - every script, including the ones in Lumora's own default
 * plugin store, lives in `filesDir/plugin_scripts/` and only gets there because the user
 * explicitly installed it (browsing a store, or pasting/fetching a `.js` URL directly). There is
 * deliberately no "trusted because it's in the APK" tier: label/description text always comes
 * from the script the user chose to install, or from a store's catalog before that - never
 * hardcoded in Lumora itself.
 */
data class PluginScript(
    val fileName: String,
    /** Stable id from the script's `PLUGIN.id`, falling back to its filename. */
    val id: String,
    val label: String,
    val description: String?,
    val capabilities: Set<String>,
    val enabled: Boolean,
    /**
     * True for a `stream_search` script whose `PLUGIN.resolvesNatively` is set (e.g.
     * torrent-search.js) - its results are resolved by a native engine
     * ([com.lumora.torrent.TorrentEngine]) instead of calling the script's own `resolve()`,
     * because a JS script can't run a persistent local HTTP server or hold a libtorrent session
     * open the way a companion process used to.
     */
    val resolvesNatively: Boolean = false,
    /**
     * Optional self-declared `PLUGIN.contentTypes` (e.g. `["anime"]`) - lets Lumora pick between
     * several enabled `stream_search` scripts by what a title actually is instead of an
     * arbitrary pick (see [com.lumora.MainActivity.enabledStreamSearchPlugin]), without Lumora
     * ever hardcoding which specific plugin handles what. Empty means general-purpose/unspecified.
     */
    val contentTypes: Set<String> = emptySet(),
) {
    val supportsDiscovery: Boolean
        get() = JsPluginContract.CAPABILITY_PROVIDER_DISCOVERY in capabilities

    val supportsStreamSearch: Boolean
        get() = JsPluginContract.CAPABILITY_STREAM_SEARCH in capabilities
}
