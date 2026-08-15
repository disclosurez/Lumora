package com.lumora.model

/**
 * One configured personal-media-server account - a Jellyfin login or a Plex account/server
 * pair. Any number can exist side by side, the same way [IptvProviderConfig] entries do, and
 * their catalogs are merged into the one browsing experience.
 *
 * Kept apart from [IptvProviderConfig] rather than folded into it because the two describe
 * different things: an IPTV provider is a URL plus credentials, while these carry a session
 * (Jellyfin token + user id, Plex per-server token plus the account token that listed it) and
 * are reached through their own clients and playback negotiation.
 */
data class MediaServerConfig(
    val id: String,
    /** "jellyfin" or "plex". */
    val type: String,
    /** What the row is called in Settings and, for Plex, the server's own name. */
    val name: String,
    val enabled: Boolean = true,
    /** Jellyfin: the server URL the user typed. Plex: whichever published endpoint answered
     *  during sign-in (see PlexProvider.pickConnection) - never something typed. */
    val url: String? = null,
    // Jellyfin password login. Absent on a Quick Connect session, which only ever yields a
    // token (see token/userId below).
    val username: String? = null,
    val password: String? = null,
    /** Jellyfin access token, or the Plex *server* token. Both are the credential the client
     *  authenticates with from here on. */
    val token: String? = null,
    /** Jellyfin user id that goes with [token]. */
    val userId: String? = null,
    /** Plex account token: kept so the account's server list can be re-read (to switch
     *  servers) without a second sign-in - a per-server token can't list an account's
     *  resources. */
    val accountToken: String? = null,
    /** Per-content-type gates, mirroring IptvProviderConfig. Plex never produces live
     *  channels (its Live TV is a tuner-session flow Lumora's URL-per-channel model can't
     *  express), so [liveEnabled] is meaningless there and left at its default. */
    val liveEnabled: Boolean = true,
    val moviesEnabled: Boolean = true,
    val seriesEnabled: Boolean = true
) {
    val isJellyfin: Boolean get() = type == "jellyfin"
    val isPlex: Boolean get() = type == "plex"

    /** Configured enough to fetch from: a Jellyfin entry needs a server, a Plex entry needs
     *  both halves its sign-in writes together (either alone means the flow never finished). */
    val isComplete: Boolean
        get() = when (type) {
            "plex" -> !url.isNullOrBlank() && !token.isNullOrBlank()
            else -> !url.isNullOrBlank()
        }
}
