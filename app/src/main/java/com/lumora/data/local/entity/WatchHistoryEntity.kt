package com.lumora.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "watch_history",
    indices = [Index("channelId"), Index("lastWatchedAt")]
)
data class WatchHistoryEntity(
    @PrimaryKey val id: String,
    val channelId: String,
    val channelName: String,
    val mediaType: String,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val progressPercent: Float = 0f,
    val status: String = "IN_PROGRESS", // IN_PROGRESS, COMPLETED_AUTO, COMPLETED_MANUAL
    val watchCount: Int = 1,
    val firstWatchedAt: Long = System.currentTimeMillis(),
    val lastWatchedAt: Long = System.currentTimeMillis()
)
