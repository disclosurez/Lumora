package com.lumora.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // BOOT_COMPLETED lands on the main thread and rescheduleAll() does
                // SharedPreferences IO plus one alarm per reminder. Run it off the main
                // thread and keep the broadcast alive with goAsync() so the receiver
                // window can't blow into an ANR while the process is being brought up.
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ReminderScheduler.rescheduleAll(context.applicationContext)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
