package com.lumora.cache

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "ReminderStore"
private const val FILE_NAME = "reminders.json"

data class ProgramReminder(
    val channelId: String,
    val channelName: String,
    val programTitle: String,
    val startTimestamp: Long
) {
    /** Unique per (channel, program start) so the same show tomorrow is a different reminder. */
    val key: String get() = "$channelId:$startTimestamp"
}

/** Scheduled EPG reminders, persisted so they survive app restarts and can be re-armed after reboot. */
object ReminderStore {

    // In-memory mirror of the persisted list, keyed by ProgramReminder.key. Every public
    // method is @Synchronized, so the map is only ever touched under the object lock.
    private val cache = mutableMapOf<String, ProgramReminder>()
    // The file is read once, lazily, on first access; writes go through save(), which
    // rewrites both the file and the map. Without this, get() re-read the file (a full
    // readText() + JSONArray parse) on every call - the guide calls isReminderSet() per
    // programme block per row, so scrolling the guide meant dozens of file reads on the
    // main thread per pass.
    @Volatile
    private var loaded = false

    @Synchronized
    fun getAll(context: Context): List<ProgramReminder> {
        ensureLoaded(context)
        return cache.values.toList()
    }

    @Synchronized
    fun get(context: Context, key: String): ProgramReminder? {
        ensureLoaded(context)
        return cache[key]
    }

    @Synchronized
    fun add(context: Context, reminder: ProgramReminder) {
        ensureLoaded(context)
        // remove-then-put keeps LinkedHashMap insertion order in line with the old
        // filterNot { it.key == key }.add() behaviour: a re-added key lands at the end.
        cache.remove(reminder.key)
        cache[reminder.key] = reminder
        save(context, cache.values.toList())
    }

    @Synchronized
    fun remove(context: Context, key: String) {
        ensureLoaded(context)
        if (cache.remove(key) != null) {
            save(context, cache.values.toList())
        }
    }

    /** Drops reminders whose program has already started - nothing left to schedule for them. */
    @Synchronized
    fun pruneExpired(context: Context, nowSeconds: Long) {
        ensureLoaded(context)
        val before = cache.size
        val it = cache.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.startTimestamp <= nowSeconds) it.remove()
        }
        if (cache.size != before) save(context, cache.values.toList())
    }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        cache.clear()
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val reminder = ProgramReminder(
                        channelId = obj.getString("channelId"),
                        channelName = obj.optString("channelName", ""),
                        programTitle = obj.optString("programTitle", ""),
                        startTimestamp = obj.getLong("startTimestamp")
                    )
                    cache[reminder.key] = reminder
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load: ${e.message}")
        } finally {
            loaded = true
        }
    }

    @Synchronized
    private fun save(context: Context, list: List<ProgramReminder>) {
        try {
            val arr = JSONArray()
            for (r in list) {
                arr.put(JSONObject().apply {
                    put("channelId", r.channelId)
                    put("channelName", r.channelName)
                    put("programTitle", r.programTitle)
                    put("startTimestamp", r.startTimestamp)
                })
            }
            val file = File(context.filesDir, FILE_NAME)
            val tempFile = File(context.filesDir, "${FILE_NAME}.tmp")
            tempFile.writeText(arr.toString())
            tempFile.renameTo(file)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save: ${e.message}")
        }
    }
}
