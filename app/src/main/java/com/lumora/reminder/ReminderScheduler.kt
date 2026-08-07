package com.lumora.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.lumora.cache.ProgramReminder
import com.lumora.cache.ReminderStore

private const val LEAD_TIME_MINUTES = 5

const val EXTRA_CHANNEL_ID = "channelId"
const val EXTRA_CHANNEL_NAME = "channelName"
const val EXTRA_PROGRAM_TITLE = "programTitle"
const val EXTRA_START_TIMESTAMP = "startTimestamp"

/**
 * Schedules/cancels the reminder alarm and keeps ReminderStore as the source of
 * truth. Uses an inexact-while-idle alarm (no SCHEDULE_EXACT_ALARM permission
 * dance needed) since "5 minutes before a show" doesn't need to-the-second precision.
 */
object ReminderScheduler {

    fun isScheduled(context: Context, key: String): Boolean = ReminderStore.get(context, key) != null

    /**
     * Schedules a reminder and arms its alarm. Returns true when the reminder was stored and
     * armed. Returns false when the trigger time (5 minutes before the program) has already
     * passed: such a reminder can never fire, so it must not be persisted - otherwise it would
     * linger forever, stored but never armed. Callers can use the result to tell the user the
     * reminder couldn't be set.
     */
    fun schedule(context: Context, reminder: ProgramReminder): Boolean {
        val triggerAtMillis = (reminder.startTimestamp - LEAD_TIME_MINUTES * 60) * 1000
        if (triggerAtMillis <= System.currentTimeMillis()) return false
        ReminderStore.add(context, reminder)
        armAlarm(context, reminder)
        return true
    }

    fun cancel(context: Context, reminder: ProgramReminder) {
        ReminderStore.remove(context, reminder.key)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntentFor(context, reminder))
    }

    /** Re-arms every still-future reminder - called after device reboot. */
    fun rescheduleAll(context: Context) {
        val nowSeconds = System.currentTimeMillis() / 1000
        ReminderStore.pruneExpired(context, nowSeconds)
        ReminderStore.getAll(context).forEach { armAlarm(context, it) }
    }

    private fun armAlarm(context: Context, reminder: ProgramReminder) {
        val triggerAtMillis = (reminder.startTimestamp - LEAD_TIME_MINUTES * 60) * 1000
        // Defensive backstop: schedule() validates before persisting and rescheduleAll() prunes
        // expired reminders first, so a past trigger here is unexpected - skip rather than fire
        // an immediate alarm.
        if (triggerAtMillis <= System.currentTimeMillis()) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntentFor(context, reminder))
    }

    private fun pendingIntentFor(context: Context, reminder: ProgramReminder): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            putExtra(EXTRA_CHANNEL_ID, reminder.channelId)
            putExtra(EXTRA_CHANNEL_NAME, reminder.channelName)
            putExtra(EXTRA_PROGRAM_TITLE, reminder.programTitle)
            putExtra(EXTRA_START_TIMESTAMP, reminder.startTimestamp)
        }
        return PendingIntent.getBroadcast(
            context,
            reminder.key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
