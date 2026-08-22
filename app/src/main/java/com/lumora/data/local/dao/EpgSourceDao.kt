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

    @Query("UPDATE epg_sources SET lastRefreshedAt = :timestamp, lastSuccessAt = :timestamp, consecutiveFailures = 0 WHERE id = :id")
    suspend fun markRefreshed(id: String, timestamp: Long)

    /** [timestamp] lands in `lastRefreshedAt`, which means *attempted*, not *succeeded* -
     *  `lastSuccessAt` is the one that only a real refresh moves. The sync worker needs to
     *  know when a failing source was last tried so it can back off from it instead of
     *  hammering it, and that is what this column is for. */
    @Query("UPDATE epg_sources SET consecutiveFailures = consecutiveFailures + 1, lastRefreshedAt = :timestamp WHERE id = :id")
    suspend fun incrementFailures(id: String, timestamp: Long)
}
