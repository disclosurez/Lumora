package com.lumora.cache

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lumora.model.Channel
import com.lumora.model.MediaType
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val TAG = "PlaybackPositionStore"
private const val FILE_NAME = "playback_positions.json"
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
    private const val MAX_ENTRIES = 500
    private const val DEBOUNCE_MS = 5_000L
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var pendingSaveRunnable: Runnable? = null

    // Serializes all disk writes (rebuild JSONObject + atomic temp-write/rename) off the main
    // thread so a large persist can't jank the UI on TV hardware. Single-threaded so two
    // persists can never race the shared .tmp file.
    private val writeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "PlaybackPositionWriter") }

    // ConcurrentHashMap: the background writer persists this map while the main thread keeps
    // mutating it, so iteration on either side must not throw ConcurrentModificationException.
    private var cache: MutableMap<String, PlaybackPosition>? = null

    fun get(context: Context, key: String): PlaybackPosition? = ensureLoaded(context)[key]

    fun save(context: Context, key: String, positionMs: Long, durationMs: Long, channel: Channel? = null) {
        if (key.isBlank() || durationMs <= 0) return
        val map = ensureLoaded(context)
        map[key] = PlaybackPosition(positionMs, durationMs, System.currentTimeMillis(), channel)
        if (map.size > MAX_ENTRIES) {
            map.entries.minByOrNull { it.value.updatedAt }?.key?.let { map.remove(it) }
        }
        // Debounce writes — only flush to disk after DEBOUNCE_MS of inactivity
        pendingSaveRunnable?.let { debounceHandler.removeCallbacks(it) }
        val runnable = Runnable { flushToDisk(context) }
        pendingSaveRunnable = runnable
        debounceHandler.postDelayed(runnable, DEBOUNCE_MS)
    }

    private fun flushToDisk(context: Context) {
        val map = cache ?: return
        // Debounce stays on the main looper (scheduling is cheap); the JSON rebuild + file
        // write itself runs on the background writer.
        writeExecutor.execute { persist(context, map) }
    }

    fun clear(context: Context, key: String) {
        val map = ensureLoaded(context)
        if (map.remove(key) != null) writeExecutor.execute { persist(context, map) }
    }

    /** In-progress (not near-complete) items with a resumable channel snapshot, most
     *  recently watched first - for "Continue Watching". Entries saved before the
     *  channel snapshot existed are skipped, not crash-worthy garbage. A snapshot with
     *  neither an id nor a url is structurally broken - it can't render a tile or
     *  resume, so it's dropped too. */
    fun getAllInProgress(context: Context): List<Channel> =
        ensureLoaded(context).entries
            .filter { !it.value.isNearComplete && it.value.positionMs > 0 }
            .filter { entry ->
                val ch = entry.value.channel
                ch != null && (ch.id.isNotBlank() || ch.url.isNotBlank())
            }
            .sortedByDescending { it.value.updatedAt }
            .mapNotNull { it.value.channel }

    fun clearAll(context: Context) {
        cache = ConcurrentHashMap()
        // Route the disk wipe through the same single-thread executor as every other write:
        // a persist queued before this captured the OLD map reference and must run first and
        // be overwritten by this delete - deleting the file inline while a queued persist was
        // still pending let that persist rewrite the old data afterwards, resurrecting
        // "cleared" history on the next load. FIFO ordering makes the wipe stick.
        writeExecutor.execute { runCatching { File(context.filesDir, FILE_NAME).delete() } }
    }

    /** Most recently-completed episode snapshot per series, only for series with no
     *  in-progress entry left (those are already in Continue Watching). This is what feeds
     *  the Home "up next" tiles: a series whose watched trail ends at a completed episode
     *  has nothing in Continue Watching today (it filters near-complete entries out), so
     *  its next episode would be silently unreachable from Home.
     *  Requires the parent-series id on the snapshot - only Xtream and Jellyfin episodes
     *  carry it (Stalker/Anime don't stamp categoryId), so those providers are naturally
     *  skipped here. */
    fun getCompletedSeriesTrails(context: Context): List<Channel> {
        val inProgressSeries = mutableSetOf<String>()
        val trailUpdated = HashMap<String, Long>()
        val trailChannel = HashMap<String, Channel>()
        for ((_, pos) in ensureLoaded(context)) {
            val ch = pos.channel ?: continue
            if (ch.mediaType != MediaType.SERIES) continue
            val seriesId = ch.categoryId
            if (seriesId.isNullOrBlank()) continue
            if (!pos.isNearComplete) {
                if (pos.positionMs > 0) inProgressSeries.add(seriesId)
                continue
            }
            if (trailUpdated[seriesId] == null || pos.updatedAt > trailUpdated.getValue(seriesId)) {
                trailUpdated[seriesId] = pos.updatedAt
                trailChannel[seriesId] = ch
            }
        }
        return trailChannel.values
            .filter { it.categoryId !in inProgressSeries }
            .sortedByDescending { trailUpdated[it.categoryId] ?: 0L }
    }

    private fun ensureLoaded(context: Context): MutableMap<String, PlaybackPosition> {
        cache?.let { return it }
        // Cancel any pending flush so we don't overwrite the freshly loaded data with stale
        // in-memory state (e.g. if a delayed save fires after a cold-start load completes).
        val pending = pendingSaveRunnable
        if (pending != null) {
            debounceHandler.removeCallbacks(pending)
            pendingSaveRunnable = null
        }
        val loaded: MutableMap<String, PlaybackPosition> = try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) {
                ConcurrentHashMap()
            } else {
                val obj = JSONObject(file.readText())
                val map = ConcurrentHashMap<String, PlaybackPosition>()
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
                                mediaType = runCatching { MediaType.valueOf(c.optString("mediaType")) }.getOrDefault(MediaType.MOVIE),
                                // Kept so Continue Watching can tell whether an entry is
                                // adult content without re-finding it in the catalog - the
                                // catalog may not be loaded yet, and a series episode is
                                // never in it at all. Absent on entries written before this
                                // was stored; callers fall back to the catalog then.
                                group = c.optString("group", null),
                                categoryName = c.optString("categoryName", null),
                                // A plugin-resolved stream can't be replayed from `url` alone:
                                // the CDN signs it with an expiry and gates it behind the
                                // headers below. These three are what lets the resume path
                                // re-run the plugin's resolve() for a fresh URL instead.
                                episodeNum = c.optInt("episodeNum", -1).takeIf { it >= 0 },
                                categoryId = c.optString("categoryId", null),
                                isJellyfin = c.optBoolean("isJellyfin", false),
                                sourceProviderId = c.optString("sourceProviderId", null),
                                pluginToken = c.optString("pluginToken", null),
                                pluginId = c.optString("pluginId", null),
                                streamHeaders = c.optJSONObject("streamHeaders")?.let { h ->
                                    h.keys().asSequence().associateWith { k -> h.getString(k) }
                                }
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
                            ch.group?.let { put("group", it) }
                            ch.categoryName?.let { put("categoryName", it) }
                            ch.episodeNum?.let { put("episodeNum", it) }
                            // Provider provenance, so Continue Watching can rebuild the
                            // auto-advance episode queue after a cold restart (a series
                            // episode is never in the catalog). Absent on entries written
                            // before this was stored; the queue rebuild no-ops then.
                            ch.categoryId?.let { put("categoryId", it) }
                            put("isJellyfin", ch.isJellyfin)
                            ch.sourceProviderId?.let { put("sourceProviderId", it) }
                            ch.pluginToken?.let { put("pluginToken", it) }
                            ch.pluginId?.let { put("pluginId", it) }
                            ch.streamHeaders?.takeIf { it.isNotEmpty() }?.let { headers ->
                                put("streamHeaders", JSONObject(headers as Map<*, *>))
                            }
                        })
                    }
                })
            }
            writeToFile(File(context.filesDir, FILE_NAME), obj.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save: ${e.message}")
        }
    }

    /** Atomically writes JSON to file: write to a temp file, then rename to prevent corruption on process death. */
    private fun writeToFile(file: File, json: String) {
        val tempFile = File(file.absolutePath + ".tmp")
        tempFile.writeText(json)
        tempFile.renameTo(file)
    }
}
