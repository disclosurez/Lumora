package com.lumora.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lumora.data.local.entity.EpgProgramEntity

@Dao
interface EpgProgramDao {

    /** A channel's still-relevant programmes: anything that hasn't finished yet, in air order.
     *  Rows that already ended are useless to the guide and are pruned separately. */
    @Query(
        "SELECT * FROM epg_programs WHERE channelId = :channelId AND stopTimestamp >= :nowSeconds " +
            "ORDER BY startTimestamp ASC LIMIT :limit"
    )
    suspend fun upcomingFor(channelId: String, nowSeconds: Long, limit: Int = 16): List<EpgProgramEntity>

    /** When this channel's guide was last written - the freshness test for the whole channel. */
    @Query("SELECT MAX(fetchedAt) FROM epg_programs WHERE channelId = :channelId")
    suspend fun lastFetchedAt(channelId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(programs: List<EpgProgramEntity>)

    @Query("DELETE FROM epg_programs WHERE channelId = :channelId")
    suspend fun deleteForChannel(channelId: String)

    /** Drops programmes that finished before [beforeSeconds]. Run once per session so the
     *  table tracks the guide window instead of growing without limit. */
    @Query("DELETE FROM epg_programs WHERE stopTimestamp < :beforeSeconds")
    suspend fun pruneEndedBefore(beforeSeconds: Long): Int

    @Query("SELECT COUNT(*) FROM epg_programs")
    suspend fun count(): Int
}
