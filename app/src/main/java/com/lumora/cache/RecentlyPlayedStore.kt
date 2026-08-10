package com.lumora.cache

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.File

private const val TAG = "RecentlyPlayedStore"
private const val FILE_NAME = "recently_played.json"
private const val MAX_ENTRIES = 20

/** Recently-played live channel ids, most recent first, for the Home tab's "Recently Played" shelf. */
object RecentlyPlayedStore {

    @Synchronized
    fun recordPlayed(context: Context, channelId: String) {
        if (channelId.isBlank()) return
        val entries = load(context).toMutableList()
        entries.removeAll { it == channelId }
        entries.add(0, channelId)
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
        save(context, entries)
    }

    @Synchronized
    fun getRecentIds(context: Context): List<String> = load(context)

    @Synchronized
    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    @Synchronized
    private fun load(context: Context): List<String> = try {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { arr.getString(it) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to load: ${e.message}")
        emptyList()
    }

    @Synchronized
    private fun save(context: Context, ids: List<String>) {
        try {
            val arr = JSONArray()
            ids.forEach { arr.put(it) }
            val file = File(context.filesDir, FILE_NAME)
            val tempFile = File(context.filesDir, "${FILE_NAME}.tmp")
            tempFile.writeText(arr.toString())
            tempFile.renameTo(file)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save: ${e.message}")
        }
    }
}
