package com.lumora.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * One cached programme for one channel.
 *
 * Guide data used to live only in [com.lumora.cache.EpgListCache], an in-memory map, so every
 * cold start began with an empty guide and re-fetched a short EPG per channel over the network
 * as rows scrolled into view - the "EPG takes ages after opening the app" case. Persisting it
 * makes the second and later launches read the guide off disk instead.
 *
 * Keyed by (channelId, startTimestamp): a provider re-sending the same programme (a title
 * correction, a schedule shuffle) replaces the row rather than duplicating it. [fetchedAt] is
 * per row so a channel's freshness is judged by the newest row written for it.
 */
@Entity(
    tableName = "epg_programs",
    primaryKeys = ["channelId", "startTimestamp"],
    indices = [Index("channelId"), Index("stopTimestamp")]
)
data class EpgProgramEntity(
    val channelId: String,
    /** Unix seconds - the same unit XtreamClient.EpgProgram uses. */
    val startTimestamp: Long,
    val stopTimestamp: Long,
    val title: String,
    val fetchedAt: Long = System.currentTimeMillis()
)
