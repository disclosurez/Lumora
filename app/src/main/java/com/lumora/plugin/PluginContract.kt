package com.lumora.plugin

/**
 * The wire protocol between Lumora and a companion plugin APK.
 *
 * Plugins are separate applications, so there is no shared class loader and no way to hand
 * an interface implementation across the boundary - everything here is `Message`/`Bundle`
 * primitives over a bound [android.os.Messenger], which needs no AIDL on either side and
 * keeps a misbehaving plugin in its own process.
 *
 * A plugin declares itself by exporting a Service with an intent filter for
 * [ACTION_PLUGIN_SERVICE] and describing itself in that service's meta-data:
 *
 * ```xml
 * <service android:name=".MyPluginService" android:exported="true">
 *     <intent-filter><action android:name="com.lumora.action.PLUGIN" /></intent-filter>
 *     <meta-data android:name="lumora.plugin.id"           android:value="com.example.myplugin" />
 *     <meta-data android:name="lumora.plugin.description"  android:value="What this plugin does" />
 *     <meta-data android:name="lumora.plugin.capabilities" android:value="provider_discovery" />
 * </service>
 * ```
 *
 * Conversation for the one capability that exists today, [CAPABILITY_PROVIDER_DISCOVERY]:
 *
 *  1. Host binds the service and sends [MSG_START_DISCOVERY] with `replyTo` set to its own
 *     Messenger and [KEY_REQUEST_ID] in the data bundle.
 *  2. Plugin replies with any number of [MSG_PROGRESS] (a human-readable status line) and
 *     [MSG_CANDIDATE] messages (one proposed provider each), all echoing the request id.
 *  3. Plugin ends the job with exactly one [MSG_FINISHED] or [MSG_ERROR].
 *  4. Host may send [MSG_CANCEL] at any point; the plugin should stop and still send a
 *     terminal message. The host unbinds after the terminal message or [DISCOVERY_TIMEOUT_MS].
 *
 * A candidate is a *proposal*, never a configured provider: the host validates every field,
 * shows the user what came back, and only writes it to the provider list on an explicit
 * confirmation. Plugins are untrusted input - see PluginClient.sanitizeCandidate.
 */
object PluginContract {

    /** Intent action a plugin's Service must be exported under to be discoverable. */
    const val ACTION_PLUGIN_SERVICE = "com.lumora.action.PLUGIN"

    // ── Service meta-data keys ──
    const val META_PLUGIN_ID = "lumora.plugin.id"
    const val META_DESCRIPTION = "lumora.plugin.description"
    /** Comma-separated capability list, e.g. "provider_discovery". Unknown entries are ignored. */
    const val META_CAPABILITIES = "lumora.plugin.capabilities"

    /**
     * Plugin can search somewhere the host knows nothing about and propose provider configs.
     * The host supplies no input and expects no channels - just candidates the user can add.
     */
    const val CAPABILITY_PROVIDER_DISCOVERY = "provider_discovery"

    /**
     * Plugin can find a playable stream for one specific title on demand, rather than a
     * standing catalog. The host sends a title (and season/episode for TV); the plugin returns
     * a list of source options and, for whichever one the user picks, resolves it to a plain
     * HTTP URL the host plays with its normal player.
     *
     * Conversation (host side is StreamSearchClient):
     *  1. Host binds the service and sends [MSG_START_SEARCH] with the query fields.
     *  2. Plugin streams [MSG_PROGRESS] and [MSG_RESULT] (one source option each), then a
     *     terminal [MSG_FINISHED] or [MSG_ERROR] for the search phase.
     *  3. Host sends [MSG_RESOLVE_STREAM] with the [KEY_MAGNET] of the chosen result.
     *  4. Plugin does whatever slow work turns that into a playable URL and replies with one
     *     [MSG_STREAM_READY] ([KEY_STREAM_URL]) or [MSG_ERROR].
     *  5. When playback ends the host sends [MSG_STOP_STREAM]; the plugin tears the stream down.
     *
     * The binding stays open across all of this - unlike discovery it is not fire-and-forget,
     * because the URL the plugin returns is served *by the plugin* (e.g. a local HTTP server in
     * its process) and must stay alive while the host is playing it.
     */
    const val CAPABILITY_STREAM_SEARCH = "stream_search"

    // ── Host -> plugin ──
    const val MSG_START_DISCOVERY = 1
    const val MSG_CANCEL = 2
    const val MSG_START_SEARCH = 3
    const val MSG_RESOLVE_STREAM = 4
    const val MSG_STOP_STREAM = 5

    // ── Plugin -> host ──
    const val MSG_PROGRESS = 10
    const val MSG_CANDIDATE = 11
    const val MSG_FINISHED = 12
    const val MSG_ERROR = 13
    const val MSG_RESULT = 14
    const val MSG_STREAM_READY = 15

    // ── Bundle keys ──
    const val KEY_REQUEST_ID = "request_id"
    /** Status line ([MSG_PROGRESS]), summary ([MSG_FINISHED]) or reason ([MSG_ERROR]). */
    const val KEY_MESSAGE = "message"
    /** One of [SUPPORTED_PROVIDER_TYPES]. Anything else is dropped by the host. */
    const val KEY_TYPE = "provider_type"
    const val KEY_LABEL = "label"
    const val KEY_URL = "url"
    const val KEY_USERNAME = "username"
    const val KEY_PASSWORD = "password"
    /** Stalker's MAC, or a custom User-Agent for M3U - the same slot IptvProviderConfig uses. */
    const val KEY_USER_AGENT = "user_agent"
    /** Free-text detail shown under the candidate's name (expiry, channel count, ...). */
    const val KEY_DETAIL = "detail"
    /** True if the plugin already confirmed the credentials work. Advisory - shown, not trusted. */
    const val KEY_VERIFIED = "verified"

    // ── Stream-search bundle keys ──
    const val KEY_QUERY = "query"
    const val KEY_YEAR = "year"
    /** 0/absent for a movie; 1-based season for TV. */
    const val KEY_SEASON = "season"
    const val KEY_EPISODE = "episode"
    /** Opaque token identifying a search result - handed back verbatim in [MSG_RESOLVE_STREAM]. */
    const val KEY_MAGNET = "magnet"
    const val KEY_SEEDERS = "seeders"
    const val KEY_SIZE = "size"
    /** e.g. "4K", "1080p" - shown as-is, host does not parse it. */
    const val KEY_QUALITY = "quality"
    /** Which scraper/indexer produced the result. */
    const val KEY_SOURCE = "source"
    /** The playable http/https URL a resolve produced. Validated by the host before playback. */
    const val KEY_STREAM_URL = "stream_url"

    /** Provider types the host can actually build an IptvProviderConfig for. */
    val SUPPORTED_PROVIDER_TYPES = setOf("m3u", "xtream", "stalker")

    /** Caps on what one discovery run may return, so a runaway plugin can't exhaust the UI. */
    const val MAX_CANDIDATES = 100
    const val MAX_RESULTS = 200
    const val MAX_TEXT_LENGTH = 200

    /** A discovery run is abandoned (and the service unbound) after this long with no terminal message. */
    const val DISCOVERY_TIMEOUT_MS = 180_000L
    /** Search phase silence timeout - same idea as discovery. */
    const val SEARCH_TIMEOUT_MS = 120_000L
    /** Resolving a torrent to a stream (fetch metadata, buffer first pieces) is slow - allow more. */
    const val RESOLVE_TIMEOUT_MS = 300_000L
}
