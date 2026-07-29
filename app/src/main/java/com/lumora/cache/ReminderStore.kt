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

    @Synchronized
    fun getAll(context: Context): List<ProgramReminder> = load(context)

    @Synchronized
    fun get(context: Context, key: String): ProgramReminder? = load(context).firstOrNull { it.key == key }

    @Synchronized
    fun add(context: Context, reminder: ProgramReminder) {
        val list = load(context).filterNot { it.key == reminder.key }.toMutableList()
        list.add(reminder)
        save(context, list)
    }

    @Synchronized
    fun remove(context: Context, key: String) {
        save(context, load(context).filterNot { it.key == key })
    }

    /** Drops reminders whose program has already started - nothing left to schedule for them. */
    @Synchronized
    fun pruneExpired(context: Context, nowSeconds: Long) {
        val all = load(context)
        val remaining = all.filter { it.startTimestamp > nowSeconds }
        if (remaining.size != all.size) save(context, remaining)
    }

    @Synchronized
    private fun load(context: Context): List<ProgramReminder> = try {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ProgramReminder(
                    channelId = obj.getString("channelId"),
                    channelName = obj.optString("channelName", ""),
                    programTitle = obj.optString("programTitle", ""),
                    startTimestamp = obj.getLong("startTimestamp")
                )
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to load: ${e.message}")
        emptyList()
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
            File(context.filesDir, FILE_NAME).writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save: ${e.message}")
        }
    }
}
