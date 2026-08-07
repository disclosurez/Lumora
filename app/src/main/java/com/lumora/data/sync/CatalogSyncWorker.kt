package com.lumora.data.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import com.lumora.BaseApplication
import com.lumora.data.local.LumoraDatabase
import com.lumora.data.local.entity.ChannelEntity
import com.lumora.data.local.entity.ProviderEntity
import com.lumora.model.Provider
import com.lumora.model.ProviderType
import com.lumora.parser.M3uParser
import com.lumora.parser.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for background catalog sync.
 * Refreshes provider content periodically.
 */
class CatalogSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val TAG = "CatalogSync"

    override suspend fun doWork(): Result {
        val db = LumoraDatabase.getInstance(applicationContext)
        val providerId = inputData.getString("provider_id")

        // No provider_id: a bulk request (BackgroundWorkEnabler.syncAllNow enqueues without
        // input data) - sync every provider in the database instead of failing. One slow or
        // dead provider is contained by the per-provider try/catch below.
        if (providerId == null) {
            val providers = db.providerDao().getAll()
            if (providers.isEmpty()) return Result.success()
            var allSuccess = true
            for (entity in providers) {
                try {
                    if (syncProvider(db, entity)) {
                        db.providerDao().updateLastSync(entity.id, System.currentTimeMillis())
                    } else {
                        allSuccess = false
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Sync failed for ${entity.name}: ${e.message}")
                    allSuccess = false
                }
            }
            // Mirror the single-provider path's attempt cap: without it a permanently dead
            // provider retries forever (exponential backoff up to ~5h), and each retry
            // re-syncs every provider and re-stamps their lastSyncAt.
            if (allSuccess) return Result.success()
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }

        val providerEntity = db.providerDao().getById(providerId) ?: return Result.failure()

        Log.d(TAG, "Starting sync for provider: ${providerEntity.name}")

        return try {
            if (syncProvider(db, providerEntity)) {
                db.providerDao().updateLastSync(providerId, System.currentTimeMillis())
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed: ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun syncProvider(db: LumoraDatabase, entity: ProviderEntity): Boolean {
        val provider = entity.toModel()
        return when (provider.type) {
            ProviderType.XTREAM -> syncXtream(provider, db, entity)
            else -> syncM3u(provider, db, entity)
        }
    }

    private suspend fun syncXtream(
        provider: Provider, db: LumoraDatabase, entity: ProviderEntity
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = XtreamClient(BaseApplication.instance.okHttpClient)
            val auth = client.authenticate(provider).getOrNull() ?: return@withContext false
            if (!auth.valid) return@withContext false

            val live = client.getLiveStreams(provider)
            val films = client.getVodStreams(provider)
            val series = client.getSeries(provider)

            // Convert and store
            val channels = (live + films + series).map { ch ->
                ChannelEntity(
                    id = ch.id,
                    providerId = entity.id,
                    name = ch.name,
                    url = ch.url,
                    logoUrl = ch.logoUrl,
                    posterUrl = ch.posterUrl,
                    backdropUrl = ch.backdropUrl,
                    groupTitle = ch.group,
                    tvgId = ch.tvgId,
                    tvgName = ch.tvgName,
                    tvgChno = ch.tvgChno,
                    mediaType = ch.mediaType.name,
                    categoryId = ch.categoryId,
                    categoryName = ch.categoryName,
                    description = ch.description,
                    year = ch.year,
                    rating = ch.rating
                )
            }

            db.channelDao().deleteByProvider(entity.id)
            db.channelDao().insertAll(channels)

            Log.d(TAG, "Synced ${channels.size} channels for ${entity.name}")
            return@withContext true
        } catch (e: Exception) {
            Log.w(TAG, "Xtream sync error: ${e.message}")
            return@withContext false
        }
    }

    private suspend fun syncM3u(
        provider: Provider, db: LumoraDatabase, entity: ProviderEntity
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = provider.m3uUrl ?: return@withContext false
            val result = M3uParser.parseFromUrl(url, BaseApplication.instance.okHttpClient)

            val channels = result.channels.map { ch ->
                ChannelEntity(
                    id = ch.id.ifBlank { ch.url.hashCode().toString() },
                    providerId = entity.id,
                    name = ch.name,
                    url = ch.url,
                    logoUrl = ch.logoUrl,
                    posterUrl = ch.posterUrl,
                    groupTitle = ch.group,
                    tvgId = ch.tvgId,
                    tvgName = ch.tvgName,
                    tvgChno = ch.tvgChno,
                    mediaType = ch.mediaType.name,
                    categoryName = ch.categoryName,
                    year = ch.year,
                    rating = ch.rating
                )
            }

            db.channelDao().deleteByProvider(entity.id)
            db.channelDao().insertAll(channels)

            Log.d(TAG, "Synced ${channels.size} channels for ${entity.name}")
            return@withContext true
        } catch (e: Exception) {
            Log.w(TAG, "M3U sync error: ${e.message}")
            return@withContext false
        }
    }

    private fun ProviderEntity.toModel(): Provider {
        return when (type) {
            "xtream" -> Provider(
                name = name,
                type = ProviderType.XTREAM,
                serverUrl = serverUrl,
                username = username,
                password = passwordEncrypted // Will be decrypted at usage point
            )
            else -> Provider(
                name = name,
                type = ProviderType.M3U,
                m3uUrl = m3uUrl,
                userAgent = userAgent
            )
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME_PREFIX = "catalog_sync_"

        fun enqueue(context: Context, providerId: String) {
            val input = Data.Builder().putString("provider_id", providerId).build()
            val request = OneTimeWorkRequestBuilder<CatalogSyncWorker>()
                .setInputData(input)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "$UNIQUE_WORK_NAME_PREFIX$providerId",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }

        fun schedulePeriodic(context: Context, providerId: String, intervalHours: Long = 6) {
            val input = Data.Builder().putString("provider_id", providerId).build()
            val request = PeriodicWorkRequestBuilder<CatalogSyncWorker>(intervalHours, TimeUnit.HOURS)
                .setInputData(input)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "$UNIQUE_WORK_NAME_PREFIX${providerId}_periodic",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
        }
    }
}
