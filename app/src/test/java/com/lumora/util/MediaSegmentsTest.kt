package com.lumora.util

import com.lumora.model.MediaChapter
import com.lumora.model.MediaSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chapter-name fallback behind the skip button, for servers that ship chapters but no
 * intro/credits markers of their own.
 *
 * Two rules carry the feature and are the ones worth pinning: an intro ends where the next
 * chapter starts, and credits run to the end of the runtime regardless of what follows them -
 * a post-credits stinger is exactly the part of the tail a viewer does not want skipped past
 * silently, so the credits segment must not stop at it.
 */
class MediaSegmentsTest {

    private fun chapter(name: String, positionMs: Long) = MediaChapter(name, positionMs, null)

    @Test
    fun introEndsAtTheNextChapter() {
        val segments = MediaSegments.fromChapters(
            listOf(
                chapter("Intro", 0L),
                chapter("Chapter 2", 90_000L),
                chapter("Chapter 3", 600_000L)
            ),
            durationMs = 1_500_000L
        )
        assertEquals(1, segments.size)
        assertEquals(MediaSegment.Type.INTRO, segments[0].type)
        assertEquals(0L, segments[0].startMs)
        assertEquals(90_000L, segments[0].endMs)
    }

    @Test
    fun creditsRunToTheEndPastAStinger() {
        val segments = MediaSegments.fromChapters(
            listOf(
                chapter("Chapter 1", 0L),
                chapter("End Credits", 1_200_000L),
                chapter("Stinger", 1_400_000L)
            ),
            durationMs = 1_500_000L
        )
        assertEquals(1, segments.size)
        assertEquals(MediaSegment.Type.CREDITS, segments[0].type)
        assertEquals(1_500_000L, segments[0].endMs)
    }

    @Test
    fun unnamedChaptersProduceNothing() {
        val segments = MediaSegments.fromChapters(
            listOf(chapter("Chapter 1", 0L), chapter("Chapter 2", 300_000L)),
            durationMs = 900_000L
        )
        assertTrue(segments.isEmpty())
    }

    @Test
    fun anUnknownRuntimeProducesNothing() {
        val segments = MediaSegments.fromChapters(
            listOf(chapter("Intro", 0L), chapter("Chapter 2", 90_000L)),
            durationMs = 0L
        )
        assertTrue(segments.isEmpty())
    }

    @Test
    fun aChapterTooShortToSkipIsIgnored() {
        val segments = MediaSegments.fromChapters(
            listOf(chapter("Intro", 0L), chapter("Chapter 2", 2_000L)),
            durationMs = 900_000L
        )
        assertTrue(segments.isEmpty())
    }

    @Test
    fun sanitizeClampsAMarkerReportedPastTheRuntime() {
        val sanitized = MediaSegments.sanitize(
            listOf(MediaSegment(MediaSegment.Type.CREDITS, 1_400_000L, 1_600_000L)),
            durationMs = 1_500_000L
        )
        assertEquals(1, sanitized.size)
        assertEquals(1_500_000L, sanitized[0].endMs)
    }

    @Test
    fun sanitizeDropsASegmentTooShortToOffer() {
        val sanitized = MediaSegments.sanitize(
            listOf(MediaSegment(MediaSegment.Type.INTRO, 0L, 2_000L)),
            durationMs = 900_000L
        )
        assertTrue(sanitized.isEmpty())
    }
}
