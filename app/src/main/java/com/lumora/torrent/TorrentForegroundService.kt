package com.lumora.torrent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.lumora.R

/**
 * Purely a foreground-notification holder while [TorrentEngine] is streaming - modern Android
 * requires a visible foreground service for any long-running background network operation.
 * It has no bound API and touches no torrent state itself; [TorrentEngine] is a plain class
 * MainActivity calls directly. Started when a torrent stream begins, stopped when it ends or the
 * player closes - see MainActivity's `activeTorrentSession` handling.
 */
class TorrentForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Streaming", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Streaming torrent")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "torrent_stream"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TorrentForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TorrentForegroundService::class.java))
        }
    }
}
