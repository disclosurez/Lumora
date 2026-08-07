package com.lumora.data.local.dao

import androidx.room.*
import com.lumora.data.local.entity.ProviderEntity

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY sortOrder ASC")
    suspend fun getAll(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun getById(id: String): ProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(provider: ProviderEntity)

    @Query("UPDATE providers SET lastSyncAt = :timestamp WHERE id = :id")
    suspend fun updateLastSync(id: String, timestamp: Long)
}
