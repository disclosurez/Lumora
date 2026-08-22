package com.lumora.parser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Display-name extraction from the EXTINF line.
 *
 * The title is everything after the first comma that sits outside double-quoted attribute
 * values. The old lastIndexOf(',') separator truncated any title containing a comma of its
 * own - "Breaking Bad, Season 5" came out as "Season 5" - because the attributes' quoted
 * values are exactly where commas are common (group-title="Films, Drama").
 */
class M3uExtInfTitleTest {

    private fun name(extInf: String): String =
        M3uParser.parse("#EXTM3U\n#EXTINF:$extInf\nhttps://ex.com/stream/1.ts\n").channels.single().name

    @Test
    fun `title with a comma after the attributes is kept whole`() {
        assertEquals(
            "Breaking Bad, Season 5",
            name("""-1 tvg-id="bb05" tvg-name="Breaking Bad" group-title="Series",Breaking Bad, Season 5""")
        )
    }

    @Test
    fun `commas inside quoted attribute values are not separators`() {
        // Both attributes carry commas; neither may split the title off early.
        assertEquals(
            "NCIS, Los Angeles",
            name("""-1 tvg-name="CSI, Las Vegas" group-title="Crime, Drama",NCIS, Los Angeles""")
        )
    }

    @Test
    fun `well formed single comma line is unchanged`() {
        assertEquals("CNN", name("""-1 tvg-id="cnn" group-title="News",CNN"""))
        // No attributes at all - the historical shape of a bare playlist entry.
        assertEquals("CNN", name("-1,CNN"))
    }

    @Test
    fun `no comma leaves the generated fallback name`() {
        val fallback = "Channel ${"https://ex.com/stream/1.ts".hashCode()}"
        assertEquals(fallback, name("""-1 tvg-id="x" group-title="News""""))
    }

    @Test
    fun `surrounding whitespace around the title is trimmed`() {
        assertEquals(
            "BBC World News",
            name("""-1 group-title="News",   BBC World News   """)
        )
    }
}
