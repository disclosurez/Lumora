package com.lumora.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "downloads",
    indices = [Index("status"), Index("channelId")]
)
data class DownloadEntity(
    @PrimaryKey val id: String,
    val channelId: String,
    val title: String,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    val mediaType: String = "MOVIE",
    val status: String = "PENDING", // PENDING, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
    val downloadManagerId: Long = 0,
    val localFilePath: String? = null,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val retryCount: Int = 0,
    val isSeriesDownload: Boolean = false,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val sourceUrl: String? = null,
    val containerExtension: String = "mp4",
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)
