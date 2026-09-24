package com.lumora.parser

import com.lumora.model.MediaType
import com.lumora.model.Provider
import com.lumora.model.ProviderType
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mapping half of the streaming Xtream bulk parser (issue #8).
 *
 * The JsonReader plumbing around these can't execute on the JVM
 * (android.util.JsonReader is a stub there), so what these pin down is the
 * field mapping itself: every quirk the old org.json path handled must survive
 * the move - numeric-shaped flags, literal "null" strings, the plural
 * category_ids fallback, container defaults, archive flags and trailer keys.
 */
class XtreamBulkMappingTest {

    private val client = XtreamClient(OkHttpClient())
    private val provider = Provider(
        name = "P", type = ProviderType.XTREAM,
        serverUrl = "http://panel.example:8080", username = "user", password = "pass"
    )

    private fun live(map: Map<String, String?>, firstCat: String? = null) =
        client.streamFromMap(map, firstCat, MediaType.LIVE, provider)!!

    // ── streams ──

    @Test
    fun `live stream maps with built url`() {
        val ch = live(
            mapOf(
                "stream_id" to "101", "name" to "BBC One",
                "stream_icon" to "http://ex.com/bbc.png",
                "category_id" to "5", "container_extension" to "ts"
            )
        )
        assertEquals("101", ch.id)
        assertEquals("BBC One", ch.name)
        assertEquals("http://panel.example:8080/live/user/pass/101.ts", ch.url)
        assertEquals("http://ex.com/bbc.png", ch.logoUrl)
        assertNull(ch.posterUrl)
        assertEquals("5", ch.categoryId)
        assertEquals(MediaType.LIVE, ch.mediaType)
    }

    @Test
    fun `movie stream builds movie url with poster`() {
        val ch = client.streamFromMap(
            mapOf("stream_id" to "202", "name" to "A Film", "category_id" to "7"),
            null, MediaType.MOVIE, provider
        )!!
        assertEquals("http://panel.example:8080/movie/user/pass/202.mp4", ch.url)
        assertNull(ch.posterUrl) // no icon sent
    }

    @Test
    fun `missing stream id drops the row`() {
        assertNull(client.streamFromMap(mapOf("name" to "No Id"), null, MediaType.LIVE, provider))
        assertNull(client.streamFromMap(mapOf("stream_id" to ""), null, MediaType.LIVE, provider))
    }

    @Test
    fun `literal null strings treated as absent`() {
        val ch = live(mapOf("stream_id" to "1", "category_id" to "null", "category_name" to "null"))
        assertEquals("", ch.categoryId)
        assertEquals("", ch.categoryName)
    }

    @Test
    fun `plural category ids fall back to first entry`() {
        val ch = live(mapOf("stream_id" to "1"), firstCat = "9")
        assertEquals("9", ch.categoryId)
        // Singular wins when present.
        val ch2 = live(mapOf("stream_id" to "1", "category_id" to "4"), firstCat = "9")
        assertEquals("4", ch2.categoryId)
    }

    @Test
    fun `archive flags read in string shape`() {
        val ch = live(
            mapOf(
                "stream_id" to "1", "tv_archive" to "1",
                "tv_archive_duration" to "7"
            )
        )
        assertTrue(ch.tvArchive)
        assertEquals(7, ch.tvArchiveDays)
        // Advertised archive with zero days kept counts as no archive.
        val ch2 = live(mapOf("stream_id" to "1", "tv_archive" to "1", "tv_archive_duration" to "0"))
        assertFalse(ch2.tvArchive)
    }

    @Test
    fun `tmdb zero means no id, trailer key extracted`() {
        val ch = live(mapOf("stream_id" to "1", "tmdb" to "0", "trailer" to "dQw4w9WgXcQ"))
        assertNull(ch.tmdbId)
        assertEquals("dQw4w9WgXcQ", ch.trailerKey)
        val ch2 = live(mapOf("stream_id" to "1", "tmdb" to "123"))
        assertEquals("123", ch2.tmdbId)
    }

    // ── series ──

    @Test
    fun `series item maps with cover and release date`() {
        val ch = client.seriesFromMap(
            mapOf(
                "series_id" to "55", "name" to "A Show", "cover" to "http://ex.com/c.png",
                "category_id" to "3", "release_date" to "2024-01-01", "youtube_trailer" to "abc"
            )
        )!!
        assertEquals("55", ch.id)
        assertEquals("", ch.url)
        assertEquals("http://ex.com/c.png", ch.posterUrl)
        assertEquals(MediaType.SERIES, ch.mediaType)
        assertEquals("2024-01-01", ch.releaseDate)
        assertEquals("abc", ch.trailerKey)
    }

    @Test
    fun `series without id dropped`() {
        assertNull(client.seriesFromMap(mapOf("name" to "No Id")))
    }

    // ── categories ──

    @Test
    fun `category maps id and cleans nbsp names`() {
        val (id, name) = client.categoryFromMap(
            mapOf("category_id" to "12", "category_name" to "Sports\u00A0\u00A0  4K")
        )!!
        assertEquals("12", id)
        assertEquals("Sports 4K", name)
    }

    @Test
    fun `category without id dropped`() {
        assertNull(client.categoryFromMap(mapOf("category_name" to "No Id")))
    }
}
