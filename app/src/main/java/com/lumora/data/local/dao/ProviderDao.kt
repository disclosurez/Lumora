package com.lumora.data.local.dao

import androidx.room.*
import com.lumora.data.local.entity.ProviderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY sortOrder ASC")
    fun getAllFlow(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers ORDER BY sortOrder ASC")
    suspend fun getAll(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun getById(id: String): ProviderEntity?

    @Query("SELECT * FROM providers WHERE active = 1 LIMIT 1")
    suspend fun getActive(): ProviderEntity?

    @Query("SELECT * FROM providers WHERE active = 1")
    fun getActiveFlow(): Flow<ProviderEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(provider: ProviderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(providers: List<ProviderEntity>)

    @Update
    suspend fun update(provider: ProviderEntity)

    @Delete
    suspend fun delete(provider: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE providers SET active = 0")
    suspend fun deactivateAll()

    @Query("UPDATE providers SET active = 1 WHERE id = :id")
    suspend fun activate(id: String)

    @Query("UPDATE providers SET lastSyncAt = :timestamp WHERE id = :id")
    suspend fun updateLastSync(id: String, timestamp: Long)
}
