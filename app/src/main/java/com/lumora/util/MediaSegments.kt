package com.lumora.util

import com.lumora.model.MediaChapter
import com.lumora.model.MediaSegment

/**
 * Segment inference for servers that ship chapters but no markers.
 *
 * Plex's marker detection and Jellyfin's Intro Skipper both fingerprint a season's audio to
 * find the repeated theme; nothing like that can run on a TV stick, so where the numbers are
 * absent the only honest source left is what the file itself labels its chapters. A chapter
 * named "Intro" or "Opening Credits" is the marker, in every practical sense - it just has to
 * be recognised by name and paired with the following chapter's start to get an end.
 */
object MediaSegments {

    private val INTRO_NAMES = Regex(
        "^(intro(duction)?|opening( credits| titles| theme)?|theme( song| tune)?|op|titles)$",
        RegexOption.IGNORE_CASE
    )
    private val CREDITS_NAMES = Regex(
        "^((end |closing |ending )?credits|outro|ending|ed|end ?card)$",
        RegexOption.IGNORE_CASE
    )

    /** Anything shorter than this is noise, not a stretch worth offering to skip. */
    private const val MIN_SEGMENT_MS = 5_000L

    /**
     * Segments read off [chapters]. An intro runs to the next chapter's start; credits run to
     * [durationMs], since a credits chapter is by definition the last thing in the file.
     * Returns empty when the runtime isn't known yet - a credits segment with no end is
     * unskippable, and an intro one is indistinguishable from a mis-titled chapter.
     */
    fun fromChapters(chapters: List<MediaChapter>, durationMs: Long): List<MediaSegment> {
        if (chapters.size < 2 || durationMs <= 0) return emptyList()
        val sorted = chapters.sortedBy { it.positionMs }
        return sorted.mapIndexedNotNull { index, chapter ->
            val name = chapter.name.trim()
            val end = sorted.getOrNull(index + 1)?.positionMs ?: durationMs
            val type = when {
                INTRO_NAMES.matches(name) -> MediaSegment.Type.INTRO
                CREDITS_NAMES.matches(name) -> MediaSegment.Type.CREDITS
                else -> null
            } ?: return@mapIndexedNotNull null
            // Credits always run to the end of the file: a "Credits" chapter followed by a
            // stinger chapter would otherwise skip only as far as the stinger, which is the
            // one bit of the tail a viewer does want.
            val segmentEnd = if (type == MediaSegment.Type.CREDITS) durationMs else end
            MediaSegment(type, chapter.positionMs, segmentEnd)
                .takeIf { it.durationMs >= MIN_SEGMENT_MS }
        }
    }

    /** Drops segments too short to be worth a button, and anything outside the runtime. */
    fun sanitize(segments: List<MediaSegment>, durationMs: Long): List<MediaSegment> =
        segments.mapNotNull { segment ->
            val start = segment.startMs.coerceAtLeast(0L)
            // A server can report an end past the runtime (Plex rounds credits markers up to
            // the container duration, which the player's own duration may undercut).
            val end = if (durationMs > 0) segment.endMs.coerceAtMost(durationMs) else segment.endMs
            if (end - start < MIN_SEGMENT_MS) null else segment.copy(startMs = start, endMs = end)
        }.sortedBy { it.startMs }
}
