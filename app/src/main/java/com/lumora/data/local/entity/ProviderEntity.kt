package com.lumora.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String, // m3u, xtream, stalker, jellyfin
    val serverUrl: String? = null,
    val username: String? = null,
    val passwordEncrypted: String? = null,
    val m3uUrl: String? = null,
    val userAgent: String? = null,
    val macAddress: String? = null, // Stalker
    val serialNumber: String? = null, // Stalker
    val jellyfinToken: String? = null, // Jellyfin
    val jellyfinUserId: String? = null, // Jellyfin
    val active: Boolean = true,
    val syncEnabled: Boolean = true,
    val epgSyncEnabled: Boolean = true,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastSyncAt: Long? = null,
    val expDateSeconds: Long? = null,
    val isTrial: Boolean = false,
    val maxConnections: Int = 0
)
