package com.lumora.download

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import com.lumora.model.Channel
import java.io.File
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

    /** Held so [enqueue] can put a stream's headers on it before the download starts. */
    private val upstream = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(60_000)

    /** True for a stream this downloader is the right one for. */
    fun isHls(url: String): Boolean = url.contains("m3u8", ignoreCase = true)

    @Synchronized
    fun cache(context: Context): SimpleCache = cacheInstance ?: SimpleCache(
        File(context.applicationContext.getExternalFilesDir(null), CACHE_DIR),
        NoOpCacheEvictor(),
        StandaloneDatabaseProvider(context.applicationContext),
    ).also { cacheInstance = it }

    @Synchronized
    fun manager(context: Context): DownloadManager {
        managerInstance?.let { return it }
        val app = context.applicationContext
        val databaseProvider = StandaloneDatabaseProvider(app)
        return DownloadManager(
            app,
            DefaultDownloadIndex(databaseProvider),
            DefaultDownloaderFactory(
                // Reads through the same cache the player will later read back from, which is
                // what makes a downloaded stream playable offline rather than merely stored.
                CacheDataSource.Factory()
                    .setCache(cache(app))
                    .setUpstreamDataSourceFactory(upstream),
                Executors.newFixedThreadPool(MAX_PARALLEL_DOWNLOADS),
            ),
        ).apply {
            maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
            managerInstance = this
        }
    }

    /**
     * Queues [channel]'s HLS stream for download.
     *
     * A hotlink-protected CDN 403s every segment without its Referer, so the stream's headers
     * have to be applied. [DownloadRequest] has nowhere to carry them, so they go on the shared
     * upstream factory instead - which means they are global, not per-download. Two simultaneous
     * downloads from different hosts would have the second one's headers applied to both. Left
     * as-is because downloads are started one at a time from the UI; if that changes this needs
     * a per-host data source rather than a shared factory.
     */
    fun enqueue(context: Context, channel: Channel) {
        val headers = buildMap {
            channel.streamHeaders?.let { putAll(it) }
            channel.streamUserAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        }
        if (headers.isNotEmpty()) upstream.setDefaultRequestProperties(headers)
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

    /** Percent complete for [id], or null when this downloader has never seen it. */
    fun progressFor(context: Context, id: String): Float? =
        manager(context).currentDownloads.firstOrNull { it.request.id == id }?.percentDownloaded

    fun remove(context: Context, id: String) {
        manager(context).removeDownload(id)
    }
}
