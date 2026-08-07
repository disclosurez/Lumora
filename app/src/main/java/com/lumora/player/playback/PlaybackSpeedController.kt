package com.lumora.player.playback

import androidx.media3.exoplayer.ExoPlayer

/**
 * Controls playback speed for VOD content.
 * Supports 0.25x to 3.0x speed range.
 */
class PlaybackSpeedController(private val player: ExoPlayer) {

    var currentSpeed: Float = 1.0f
        private set

    /**
     * Set a specific playback speed.
     */
    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 3.0f)
        currentSpeed = clamped
        player.setPlaybackSpeed(clamped)
    }

    /**
     * Reset to normal speed (1.0x).
     */
    fun resetSpeed() {
        setSpeed(1.0f)
    }
}
