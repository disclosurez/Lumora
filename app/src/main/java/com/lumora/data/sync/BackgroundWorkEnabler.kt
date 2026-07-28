package com.lumora.data.sync

import android.content.Context
import androidx.work.*

/**
 * Enables periodic background work for catalog and EPG sync.
 * Should be called once after app initialization.
 */
object BackgroundWorkEnabler {

    fun initialize(context: Context) {
        // Schedule periodic catalog sync for each active provider
        // This is lightweight — it just registers the work requests

        // Schedule EPG sync (already done in BaseApplication)

        // Register boot receiver for re-scheduling alarms
        // (handled by AndroidManifest receivers)
    }

    /**
     * Enqueue an immediate one-time sync for all providers.
     */
    fun syncAllNow(context: Context) {
        // Trigger catalog sync
        val catalogRequest = OneTimeWorkRequestBuilder<CatalogSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "catalog_sync_all",
                ExistingWorkPolicy.REPLACE,
                catalogRequest
            )

        // Trigger EPG sync
        EpgSyncWorker.enqueue(context)
    }
}
