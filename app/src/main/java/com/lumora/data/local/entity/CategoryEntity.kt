package com.lumora.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    indices = [Index("providerId"), Index("parentId")]
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val name: String,
    val parentId: String? = null,
    val isAdult: Boolean = false,
    val isHidden: Boolean = false,
    val isLocked: Boolean = false,
    val isPinned: Boolean = false,
    val customSortOrder: Int = 0,
    val mediaType: String = "LIVE", // LIVE, MOVIE, SERIES
    val channelCount: Int = 0
)
