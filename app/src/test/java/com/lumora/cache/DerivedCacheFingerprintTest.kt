package com.lumora.cache

import com.lumora.model.Channel
import com.lumora.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The derived-cache key has to move when the data the derive reads moves.
 *
 * Regression pinned here: the derived series file written before M3U episode rows were
 * stamped with episodeNum stayed valid after the upgrade (the raw catalogue was
 * byte-identical as far as the old hash was concerned), so the app kept serving its
 * un-collapsed episode-per-card series list and never re-ran the collapse - which is
 * what left every M3U show opening straight into "Find & Play".
 */
class DerivedCacheFingerprintTest {

    private fun row(name: String, episode: Int? = null, category: String? = null) = Channel(
        id = "http://ex.com/series/$name-$episode.mp4",
        name = name,
        url = "http://ex.com/series/$name-$episode.mp4",
        mediaType = MediaType.SERIES,
        episodeNum = episode,
        categoryId = category,
        sourceProviderId = "prov-1"
    )

    @Test
    fun `episode stamp changes the fingerprint`() {
        val unstamped = listOf(row("Show (2026) S01E01"), row("Show (2026) S01E02"))
        val stamped = listOf(
            row("Show (2026) S01E01", episode = 1, category = "m3u-show:show"),
            row("Show (2026) S01E02", episode = 2, category = "m3u-show:show")
        )
        assertNotEquals(
            DerivedCache.catalogFingerprint(unstamped, "vod:true:true:true"),
            DerivedCache.catalogFingerprint(stamped, "vod:true:true:true")
        )
    }

    @Test
    fun `identical catalogues keep the cache warm`() {
        val a = listOf(row("Show (2026) S01E01", 1, "m3u-show:show"))
        val b = listOf(row("Show (2026) S01E01", 1, "m3u-show:show"))
        assertEquals(
            DerivedCache.catalogFingerprint(a, "vod:true:true:true"),
            DerivedCache.catalogFingerprint(b, "vod:true:true:true")
        )
    }

    @Test
    fun `prefs part still separates the two halves`() {
        val list = listOf(row("Show (2026) S01E01", 1, "m3u-show:show"))
        assertNotEquals(
            DerivedCache.catalogFingerprint(list, "live:true:false:true"),
            DerivedCache.catalogFingerprint(list, "vod:true:true:true")
        )
    }
}
