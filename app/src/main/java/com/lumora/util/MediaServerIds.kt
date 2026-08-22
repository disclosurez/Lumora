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
 * The separator is ':', which appears in neither the UUID the config id is nor a Plex rating
 * key - so the first ':' in a qualified id is always the separator, and an item id that itself
 * contains ':' survives the strip intact. An unqualified id (written by an older build, or by
 * a config-less stub) passes through both functions unchanged.
 */
fun qualifiedMediaItemId(sourceId: String?, itemId: String): String =
    if (sourceId.isNullOrBlank() || itemId.isBlank()) itemId else "$sourceId:$itemId"

/** The bare server-side id inside a [qualifiedMediaItemId] - what every API call takes. */
fun rawMediaItemId(id: String): String {
    val sep = id.indexOf(':')
    if (sep < 0) return id
    val prefix = id.substring(0, sep)
    // Split on the first ':' only, and only when the prefix is a config id (always a UUID):
    // everything after the separator - including any ':' inside the item id itself - is the
    // bare id, while an unqualified id that happens to contain ':' is left untouched.
    return if (CONFIG_ID.matches(prefix)) id.substring(sep + 1) else id
}

private val CONFIG_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
