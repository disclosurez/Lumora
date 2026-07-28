package com.lumora.data.local.dao

import androidx.room.*
import com.lumora.data.local.entity.CustomGroupEntity
import com.lumora.data.local.entity.CustomGroupMemberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomGroupDao {
    @Query("SELECT * FROM custom_groups ORDER BY sortOrder ASC")
    fun getAllFlow(): Flow<List<CustomGroupEntity>>

    @Query("SELECT * FROM custom_groups ORDER BY sortOrder ASC")
    suspend fun getAll(): List<CustomGroupEntity>

    @Query("SELECT * FROM custom_groups WHERE id = :id")
    suspend fun getById(id: String): CustomGroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: CustomGroupEntity)

    @Update
    suspend fun update(group: CustomGroupEntity)

    @Delete
    suspend fun delete(group: CustomGroupEntity)

    @Query("DELETE FROM custom_groups WHERE id = :id")
    suspend fun deleteById(id: String)

    // Members
    @Query("SELECT * FROM custom_group_members WHERE groupId = :groupId ORDER BY sortOrder ASC")
    suspend fun getMembers(groupId: String): List<CustomGroupMemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: CustomGroupMemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<CustomGroupMemberEntity>)

    @Delete
    suspend fun deleteMember(member: CustomGroupMemberEntity)

    @Query("DELETE FROM custom_group_members WHERE groupId = :groupId")
    suspend fun deleteMembersByGroup(groupId: String)

    @Query("DELETE FROM custom_group_members WHERE channelId = :channelId")
    suspend fun deleteMembersByChannel(channelId: String)
}
