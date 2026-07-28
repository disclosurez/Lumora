package com.lumora.data.update

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
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

        // The public Downloads dir (setDestinationInExternalPublicDir) requires
        // WRITE_EXTERNAL_STORAGE, which the manifest only grants up to API 28 - past that
        // it throws SecurityException and crashes the app outright. The app-specific
        // external dir needs no such permission and is still reachable by FileProvider.
        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("Lumora Update")
            .setDescription("Downloading v$versionName")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        return downloadManager.enqueue(request)
    }

    /**
     * Install a downloaded APK.
     * Requires ACTION_VIEW with a FileProvider URI for Android 7+.
     * Returns true if the install prompt was actually launched, false if the user was
     * redirected to grant the "install unknown apps" permission instead - callers can use
     * that to auto-retry once the user comes back.
     */
    fun installApk(filePath: String): Boolean {
        val file = File(filePath)
        if (!file.exists()) return false

        // Separate from the REQUEST_INSTALL_PACKAGES manifest permission - this is the
        // per-app "install unknown apps" toggle the user has to grant Lumora specifically.
        // Without checking it first, the install silently does nothing (or the system
        // shows its own blocked-source prompt) with no way for the user to tell why.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(context, "Allow Lumora to install updates, then try again", Toast.LENGTH_LONG).show()
            val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            runCatching { context.startActivity(settingsIntent) }
            return false
        }

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

        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app found to install the update", Toast.LENGTH_LONG).show()
            false
        }
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
