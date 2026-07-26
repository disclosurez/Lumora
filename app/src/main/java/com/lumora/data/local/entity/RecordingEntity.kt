package com.lumora.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recordings",
    indices = [Index("channelId"), Index("status")]
)
data class RecordingEntity(
    @PrimaryKey val id: String,
    val channelId: String,
    val channelName: String,
    val programTitle: String,
    val startTimeUtc: Long,
    val stopTimeUtc: Long,
    val status: String = "SCHEDULED", // SCHEDULED, RECORDING, COMPLETED, FAILED, CANCELLED
    val sourceType: String = "TS", // TS, HLS, DASH
    val filePath: String? = null,
    val fileSize: Long = 0,
    val durationMs: Long = 0,
    val priority: Int = 0,
    val paddingBeforeMin: Int = 2,
    val paddingAfterMin: Int = 5,
    val recurringRule: String? = null, // null, DAILY, WEEKLY
    val failureReason: String? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val providerId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)
