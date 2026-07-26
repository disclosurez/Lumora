package com.lumora.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recording_storage")
data class RecordingStorageEntity(
    @PrimaryKey val id: String = "default",
    val localPath: String? = null,
    val safTreeUri: String? = null,
    val maxSimultaneous: Int = 2,
    val retentionDays: Int = 90,
    val fileNamePattern: String = "{title}_{date}_{time}"
)
