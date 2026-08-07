package com.lumora.data.local.dao

import androidx.room.*
import com.lumora.data.local.entity.EpgSourceEntity

@Dao
interface EpgSourceDao {
    @Query("SELECT * FROM epg_sources ORDER BY priority ASC")
    suspend fun getAll(): List<EpgSourceEntity>

    @Query("SELECT * FROM epg_sources WHERE enabled = 1 ORDER BY priority ASC")
    suspend fun getEnabled(): List<EpgSourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: EpgSourceEntity)

    @Delete
    suspend fun delete(source: EpgSourceEntity)

    @Query("UPDATE epg_sources SET lastRefreshedAt = :timestamp, lastSuccessAt = :timestamp WHERE id = :id")
    suspend fun markRefreshed(id: String, timestamp: Long)
}
