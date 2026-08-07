package com.lumora.data.local.dao

import androidx.room.*
import com.lumora.data.local.entity.ChannelEntity

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE providerId = :providerId AND mediaType = :mediaType ORDER BY customSortOrder ASC")
    suspend fun getByProviderAndType(providerId: String, mediaType: String): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun getById(id: String): ChannelEntity?

    /** Every channel, for building a tvg-id → channel-ids index (the EPG worker maps
     *  raw XMLTV tvg-ids onto app channel ids before persisting). */
    @Query("SELECT * FROM channels")
    suspend fun getAll(): List<ChannelEntity>

    /** Global tvg-id lookup across all providers (an XMLTV source has no providerId
     *  link, so the worker can't use the per-provider [getByTvgId]). */
    @Query("SELECT * FROM channels WHERE tvgId = :tvgId")
    suspend fun getAllByTvgId(tvgId: String): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE tvgId = :tvgId AND providerId = :providerId LIMIT 1")
    suspend fun getByTvgId(tvgId: String, providerId: String): ChannelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE providerId = :providerId")
    suspend fun deleteByProvider(providerId: String)
}
