package com.lumora.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "channels",
    indices = [
        Index("providerId"),
        Index("categoryId"),
        Index("mediaType"),
        Index("tvgId")
    ]
)
data class ChannelEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val name: String,
    val url: String,
    val logoUrl: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val groupTitle: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val tvgChno: String? = null,
    val tvgLogo: String? = null,
    val mediaType: String = "LIVE", // LIVE, MOVIE, SERIES
    val categoryId: String? = null,
    val categoryName: String? = null,
    val description: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val episodeCount: Int = 0,
    val isAdult: Boolean = false,
    val containerExtension: String? = null,
    val customSortOrder: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
)
