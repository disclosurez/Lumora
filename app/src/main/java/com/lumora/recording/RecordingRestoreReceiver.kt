package com.lumora.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restores scheduled recordings after device reboot.
 */
class RecordingRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            RecordingScheduler.rescheduleAll(context)
        }
    }
}
