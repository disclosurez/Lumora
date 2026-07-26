package com.lumora.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_groups")
data class CustomGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mediaType: String = "LIVE", // LIVE, MOVIE, SERIES
    val isHidden: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
