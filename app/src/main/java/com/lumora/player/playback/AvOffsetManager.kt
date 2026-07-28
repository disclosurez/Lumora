package com.lumora.player.playback

import android.content.Context

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
}
