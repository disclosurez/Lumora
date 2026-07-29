package com.lumora.player.playback

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * Manages decoder mode, buffer mode, and surface mode preferences.
 * Allows per-session tuning of the ExoPlayer pipeline for compatibility.
 */
class DecoderModeManager(private val context: Context) {

    private val PREFS_NAME = "iptv_prefs"
    private val KEY_DECODER_MODE = "decoder_mode"        // AUTO, HARDWARE, SOFTWARE
    private val KEY_BUFFER_MODE = "buffer_mode"           // DEFAULT, LOW_LATENCY, HIGH_QUALITY
    private val KEY_SURFACE_MODE = "surface_mode"         // AUTO, SURFACE_VIEW, TEXTURE_VIEW

    enum class DecoderMode(val label: String) {
        AUTO("Auto"),
        HARDWARE("Hardware"),
        SOFTWARE("Software")
    }

    enum class BufferMode(val label: String) {
        DEFAULT("Default"),
        LOW_LATENCY("Low Latency"),
        HIGH_QUALITY("High Quality")
    }

    enum class SurfaceMode(val label: String) {
        AUTO("Auto"),
        SURFACE_VIEW("SurfaceView"),
        TEXTURE_VIEW("TextureView")
    }

    data class PlaybackSettings(
        val decoderMode: DecoderMode = DecoderMode.AUTO,
        val bufferMode: BufferMode = BufferMode.DEFAULT,
        val surfaceMode: SurfaceMode = SurfaceMode.AUTO,
        val enableFfmpeg: Boolean = true
    )

    private var currentSettings = load()

    fun load(): PlaybackSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return PlaybackSettings(
            decoderMode = runCatching { DecoderMode.valueOf(prefs.getString(KEY_DECODER_MODE, "AUTO") ?: "AUTO") }
                .getOrDefault(DecoderMode.AUTO),
            bufferMode = runCatching { BufferMode.valueOf(prefs.getString(KEY_BUFFER_MODE, "DEFAULT") ?: "DEFAULT") }
                .getOrDefault(BufferMode.DEFAULT),
            surfaceMode = runCatching { SurfaceMode.valueOf(prefs.getString(KEY_SURFACE_MODE, "AUTO") ?: "AUTO") }
                .getOrDefault(SurfaceMode.AUTO),
            enableFfmpeg = prefs.getBoolean("enable_ffmpeg", true)
        )
    }

    fun save(settings: PlaybackSettings) {
        currentSettings = settings
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_DECODER_MODE, settings.decoderMode.name)
            .putString(KEY_BUFFER_MODE, settings.bufferMode.name)
            .putString(KEY_SURFACE_MODE, settings.surfaceMode.name)
            .putBoolean("enable_ffmpeg", settings.enableFfmpeg)
            .apply()
    }

    fun getSettings(): PlaybackSettings = currentSettings

    /**
     * Retry count for the current buffer mode, for whoever builds the MediaSource.Factory -
     * ExoPlayer itself has no runtime retry-count setter, this has to go through
     * DefaultLoadErrorHandlingPolicy(minLoadableRetryCount) at MediaSource-creation time.
     */
    fun loadRetryCount(): Int = when (currentSettings.bufferMode) {
        BufferMode.LOW_LATENCY -> 1
        BufferMode.HIGH_QUALITY -> Int.MAX_VALUE
        BufferMode.DEFAULT -> 3
    }

    /**
     * Cycle decoder mode: AUTO -> HARDWARE -> SOFTWARE -> AUTO.
     */
    fun cycleDecoderMode(): DecoderMode {
        val modes = DecoderMode.entries
        val next = modes[(modes.indexOf(currentSettings.decoderMode) + 1) % modes.size]
        save(currentSettings.copy(decoderMode = next))
        return next
    }

    /**
     * Cycle buffer mode.
     */
    fun cycleBufferMode(): BufferMode {
        val modes = BufferMode.entries
        val next = modes[(modes.indexOf(currentSettings.bufferMode) + 1) % modes.size]
        save(currentSettings.copy(bufferMode = next))
        return next
    }

    /**
     * Returns true if the current decoder/surface settings cannot be applied at runtime
     * and require a player rebuild to take effect. Decoder mode and surface mode are
     * selected at ExoPlayer construction time; changes after the fact are ignored.
     */
    fun needsPlayerRebuild(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedDecoder = prefs.getString(KEY_DECODER_MODE, "AUTO")
        val savedSurface = prefs.getString(KEY_SURFACE_MODE, "AUTO")
        return savedDecoder != currentSettings.decoderMode.name || savedSurface != currentSettings.surfaceMode.name
    }

    /** Log and return a summary of current decoder settings as a formatted string. */
    fun logCurrentSettings(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return "Decoder: ${prefs.getString("decoder_mode", "auto")}, Buffer: ${prefs.getString("buffer_mode", "default")}, Surface: ${prefs.getString("surface_mode", "auto")}, FFmpeg: ${prefs.getBoolean("enable_ffmpeg", true)}"
    }
}
