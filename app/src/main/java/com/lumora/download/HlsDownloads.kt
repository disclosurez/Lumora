package com.lumora.download

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import com.lumora.model.Channel
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Offline download for HLS streams.
 *
 * [VodDownloader] hands a single URL to Android's system `DownloadManager`, which is correct for
 * a provider's direct MP4 and useless for anything else: point it at an `.m3u8` and it saves a
 * few KB of text listing segment URLs. Every scraper-resolved stream is HLS, so "download" was
 * refusing for the entire streaming-site half of the app.
 *
 * HLS has to be downloaded segment by segment into a cache that the player can later read back
 * as if it were the live stream, which is what Media3's offline stack does. That is why this is a
 * second downloader rather than a change to the first - the two have nothing in common beyond
 * the word.
 *
 * The cache is deliberately its own directory and never evicted ([NoOpCacheEvictor]): these are
 * files the user asked to keep, and a size-based evictor would quietly delete a downloaded
 * episode to make room for a newer one.
 */
object HlsDownloads {

    private const val CACHE_DIR = "hls_downloads"

    /** Segment fetches run in parallel; more than this saturates a TV stick's link. */
    private const val MAX_PARALLEL_DOWNLOADS = 3

    @Volatile
    private var cacheInstance: SimpleCache? = null

    @Volatile
    private var managerInstance: DownloadManager? = null

    @Volatile
    private var databaseInstance: StandaloneDatabaseProvider? = null

    /**
     * One provider for both the cache index and the download index.
     *
     * Both live in the same underlying `exoplayer_internal.db`, so handing each a
     * [StandaloneDatabaseProvider] of its own puts two SQLiteOpenHelpers on one file and the
     * second one to open can fail with a locked database.
     */
    @Synchronized
    private fun database(context: Context): StandaloneDatabaseProvider =
        databaseInstance ?: StandaloneDatabaseProvider(context.applicationContext)
            .also { databaseInstance = it }

    /**
     * Shared HTTP source factory for segment fetches; request headers are stamped per data source
     * by [headerStampingFactory] rather than set here in [enqueue], because the downloader reads
     * them on its own thread after enqueueing.
     */
    private val upstream = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(60_000)

    /**
     * Headers for the download most recently passed to [enqueue], applied at data-source creation.
     *
     * Media3's downloader runs on its own executor and reads request properties from [upstream]
     * only when it actually builds a network request, which is strictly after [enqueue] returns -
     * so setting them on the shared factory and clearing them synchronously in a `finally` meant
     * the clear always won and the headers never reached a segment fetch. They are instead stamped
     * per data source by [headerStampingFactory], on the downloader's own thread, where they cannot
     * race. Replaced wholesale on every enqueue (empty for a headerless stream) so one download's
     * headers can never survive into the next.
     */
    @Volatile
    private var enqueuedHeaders: Map<String, String> = emptyMap()

    /**
     * True for a stream this downloader is the right one for.
     *
     * Matching on the string "m3u8" alone was too narrow in both directions a scraper delivers
     * HLS. Some hosts hand back the playlist *inline* as a `data:` URI with no path at all
     * (`data:application/vnd.apple.mpegurl;base64,…`), and some serve it from a signed URL whose
     * path ends in a token rather than an extension. Neither contains "m3u8", so both fell
     * through to the system downloader - which rejects a non-HTTP URI by throwing, taking the
     * app down with it.
     */
    fun isHls(url: String): Boolean =
        url.contains("m3u8", ignoreCase = true) ||
            url.contains("mpegurl", ignoreCase = true)

