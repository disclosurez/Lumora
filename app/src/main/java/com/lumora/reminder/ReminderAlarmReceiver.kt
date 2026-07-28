package com.lumora.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.lumora.MainActivity
import com.lumora.R
import com.lumora.cache.ProgramReminder
import com.lumora.cache.ReminderStore

const val REMINDER_NOTIFICATION_CHANNEL_ID = "program_reminders"

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: return
        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: ""
        val programTitle = intent.getStringExtra(EXTRA_PROGRAM_TITLE) ?: ""
        val startTimestamp = intent.getLongExtra(EXTRA_START_TIMESTAMP, 0L)

        ReminderStore.remove(context, ProgramReminder(channelId, channelName, programTitle, startTimestamp).key)
        showNotification(context, channelId, channelName, programTitle)
    }

    private fun showNotification(context: Context, channelId: String, channelName: String, programTitle: String) {
        ensureChannel(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_channel_id", channelId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, channelId.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, REMINDER_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(programTitle.ifBlank { "Starting soon" })
            .setContentText("$channelName starts in about 5 minutes")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(channelId.hashCode(), notification) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(REMINDER_NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            REMINDER_NOTIFICATION_CHANNEL_ID,
            "Programme reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Alerts a few minutes before a show you set a reminder for" }
        manager.createNotificationChannel(channel)
    }
}
