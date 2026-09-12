package com.lumora.model

/**
 * One skippable stretch of the title being played - a series intro, or the closing credits.
 *
 * Backend-neutral, like [MediaChapter]: Plex calls these markers, Jellyfin 10.10+ calls them
 * media segments and older Jellyfin exposes the Intro Skipper plugin's own shape, and a server
 * with none of those can still have the stretch inferred from chapter names. The player's skip
 * button has no business knowing which of those produced the numbers.
 */
data class MediaSegment(
    val type: Type,
    val startMs: Long,
    val endMs: Long
) {
    enum class Type { INTRO, CREDITS }

    val durationMs: Long get() = endMs - startMs
}