    /** True for a URI the system `DownloadManager` would reject outright. */
    fun isNonHttpUri(url: String): Boolean =
        !url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)

    @Synchronized
    fun cache(context: Context): SimpleCache = cacheInstance ?: SimpleCache(
        // getExternalFilesDir can return null when external storage is unmounted; a bare null here
        // becomes File(null, ...) which NPEs, so fall back to the always-available internal dir.
        File(
            context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir,
            CACHE_DIR,
        ),
        NoOpCacheEvictor(),
        database(context),
    ).also { cacheInstance = it }

    @Synchronized
    fun manager(context: Context): DownloadManager {
        managerInstance?.let { return it }
        val app = context.applicationContext
        return DownloadManager(
            app,
            DefaultDownloadIndex(database(app)),
            DefaultDownloaderFactory(
                // Reads through the same cache the player will later read back from, which is
                // what makes a downloaded stream playable offline rather than merely stored.
                CacheDataSource.Factory()
                    .setCache(cache(app))
                    // DefaultDataSource, not the bare HTTP one: a scraper can return the
                    // playlist itself as a `data:` URI, which only the scheme-dispatching
                    // source can open. Its segment URLs are ordinary https and still go
                    // through `upstream`, headers and all, stamped per request.
                    .setUpstreamDataSourceFactory(headerStampingFactory(app)),
                Executors.newFixedThreadPool(MAX_PARALLEL_DOWNLOADS),
            ),
        ).apply {
            maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
            managerInstance = this
        }
    }

    /**
     * Wraps [upstream] so each created data source carries the stream's current [enqueuedHeaders].
     *
     * [upstream] is shared across every download, so its request properties cannot be set from
     * [enqueue] - the downloader reads them later, on its own thread, and a synchronous clear would
     * always win. Stamping them here, inside `createDataSource()` - which the downloader calls on
     * that thread just before building a request - puts the right headers on exactly the segment
     * fetches that need them. [upstream]'s properties are reset on every call (to [enqueuedHeaders],
     * which is empty for a headerless stream), so one download's Referer can never leak into the
     * next. Two *simultaneous* downloads from different hosts would still get one host's headers
     * applied to both; that needs a per-host data source rather than a shared factory.
     */
    private fun headerStampingFactory(context: Context): DataSource.Factory {
        val delegate = DefaultDataSource.Factory(context, upstream)
        return object : DataSource.Factory {
            override fun createDataSource(): DataSource {
                // DefaultDataSource.Factory constructs the underlying HTTP source here, inside
                // createDataSource(), so properties set on `upstream` at this point are captured
                // by the source the returned DefaultDataSource will use.
                upstream.setDefaultRequestProperties(enqueuedHeaders)
                return delegate.createDataSource()
            }
        }
    }

    /**
     * Queues [channel]'s HLS stream for download.
     *
     * A hotlink-protected CDN 403s every segment without its Referer, so the stream's headers
     * have to be applied. [DownloadRequest] has nowhere to carry them, so they go on the shared
     * upstream factory - but Media3's downloader reads that factory on its own executor thread,
     * strictly after this method returns, so setting the properties here and clearing them in a
     * `finally` never reached a segment fetch. The headers are instead recorded in
     * [enqueuedHeaders] and stamped onto each data source at creation time by
     * [headerStampingFactory], on the downloader's thread, exactly when a request is built. The
     * recorded headers are replaced wholesale on every enqueue (empty for a headerless stream), so
     * one download's headers never survive into the next. Two *simultaneous* downloads from
     * different hosts would still have one host's headers applied to both; that needs a per-host
     * data source rather than a shared factory.
     */
    fun enqueue(context: Context, channel: Channel) {
        val headers = buildMap {
            channel.streamHeaders?.let { putAll(it) }
            channel.streamUserAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        }
        enqueuedHeaders = headers
        val item = MediaItem.Builder()
            .setUri(channel.url)
            .setMediaId(channel.id)
            .build()
        val request = DownloadRequest.Builder(channel.id, item.localConfiguration!!.uri)
            .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
            .setData(channel.name.toByteArray())
            .build()
        manager(context).addDownload(request)
        manager(context).resumeDownloads()
    }

    /**
     * Marks a [DownloadRecord] as belonging to this downloader rather than the system one.
     *
     * Media3 owns an HLS download's lifecycle, so there is no system `DownloadManager` id to
     * record - and the read paths all key off that id. Left unhandled, an HLS record is looked
     * up in the system downloader, found missing, and reported FAILED the moment the Downloads
     * tab is opened, which is exactly what a working download looked like from the outside.
     */
    const val NO_SYSTEM_ID = -1L

    fun owns(record: DownloadRecord): Boolean = record.downloadManagerId == NO_SYSTEM_ID

    /**
     * Current state of [record] read from Media3's own download index.
     *
     * Returns the record unchanged when the index has never heard of it: a download queued
     * before this app version, or one whose index row was lost, should stay as it is rather
     * than being declared failed.
     */
    fun refreshStatus(context: Context, record: DownloadRecord): DownloadRecord {
        val download = runCatching { manager(context).downloadIndex.getDownload(record.id) }
            .getOrNull() ?: return record
        val status = when (download.state) {
            Download.STATE_COMPLETED -> DownloadStatus.COMPLETE
            Download.STATE_FAILED -> DownloadStatus.FAILED
            Download.STATE_DOWNLOADING -> DownloadStatus.DOWNLOADING
            else -> DownloadStatus.QUEUED
        }
        // percentDownloaded is NaN until the first segment lands and the total is known.
        val percent = download.percentDownloaded.takeIf { !it.isNaN() }?.toInt() ?: 0
        val updated = record.copy(status = status, progressPercent = percent.coerceIn(0, 100))
        if (status != record.status) DownloadStore.update(context, updated)
        return updated
    }

    /**
     * The stream URL a completed download was fetched from.
     *
     * An HLS download has no single file on disk to point a player at - it is a tree of segments
     * inside [cache], keyed by their original URLs. Playing it back means replaying the *same*
     * URL through a data source that reads this cache, so the URL itself is what has to be
     * recovered, and Media3's index is where it was kept.
     */
    fun sourceUrl(context: Context, id: String): String? =
        runCatching { manager(context).downloadIndex.getDownload(id)?.request?.uri?.toString() }
            .getOrNull()

    /**
     * A data source that serves a completed download from [cache] and never reaches the network.
     *
     * [CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR] is deliberately not set: a miss should fail
     * loudly rather than silently re-fetching over the network, which would make a broken
     * offline download look like a slow one.
     */
    fun offlineDataSourceFactory(context: Context): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(null)
            .setCacheWriteDataSinkFactory(null)

    fun remove(context: Context, id: String) {
        manager(context).removeDownload(id)
    }
}
