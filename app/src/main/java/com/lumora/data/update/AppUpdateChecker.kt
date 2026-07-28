package com.lumora.data.update

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Checks for app updates via GitHub Releases API.
 * Auto-detects the latest release and compares with the installed version.
 */
class AppUpdateChecker(private val context: Context) {

    private val TAG = "AppUpdate"
    private val GITHUB_REPO = "disclosurez/Lumora"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val latestVersion: String,
        val currentVersion: String,
        val downloadUrl: String,
        val releaseNotes: String,
        val isUpdateAvailable: Boolean
    )

    /**
     * Check for updates by fetching the latest GitHub release.
     */
    suspend fun checkForUpdate(): UpdateInfo? {
        return try {
            val url = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
            val request = Request.Builder().url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Lumora/2.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "GitHub API: HTTP ${response.code}")
                return null
            }

            val body = response.body?.string() ?: return null
            val json = org.json.JSONObject(body)

            val latestTag = json.optString("tag_name", "")?.removePrefix("v")
            val releaseNotes = json.optString("body", "")
            val assets = json.optJSONArray("assets")

            var downloadUrl = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk")) {
                        downloadUrl = asset.optString("browser_download_url", "")
                        break
                    }
                }
            }

            val currentVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
            } catch (e: Exception) { "1.0" }

            val isUpdate = latestTag != null && isNewerVersion(latestTag, currentVersion)

            UpdateInfo(
                latestVersion = latestTag ?: currentVersion,
                currentVersion = currentVersion,
                downloadUrl = downloadUrl,
                releaseNotes = releaseNotes.take(500),
                isUpdateAvailable = isUpdate
            )
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    /** Numeric, part-by-part comparison - a plain string ">" breaks past single digits
     *  ("1.10" < "1.9" lexically, even though 1.10 is the newer release). */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }
}
