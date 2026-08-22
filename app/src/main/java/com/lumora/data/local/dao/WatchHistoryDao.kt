package com.lumora.data.local.dao

import androidx.room.*
import com.lumora.data.local.entity.WatchHistoryEntity

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY lastWatchedAt DESC LIMIT 50")
    suspend fun getRecent(): List<WatchHistoryEntity>

    /** Unbounded read for the backup export, which must round-trip every row - getRecent()'s
     *  LIMIT 50 silently truncated each backup to whatever was watched most recently. */
    @Query("SELECT * FROM watch_history ORDER BY lastWatchedAt DESC")
    suspend fun getAll(): List<WatchHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: WatchHistoryEntity)
}
