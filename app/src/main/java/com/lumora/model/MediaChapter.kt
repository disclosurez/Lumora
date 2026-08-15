package com.lumora.model

/** One chapter marker on the title being played.
 *
 *  Backend-neutral on purpose: Jellyfin and Plex both expose chapters, in their own shapes
 *  and their own units, and the player's chapter button and picker have no business knowing
 *  which server the current title came from. Each provider maps into this at fetch time. */
data class MediaChapter(
    val name: String,
    val positionMs: Long,
    val imageUrl: String?
)
