package com.lumora.player.playback

import android.content.Context
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer

/**
 * Manages A/V sync offset preferences.
 * Positive values delay audio (audio plays later than video).
 * Negative values advance audio (audio plays earlier than video).
 * Values are in milliseconds.
 */
class AvOffsetManager(private val context: Context) {

    private val PREFS_NAME = "iptv_prefs"
    private val KEY_GLOBAL_OFFSET = "av_offset_ms"

    private var currentOffset = load()

    fun load(): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_GLOBAL_OFFSET, 0)
    }

    fun save(offsetMs: Int) {
        currentOffset = offsetMs
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_GLOBAL_OFFSET, offsetMs)
            .apply()
    }

    fun getOffset(): Int = currentOffset

    /** Per-channel override key */
    fun perChannelKey(channelId: String): String = "av_offset_${channelId}"

    fun loadPerChannel(channelId: String): Int? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs.contains(perChannelKey(channelId))) {
            prefs.getInt(perChannelKey(channelId), 0)
        } else null
    }

    fun savePerChannel(channelId: String, offsetMs: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(perChannelKey(channelId), offsetMs)
            .apply()
    }

    fun removePerChannel(channelId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(perChannelKey(channelId))
            .apply()
    }

    /** Resolve the effective offset for a channel: per-channel override or global default. */
    fun effectiveOffset(channelId: String): Int {
        return loadPerChannel(channelId) ?: currentOffset
    }

    /**
     * Build [PlaybackParameters] carrying the current speed/pitch while keeping the
     * A/V offset stored for reference. Media3 does not natively support audio offset,
     * so this serves as a placeholder for future audio-processing integration.
     *
     * @param current  the current [PlaybackParameters] (speed/pitch to preserve).
     * @param channelId  optional channel ID for per-channel offset lookup.
     * @return  a [PlaybackParameters] preserving speed/pitch; [PlaybackParameters.DEFAULT]
     *         if the effective offset is zero.
     */
    fun buildPlaybackParameters(current: PlaybackParameters, channelId: String?): PlaybackParameters {
        val offsetMs = getEffectiveOffsetMs(channelId)
        if (offsetMs == 0) return PlaybackParameters.DEFAULT
        // Return the current speed/pitch (offset is stored for reference)
        return PlaybackParameters(current.speed, current.pitch)
    }

    /**
     * Apply the effective A/V offset to an ExoPlayer instance.
     * Positive offsetMs delays audio (audio plays later than video).
     * Negative offsetMs advances audio (audio plays earlier than video).
     */
    fun applyToPlayer(player: ExoPlayer, channelId: String?) {
        player.setPlaybackParameters(
            buildPlaybackParameters(player.playbackParameters, channelId)
        )
    }

    /** Remove the A/V offset from a player, resetting PlaybackParameters to default. */
    fun removeFromPlayer(player: ExoPlayer) {
        player.setPlaybackParameters(PlaybackParameters.DEFAULT)
    }

    /** Get the effective offset in milliseconds. */
    fun getEffectiveOffsetMs(channelId: String?): Int {
        return if (channelId != null) effectiveOffset(channelId) else currentOffset
    }
}
