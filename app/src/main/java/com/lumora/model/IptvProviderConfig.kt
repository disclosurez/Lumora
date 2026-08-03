package com.lumora.model

/** One configured IPTV source (Xtream/M3U/Stalker) - unlike Jellyfin, which is one
 *  independent slot, there can be any number of these, each independently toggleable,
 *  merged together into the catalog when enabled. */
data class IptvProviderConfig(
    val id: String,
    val type: String, // "m3u" | "xtream" | "stalker"
    val name: String,
    val enabled: Boolean = true,
    // Per-provider VOD gate: hides this provider's movies/series (but keeps live TV).
    // The effective gate for this provider is the global isVodDisabled() OR this flag.
    val disableVod: Boolean = false,
    val url: String? = null,
    val username: String? = null,
    val password: String? = null,
    // Stalker's MAC address or M3U's custom User-Agent - same slot, different meaning per
    // type, baked onto every Channel this source produces (see Channel.streamUserAgent) so
    // playback sends the right one without needing to know which provider a channel came
    // from at that point.
    val userAgent: String? = null
)
