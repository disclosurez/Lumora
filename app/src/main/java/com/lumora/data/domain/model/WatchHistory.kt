package com.lumora.data.domain.model

/**
 * Domain model for watch history entries.
 */
data class WatchHistory(
    val channelId: String,
    val channelName: String,
    val mediaType: String,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val progressPercent: Float = 0f,
    val status: WatchStatus = WatchStatus.IN_PROGRESS,
    val watchCount: Int = 1,
    val lastWatchedAt: Long = System.currentTimeMillis()
)

enum class WatchStatus {
    IN_PROGRESS,
    COMPLETED_AUTO,
    COMPLETED_MANUAL
}
