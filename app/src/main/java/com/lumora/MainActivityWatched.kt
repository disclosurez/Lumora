package com.lumora

import android.util.Log
import com.lumora.cache.PlaybackPositionStore
import com.lumora.cache.WatchedStore
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.util.cleanVodTitle
import com.lumora.util.normalizeTitleForGrouping
import com.lumora.util.rawMediaItemId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Cross-provider watched state ──
//
// Extracted from MainActivity.kt; see that file's header.
//
// The problem this solves: PlaybackPositionStore is keyed by a provider-scoped id, which is
// correct for a resume position (that really is per-file) but wrong for "have I seen this".
// The same episode present on Plex, on Jellyfin and across three IPTV panels has five ids, so
// finishing it left the other four looking untouched - and the next-unwatched Play button on
// each of those copies kept offering an episode already seen.
//
// So a watched mark is stored a second time under a key derived from the title and the
// season/episode numbers, which every copy computes identically. Reads consult both: the
// per-copy position entry first (it is the precise answer for the copy actually played) and
// the shared mark second.

/**
 * The provider-independent key for [item], or null when one can't be built.
 *
 * Uses [normalizeTitleForGrouping] - the same normalisation that decides two catalogue entries
 * are the same title when duplicate versions are grouped - so "4K-AMZ - SAS Rogue Heroes" and
 * a Plex library's "SAS Rogue Heroes" land on one key.
 *
 * Null for anything that isn't a single watchable title: a top-level series entry (the series
 * as a whole is not a thing you finish), a live channel, or an episode whose series name or
 * numbering can't be determined. Callers fall back to per-copy state, which is exactly the old
 * behaviour.
 */
internal fun MainActivity.watchedKeyFor(item: Channel): String? = when (item.mediaType) {
    MediaType.LIVE -> null
    MediaType.MOVIE -> movieWatchedKey(item.name)
    // Season-less numbering still keys consistently across providers as long as every copy
    // agrees there is no season, which is the case for the flat-numbered anime/documentary
    // strands that omit it.
    MediaType.SERIES -> episodeWatchedKey(
        seriesTitleForEpisode(item), tileSeasonNumber(item), item.episodeNum
    )
}

/** [watchedKeyFor] for a film, from its raw catalogue title. */
internal fun movieWatchedKey(title: String): String? =
    normalizeForWatchedKey(title)?.let { "m|$it" }

/**
 * [watchedKeyFor] for an episode, from the parts rather than a [Channel].
 *
 * This is the form the media-server imports use: a JellyfinItem/PlexItem already states its
 * series name, season and episode, so their played marks can be keyed without resolving
 * anything through the catalogue - which matters, because an import runs over a whole season
 * at a time and the catalogue lookup is a linear scan.
 */
internal fun episodeWatchedKey(seriesName: String?, season: Int?, episode: Int?): String? {
    if (episode == null) return null
    val normalized = normalizeForWatchedKey(seriesName ?: return null) ?: return null
    return "e|$normalized|s${season ?: 0}|e$episode"
}

/** Shared watched key for a Jellyfin item, straight off the fields the server sent. */
internal fun sharedWatchedKeyFor(item: com.lumora.data.remote.jellyfin.JellyfinProvider.JellyfinItem): String? =
    when (item.mediaType) {
        "Movie" -> movieWatchedKey(item.name)
        "Episode" -> episodeWatchedKey(item.seriesName, item.seasonNumber, item.episodeNumber)
        else -> null
    }

/** Shared watched key for a Plex item, straight off the fields the server sent. */
internal fun sharedWatchedKeyFor(item: com.lumora.data.remote.plex.PlexProvider.PlexItem): String? =
    when (item.mediaType) {
        "Movie" -> movieWatchedKey(item.name)
        "Episode" -> episodeWatchedKey(item.seriesName, item.seasonNumber, item.episodeNumber)
        else -> null
    }

private fun normalizeForWatchedKey(title: String): String? {
    val normalized = normalizeTitleForGrouping(cleanVodTitle(title))
    if (normalized.isBlank()) return null
    // A newline would corrupt the store's line-per-key file; '|' is this key's own separator.
    if (normalized.any { it == '\n' || it == '\r' || it == '|' }) return null
    return normalized
}

