package com.lumora.data.repository

import com.lumora.data.local.LumoraDatabase
import com.lumora.data.local.entity.CustomGroupEntity
import com.lumora.data.local.entity.CustomGroupMemberEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Repository for custom channel groups.
 */
class CustomGroupRepository(private val db: LumoraDatabase) {

    fun getAllFlow(): Flow<List<CustomGroupEntity>> = db.customGroupDao().getAllFlow()

    suspend fun getAll(): List<CustomGroupEntity> = db.customGroupDao().getAll()

    suspend fun getById(id: String) = db.customGroupDao().getById(id)

    suspend fun create(name: String, mediaType: String = "LIVE"): CustomGroupEntity {
        val group = CustomGroupEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            mediaType = mediaType
        )
        db.customGroupDao().insert(group)
        return group
    }

    suspend fun update(id: String, name: String) {
        val existing = db.customGroupDao().getById(id) ?: return
        db.customGroupDao().update(existing.copy(name = name))
    }

    suspend fun delete(id: String) {
        db.customGroupDao().deleteById(id)
        db.customGroupDao().deleteMembersByGroup(id)
    }

    suspend fun setHidden(id: String, hidden: Boolean) {
        val existing = db.customGroupDao().getById(id) ?: return
        db.customGroupDao().update(existing.copy(isHidden = hidden))
    }

    suspend fun addChannel(groupId: String, channelId: String) {
        val member = CustomGroupMemberEntity(
            id = "${groupId}_$channelId",
            groupId = groupId,
            channelId = channelId
        )
        db.customGroupDao().insertMember(member)
    }

    suspend fun removeChannel(channelId: String) {
        db.customGroupDao().deleteMembersByChannel(channelId)
    }

    suspend fun getMembers(groupId: String) = db.customGroupDao().getMembers(groupId)

    suspend fun getChannelsInGroup(groupId: String): List<String> {
        return db.customGroupDao().getMembers(groupId).map { it.channelId }
    }
}
