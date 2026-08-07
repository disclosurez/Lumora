package com.lumora.data.local.dao

import androidx.room.*
import com.lumora.data.local.entity.CustomGroupEntity
import com.lumora.data.local.entity.CustomGroupMemberEntity

@Dao
interface CustomGroupDao {
    @Query("SELECT * FROM custom_groups ORDER BY sortOrder ASC")
    suspend fun getAll(): List<CustomGroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: CustomGroupEntity)

    // Members
    @Query("SELECT * FROM custom_group_members WHERE groupId = :groupId ORDER BY sortOrder ASC")
    suspend fun getMembers(groupId: String): List<CustomGroupMemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<CustomGroupMemberEntity>)
}
