package com.lumora.parser

import com.lumora.util.isLocalFileUrl
import com.lumora.util.localFileDisplayName
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local .m3u files: source detection, paths with spaces, and relative entries.
 *
 * A playlist saved on the device is the same format as a remote one - the differences
 * are all at the edges: recognising a local path/URI so it isn't fed to OkHttp, not
 * truncating file paths at spaces (remote URLs strip trailing parameters there), and
 * resolving entries listed relative to the playlist file.
 */
class M3uLocalFileTest {

    // ── isLocalFileUrl ──

    @Test
    fun `saf content uri is local`() {
        assertTrue(isLocalFileUrl("content://com.android.providers.downloads.documents/document/msd%3A1234"))
    }

    @Test
    fun `file uri and absolute path are local`() {
        assertTrue(isLocalFileUrl("file:///storage/emulated/0/Download/list.m3u"))
        assertTrue(isLocalFileUrl("/storage/emulated/0/Download/list.m3u"))
    }

    @Test
    fun `remote urls are not local`() {
        assertFalse(isLocalFileUrl("https://example.com/playlist.m3u"))
        assertFalse(isLocalFileUrl("http://example.com:8080/get.php?user=x&pass=y"))
        // Bare host paste without a scheme - still a network playlist, not a file.
        assertFalse(isLocalFileUrl("example.com:8080/playlist.m3u"))
    }

    @Test
    fun `local file display name shows file name`() {
        assertEquals(
            "list.m3u",
            localFileDisplayName("content://com.android.providers.downloads.documents/document/primary%3ADownload%2Flist.m3u")
        )
        assertEquals("list.m3u", localFileDisplayName("/storage/emulated/0/Download/list.m3u"))
    }

    // ── parsing ──

    @Test
    fun `local absolute path with spaces is kept whole`() {
        val channels = M3uParser.parse(
            "#EXTM3U\n#EXTINF:-1,BBC One\n/storage/emulated/0/My Playlists/bbc one.ts\n"
        ).channels
        assertEquals("/storage/emulated/0/My Playlists/bbc one.ts", channels.single().url)
    }

    @Test
    fun `remote url trailing parameters still stripped`() {
        val channels = M3uParser.parse(
            "#EXTM3U\n#EXTINF:-1,BBC One\nhttps://ex.com/1.ts extra-param\n"
        ).channels
        assertEquals("https://ex.com/1.ts", channels.single().url)
    }

    @Test
    fun `http streams inside a local playlist keep working`() {
        val channels = M3uParser.parse(
            "#EXTM3U\n#EXTINF:-1 group-title=\"Movies\",A Film\nhttps://ex.com/movie.mkv\n",
            baseDir = File("/storage/emulated/0/Download")
        ).channels
        assertEquals("https://ex.com/movie.mkv", channels.single().url)
    }

    @Test
    fun `relative entry resolves against playlist directory`() {
        val channels = M3uParser.parse(
            "#EXTM3U\n#EXTINF:-1,A Film\nmovies/film.mp4\n",
            baseDir = File("/storage/emulated/0/Download")
        ).channels
        assertEquals("/storage/emulated/0/Download/movies/film.mp4", channels.single().url)
    }
}
