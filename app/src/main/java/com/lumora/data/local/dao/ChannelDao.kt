package com.lumora.data.local.dao

import androidx.room.*
import com.lumora.data.local.entity.ChannelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE providerId = :providerId ORDER BY customSortOrder ASC")
    fun getByProviderFlow(providerId: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE providerId = :providerId ORDER BY customSortOrder ASC")
    suspend fun getByProvider(providerId: String): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE providerId = :providerId AND mediaType = :mediaType ORDER BY customSortOrder ASC")
    suspend fun getByProviderAndType(providerId: String, mediaType: String): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE providerId = :providerId AND mediaType = :mediaType ORDER BY customSortOrder ASC")
    fun getByProviderAndTypeFlow(providerId: String, mediaType: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun getById(id: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE categoryId IN (:categoryIds) AND providerId = :providerId")
    suspend fun getByCategories(providerId: String, categoryIds: List<String>): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE tvgId = :tvgId AND providerId = :providerId LIMIT 1")
    suspend fun getByTvgId(tvgId: String, providerId: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE providerId = :providerId AND mediaType = :mediaType AND categoryId = :categoryId")
    suspend fun getByCategory(providerId: String, mediaType: String, categoryId: String): List<ChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(channel: ChannelEntity)

    @Update
    suspend fun update(channel: ChannelEntity)

    @Query("DELETE FROM channels WHERE providerId = :providerId")
    suspend fun deleteByProvider(providerId: String)

    @Query("DELETE FROM channels WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM channels WHERE providerId = :providerId")
    suspend fun countByProvider(providerId: String): Int

    @Query("SELECT COUNT(*) FROM channels WHERE providerId = :providerId AND mediaType = :mediaType")
    suspend fun countByProviderAndType(providerId: String, mediaType: String): Int
}
