package com.lumora.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "epg_sources")
data class EpgSourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val userAgent: String? = null,
    val lastRefreshedAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val refreshIntervalHours: Int = 24,
    val channelCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
