package com.lumora.model

/** One configured IPTV source (Xtream/M3U/Stalker) - unlike Jellyfin, which is one
 *  independent slot, there can be any number of these, each independently toggleable,
 *  merged together into the catalog when enabled. */
data class IptvProviderConfig(
    val id: String,
    val type: String, // "m3u" | "xtream" | "stalker"
    val name: String,
    val enabled: Boolean = true,
    // Per-content-type gates: TV (live) / Movies / Series can each be switched off for a
    // single provider without touching the others. Movies and Series are also subject to
    // the global isVodDisabled() gate (the effective gate for VOD is the global flag OR
    // this provider's flag). The old single disableVod field (movies+series together) is
    // migrated into moviesEnabled/seriesEnabled on load.
    val liveEnabled: Boolean = true,
    val moviesEnabled: Boolean = true,
    val seriesEnabled: Boolean = true,
    val url: String? = null,
    val username: String? = null,
    val password: String? = null,
    // Stalker's MAC address or M3U's custom User-Agent - same slot, different meaning per
    // type, baked onto every Channel this source produces (see Channel.streamUserAgent) so
    // playback sends the right one without needing to know which provider a channel came
    // from at that point.
    val userAgent: String? = null
)
