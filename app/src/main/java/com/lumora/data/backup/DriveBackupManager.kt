package com.lumora.data.backup

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google Drive backup sync.
 * Stores app configuration in Drive's appDataFolder (hidden, app-private).
 * Uses a simple REST API approach with a stored auth token.
 *
 * NOTE: Full OAuth + Google Drive API integration requires Play Services.
 * This is a simplified version that works on Android TV without browser-based auth.
 */
class DriveBackupManager(private val context: Context) {

    private val TAG = "DriveBackup"
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val PREFS_KEY_TOKEN = "drive_auth_token"
    private val PREFS_KEY_LAST_PUSH = "drive_last_push"
    private val PREFS_KEY_LAST_PULL = "drive_last_pull"
    private val FILE_NAME = "lumora_backup.json"
    private val MIME_TYPE = "application/json"

    data class DriveStatus(
        val lastPushAt: Long? = null,
        val lastPullAt: Long? = null,
        val isSignedIn: Boolean = false,
        val error: String? = null
    )

    /**
     * Check if the user is signed into Google Drive.
     */
    fun isSignedIn(): Boolean {
        val token = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            .getString(PREFS_KEY_TOKEN, null)
        return !token.isNullOrBlank()
    }

    /**
     * Sign out of Google Drive.
     */
    fun signOut() {
        context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            .edit()
            .remove(PREFS_KEY_TOKEN)
            .apply()
    }

    /**
     * Get the current Drive sync status.
     */
    fun getStatus(): DriveStatus {
        val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        return DriveStatus(
            lastPushAt = prefs.getLong(PREFS_KEY_LAST_PUSH, 0L).takeIf { it > 0 },
            lastPullAt = prefs.getLong(PREFS_KEY_LAST_PULL, 0L).takeIf { it > 0 },
            isSignedIn = isSignedIn()
        )
    }

    /**
     * Push backup to Google Drive appDataFolder.
     * Returns true on success.
     */
    suspend fun pushBackup(backupJson: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = getAccessToken() ?: return@withContext false

            // Find existing file
            val fileId = findExistingFile(token)
            val fileUrl = if (fileId != null) {
                // Update existing file
                URL("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
            } else {
                // Create new file
                URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=media")
            }

            val conn = fileUrl.openConnection() as HttpURLConnection
            conn.requestMethod = if (fileId != null) "PATCH" else "POST"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", MIME_TYPE)
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(backupJson)
                writer.flush()
            }

            val code = conn.responseCode
            if (code in 200..299) {
                // If new file, set parent to appDataFolder
                if (fileId == null) {
                    val responseBody = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val newFileId = org.json.JSONObject(responseBody).optString("id", "")
                    if (newFileId.isNotBlank()) {
                        setAppDataFolder(token, newFileId)
                    }
                }
                context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
                    .edit().putLong(PREFS_KEY_LAST_PUSH, System.currentTimeMillis()).apply()
                Log.d(TAG, "Backup pushed to Drive")
                true
            } else {
                Log.w(TAG, "Drive push failed: HTTP $code")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Push failed: ${e.message}")
            false
        }
    }

    /**
     * Pull backup from Google Drive appDataFolder.
     * Returns the backup JSON or null.
     */
    suspend fun pullBackup(): String? = withContext(Dispatchers.IO) {
        try {
            val token = getAccessToken() ?: return@withContext null
            val fileId = findExistingFile(token) ?: return@withContext null

            val url = URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connect()

            if (conn.responseCode != 200) return@withContext null

            val json = BufferedReader(InputStreamReader(conn.inputStream)).readText()

            context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
                .edit().putLong(PREFS_KEY_LAST_PULL, System.currentTimeMillis()).apply()

            Log.d(TAG, "Backup pulled from Drive")
            json
        } catch (e: Exception) {
            Log.e(TAG, "Pull failed: ${e.message}")
            null
        }
    }

    private fun getAccessToken(): String? {
        val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        return prefs.getString(PREFS_KEY_TOKEN, null)
    }

    private fun findExistingFile(token: String): String? {
        return try {
            val query = URLEncoder.encode("name='$FILE_NAME' and 'appDataFolder' in parents", "UTF-8")
            val url = URL("https://www.googleapis.com/drive/v3/files?q=$query&spaces=appDataFolder")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connect()

            val body = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            val files = org.json.JSONObject(body).optJSONArray("files")
            if (files != null && files.length() > 0) {
                files.getJSONObject(0).optString("id", null)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun setAppDataFolder(token: String, fileId: String) {
        try {
            val url = URL("https://www.googleapis.com/drive/v3/files/$fileId")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "PATCH"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = org.json.JSONObject().apply {
                put("parents", listOf("appDataFolder"))
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(body.toString())
                it.flush()
            }
            conn.responseCode // consume
        } catch (e: Exception) {
            Log.w(TAG, "Set appDataFolder failed: ${e.message}")
        }
    }
}
