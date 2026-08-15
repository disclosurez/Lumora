package com.lumora.data.remote.plex

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Guards the two URL shapes Plex playback depends on, both of which look malformed and are
 * not:
 *
 *  - Plex's control endpoints put a bare `:` in the middle of the path (`/:/timeline`,
 *    `/video/:/transcode/universal/decision`). If the URL parser rejected those, every
 *    timeline report and every transcode decision would silently no-op.
 *  - The transcode client profile is a clause list containing literal `&` and `=`, and it has
 *    to reach the server as *one* parameter value. Building the query by hand would split it
 *    into a dozen junk parameters and the server would answer with a decision for a profile
 *    nobody asked for.
 */
class PlexUrlBuildingTest {

    @Test
    fun controlPathsWithBareColonSegmentsParse() {
        assertNotNull("http://10.0.0.5:32400/:/timeline".toHttpUrlOrNull())
        assertNotNull("http://10.0.0.5:32400/:/scrobble".toHttpUrlOrNull())
        assertNotNull("http://10.0.0.5:32400/video/:/transcode/universal/decision".toHttpUrlOrNull())
        assertNotNull("http://10.0.0.5:32400/video/:/transcode/universal/start.m3u8".toHttpUrlOrNull())
    }

    @Test
    fun clientProfileExtraSurvivesAsOneParameter() {
        val profile = "add-transcode-target(type=videoProfile&context=streaming" +
            "&protocol=hls&container=mpegts&videoCodec=h264&audioCodec=aac)" +
            "+add-settings(DirectPlayStreamSelection=true)"
        val url = "http://10.0.0.5:32400/video/:/transcode/universal/decision"
            .toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("X-Plex-Client-Profile-Extra", profile)
            .addQueryParameter("protocol", "hls")
            .build()

        assertEquals(2, url.querySize)
        assertEquals(profile, url.queryParameter("X-Plex-Client-Profile-Extra"))
        assertEquals("hls", url.queryParameter("protocol"))
    }
}
