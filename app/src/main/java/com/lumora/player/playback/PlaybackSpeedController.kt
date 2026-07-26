package com.lumora.player.playback

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Controls playback speed for VOD content.
 * Supports 0.25x to 3.0x speed range.
 */
class PlaybackSpeedController(private val player: ExoPlayer) {

    private val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

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
     * Cycle to the next preset speed step.
     */
    fun cycleSpeedUp(): Float {
        val index = speeds.indexOfFirst { it > currentSpeed + 0.01f }
        return if (index >= 0) {
            setSpeed(speeds[index])
            currentSpeed
        } else {
            currentSpeed
        }
    }

    /**
     * Cycle to the previous preset speed step.
     */
    fun cycleSpeedDown(): Float {
        val index = speeds.indexOfLast { it < currentSpeed - 0.01f }
        return if (index >= 0) {
            setSpeed(speeds[index])
            currentSpeed
        } else {
            currentSpeed
        }
    }

    /**
     * Reset to normal speed (1.0x).
     */
    fun resetSpeed() {
        setSpeed(1.0f)
    }
}
