package com.lumora.cache

import android.content.Context
import android.util.Log
import com.lumora.model.Channel
import com.lumora.model.MediaType
import org.json.JSONObject
import java.io.File

private const val TAG = "PlaybackPositionStore"
private const val FILE_NAME = "playback_positions.json"
private const val MAX_ENTRIES = 500
private const val COMPLETION_THRESHOLD = 0.95

data class PlaybackPosition(
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    // Snapshot of the playable item at save time - a series episode isn't retrievable
    // from any list once you've left its detail screen (only its parent series is kept
    // around), so "Continue Watching" needs enough here to resume it directly rather
    // than re-looking it up.
    val channel: Channel? = null
) {
    val isNearComplete: Boolean get() = durationMs > 0 && positionMs >= durationMs * COMPLETION_THRESHOLD
}

/** Saves VOD/series watch progress to disk so playback can resume where it left off. */
object PlaybackPositionStore {
    private var cache: MutableMap<String, PlaybackPosition>? = null

    fun get(context: Context, key: String): PlaybackPosition? = ensureLoaded(context)[key]

    fun save(context: Context, key: String, positionMs: Long, durationMs: Long, channel: Channel? = null) {
        if (key.isBlank() || durationMs <= 0) return
        val map = ensureLoaded(context)
        map[key] = PlaybackPosition(positionMs, durationMs, System.currentTimeMillis(), channel)
        if (map.size > MAX_ENTRIES) {
            map.entries.minByOrNull { it.value.updatedAt }?.key?.let { map.remove(it) }
        }
        persist(context, map)
    }

    fun clear(context: Context, key: String) {
        val map = ensureLoaded(context)
        if (map.remove(key) != null) persist(context, map)
    }

    /** In-progress (not near-complete) items with a resumable channel snapshot, most
     *  recently watched first - for "Continue Watching". Entries saved before the
     *  channel snapshot existed are skipped, not crash-worthy garbage. */
    fun getAllInProgress(context: Context): List<Channel> =
        ensureLoaded(context).entries
            .filter { !it.value.isNearComplete && it.value.positionMs > 0 }
            .sortedByDescending { it.value.updatedAt }
            .mapNotNull { it.value.channel }

    fun clearAll(context: Context) {
        cache = mutableMapOf()
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    private fun ensureLoaded(context: Context): MutableMap<String, PlaybackPosition> {
        cache?.let { return it }
        val loaded: MutableMap<String, PlaybackPosition> = try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) {
                mutableMapOf()
            } else {
                val obj = JSONObject(file.readText())
                val map = mutableMapOf<String, PlaybackPosition>()
                obj.keys().forEach { key ->
                    val entry = obj.getJSONObject(key)
                    val channel = entry.optJSONObject("channel")?.let { c ->
                        runCatching {
                            Channel(
                                id = c.optString("id"),
                                name = c.optString("name"),
                                url = c.optString("url"),
                                logoUrl = c.optString("logoUrl", null),
                                posterUrl = c.optString("posterUrl", null),
                                mediaType = runCatching { MediaType.valueOf(c.optString("mediaType")) }.getOrDefault(MediaType.MOVIE)
                            )
                        }.getOrNull()
                    }
                    map[key] = PlaybackPosition(
                        positionMs = entry.optLong("positionMs"),
                        durationMs = entry.optLong("durationMs"),
                        updatedAt = entry.optLong("updatedAt"),
                        channel = channel
                    )
                }
                map
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load: ${e.message}")
            mutableMapOf()
        }
        cache = loaded
        return loaded
    }

    private fun persist(context: Context, map: Map<String, PlaybackPosition>) {
        try {
            val obj = JSONObject()
            for ((key, pos) in map) {
                obj.put(key, JSONObject().apply {
                    put("positionMs", pos.positionMs)
                    put("durationMs", pos.durationMs)
                    put("updatedAt", pos.updatedAt)
                    pos.channel?.let { ch ->
                        put("channel", JSONObject().apply {
                            put("id", ch.id)
                            put("name", ch.name)
                            put("url", ch.url)
                            ch.logoUrl?.let { put("logoUrl", it) }
                            ch.posterUrl?.let { put("posterUrl", it) }
                            put("mediaType", ch.mediaType.name)
                        })
                    }
                })
            }
            File(context.filesDir, FILE_NAME).writeText(obj.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save: ${e.message}")
        }
    }
}