/**
 * The show an episode belongs to, by name.
 *
 * Preferred source is the catalogue entry the episode's parent id points at, because that is
 * the show's real title. Falling back to the episode's own name only works for the providers
 * that bake the series into it, and the marker/title decoration has to come off first or two
 * copies of one show would normalise differently.
 */
private fun MainActivity.seriesTitleForEpisode(item: Channel): String? {
    if (item.episodeNum == null) return null
    resolveHomeTileSeries(item)?.let { return cleanVodTitle(it.name) }
    // "SAS Rogue Heroes - S03E03 - Title" / "S03E03 · Title" / "Series · Title": drop the
    // marker and anything after it, leaving the leading show name if there is one.
    val name = cleanVodTitle(item.name)
    val marker = ANY_SEASON_MARKER_REGEX.find(name) ?: return null
    val prefix = name.substring(0, marker.range.first).trim().trim('-', '·', ':').trim()
    return prefix.ifBlank { null }
}

/**
 * Whether [item] has been watched, by any copy of it on any provider.
 *
 * The per-copy position entry is checked first: it is the authoritative answer for the exact
 * file that played, and it is what a provider with no resolvable title key still relies on.
 */
internal fun MainActivity.isItemWatched(item: Channel): Boolean {
    val key = item.id.ifBlank { item.url }
    if (key.isNotBlank() && PlaybackPositionStore.get(this, key)?.isNearComplete == true) return true
    val shared = watchedKeyFor(item) ?: return false
    return WatchedStore.isWatched(this, shared)
}

/**
 * Records [item] as watched (or not) everywhere it exists.
 *
 * Three things happen: the per-copy position entry is written or cleared as before, the shared
 * title key is set so every other copy in the catalogue reads the new state immediately, and -
 * best effort, in the background - the matching item on each configured Jellyfin/Plex server is
 * marked so their other clients agree.
 *
 * [alsoPushToServers] is off for state arriving *from* a server, which would otherwise echo
 * straight back to it.
 */
internal fun MainActivity.setItemWatched(
    item: Channel,
    watched: Boolean,
    alsoPushToServers: Boolean = true
) {
    val copyKey = item.id.ifBlank { item.url }
    if (copyKey.isNotBlank()) {
        val existing = PlaybackPositionStore.get(this, copyKey)
        when {
            // Already finished from real playback - leave the entry alone. Overwriting it
            // with the stub below would throw away the true duration for no gain, and this
            // path runs on every teardown of an already-watched title.
            watched && existing?.isNearComplete == true -> Unit
            // Duration is unknown for something never played; 1ms of 1ms clears the
            // completion threshold, which is what the episode row already did by hand.
            watched -> PlaybackPositionStore.save(this, copyKey, 1L, 1L, item)
            else -> PlaybackPositionStore.clear(this, copyKey)
        }
    }
    val shared = watchedKeyFor(item)
    if (shared != null) {
        WatchedStore.setWatched(this, shared, watched)
        clearSiblingCopyPositions(item, shared)
    }
    if (alsoPushToServers) pushWatchedToMediaServers(item, watched)
}

/**
 * Drops every *other* catalogue copy's position entry for the same title.
 *
 * Needed in both directions, for opposite reasons. Marking watched: a sibling's half-finished
 * resume point is moot now the title has been seen, and leaving it would keep that copy sitting
 * in Continue Watching. Marking unwatched: a sibling's near-complete entry would out-vote the
 * cleared shared mark, because [isItemWatched] consults the per-copy entry first.
 */
private fun MainActivity.clearSiblingCopyPositions(item: Channel, sharedKey: String) {
    val ownKey = item.id.ifBlank { item.url }
    for (candidate in allChannels) {
        if (candidate.mediaType != item.mediaType) continue
        val key = candidate.id.ifBlank { candidate.url }
        if (key.isBlank() || key == ownKey) continue
        if (PlaybackPositionStore.get(this, key) == null) continue
        if (watchedKeyFor(candidate) == sharedKey) PlaybackPositionStore.clear(this, key)
    }
}

