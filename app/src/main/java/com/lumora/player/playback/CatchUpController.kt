package com.lumora.player.playback

import android.content.Context
import android.util.Log
import com.lumora.parser.XtreamClient
import com.lumora.model.Channel
import com.lumora.model.Provider
import com.lumora.model.ProviderType

/**
 * Controls catch-up / timeshift playback for live TV.
 * Detects if a channel supports catch-up, builds the appropriate URL,
 * and manages the timeshift buffer for pause/rewind.
 */
class CatchUpController(private val context: Context) {

    private val TAG = "CatchUpController"
    private var timeshiftBuffer: TimeshiftBuffer? = null

    /**
     * Check if a channel supports catch-up playback.
     */
    fun supportsCatchUp(channel: Channel, provider: Provider): Boolean {
        return provider.type == ProviderType.XTREAM && channel.tvgId != null
    }

    /**
     * Build a catch-up URL for a program that already aired.
     */
    fun buildCatchUpUrl(
        channel: Channel,
        provider: Provider,
        startTimestamp: Long,
        durationSeconds: Int = 7200
    ): String? {
        return CatchUpUrlBuilder.buildXtreamCatchUpUrl(
            provider = provider,
            streamId = channel.id,
            containerExtension = "ts",
            startTimestamp = startTimestamp,
            durationSeconds = durationSeconds
        )
    }

    /**
     * Initialize the timeshift buffer for live pause/rewind.
     */
    fun initTimeshift(maxMinutes: Int = 30) {
        if (timeshiftBuffer == null) {
            timeshiftBuffer = TimeshiftBuffer(context)
            timeshiftBuffer!!.init(maxMinutes)
            Log.d(TAG, "Timeshift buffer initialized ($maxMinutes min)")
        }
    }

    /**
     * Get the timeshift buffer for live pause/rewind.
     */
    fun getTimeshiftBuffer(): TimeshiftBuffer? = timeshiftBuffer

    /**
     * Release timeshift resources.
     */
    fun release() {
        timeshiftBuffer?.release()
        timeshiftBuffer = null
    }
}
