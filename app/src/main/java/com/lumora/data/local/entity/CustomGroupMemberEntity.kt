package com.lumora.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "custom_group_members",
    indices = [Index("groupId"), Index("channelId")]
)
data class CustomGroupMemberEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val channelId: String,
    val sortOrder: Int = 0
)
