package com.lumora.player.playback

import com.lumora.model.Provider
import com.lumora.model.ProviderType
import java.net.URLEncoder

/**
 * Builds catch-up/timeshift stream URLs for providers that support archive playback.
 * Xtream: ?start=XXXX&duration=YYYY
 * Stalker: uses create_link with offset parameter
 */
object CatchUpUrlBuilder {

    /**
     * Build a catch-up URL for an Xtream provider.
     */
    fun buildXtreamCatchUpUrl(
        provider: Provider,
        streamId: String,
        containerExtension: String,
        startTimestamp: Long,
        durationSeconds: Int = 7200 // default 2 hours
    ): String? {
        val base = provider.serverUrl ?: return null
        val user = URLEncoder.encode(provider.username.orEmpty(), "UTF-8")
        val pass = URLEncoder.encode(provider.password.orEmpty(), "UTF-8")
        // Xtream catch-up: /live/username/password/streamid.ext?start=123&duration=456
        return "$base/live/$user/$pass/$streamId.$containerExtension" +
                "?start=$startTimestamp&duration=$durationSeconds"
    }

    /**
     * Build a catch-up URL for Stalker providers that support timeshift.
     */
    fun buildStalkerCatchUpUrl(
        serverUrl: String,
        channelCmd: String,
        startTimestamp: Long,
        durationSeconds: Int
    ): String {
        val base = serverUrl.trimEnd('/')
        return "$base/stalker_portal/${channelCmd.trimStart('/')}" +
                "?utc_start=$startTimestamp&duration=$durationSeconds"
    }
}
