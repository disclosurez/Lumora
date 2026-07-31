package com.lumora.plugin.js

/**
 * Contract for in-process JavaScript plugins, replacing the old Messenger/APK protocol in
 * [com.lumora.plugin.PluginContract]. A plugin is a `.js` text file the user installs (browsing a
 * plugin store, or pasting/fetching a URL directly) and which then runs in its own
 * [com.whl.quickjs.wrapper.QuickJSContext] - no separate app, no IPC, no install step beyond that.
 *
 * A script declares itself with a top-level `PLUGIN` object:
 * ```js
 * PLUGIN = { id: "my.plugin", label: "My Plugin", description: "...", capabilities: ["stream_search"] };
 * ```
 *
 * A `stream_search` script can optionally add `contentTypes: ["anime"]` (or omit it, meaning
 * general-purpose) - with more than one enabled `stream_search` script, Lumora uses this to pick
 * the one that actually matches a title instead of an arbitrary one (see
 * [com.lumora.plugin.js.PluginScript.contentTypes]'s kdoc).
 *
 * and implements capability entry points the host calls into:
 *  - `provider_discovery`: `function discover(host) { ... }` - calls `host.reportProgress`/
 *    `host.reportCandidate` zero-or-more times, returns or throws when done.
 *  - `stream_search`: `function search(host, query, year, season, episode) { ... }` - calls
 *    `host.reportProgress`/`host.reportResult`, returns an optional summary string or throws;
 *    `function resolve(host, token, season, episode)` returns a playable http(s) URL or throws.
 *
 * `resolve()` may return either a bare URL string or an object carrying what the stream needs
 * beyond its URL:
 * ```js
 * return {
 *   url: "https://cdn.example/playlist.m3u8",
 *   headers: JSON.stringify({ "Referer": "https://example/" }),   // hotlink-protected CDNs
 *   subtitles: JSON.stringify([{ url: "https://cdn.example/eng.vtt",
 *                                label: "English", language: "en", default: true }]),
 * };
 * ```
 * `headers`/`subtitles` are JSON *strings*, not nested objects/arrays - a nested value can be
 * dropped or come back as an already-freed handle depending on the bridge, where a string
 * round-trips deterministically (a raw object is still accepted for `headers` as a fallback).
 * Sideloading is the only way a sidecar subtitle track ever reaches the player: sources that
 * call themselves hardsubbed don't always burn them in, and nothing in the stream advertises
 * them. Entries without an http(s) `url` are dropped by the host.
 *
 * `host` is the object bound by [com.lumora.plugin.js.JsHostImpl] - see that file for the full
 * function list. A candidate/result is a proposal, never trusted as-is: the host validates every
 * field the same way [com.lumora.plugin.PluginClient] used to.
 *
 * `host.httpGet`/`httpPost` never throw - a network-level failure (timeout, DNS, connection
 * refused) comes back as `{status: 0, body: ""}` rather than a catchable JS exception, because a
 * Kotlin exception thrown inside a host function does not unwind through a script's own
 * `try/catch` (see [com.lumora.plugin.js.JsHostImpl.execute]'s kdoc). Check `resp.status` instead
 * of wrapping `host.httpGet` calls in `try/catch` to handle a source being unreachable.
 */
object JsPluginContract {

    const val CAPABILITY_PROVIDER_DISCOVERY = "provider_discovery"
    const val CAPABILITY_STREAM_SEARCH = "stream_search"

    /** Field names on a reported candidate object - mirrors [com.lumora.plugin.DiscoveredProvider]. */
    const val KEY_TYPE = "type"
    const val KEY_LABEL = "label"
    const val KEY_URL = "url"
    const val KEY_USERNAME = "username"
    const val KEY_PASSWORD = "password"
    const val KEY_USER_AGENT = "userAgent"
    const val KEY_DETAIL = "detail"
    const val KEY_VERIFIED = "verified"

    /** Field names on a reported search result - mirrors [com.lumora.plugin.TorrentResult]. */
    const val KEY_TITLE = "title"
    const val KEY_TOKEN = "token"
    const val KEY_SEEDERS = "seeders"
    const val KEY_SIZE = "size"
    const val KEY_QUALITY = "quality"
    const val KEY_SOURCE = "source"
    /** Audio category hint on a search result / resolve return: "sub", "dub", or absent. */
    const val KEY_AUDIO = "audio"

    /** Provider types the host can actually build an IptvProviderConfig for. */
    val SUPPORTED_PROVIDER_TYPES = setOf("m3u", "xtream", "stalker")

    /** Caps on what one run may report, so a runaway script can't exhaust the UI. */
    const val MAX_CANDIDATES = 100
    const val MAX_RESULTS = 200
    const val MAX_TEXT_LENGTH = 200

    /** A discovery run is aborted after this long with no terminal outcome. */
    const val DISCOVERY_TIMEOUT_MS = 180_000L
    /** Search phase timeout - same idea as discovery. */
    const val SEARCH_TIMEOUT_MS = 120_000L
    /** Resolving a result to a stream (fetch metadata, first-episode lookup, ...) is slow. */
    const val RESOLVE_TIMEOUT_MS = 300_000L
}