/**
 * Marks the equivalent item played on every configured Jellyfin and Plex server.
 *
 * A film resolves straight out of the catalogue - the server's own copy is already a Channel
 * there. An episode does not: the catalogue holds media-server *series*, and their episode
 * lists are only fetched when a series is opened, so the matching episode has to be looked up
 * over the network. That is one request per server per mark, in the background, and a failure
 * is silent - the local shared mark has already made every copy read as watched, and this is
 * only about agreeing with other clients.
 */
private fun MainActivity.pushWatchedToMediaServers(item: Channel, watched: Boolean) {
    val shared = watchedKeyFor(item) ?: return
    if (jellyfinClients.isEmpty() && plexClients.isEmpty()) return
    val normalizedTitle = shared.substringAfter('|').substringBefore('|')
    scope.launch {
        for (candidate in allChannels) {
            if (!candidate.isOwnLibrary) continue
            val sourceId = candidate.sourceProviderId ?: continue
            val rawId = rawMediaItemId(candidate.id)
            if (rawId.isBlank()) continue

            if (item.mediaType == MediaType.MOVIE) {
                // A film's server copy is already in the catalogue - no lookup needed.
                if (candidate.mediaType != MediaType.MOVIE) continue
                if (candidate.id == item.id) continue
                if (watchedKeyFor(candidate) != shared) continue
                markOnServer(candidate.isJellyfin, sourceId, rawId, watched, candidate.name)
                continue
            }

            // Episodes: the catalogue holds media-server *series*, whose episode lists are
            // only fetched when a series is opened - so the matching episode has to be looked
            // up over the network, one request per server that carries the show.
            if (candidate.mediaType != MediaType.SERIES || candidate.episodeNum != null) continue
            if (normalizeTitleForGrouping(cleanVodTitle(candidate.name)) != normalizedTitle) continue
            val season = tileSeasonNumber(item)
            val episodeNum = item.episodeNum ?: return@launch
            val targetId = runCatching {
                serverEpisodeId(candidate.isJellyfin, sourceId, rawId, season, episodeNum)
            }.getOrNull() ?: continue
            markOnServer(candidate.isJellyfin, sourceId, targetId, watched, candidate.name)
        }
    }
}

/**
 * The server's own id for season [season] episode [episodeNum] of series [seriesRawId], or null
 * if that server doesn't have it.
 *
 * Matched on the servers' own season/episode fields rather than by parsing a name: both
 * JellyfinItem and PlexItem carry them, which is the whole reason this goes to the raw items
 * instead of converting to Channels first. A season of null on the source (flat-numbered
 * strands, or a provider that never stated one) matches on episode number alone.
 */
private suspend fun MainActivity.serverEpisodeId(
    isJellyfin: Boolean,
    sourceId: String,
    seriesRawId: String,
    season: Int?,
    episodeNum: Int
): String? = withContext(Dispatchers.IO) {
    if (isJellyfin) {
        jellyfinClients[sourceId]?.getEpisodes(seriesRawId)?.firstOrNull {
            it.episodeNumber == episodeNum && (season == null || it.seasonNumber == season)
        }?.id
    } else {
        plexClients[sourceId]?.getEpisodes(seriesRawId)?.firstOrNull {
            it.episodeNumber == episodeNum && (season == null || it.seasonNumber == season)
        }?.id
    }
}

/** Applies the mark to one media-server item. Best effort: the local shared mark has already
 *  made every copy read as watched, so this only decides whether other clients agree. */
private suspend fun MainActivity.markOnServer(
    isJellyfin: Boolean,
    sourceId: String,
    rawId: String,
    watched: Boolean,
    label: String
) {
    val ok = withContext(Dispatchers.IO) {
        if (isJellyfin) {
            jellyfinClients[sourceId]?.setPlayed(rawId, watched)
        } else {
            plexClients[sourceId]?.let {
                if (watched) it.markWatched(rawId) else it.clearUserData(rawId)
            }
        }
    }
    if (ok != true) Log.d("LumoraWatched", "server mark failed for $label (watched=$watched)")
}
