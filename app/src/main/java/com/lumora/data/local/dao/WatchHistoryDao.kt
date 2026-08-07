package com.lumora.data.local.dao

import androidx.room.*
import com.lumora.data.local.entity.WatchHistoryEntity

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY lastWatchedAt DESC LIMIT 50")
    suspend fun getRecent(): List<WatchHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: WatchHistoryEntity)
}
