package com.lumora.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restores scheduled recordings after device reboot.
 */
class RecordingRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // BOOT_COMPLETED lands on the main thread and rescheduleAll() blocks (DB read + alarm
        // setup). Run it off the main thread and keep the broadcast alive with goAsync() so the
        // ~10s receiver window can't blow into an ANR while the process is being brought up.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                RecordingScheduler.rescheduleAll(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
