package com.lumora.util

/**
 * Item ids on a personal media server are only unique *within* that server - a Plex rating
 * key is a small per-library integer, so two Plex servers hand out "12345" for two unrelated
 * films. With several servers configured at once those ids all land in the same catalog, the
 * same favourites store and the same playback-position store, where a collision silently
 * merges two different titles.
 *
 * So every media-server item id carried on a [com.lumora.model.Channel] is qualified with the
 * id of the [com.lumora.model.MediaServerConfig] it came from. Anything that talks back to the
 * server ([rawMediaItemId]) strips it first - the server only knows its own bare id.
 *
 * The separator is ':', which appears in neither a Jellyfin GUID, a Plex rating key, nor the
 * UUID the config id is - so stripping is unambiguous, and an unqualified id (written by an
 * older build, or by a config-less stub) passes through both functions unchanged.
 */
fun qualifiedMediaItemId(sourceId: String?, itemId: String): String =
    if (sourceId.isNullOrBlank() || itemId.isBlank()) itemId else "$sourceId:$itemId"

/** The bare server-side id inside a [qualifiedMediaItemId] - what every API call takes. */
fun rawMediaItemId(id: String): String = id.substringAfterLast(':')
