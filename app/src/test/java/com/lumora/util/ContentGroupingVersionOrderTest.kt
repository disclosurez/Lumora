package com.lumora.util

import com.lumora.model.Channel
import com.lumora.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Version ordering for duplicated films/series: own library first, then best quality. */
class ContentGroupingVersionOrderTest {

    private fun movie(name: String, id: String, ownLibrary: Boolean = false) = Channel(
        id = id,
        name = name,
        url = "http://example/$id",
        mediaType = MediaType.MOVIE,
        isJellyfin = ownLibrary
    )

    private fun versionsOf(vararg movies: Channel): List<String> {
        val (reps, versions) = groupDuplicateMovies(movies.toList())
        return versions[reps.first().id]!!.map { it.id }
    }

    @Test
    fun `4K copy plays before HD and untagged copies of the same film`() {
        assertEquals(
            listOf("4k", "fhd", "hd", "plain"),
            versionsOf(
                movie("The Breadwinner (2026)", "plain"),
                movie("HD - The Breadwinner (2026)", "hd"),
                movie("4K-AMZ - The Breadwinner (2026)", "4k"),
                movie("FHD - The Breadwinner (2026)", "fhd")
            )
        )
    }

    @Test
    fun `pixel resolution tags rank with their named equivalent`() {
        assertEquals(
            listOf("2160", "1080", "720"),
            versionsOf(
                movie("The Breadwinner 720p", "720"),
                movie("The Breadwinner 2160p", "2160"),
                movie("The Breadwinner 1080p", "1080")
            )
        )
    }

    @Test
    fun `a disc rip wins its resolution tier but never beats a higher one`() {
        assertEquals(
            listOf("web4k", "remux1080", "web1080"),
            versionsOf(
                movie("The Breadwinner 1080p WEB-DL", "web1080"),
                movie("The Breadwinner 1080p REMUX", "remux1080"),
                movie("The Breadwinner 2160p WEB-DL", "web4k")
            )
        )
    }

    @Test
    fun `a cam rip drops below an untagged copy`() {
        assertEquals(
            listOf("plain", "cam"),
            versionsOf(
                movie("The Breadwinner HDCAM", "cam"),
                movie("The Breadwinner", "plain")
            )
        )
    }

    @Test
    fun `own library stays first even when a provider carries a 4K copy`() {
        assertEquals(
            listOf("jf", "4k", "hd"),
            versionsOf(
                movie("HD - The Breadwinner (2026)", "hd"),
                movie("4K - The Breadwinner (2026)", "4k"),
                movie("The Breadwinner", "jf", ownLibrary = true)
            )
        )
    }

    @Test
    fun `equal quality keeps provider load order`() {
        assertEquals(
            listOf("first", "second"),
            versionsOf(
                movie("HD - The Breadwinner (2026)", "first"),
                movie("HD - The Breadwinner (2026)", "second")
            )
        )
    }

    @Test
    fun `series versions order the same way`() {
        val (reps, versions) = groupDuplicateSeries(
            listOf(
                movie("Severance (2025)", "plain").copy(mediaType = MediaType.SERIES),
                movie("4K - Severance (2025)", "4k").copy(mediaType = MediaType.SERIES)
            )
        )
        assertEquals(listOf("4k", "plain"), versions[reps.first().id]!!.map { it.id })
    }

    @Test
    fun `a trailing badge is not part of the grouping key`() {
        assertEquals(
            normalizeTitleForGrouping("The Breadwinner (2026)"),
            normalizeTitleForGrouping("The Breadwinner 2160p WEB-DL HDR")
        )
    }

    @Test
    fun `a title that merely ends in a badge word is left alone`() {
        // "The Web" and "The Raw" must not both collapse to "the".
        assertEquals("the web", normalizeTitleForGrouping("The Web (1947)"))
        assertEquals("the raw", normalizeTitleForGrouping("The Raw"))
        assertEquals("cam", normalizeTitleForGrouping("Cam (2018)"))
    }

    @Test
    fun `a weak badge strips only alongside a strong one`() {
        assertEquals("the web", normalizeTitleForGrouping("The Web 1080p WEB"))
    }
}
