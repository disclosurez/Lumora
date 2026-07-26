package com.lumora.cache

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "RecentlyPlayedStore"
private const val FILE_NAME = "recently_played.json"
private const val MAX_ENTRIES = 20

/** Recently-played live channel ids, most recent first, for the Home tab's "Recently Played" shelf. */
object RecentlyPlayedStore {

    fun recordPlayed(context: Context, channelId: String) {
        if (channelId.isBlank()) return
        val entries = load(context).toMutableList()
        entries.removeAll { it == channelId }
        entries.add(0, channelId)
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
        save(context, entries)
    }

    fun getRecentIds(context: Context): List<String> = load(context)

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

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

    private fun save(context: Context, ids: List<String>) {
        try {
            val arr = JSONArray()
            ids.forEach { arr.put(it) }
            File(context.filesDir, FILE_NAME).writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save: ${e.message}")
        }
    }
}
