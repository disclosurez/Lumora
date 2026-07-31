package com.lumora.parser

import com.lumora.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Media-type classification for M3U entries (GitHub issue #1).
 *
 * The cases that matter are the ones where the signals disagree: a group name that contains
 * both a series word and "vod", a category name that says nothing at all, and live groups whose
 * names happen to contain fragments of the VOD vocabulary. Those are what the old
 * group-title-only classifier got wrong, and they are cheap to pin down here rather than by
 * reinstalling and browsing a real provider's catalogue.
 */
class M3uParserClassificationTest {

    private fun classify(extInf: String, url: String): MediaType =
        M3uParser.parse("#EXTM3U\n#EXTINF:-1 $extInf\n$url\n").channels.single().mediaType

    // ── tvg-type wins outright ──

    @Test
    fun `tvg-type series classifies as series despite a vod group`() {
        assertEquals(
            MediaType.SERIES,
            classify("""tvg-type="series" group-title="TV VOD",Some Show""", "https://ex.com/x/1.mkv")
        )
    }

    @Test
    fun `tvg-type movie classifies as movie`() {
        assertEquals(
            MediaType.MOVIE,
            classify("""tvg-type="movie" group-title="Movie VOD",Perfeitos Desconhecidos 2025""", "https://ex.com/x/1.mkv")
        )
    }

    @Test
    fun `tvg-type live overrides a movie-sounding group`() {
        assertEquals(
            MediaType.LIVE,
            classify("""tvg-type="live" group-title="Movies",BBC One""", "https://ex.com/x/1.ts")
        )
    }

    // ── Xtream URL structure, for playlists that name groups by genre ──

    @Test
    fun `series url classifies a genre-named group as series`() {
        assertEquals(
            MediaType.SERIES,
            classify("""group-title="Comedy",Some Show""", "https://ex.com/series/user/pass/1.mkv")
        )
    }

    @Test
    fun `movie url classifies a genre-named group as movie`() {
        assertEquals(
            MediaType.MOVIE,
            classify("""group-title="Action",Some Film""", "https://ex.com/movie/user/pass/1.mkv")
        )
    }

    @Test
    fun `live url classifies a genre-named group as live`() {
        assertEquals(
            MediaType.LIVE,
            classify("""group-title="Documentary",Some Channel""", "https://ex.com/live/user/pass/1.ts")
        )
    }

    // ── Group keywords, series before movies ──

    @Test
    fun `tv vod group is series, not movies`() {
        assertEquals(MediaType.SERIES, classify("""group-title="TV VOD",Some Show""", "https://ex.com/1.mkv"))
    }

    @Test
    fun `group naming both series and vod resolves to series`() {
        assertEquals(MediaType.SERIES, classify("""group-title="Series VOD",Some Show""", "https://ex.com/1.mkv"))
        assertEquals(MediaType.SERIES, classify("""group-title="VOD - TV Shows",Some Show""", "https://ex.com/1.mkv"))
    }

    @Test
    fun `season keyword classifies as series`() {
        assertEquals(MediaType.SERIES, classify("""group-title="Seasons",Some Show""", "https://ex.com/1.mkv"))
    }

    // ── Regressions: what must keep working ──

    @Test
    fun `live groups containing tv stay live`() {
        assertEquals(MediaType.LIVE, classify("""group-title="UK TV",BBC One""", "https://ex.com/1.ts"))
        assertEquals(MediaType.LIVE, classify("""group-title="TV Sports",Sky Sports""", "https://ex.com/1.ts"))
    }

    @Test
    fun `no group and no type defaults to live`() {
        assertEquals(MediaType.LIVE, classify("""tvg-id="x",BBC One""", "https://ex.com/1.ts"))
    }

    @Test
    fun `plain vod and movie groups still classify as movies`() {
        assertEquals(MediaType.MOVIE, classify("""group-title="Movies",A Film""", "https://ex.com/1.mkv"))
        assertEquals(MediaType.MOVIE, classify("""group-title="VOD",A Film""", "https://ex.com/1.mkv"))
        assertEquals(MediaType.MOVIE, classify("""group-title="Peliculas",A Film""", "https://ex.com/1.mkv"))
    }

    @Test
    fun `plain series group still classifies as series`() {
        assertEquals(MediaType.SERIES, classify("""group-title="Series",A Show""", "https://ex.com/1.mkv"))
    }
}
