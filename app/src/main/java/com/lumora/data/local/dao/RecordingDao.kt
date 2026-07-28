package com.lumora.data.local.dao

import androidx.room.*
import com.lumora.data.local.entity.RecordingEntity
import com.lumora.data.local.entity.RecordingStorageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY startTimeUtc ASC")
    fun getAllFlow(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings ORDER BY startTimeUtc ASC")
    suspend fun getAll(): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE status = 'SCHEDULED' ORDER BY startTimeUtc ASC")
    suspend fun getScheduled(): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE status = 'SCHEDULED' ORDER BY startTimeUtc ASC")
    fun getScheduledFlow(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE status = 'COMPLETED' ORDER BY completedAt DESC")
    fun getCompletedFlow(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE status = 'RECORDING'")
    suspend fun getActive(): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getById(id: String): RecordingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: RecordingEntity)

    @Update
    suspend fun update(recording: RecordingEntity)

    @Delete
    suspend fun delete(recording: RecordingEntity)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM recordings WHERE status = 'SCHEDULED'")
    suspend fun countScheduled(): Int

    @Query("SELECT COUNT(*) FROM recordings WHERE status = 'RECORDING'")
    suspend fun countActive(): Int

    // Storage config
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStorageConfig(config: RecordingStorageEntity)

    @Query("SELECT * FROM recording_storage WHERE id = 'default'")
    suspend fun getStorageConfig(): RecordingStorageEntity?
}
