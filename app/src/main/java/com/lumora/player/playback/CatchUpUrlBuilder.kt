package com.lumora.player.playback

import com.lumora.model.Provider
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds catch-up/timeshift stream URLs for providers that support archive playback.
 * Xtream: ?start=XXXX&duration=YYYY
 * Stalker: uses create_link with offset parameter
 */
object CatchUpUrlBuilder {

    /**
     * The `/timeshift/` form, which is what Xtream panels actually serve archive playback
     * from and what other clients request:
     *
     *     /timeshift/{user}/{pass}/{duration_minutes}/{yyyy-MM-dd:HH-mm}/{stream_id}.ts
     *
     * The start time is formatted in the *device's* local zone rather than UTC: the panel
     * interprets it against its own clock, and the listings this is called with have
     * already been shifted onto device-local time by XtreamClient's epoch correction, so
     * formatting as UTC here would re-introduce the offset that correction removed.
     *
     * [buildXtreamCatchUpUrl]'s `/live/…?start=&duration=` form is the older query-string
     * variant; some panels accept only one of the two.
     */
    fun buildXtreamTimeshiftUrl(
        provider: Provider,
        streamId: String,
        startTimestampSeconds: Long,
        durationMinutes: Int
    ): String? {
        val base = provider.serverUrl?.trimEnd('/') ?: return null
        val user = URLEncoder.encode(provider.username.orEmpty(), "UTF-8")
        val pass = URLEncoder.encode(provider.password.orEmpty(), "UTF-8")
        val stamp = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
            .format(Date(startTimestampSeconds * 1000L))
        return "$base/timeshift/$user/$pass/${durationMinutes.coerceAtLeast(1)}/$stamp/$streamId.ts"
    }
}
