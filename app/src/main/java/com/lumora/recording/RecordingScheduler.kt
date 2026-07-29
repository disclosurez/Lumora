package com.lumora.recording

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lumora.data.local.LumoraDatabase
import com.lumora.data.local.entity.RecordingEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * Schedules recordings using AlarmManager.
 * Fires at the scheduled start time to begin capture,
 * and at the scheduled end time to stop.
 */
class RecordingScheduler {

    companion object {
        private const val EXTRA_RECORDING_ID = "recording_id"
        private const val EXTRA_ACTION = "action"
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"

        /**
         * Schedule a recording via AlarmManager.
         */
        fun schedule(context: Context, recording: RecordingEntity) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            // Start alarm
            val startIntent = Intent(context, RecordingAlarmReceiver::class.java).apply {
                putExtra(EXTRA_RECORDING_ID, recording.id)
                putExtra(EXTRA_ACTION, ACTION_START)
            }
            val startPendingIntent = PendingIntent.getBroadcast(
                context,
                recording.id.hashCode(),
                startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val startTimeMs = recording.startTimeUtc * 1000 - (recording.paddingBeforeMin * 60 * 1000L)
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                startTimeMs,
                startPendingIntent
            )

            // Stop alarm
            val stopIntent = Intent(context, RecordingAlarmReceiver::class.java).apply {
                putExtra(EXTRA_RECORDING_ID, recording.id)
                putExtra(EXTRA_ACTION, ACTION_STOP)
            }
            val stopPendingIntent = PendingIntent.getBroadcast(
                context,
                recording.id.hashCode() + 1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val stopTimeMs = recording.stopTimeUtc * 1000 + (recording.paddingAfterMin * 60 * 1000L)
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                stopTimeMs,
                stopPendingIntent
            )
        }

        /**
         * Cancel a scheduled recording.
         */
        fun cancel(context: Context, recording: RecordingEntity) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val startIntent = Intent(context, RecordingAlarmReceiver::class.java).apply {
                putExtra(EXTRA_RECORDING_ID, recording.id)
                putExtra(EXTRA_ACTION, ACTION_START)
            }
            val startPendingIntent = PendingIntent.getBroadcast(
                context,
                recording.id.hashCode(),
                startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(startPendingIntent)

            val stopIntent = Intent(context, RecordingAlarmReceiver::class.java).apply {
                putExtra(EXTRA_RECORDING_ID, recording.id)
                putExtra(EXTRA_ACTION, ACTION_STOP)
            }
            val stopPendingIntent = PendingIntent.getBroadcast(
                context,
                recording.id.hashCode() + 1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(stopPendingIntent)
        }

        /**
         * Reschedule all future recordings (e.g., after boot).
         */
        fun rescheduleAll(context: Context) {
            runBlocking(Dispatchers.IO) {
                val db = LumoraDatabase.getInstance(context)
                val recordings = db.recordingDao().getScheduled()
                recordings.forEach { schedule(context, it) }
            }
        }

        /**
         * Create a recording from a program entry.
         */
        fun createRecording(
            channelId: String,
            channelName: String,
            programTitle: String,
            startTimeUtc: Long,
            stopTimeUtc: Long,
            providerId: String? = null
        ): RecordingEntity {
            return RecordingEntity(
                id = UUID.randomUUID().toString(),
                channelId = channelId,
                channelName = channelName,
                programTitle = programTitle,
                startTimeUtc = startTimeUtc,
                stopTimeUtc = stopTimeUtc,
                status = "SCHEDULED",
                providerId = providerId
            )
        }
    }
}

/**
 * Alarm receiver that handles recording start/stop actions.
 */
class RecordingAlarmReceiver : BroadcastReceiver() {
    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private const val TAG = "RecordingAlarm"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val recordingId = intent.getStringExtra("recording_id") ?: return
        val action = intent.getStringExtra("action") ?: return

        scope.launch {
            try {
                val db = LumoraDatabase.getInstance(context)

                when (action) {
                    "start" -> {
                        val recording = db.recordingDao().getById(recordingId) ?: return@launch
                        val updated = recording.copy(status = "RECORDING")
                        db.recordingDao().update(updated)
                        Log.d(TAG, "Recording started: ${recording.programTitle}")
                    }
                    "stop" -> {
                        val recording = db.recordingDao().getById(recordingId) ?: return@launch
                        val updated = recording.copy(
                            status = "COMPLETED",
                            completedAt = System.currentTimeMillis()
                        )
                        db.recordingDao().update(updated)
                        Log.d(TAG, "Recording completed: ${recording.programTitle}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Recording alarm failed: ${e.message}")
            }
        }
    }
}
