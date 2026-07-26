package com.lumora.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File

/**
 * Downloads and installs APK updates from GitHub releases.
 * Uses DownloadManager for the download and a file provider for the install intent.
 */
class AppUpdateInstaller(private val context: Context) {

    private val TAG = "AppUpdateInstaller"

    /**
     * Start downloading an APK update.
     * Returns the DownloadManager ID for tracking progress.
     */
    fun downloadApk(downloadUrl: String, versionName: String): Long {
        val fileName = "Lumora_v$versionName.apk"
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("Lumora Update")
            .setDescription("Downloading v$versionName")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        return downloadManager.enqueue(request)
    }

    /**
     * Install a downloaded APK.
     * Requires ACTION_VIEW with a FileProvider URI for Android 7+.
     */
    fun installApk(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) return

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } else {
            Uri.fromFile(file)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        context.startActivity(intent)
    }

    /**
     * Check if a downloaded file is ready to install.
     */
    fun isDownloadComplete(downloadId: Long): Boolean {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = downloadManager.query(
            DownloadManager.Query().setFilterById(downloadId)
        )
        cursor.use {
            if (!it.moveToFirst()) return false
            val status = it.getInt(it.getColumnIndex(DownloadManager.COLUMN_STATUS))
            return status == DownloadManager.STATUS_SUCCESSFUL
        }
    }

    /** Distinct from "not complete yet" - a caller polling isDownloadComplete() alone
     *  would spin forever on a failed/cancelled download without this. */
    fun isDownloadFailed(downloadId: Long): Boolean {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = downloadManager.query(
            DownloadManager.Query().setFilterById(downloadId)
        )
        cursor.use {
            if (!it.moveToFirst()) return true
            val status = it.getInt(it.getColumnIndex(DownloadManager.COLUMN_STATUS))
            return status == DownloadManager.STATUS_FAILED
        }
    }

    /**
     * Get the file path of a completed download.
     */
    fun getDownloadedFilePath(downloadId: Long): String? {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = downloadManager.query(
            DownloadManager.Query().setFilterById(downloadId)
        )
        cursor.use {
            if (!it.moveToFirst()) return null
            val localUri = it.getString(it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI))
            return localUri?.let { Uri.parse(it).path }
        }
    }
}
