package com.lumora.data.local.dao

import androidx.room.*
import com.lumora.data.local.entity.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY lastWatchedAt DESC")
    fun getAllFlow(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE status = 'IN_PROGRESS' ORDER BY lastWatchedAt DESC")
    fun getInProgressFlow(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE status = 'IN_PROGRESS' ORDER BY lastWatchedAt DESC LIMIT 20")
    suspend fun getInProgress(): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history ORDER BY lastWatchedAt DESC LIMIT 50")
    suspend fun getRecent(): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE channelId = :channelId")
    suspend fun getByChannel(channelId: String): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: WatchHistoryEntity)

    @Update
    suspend fun update(entry: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE channelId = :channelId")
    suspend fun deleteByChannel(channelId: String)

    @Query("DELETE FROM watch_history")
    suspend fun deleteAll()

    @Query("UPDATE watch_history SET status = 'COMPLETED_MANUAL' WHERE channelId = :channelId")
    suspend fun markCompleted(channelId: String)

    @Query("UPDATE watch_history SET status = 'IN_PROGRESS' WHERE channelId = :channelId")
    suspend fun markInProgress(channelId: String)
}
