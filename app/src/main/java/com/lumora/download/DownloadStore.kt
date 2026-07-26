package com.lumora.download

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "DownloadStore"
private const val FILE_NAME = "downloads.json"

enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETE, FAILED }

data class DownloadRecord(
    val id: String,
    val title: String,
    val subtitle: String,
    val posterUrl: String?,
    val mediaType: String,
    val downloadManagerId: Long,
    val status: DownloadStatus,
    val localFilePath: String? = null,
    // Live-only, never persisted - recomputed from DownloadManager each time the list is shown.
    val progressPercent: Int = 0
)

/** Persists which VOD items have been downloaded, so the Downloads tab survives app restarts. */
object DownloadStore {

    fun getAll(context: Context): List<DownloadRecord> = load(context)

    fun get(context: Context, id: String): DownloadRecord? = load(context).firstOrNull { it.id == id }

    fun add(context: Context, record: DownloadRecord) {
        val list = load(context).filterNot { it.id == record.id }.toMutableList()
        list.add(record)
        save(context, list)
    }

    fun update(context: Context, record: DownloadRecord) {
        val list = load(context).map { if (it.id == record.id) record else it }
        save(context, list)
    }

    fun remove(context: Context, id: String) {
        save(context, load(context).filterNot { it.id == id })
    }

    private fun load(context: Context): List<DownloadRecord> = try {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                DownloadRecord(
                    id = obj.getString("id"),
                    title = obj.optString("title", ""),
                    subtitle = obj.optString("subtitle", ""),
                    posterUrl = obj.optString("posterUrl", "").ifEmpty { null },
                    mediaType = obj.optString("mediaType", "MOVIE"),
                    downloadManagerId = obj.getLong("downloadManagerId"),
                    status = runCatching { DownloadStatus.valueOf(obj.optString("status", "QUEUED")) }
                        .getOrDefault(DownloadStatus.QUEUED),
                    localFilePath = obj.optString("localFilePath", "").ifEmpty { null }
                )
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to load: ${e.message}")
        emptyList()
    }

    private fun save(context: Context, list: List<DownloadRecord>) {
        try {
            val arr = JSONArray()
            for (r in list) {
                arr.put(JSONObject().apply {
                    put("id", r.id)
                    put("title", r.title)
                    put("subtitle", r.subtitle)
                    put("posterUrl", r.posterUrl ?: "")
                    put("mediaType", r.mediaType)
                    put("downloadManagerId", r.downloadManagerId)
                    put("status", r.status.name)
                    put("localFilePath", r.localFilePath ?: "")
                })
            }
            File(context.filesDir, FILE_NAME).writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save: ${e.message}")
        }
    }
}
