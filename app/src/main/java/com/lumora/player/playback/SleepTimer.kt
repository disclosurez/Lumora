package com.lumora.player.playback

import android.os.CountDownTimer
import androidx.media3.common.Player

/**
 * Configurable sleep timer that stops playback after a set duration.
 * Preset intervals: 15, 30, 45, 60, 90, 120 minutes.
 */
class SleepTimer(private val player: Player) {

    enum class Preset(val label: String, val millis: Long) {
        OFF("Off", 0),
        MIN_15("15 min", 15 * 60 * 1000L),
        MIN_30("30 min", 30 * 60 * 1000L),
        MIN_45("45 min", 45 * 60 * 1000L),
        MIN_60("60 min", 60 * 60 * 1000L),
        MIN_90("90 min", 90 * 60 * 1000L),
        MIN_120("120 min", 120 * 60 * 1000L)
    }

    private var timer: CountDownTimer? = null
    var currentPreset: Preset = Preset.OFF
        private set
    var remainingMillis: Long = 0
        private set
    var onSleep: (() -> Unit)? = null

    /**
     * Start the sleep timer with a given preset.
     */
    fun start(preset: Preset) {
        stop()
        if (preset == Preset.OFF) return

        currentPreset = preset
        remainingMillis = preset.millis

        timer = object : CountDownTimer(preset.millis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                remainingMillis = millisUntilFinished
            }

            override fun onFinish() {
                remainingMillis = 0
                player.stop()
                onSleep?.invoke()
            }
        }.start()
    }

    /**
     * Stop the sleep timer.
     */
    fun stop() {
        timer?.cancel()
        timer = null
        currentPreset = Preset.OFF
        remainingMillis = 0
    }

    /**
     * Add 15 more minutes to the current timer.
     */
    fun addTime() {
        if (currentPreset == Preset.OFF) return
        val newDuration = remainingMillis + 15 * 60 * 1000L
        start(Preset.OFF) // hack: stop existing
        timer = object : CountDownTimer(newDuration, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                remainingMillis = millisUntilFinished
            }

            override fun onFinish() {
                remainingMillis = 0
                player.stop()
                onSleep?.invoke()
            }
        }.start()
    }

    fun isActive(): Boolean = timer != null

    fun getRemainingDisplay(): String {
        if (!isActive()) return "Off"
        val totalSec = remainingMillis / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }
}
