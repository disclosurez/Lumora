package com.lumora.data.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import com.lumora.BaseApplication
import com.lumora.data.local.LumoraDatabase
import com.lumora.data.parser.XmltvParser
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for background EPG sync.
 * Fetches and parses XMLTV EPG sources.
 */
class EpgSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val TAG = "EpgSync"

    override suspend fun doWork(): Result {
        val db = LumoraDatabase.getInstance(applicationContext)
        val sources = db.epgSourceDao().getEnabled()

        if (sources.isEmpty()) return Result.success()

        var allSuccess = true
        for (source in sources) {
            try {
                Log.d(TAG, "Fetching EPG: ${source.name}")
                val request = Request.Builder().url(source.url)
                    .header("User-Agent", source.userAgent ?: "Lumora/2.0")
                    .header("Accept", "application/xml, text/xml, */*")
                    .build()

                val response = BaseApplication.instance.okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} for ${source.name}")
                    allSuccess = false
                    continue
                }

                val body = response.body ?: continue
                val result = XmltvParser.parse(body.byteStream())

                Log.d(TAG, "Parsed ${result.channels.size} channels, ${result.programmes.size} programs from ${source.name}")

                db.epgSourceDao().markRefreshed(source.id, System.currentTimeMillis())

            } catch (e: Exception) {
                Log.w(TAG, "EPG sync error for ${source.name}: ${e.message}")
                allSuccess = false
            }
        }

        return if (allSuccess) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "epg_sync"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<EpgSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun schedulePeriodic(context: Context, intervalHours: Long = 24) {
            val request = PeriodicWorkRequestBuilder<EpgSyncWorker>(intervalHours, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "${UNIQUE_WORK_NAME}_periodic",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
        }
    }
}
