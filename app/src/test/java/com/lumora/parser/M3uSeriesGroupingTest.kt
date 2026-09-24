package com.lumora.parser

import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.util.collapseM3uEpisodeRows
import com.lumora.util.groupDuplicateSeries
import com.lumora.util.m3uEpisodeTag
import com.lumora.util.m3uShowId
import com.lumora.util.seriesShowKey
import com.lumora.util.seriesShowTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * m3u_plus panels list every episode as its own playlist entry ("Show (2026) S01E02"
 * with a direct stream URL) instead of a show with an episode endpoint. These pin down
 * the three steps that turn those rows into playable shows: the parser stamps the
 * episode number + parent show id, the catalog collapses rows into one card per show,
 * and the detail screen's matcher (same show id) finds the episodes back.
 *
 * Real shapes taken from a live m3u_plus dump (group-title "Series | …",
 * ".../series/user/pass/<id>.mp4" URLs).
 */
class M3uSeriesGroupingTest {

    private fun seriesEntry(name: String, id: String = "http://ex.com/series/u/p/$name.mp4"): Channel =
        M3uParser.parse(
            "#EXTM3U\n#EXTINF:-1 tvg-name=\"$name\" group-title=\"Series | Netflix\",$name\n$id\n"
        ).channels.single()

    // ── parser stamps ──

    @Test
    fun `episode marker stamps episode number and parent show id`() {
        val ep = seriesEntry("Ilusão Mortal (2026) S01E02")
        assertEquals(MediaType.SERIES, ep.mediaType)
        assertEquals(2, ep.episodeNum)
        assertEquals(m3uShowId(seriesShowKey("Ilusão Mortal (2026) S01E02")!!), ep.categoryId)
    }

    @Test
    fun `language tag between year and marker still stamps`() {
        val ep = seriesEntry("Uma Fortuna Qualquer (2026) [L] S01E06")
        assertEquals(6, ep.episodeNum)
        assertNotNull(ep.categoryId)
    }

    @Test
    fun `series row without marker stays a plain show-level item`() {
        val show = seriesEntry("Some Show (2026)")
        assertNull(show.episodeNum)
        assertNull(show.categoryId)
    }

    @Test
    fun `live channel with episode-shaped token is not stamped`() {
        val ch = M3uParser.parse(
            "#EXTM3U\n#EXTINF:-1 group-title=\"Canais | Variedades\",Class S1E2 Live\nhttp://ex.com/1.ts\n"
        ).channels.single()
        assertEquals(MediaType.LIVE, ch.mediaType)
        assertNull(ch.episodeNum)
        assertNull(ch.categoryId)
    }

    // ── show key helpers ──

    @Test
    fun `show key drops marker year and tags together`() {
        assertEquals(
            seriesShowKey("Ilusão Mortal (2026) S01E01"),
            seriesShowKey("Ilusão Mortal (2026) S02E03")
        )
        assertEquals("ilusão mortal", seriesShowKey("Ilusão Mortal (2026) S01E01"))
        assertNull(seriesShowKey("Ilusão Mortal (2026)"))
    }

    @Test
    fun `show title keeps year for downstream year resolution`() {
        assertEquals("Ilusão Mortal (2026)", seriesShowTitle("Ilusão Mortal (2026) S01E02"))
        assertEquals("Dara-san of the Reiwa Era (2026) [L]", seriesShowTitle("Dara-san of the Reiwa Era (2026) [L] S01E13"))
    }

    @Test
    fun `episode tag parses season and episode`() {
        assertEquals(1 to 13, m3uEpisodeTag("Dara-san (2026) S01E13"))
        assertNull(m3uEpisodeTag("Just A Show (2026)"))
        // Mid-title token is part of the title, not the tag.
        assertNull(m3uEpisodeTag("S01E01 Origins (2026)"))
    }

    // ── collapse ──

    private fun epRow(show: String, season: Int, episode: Int, provider: String = "prov-1"): Channel {
        val s = season.toString().padStart(2, '0')
        val e = episode.toString().padStart(2, '0')
        return Channel(
            id = "http://ex.com/series/u/p/$show-$s$episode.mp4",
            name = "$show (2026) S${s}E$e",
            url = "http://ex.com/series/u/p/$show-$s$episode.mp4",
            mediaType = MediaType.SERIES,
            episodeNum = episode,
            categoryId = m3uShowId(seriesShowKey("$show (2026) S${s}E$e")!!),
            sourceProviderId = provider
        )
    }

    @Test
    fun `episode rows collapse into one card per show`() {
        val rows = listOf(
            epRow("Ilusão Mortal", 1, 1),
            epRow("Ilusão Mortal", 1, 2),
            epRow("Instinto de Mãe", 1, 7)
        )
        val collapsed = collapseM3uEpisodeRows(rows)
        assertEquals(2, collapsed.size)
        val card = collapsed.first { it.name.startsWith("Ilusão Mortal") }
        assertEquals("Ilusão Mortal (2026)", card.name)
        assertEquals("2026", card.year)
        assertEquals(MediaType.SERIES, card.mediaType)
        assertNull(card.episodeNum)
        assertTrue(card.id.startsWith("m3u-show:"))
        // Episodes stay addressable - the detail screen matches them by this id.
        assertEquals(card.id, rows[0].categoryId)
        assertEquals(card.id, rows[1].categoryId)
    }

    @Test
    fun `same show on two providers collapses separately`() {
        val rows = listOf(epRow("Show", 1, 1, "prov-1"), epRow("Show", 1, 1, "prov-2"))
        val collapsed = collapseM3uEpisodeRows(rows)
        assertEquals(2, collapsed.size)
        assertTrue(collapsed.all { it.id.startsWith("m3u-show:") })
        assertEquals(
            setOf("prov-1", "prov-2"),
            collapsed.map { it.sourceProviderId }.toSet()
        )
    }

    @Test
    fun `non-episode rows pass through untouched`() {
        val live = Channel(id = "x", name = "BBC One", url = "http://ex.com/1.ts", mediaType = MediaType.LIVE)
        val collapsed = collapseM3uEpisodeRows(listOf(live))
        assertEquals(listOf(live), collapsed)
    }

    @Test
    fun `duplicate grouping merges collapsed show with same-name show row`() {
        // An Xtream-style show-level row plus this provider's episode rows for the same show.
        val xtreamShow = Channel(
            id = "series-42", name = "Ilusão Mortal", url = "",
            mediaType = MediaType.SERIES, sourceProviderId = "prov-xtream"
        )
        val (grouped, versions) = groupDuplicateSeries(
            listOf(xtreamShow, epRow("Ilusão Mortal", 1, 1), epRow("Ilusão Mortal", 1, 2))
        )
        assertEquals(1, grouped.size)
        assertEquals(2, versions.getValue(grouped.single().id).size)
    }
}
