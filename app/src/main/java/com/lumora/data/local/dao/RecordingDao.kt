package com.lumora.data.local.dao

import androidx.room.*
import com.lumora.data.local.entity.RecordingEntity
import com.lumora.data.local.entity.RecordingStorageEntity

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY startTimeUtc ASC")
    suspend fun getAll(): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE status = 'SCHEDULED' ORDER BY startTimeUtc ASC")
    suspend fun getScheduled(): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getById(id: String): RecordingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: RecordingEntity)

    @Update
    suspend fun update(recording: RecordingEntity)

    // Storage config
    @Query("SELECT * FROM recording_storage WHERE id = 'default'")
    suspend fun getStorageConfig(): RecordingStorageEntity?
}
