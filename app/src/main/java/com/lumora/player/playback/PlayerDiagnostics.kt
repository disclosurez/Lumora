package com.lumora.player.playback

import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.DefaultAnalyticsCollector
import java.util.Locale

/**
 * Collects and exposes real-time player diagnostics.
 * Tracks decoder info, bandwidth, video format, stall count, and A/V offset.
 */
class PlayerDiagnostics(private val player: ExoPlayer) {

    data class DiagnosticSnapshot(
        val videoDecoder: String = "N/A",
        val audioDecoder: String = "N/A",
        val videoFormat: String = "N/A",
        val audioFormat: String = "N/A",
        val bandwidthEstimate: String = "N/A",
        val stallCount: Int = 0,
        val totalStallDuration: Long = 0,
        val renderSurface: String = "N/A",
        val playbackState: String = "IDLE",
        val isUsingMediaCodec: Boolean = false,
        val audioOutputPath: String = "N/A",
        val ffmpegAvailable: Boolean = false,
        val lastFrameAge: Long = 0
    )

    private var videoDecoderName: String = "N/A"
    private var audioDecoderName: String = "N/A"
    private var videoFormat: Format? = null
    private var audioFormat: Format? = null
    private var stallCount = 0
    private var totalStallDurationMs = 0L
    private var lastStallStartMs = 0L
    private var lastFrameAgeUs: Long = 0L
    private var isBuffering = false

    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializationDurationMs: Long
        ) {
            videoDecoderName = decoderName
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializationDurationMs: Long
        ) {
            audioDecoderName = decoderName
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
        ) {
            videoFormat = format
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
        ) {
            audioFormat = format
        }

        override fun onPlaybackStateChanged(
            eventTime: AnalyticsListener.EventTime,
            state: Int
        ) {
            when (state) {
                Player.STATE_BUFFERING -> {
                    if (!isBuffering) {
                        isBuffering = true
                        lastStallStartMs = System.currentTimeMillis()
                    }
                }
                Player.STATE_READY -> {
                    if (isBuffering) {
                        stallCount++
                        totalStallDurationMs += System.currentTimeMillis() - lastStallStartMs
                        isBuffering = false
                    }
                }
            }
        }
    }

    /**
     * Get a snapshot of current diagnostics.
     */
    fun getSnapshot(): DiagnosticSnapshot {
        val tracks = player.currentTracks
        var hasVideo = false
        var hasAudio = false

        for (group in tracks.groups) {
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                when (format.sampleMimeType?.substringBefore("/")) {
                    "video" -> hasVideo = true
                    "audio" -> hasAudio = true
                }
            }
        }

        return DiagnosticSnapshot(
            videoDecoder = videoDecoderName,
            audioDecoder = audioDecoderName,
            videoFormat = formatToString(videoFormat),
            audioFormat = formatToString(audioFormat),
            bandwidthEstimate = formatBandwidth(player),
            stallCount = stallCount,
            totalStallDuration = totalStallDurationMs,
            playbackState = stateToString(player.playbackState),
            isUsingMediaCodec = videoDecoderName.contains("MediaCodec", ignoreCase = true),
            renderSurface = if (hasVideo) "SurfaceView" else "N/A",
            ffmpegAvailable = videoDecoderName.contains("FFmpeg", ignoreCase = true),
            lastFrameAge = lastFrameAgeUs
        )
    }

    fun getAnalyticsListener(): AnalyticsListener = analyticsListener

    fun resetStalls() {
        stallCount = 0
        totalStallDurationMs = 0L
    }

    private fun formatToString(format: Format?): String {
        if (format == null) return "N/A"
        val mime = format.sampleMimeType ?: "?"
        val width = format.width
        val height = format.height
        val bitrate = format.bitrate
        return if (width > 0 && height > 0) {
            "$mime ${width}x${height} ${formatBitrate(bitrate)}"
        } else {
            "$mime ${formatBitrate(bitrate)}"
        }
    }

    private fun formatBitrate(bitrate: Int): String {
        if (bitrate <= 0) return ""
        return if (bitrate > 1_000_000) {
            String.format(Locale.US, "%.1f Mbps", bitrate / 1_000_000f)
        } else {
            String.format(Locale.US, "%d kbps", bitrate / 1000)
        }
    }

    private fun formatBandwidth(player: ExoPlayer): String {
        // ExoPlayer doesn't directly expose bandwidth via public API in Media3
        return "N/A"
    }

    private fun stateToString(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN"
    }
}
