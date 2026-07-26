package com.lumora.player

import android.content.Context
import android.graphics.Color

/**
 * Manages subtitle styling preferences.
 * Controls font size, foreground color, background color.
 * Stores preferences and applies them when rendering subtitles.
 */
class SubtitleStyleManager(private val context: Context) {

    private val PREFS_NAME = "iptv_prefs"
    private val KEY_TEXT_SIZE = "subtitle_text_size"
    private val KEY_FOREGROUND_COLOR = "subtitle_foreground_color"
    private val KEY_BACKGROUND_COLOR = "subtitle_background_color"

    data class SubtitleStyle(
        val textSizePercent: Int = 100,  // 50-200%
        val foregroundColor: Int = Color.WHITE,
        val backgroundColor: Int = Color.argb(120, 0, 0, 0)
    )

    private var currentStyle = loadStyle()

    fun loadStyle(): SubtitleStyle {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return SubtitleStyle(
            textSizePercent = prefs.getInt(KEY_TEXT_SIZE, 100),
            foregroundColor = prefs.getInt(KEY_FOREGROUND_COLOR, Color.WHITE),
            backgroundColor = prefs.getInt(KEY_BACKGROUND_COLOR, Color.argb(120, 0, 0, 0))
        )
    }

    fun saveStyle(style: SubtitleStyle) {
        currentStyle = style
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_TEXT_SIZE, style.textSizePercent)
            .putInt(KEY_FOREGROUND_COLOR, style.foregroundColor)
            .putInt(KEY_BACKGROUND_COLOR, style.backgroundColor)
            .apply()
    }

    fun getStyle(): SubtitleStyle = currentStyle

    /**
     * Cycle to next font size preset.
     */
    fun cycleTextSize(): Int {
        val presets = intArrayOf(50, 75, 100, 125, 150, 175, 200)
        val current = currentStyle.textSizePercent
        val nextIndex = (presets.indexOf(current) + 1).coerceAtMost(presets.size - 1)
        val newSize = presets[nextIndex.coerceAtLeast(0)]
        saveStyle(currentStyle.copy(textSizePercent = newSize))
        return newSize
    }
}
