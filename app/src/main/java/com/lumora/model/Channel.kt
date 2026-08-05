package com.lumora.model

enum class MediaType {
    LIVE, MOVIE, SERIES
}

data class Channel(
    val id: String = "",
    val name: String,
    val url: String,
    val logoUrl: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val group: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val tvgChno: String? = null,
    val mediaType: MediaType = MediaType.LIVE,
    val categoryId: String? = null,
    val categoryName: String? = null,
    val description: String? = null,
    // Series episodes only - both Xtream and Jellyfin already bake "S01E02 · Title" into
    // `name` for display, but that format can't be parsed back out reliably (episode
    // titles can themselves contain " · "), so the episode list needs the raw number
    // straight from the source to show it as its own badge rather than shoehorned out of
    // the name string.
    val episodeNum: Int? = null,
    val year: String? = null,
    val rating: String? = null,
    // ISO "YYYY-MM-DD" - only Xtream's series list actually carries a real release date
    // in bulk (movies only expose it per-item via get_vod_info, one call per movie,
    // impractical to fetch for the whole catalog just to sort it). Null means fall back
    // to year for ordering.
    val releaseDate: String? = null,
    // Any number of IPTV providers (Xtream/M3U/Stalker) plus Jellyfin can be configured and
    // active at once now, merged into one catalog - playback URL construction and detail
    // fetching need very different handling per source, and with several providers live
    // simultaneously that can no longer be answered by checking a single global `provider`
    // field (there isn't one active provider anymore). Per-item is the only thing that's
    // actually reliable.
    val isJellyfin: Boolean = false,
    // Stalker's MAC-as-User-Agent or M3U's custom User-Agent, baked in from whichever
    // IptvProviderConfig this channel came from - playback needs the *source* provider's
    // header, not whichever IPTV provider happens to be first/active.
    val streamUserAgent: String? = null,
    // Extra HTTP request headers the stream URL needs - e.g. a plugin-resolved anime CDN that
    // hotlink-protects its playlist behind a Referer. Applied by PlayerManager alongside the UA.
    val streamHeaders: Map<String, String>? = null,
    // IptvProviderConfig.id of the Xtream provider this item came from - detail/EPG calls
    // (get_series_info, get_short_epg, get_vod_info) need the *matching* server/credentials,
    // not whichever Xtream provider happened to load last into the old single shared
    // `provider` field. Null for non-Xtream sources (M3U/Stalker have no such detail APIs;
    // Jellyfin uses jellyfinClient directly).
    val sourceProviderId: String? = null,
    // Per-channel A/V sync offset in milliseconds. Positive = audio delayed (plays later),
    // negative = audio advanced (plays sooner). 0 = use global default.
    // Applied via TrackSelector at playback start.
    val avOffsetMs: Int = 0,
    // Stalker VOD/series play command (base64), which only becomes a playable URL via a
    // create_link call at play time - unlike live, whose url is resolved up front. Paired
    // with sourceProviderId (the portal's IptvProviderConfig id) so the play step can
    // re-auth against the right portal. Null for everything that already has a direct url.
    val stalkerCmd: String? = null,
    // A stream_search plugin's opaque resolve token, for items whose playable URL is produced by
    // a plugin rather than stored anywhere. Those URLs are short-lived (the anime CDN signs them
    // with an expiry in the path) and need request headers the URL alone doesn't carry, so a
    // Continue Watching tile cannot replay the URL it was saved with - it has to re-run
    // resolve(). Paired with pluginId so the *same* script resolves it again, and with
    // episodeNum, which resolve() needs to pick the right episode.
    val pluginToken: String? = null,
    // PluginScript.id of the script that produced pluginToken. Null for everything else.
    val pluginId: String? = null,
    // Xtream `tv_archive` (0/1): the panel keeps a rolling recording of this channel, so a
    // programme that already aired can be played back from the archive. Live channels only,
    // and only Xtream reports it - M3U/Stalker/Jellyfin leave it false.
    val tvArchive: Boolean = false,
    // Xtream `tv_archive_duration`: how many days back the panel kept, typically 3-7. Bounds
    // the Catch Up day picker; 0 means "no archive" even if tvArchive somehow says otherwise.
    val tvArchiveDays: Int = 0
)
