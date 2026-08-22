package com.lumora.data.sync

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.work.*
import com.lumora.BaseApplication
import com.lumora.data.local.LumoraDatabase
import com.lumora.cache.ChannelCache
import com.lumora.data.local.entity.EpgProgramEntity
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

        // XMLTV programme rows carry the feed's raw tvg-id, but the guide reads by app
        // channel id (MainActivityEpg.resolveEpgPrograms → epgProgramDao().upcomingFor). Rows
        // persisted under the raw id would be dead disk weight, and a raw id colliding with an
        // app id would clobber rows the Xtream in-app path wrote. Load the mapping once per run
        // (single query, no N+1 per source) and map every programme onto the app channel ids
        // whose tvg-id matches.
        val tvgIdToChannelIds = buildTvgIdMapping()
        // No catalogue yet (a fresh install, or a run that beat the first load) means no
        // programme in any feed can match anything. Nothing about that is the sources' fault,
        // and charging them a failure each would retire every EPG source on a device that had
        // simply not finished loading - so the run is retried rather than scored.
        if (tvgIdToChannelIds.isEmpty()) {
            Log.w(TAG, "No channels with tvg-ids yet; deferring EPG sync")
            return Result.retry()
        }

        var allSuccess = true
        val now = System.currentTimeMillis()
        for (source in sources) {
            // A source that keeps failing is rested, not retired. Past
            // MAX_CONSECUTIVE_FAILURES it is skipped for a window that doubles with each
            // further failure up to MAX_FAILURE_BACKOFF_MS, so it stops forcing Result.retry()
            // - which re-fetches every enabled source on each attempt - without ever becoming
            // unreachable. Retiring it outright could not work: a skipped source is never
            // fetched, so its count could never reset, and three failures during one outage
            // (the retry backoff starts at 30s, so that is a matter of minutes) would kill the
            // guide until the user deleted and re-added the source. The count resets on any
            // success (markRefreshed), and a re-added source starts at zero.
            val backoff = failureBackoffMs(source.consecutiveFailures)
            // A null lastRefreshedAt is a source that has never been attempted, which cannot
            // be inside a backoff window - it is fetched.
            val sinceAttempt = source.lastRefreshedAt?.let { now - it }
            if (backoff > 0 && sinceAttempt != null && sinceAttempt < backoff) {
                Log.w(TAG, "Resting EPG source ${source.name}: ${source.consecutiveFailures} consecutive failures, retrying in ${(backoff - sinceAttempt) / 60_000}m")
                continue
            }
            try {
                Log.d(TAG, "Fetching EPG: ${source.name}")
                val request = Request.Builder().url(source.url)
                    .header("User-Agent", source.userAgent ?: "Lumora/2.0")
                    .header("Accept", "application/xml, text/xml, */*")
                    .build()

                // .use() guarantees the response body is always closed, even when the
                // parse below throws or the body is absent.
                val response = BaseApplication.instance.okHttpClient.newCall(request).execute()
                response.use {
                    if (!it.isSuccessful) {
                        Log.w(TAG, "HTTP ${it.code} for ${source.name}")
                        db.epgSourceDao().incrementFailures(source.id, System.currentTimeMillis())
                        allSuccess = false
                        return@use
                    }

                    val body = it.body
                    if (body == null) {
                        Log.w(TAG, "Empty response body for ${source.name}")
                        db.epgSourceDao().incrementFailures(source.id, System.currentTimeMillis())
                        allSuccess = false
                        return@use
                    }
                    val result = XmltvParser.parse(body.byteStream())

                    Log.d(TAG, "Parsed ${result.channels.size} channels, ${result.programmes.size} programs from ${source.name}")

                    // Map raw tvg-ids onto app channel ids, skipping programmes with no
                    // matching app channel. Persist mirrors MainActivityEpg.persistEpgPrograms:
                    // replace (delete stale rows per affected app channel, then upsert) rather
                    // than merge, so a re-fetch is the source's current truth.
                    val rows = mutableListOf<EpgProgramEntity>()
                    // Collected as the rows are built rather than by mapping over them
                    // afterwards: rows runs to six figures for a large source, and
                    // `rows.map { it.channelId }.distinct()` materialised a second list that
                    // size purely to reach the handful of distinct ids inside it.
                    val touchedChannelIds = HashSet<String>()
                    var mapped = 0
                    var skipped = 0
                    for (programme in result.programmes) {
                        val ids = tvgIdToChannelIds[programme.channel]
                        if (ids.isNullOrEmpty()) {
                            skipped++
                            continue
                        }
                        mapped++
                        for (channelId in ids) {
                            touchedChannelIds.add(channelId)
                            rows.add(
                                EpgProgramEntity(
                                    channelId = channelId,
                                    startTimestamp = programme.startTimestamp,
                                    stopTimestamp = programme.stopTimestamp,
                                    title = programme.title
                                )
                            )
                        }
                    }
                    Log.d(TAG, "EPG mapping: $mapped programmes mapped, $skipped skipped (no matching app channel) for ${source.name}")

                    if (rows.isNotEmpty()) {
                        // One transaction for the whole replace. Each suspend DAO call commits
                        // on its own, so the per-channel deletes were one SQLite transaction -
                        // journal write plus fsync - per channel; a source covering thousands
                        // of channels meant thousands of fsyncs, which is minutes of wall time
                        // on the slow flash in a budget TV stick. It also makes the replace
                        // atomic, so a sync killed midway can no longer leave channels whose
                        // old programmes are deleted and whose new ones were never written.
                        db.withTransaction {
                            for (channelId in touchedChannelIds) {
                                db.epgProgramDao().deleteForChannel(channelId)
                            }
                            db.epgProgramDao().upsertAll(rows)
                        }
                        // Only a sync that actually wrote guide rows counts as a success -
                        // lastSuccessAt is what the UI reads to decide the guide is fresh, and
                        // stamping it on a source that mapped nothing would paint a stale guide
                        // as current. Zero mapped rows is a failure like any other.
                        db.epgSourceDao().markRefreshed(source.id, System.currentTimeMillis())
                    } else {
                        Log.w(TAG, "No programmes mapped for ${source.name}; treating as failure")
                        db.epgSourceDao().incrementFailures(source.id, System.currentTimeMillis())
                        allSuccess = false
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "EPG sync error for ${source.name}: ${e.message}")
                db.epgSourceDao().incrementFailures(source.id, System.currentTimeMillis())
                allSuccess = false
            }
        }

        return if (allSuccess) Result.success() else Result.retry()
    }

    /** Indexes every known channel by tvg-id → app channel ids, so XMLTV rows can be persisted
     *  under the ids the guide actually reads. Blank tvg-ids can't match anything.
     *
     *  Reads [ChannelCache], not Room. This used to query the Room `channels` table, which
     *  only the catalog sync worker ever wrote and nothing ever scheduled - so it was empty on
     *  every install, the mapping matched nothing, and every single programme was dropped as
     *  "no matching app channel". The cache is the merged catalogue the UI actually browses,
     *  so it is the one carrying the ids the guide reads by. */
    private fun buildTvgIdMapping(): Map<String, Set<String>> {
        val map = HashMap<String, MutableSet<String>>()
        for (channel in ChannelCache.load(applicationContext).orEmpty()) {
            channel.tvgId?.takeIf { it.isNotBlank() }?.let { tvgId ->
                map.getOrPut(tvgId) { mutableSetOf() }.add(channel.id)
            }
        }
        return map
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "epg_sync"

        /** Consecutive failures tolerated before a source starts being rested (see doWork). */
        private const val MAX_CONSECUTIVE_FAILURES = 3

        /** Longest a failing source is rested for. A day is well past any outage worth
         *  waiting out, and short enough that a source fixed at the far end comes back on
         *  its own rather than on the user noticing the guide is empty. */
        private const val MAX_FAILURE_BACKOFF_MS = 24 * 60 * 60 * 1000L

        /** How long to leave a source alone after [failures] consecutive failures: nothing
         *  until the allowance is spent, then an hour, doubling to [MAX_FAILURE_BACKOFF_MS]. */
        private fun failureBackoffMs(failures: Int): Long {
            if (failures < MAX_CONSECUTIVE_FAILURES) return 0L
            val steps = (failures - MAX_CONSECUTIVE_FAILURES).coerceAtMost(10)
            return (60 * 60 * 1000L shl steps).coerceAtMost(MAX_FAILURE_BACKOFF_MS)
        }

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

        /** Re-download interval, matched to [com.lumora.EPG_DISK_TTL_MS]. The guide treats a
         *  stored programme older than that as stale and refuses to paint it, and the only
         *  other refill path is Xtream's per-channel short EPG - so an XMLTV-only channel
         *  (M3U/Stalker) went blank once the disk copy aged out. Syncing on the same 6h
         *  period keeps the stored guide inside the window that the reader accepts. */
        const val DEFAULT_INTERVAL_HOURS = 6L

        fun schedulePeriodic(context: Context, intervalHours: Long = DEFAULT_INTERVAL_HOURS) {
            val request = PeriodicWorkRequestBuilder<EpgSyncWorker>(intervalHours, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
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
