package com.lumora.player.playback

import android.util.Log
import androidx.media3.common.PlaybackException

/**
 * Classifies player errors and determines the appropriate recovery strategy.
 * Supports decoder fallback, format fallback, URL fallback, and user messaging.
 */
class PlayerErrorClassifier {

    private val TAG = "ErrorClassifier"

    enum class ErrorCategory {
        DECODER,            // Codec/decoder not supported
        NETWORK,            // Timeout, DNS failure, connection reset
        DRM,                // DRM not supported
        FORMAT,             // Container/codec format unsupported
        AUTH,               // Token expired, auth failed
        IO,                 // File not found, storage error
        UNKNOWN
    }

    enum class RecoveryAction {
        RETRY,                  // Simple retry
        FALLBACK_SOFTWARE_DECODER, // Retry with software decoder
        FALLBACK_FORMAT,        // Try alternate format (HLS ↔ MPEG-TS)
        FALLBACK_URL,           // Try alternate stream URL
        NOTIFY_USER,            // Show error message, no recovery
        NOTIFY_DRM_UNSUPPORTED  // DRM-specific message
    }

    data class Classification(
        val category: ErrorCategory,
        val action: RecoveryAction,
        val message: String = "Playback error"
    )

    /**
     * Classify a PlaybackException and determine recovery strategy.
     */
    fun classify(error: PlaybackException, currentUrl: String? = null): Classification {
        val message = (error.message ?: "").lowercase()
        val causeMessage = (error.cause?.message ?: "").lowercase()
        val combined = "$message $causeMessage"

        Log.d(TAG, "Classifying error: ${error.errorCodeName} — $combined")

        // DRM errors
        if (combined.contains("drm") || combined.contains("mediadrm") ||
            combined.contains("license") || error.errorCodeName?.contains("DRM") == true
        ) {
            return Classification(ErrorCategory.DRM, RecoveryAction.NOTIFY_DRM_UNSUPPORTED,
                "DRM content is not supported on this device")
        }

        // Decoder errors — can fall back to software
        if (combined.contains("decoder") || combined.contains("codec") ||
            combined.contains("mediacodec") || combined.contains("format not supported") ||
            error.errorCodeName?.contains("DECODER") == true
        ) {
            return Classification(ErrorCategory.DECODER, RecoveryAction.FALLBACK_SOFTWARE_DECODER,
                "Decoder error, switching to software mode")
        }

        // Network errors
        if (combined.contains("timeout") || combined.contains("dns") ||
            combined.contains("connect") || combined.contains("socket") ||
            combined.contains("network") || combined.contains("eof") ||
            combined.contains("http") || combined.contains("server returned") ||
            error.errorCodeName?.contains("IO") == true
        ) {
            return Classification(ErrorCategory.NETWORK, RecoveryAction.RETRY,
                "Network error, retrying...")
        }

        // IO errors
        if (combined.contains("file not found") || combined.contains("not found") ||
            combined.contains("404") || combined.contains("403")
        ) {
            return Classification(ErrorCategory.IO, RecoveryAction.FALLBACK_URL,
                "Stream not found, trying alternate source")
        }

        // Auth errors
        if (combined.contains("token") || combined.contains("auth") ||
            combined.contains("forbidden") || combined.contains("401")
        ) {
            return Classification(ErrorCategory.AUTH, RecoveryAction.FALLBACK_URL,
                "Auth error, refreshing...")
        }

        // Format errors — try alternate format
        if (combined.contains("format") || combined.contains("container") ||
            combined.contains("mime") || combined.contains("content type") ||
            combined.contains("unsupported")
        ) {
            return Classification(ErrorCategory.FORMAT, RecoveryAction.FALLBACK_FORMAT,
                "Format not supported, trying alternate...")
        }

        return Classification(ErrorCategory.UNKNOWN, RecoveryAction.NOTIFY_USER,
            "Playback error: ${error.message?.take(80)}")
    }
}
