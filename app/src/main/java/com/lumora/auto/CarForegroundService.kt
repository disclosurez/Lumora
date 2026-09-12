package com.lumora.auto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps the app process alive while the car screen is projected.
 *
 * The gearhead projection host binds the service but gives no guarantee the process survives
 * as a background app once the driver is elsewhere on the phone - and the car screen is the
 * app itself, so a killed process is a black head unit. fcaronte/AABrowser runs the same
 * keep-alive for the same reason; START_STICKY plus a low-importance notification is the
 * least intrusive shape that works.
 */
class CarForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        startNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(com.lumora.R.string.ui_car_keep_alive_channel),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(com.lumora.R.string.ui_car_keep_alive_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)

        val stopIntent = Intent(this, CarForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stop = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(com.lumora.R.string.ui_car_keep_alive_title))
            .setContentText(getString(com.lumora.R.string.ui_car_keep_alive_text))
            .setContentIntent(stop)
            .setOngoing(true)
            .build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    companion object {
        private const val CHANNEL_ID = "car_keep_alive"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "com.lumora.action.STOP_CAR_KEEP_ALIVE"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CarForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CarForegroundService::class.java))
        }
    }
}
