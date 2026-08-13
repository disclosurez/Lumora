package com.lumora.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.lumora.model.Channel
import com.lumora.model.MediaType

private val FILENAME_UNSAFE_REGEX = Regex("""[^A-Za-z0-9._-]+""")

/** Wraps Android's system DownloadManager - it survives app kill, shows system download
 *  progress, and needs no storage permission when writing to the app's own external dir. */
object VodDownloader {

    /**
     * Why a stream cannot be downloaded, or null when it can.
     *
     * This downloader is the system [DownloadManager], which fetches one URL to one file. That
     * covers a provider's direct MP4 and nothing else, so the two cases it cannot handle are
     * rejected up front rather than "succeeding" into a useless file:
     *
     *  - an HLS playlist would download as a few KB of text listing segment URLs;
     *  - a torrent's URL points at the local HTTP server TorrentEngine runs for playback, which
     *    stops with the session - and the real file is already on disk anyway.
     */
    fun unsupportedReason(channel: Channel): String? = when {
        channel.url.isBlank() ->
            "Play it once first - this title has no stream until a source is found for it."
        channel.url.contains("127.0.0.1") || channel.url.contains("localhost") ->
            "Torrent streams can't be downloaded this way."
        // The system DownloadManager throws on anything that is not http(s) - a `data:` URI
        // playlist from a scraper crashed the app here rather than being refused. Anything of
        // that shape belongs to HlsDownloads; if it reaches this downloader at all, say so
        // instead of handing it over.
        HlsDownloads.isNonHttpUri(channel.url) ->
            "This source can't be downloaded."
        else -> null
    }

    fun enqueue(context: Context, channel: Channel): DownloadRecord {
        val extension = channel.url.substringAfterLast('.', "mp4").takeIf { it.length in 2..4 } ?: "mp4"
        val filename = FILENAME_UNSAFE_REGEX.replace(channel.name, "_").take(80) + "_${channel.id}.$extension"

        val request = DownloadManager.Request(Uri.parse(channel.url))
            .setTitle(channel.name)
            .setDestinationInExternalFilesDir(context, "downloads", filename)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        // The headers the stream needs - a Referer for a hotlink-protected CDN, the provider's
        // User-Agent. Without them a scraper-resolved URL that plays fine 403s when downloaded,
        // because DownloadManager makes its own request with none of the player's context.
        channel.streamHeaders?.forEach { (name, value) -> request.addRequestHeader(name, value) }
        channel.streamUserAgent?.takeIf { it.isNotBlank() }
            ?.let { request.addRequestHeader("User-Agent", it) }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadManagerId = downloadManager.enqueue(request)

        val record = DownloadRecord(
            id = channel.id,
            title = channel.name,
            subtitle = if (channel.mediaType == MediaType.SERIES) channel.categoryName ?: "Episode" else "Movie",
            posterUrl = channel.posterUrl ?: channel.logoUrl,
            mediaType = channel.mediaType.name,
            downloadManagerId = downloadManagerId,
            status = DownloadStatus.QUEUED
        )
        DownloadStore.add(context, record)
        return record
    }

    /** Polls DownloadManager for this record's current state. Only writes back to disk on a status change, not every tick. */
    fun refreshStatus(context: Context, record: DownloadRecord): DownloadRecord {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(record.downloadManagerId)) ?: return record
        cursor.use {
            if (!it.moveToFirst()) return record.copy(status = DownloadStatus.FAILED)

            val statusIdx = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val downloadedIdx = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIdx = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val localUriIdx = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)

            val dmStatus = it.getInt(statusIdx)
            val downloaded = it.getLong(downloadedIdx)
            val total = it.getLong(totalIdx)
            val progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0

            val newStatus = when (dmStatus) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETE
                DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                DownloadManager.STATUS_RUNNING -> DownloadStatus.DOWNLOADING
                else -> DownloadStatus.QUEUED
            }
            val localPath = if (newStatus == DownloadStatus.COMPLETE) {
                // Cursor.getString() returns null for a NULL column, and Uri.parse(null) throws
                // NPE before the ?. can help. Read into a val and only parse when non-null;
                // a missing local URI means "not downloaded" - no file to point at.
                it.getString(localUriIdx)?.let { rawUri -> Uri.parse(rawUri).path }
            } else null

            val updated = record.copy(status = newStatus, progressPercent = progress, localFilePath = localPath ?: record.localFilePath)
            if (newStatus != record.status) DownloadStore.update(context, updated)
            return updated
        }
    }

    fun delete(context: Context, record: DownloadRecord) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        runCatching { downloadManager.remove(record.downloadManagerId) }
        DownloadStore.remove(context, record.id)
    }
}
