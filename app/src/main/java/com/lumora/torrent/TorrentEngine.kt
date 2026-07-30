package com.lumora.torrent

import android.util.Log
import com.frostwire.jlibtorrent.AddTorrentParams
import com.frostwire.jlibtorrent.AnnounceEntry
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.Sha1Hash
import com.frostwire.jlibtorrent.TorrentFlags
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import java.io.File
import java.util.Locale

/**
 * Turns a magnet link into a locally-served, seekable video stream using libtorrent.
 *
 * The flow: start a libtorrent session, fetch the torrent metadata for the magnet, pick the one
 * video file the user actually wants (by season/episode, else the largest), download it
 * sequentially, and expose it over a local HTTP server ([StreamHttpServer]) that gates every read
 * on the relevant pieces being present. The player is handed the returned
 * `http://127.0.0.1:PORT/...` URL, and Range requests (seeks) just reprioritise pieces.
 *
 * Ported near-verbatim from the old torrentplugin APK's `TorrentStreamer` - this used to run in a
 * companion app talking to Lumora over Messenger IPC; it's now a first-class part of the app
 * (see the plugin migration in `com.lumora.plugin.js`), called directly instead of through IPC,
 * with [com.lumora.torrent.TorrentForegroundService] covering the same "long-running background
 * network op needs a foreground notification" requirement the old plugin's own Service handled.
 *
 * One engine instance serves one magnet at a time; [stop] tears everything down. Nothing here is
 * thread safe beyond the single start/stop lifecycle the caller drives.
 */
class TorrentEngine(private val cacheDir: File) {

    private val session = SessionManager()
    @Volatile private var handle: TorrentHandle? = null
    @Volatile private var server: StreamHttpServer? = null
    @Volatile private var downloadDir: File? = null
    @Volatile private var cancelled = false
    // Every libtorrent native call (here and in PieceGate) is made under this lock, and teardown
    // takes it before removing the session. Otherwise a have_piece() call racing session.remove()
    // dereferences a freed handle and SIGSEGVs the whole process.
    private val nativeLock = Any()

    /**
     * Blocks until the chosen file's first pieces are ready, then returns the local URL. Throws
     * on any failure (bad magnet, no video, metadata timeout). [onProgress] gets human-readable
     * status lines for the caller to show. Must be called off the main thread.
     */
    fun start(magnet: String, season: Int?, episode: Int?, onProgress: (String) -> Unit): String {
        fun step(msg: String) { Log.i(TAG, msg); onProgress(msg) }
        step("Starting libtorrent session")
        session.start()
        runCatching { session.startDht() }

        val work = File(cacheDir, "torrent-stream").apply { deleteRecursively(); mkdirs() }
        downloadDir = work

        // Add the magnet as a single, real torrent and keep it for the whole session. (The old
        // fetchMagnet-then-download approach used a throwaway torrent for metadata that libtorrent
        // then removed; the fresh download that replaced it never established peer connections, so
        // it sat at 0 peers forever even on well-seeded content.)
        val enriched = addTrackers(magnet)
        val hash = AddTorrentParams.parseMagnetUri(enriched).infoHash()
        step("Adding magnet")
        session.download(enriched, work)

        val th = awaitHandleByHash(hash) ?: throw IllegalStateException("Could not add torrent")
        handle = th
        th.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        for (t in TRACKERS) runCatching { th.addTracker(AnnounceEntry(t)) }
        // Kick peer discovery immediately: a freshly added magnet can sit auto-managed/queued and
        // never search for peers until forced. resume + DHT announce + tracker reannounce make it
        // actually go looking, which is what was missing (0 peers even with a healthy DHT).
        runCatching { th.resume() }
        runCatching { session.dhtAnnounce(hash) }
        runCatching { th.forceReannounce() }
        runCatching { th.forceDHTAnnounce() }

        // Wait for metadata on this same handle (peers connected here keep serving the data).
        step("Fetching torrent metadata")
        val metaDeadline = System.currentTimeMillis() + METADATA_TIMEOUT_SEC * 1000L
        while (true) {
            val ready = synchronized(nativeLock) {
                if (cancelled || !th.isValid) throw IllegalStateException("Stream stopped")
                val s = th.status()
                Log.i(TAG, "metadata: peers=${s.numPeers()} dht=${session.dhtNodes()} " +
                    "dhtRun=${session.isDhtRunning} hasMeta=${s.hasMetadata()}")
                s.hasMetadata()
            }
            if (ready) break
            if (System.currentTimeMillis() > metaDeadline)
                throw IllegalStateException("Timed out fetching torrent info")
            Thread.sleep(1000)
        }

        val info = th.torrentFile() ?: throw IllegalStateException("No metadata")
        val fileIndex = pickVideoFile(info, season, episode)
            ?: throw IllegalStateException("No video file found in torrent")
        val files = info.files()
        val relPath = files.filePath(fileIndex)
        val fileSize = files.fileSize(fileIndex)
        step("Selected ${File(relPath).name} (${fileSize / (1024 * 1024)} MB)")

        // Ignore every file; the PieceGate re-enables only a small rolling window of pieces around
        // the playhead, so we never pull the whole movie onto the tiny Fire TV disk at once. Peers
        // are already connected from the metadata phase, so the window downloads immediately.
        synchronized(nativeLock) {
            th.prioritizeFiles(Array(info.numFiles()) { Priority.IGNORE })
        }
        runCatching { th.forceReannounce() }
        runCatching { th.forceDHTAnnounce() }

        val pieceLength = info.pieceLength()
        val fileOffset = files.fileOffset(fileIndex)
        val firstPiece = (fileOffset / pieceLength).toInt()
        val lastPiece = ((fileOffset + fileSize - 1) / pieceLength).toInt()

        val gate = PieceGate(th, firstPiece, lastPiece, pieceLength, fileOffset, nativeLock)
        // Prefetch a small head so the player can start immediately.
        gate.prioritizeFrom(0, PREFETCH_PIECES)
        step("Buffering (pieces $firstPiece..$lastPiece)")

        // Wait for the first piece, logging peer/rate state each second so a stall is diagnosable.
        val bufferDeadline = System.currentTimeMillis() + BUFFER_TIMEOUT_MS
        while (true) {
            val have = synchronized(nativeLock) {
                if (cancelled || !th.isValid) throw IllegalStateException("Stream stopped")
                val s = th.status()
                Log.i(TAG, "buffering: peers=${s.numPeers()} seeds=${s.numSeeds()} " +
                    "down=${s.downloadRate() / 1024}KB/s progress=${(s.progress() * 100).toInt()}% " +
                    "state=${s.state()} dht=${session.dhtNodes()} dhtRun=${session.isDhtRunning} " +
                    "have[$firstPiece]=${th.havePiece(firstPiece)}")
                onProgress("Buffering… ${s.numPeers()} peers, ${s.downloadRate() / 1024} KB/s")
                th.havePiece(firstPiece)
            }
            if (have) break
            if (System.currentTimeMillis() > bufferDeadline)
                throw IllegalStateException("No data from peers - torrent may be dead")
            Thread.sleep(1000)
        }
        step("First piece ready")

        val target = File(work, relPath)
        val srv = StreamHttpServer(target, fileSize, gate)
        srv.start()
        server = srv
        step("Ready on port ${srv.listeningPort}")
        return "http://127.0.0.1:${srv.listeningPort}/stream"
    }

    fun stop() {
        runCatching { server?.stop() }
        server = null
        // Take the native lock so we never free the session/handle while a have_piece()/status()
        // call is running on the buffering or read thread.
        synchronized(nativeLock) {
            cancelled = true
            handle?.let { th -> runCatching { session.remove(th) } }
            handle = null
            runCatching { session.stop() }
        }
        downloadDir?.let { runCatching { it.deleteRecursively() } }
        downloadDir = null
    }

    private fun awaitHandleByHash(hash: Sha1Hash): TorrentHandle? {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val th = session.find(hash)
            if (th != null && th.isValid) return th
            Thread.sleep(100)
        }
        return null
    }

    /** Appends open trackers to a magnet so peers are found via trackers, not just cold DHT. */
    private fun addTrackers(magnet: String): String =
        magnet + TRACKERS.joinToString("") { "&tr=" + java.net.URLEncoder.encode(it, "UTF-8") }

    /** Prefers a file matching SxxExx when season/episode are given, else the largest video file. */
    private fun pickVideoFile(info: TorrentInfo, season: Int?, episode: Int?): Int? {
        val files = info.files()
        val videos = (0 until info.numFiles()).filter {
            val name = files.filePath(it).lowercase(Locale.ROOT)
            VIDEO_EXTS.any { ext -> name.endsWith(ext) }
        }
        if (videos.isEmpty()) return null

        if (season != null && episode != null) {
            val patterns = listOf(
                String.format(Locale.ROOT, "s%02de%02d", season, episode),
                String.format(Locale.ROOT, "%dx%02d", season, episode)
            )
            videos.firstOrNull { idx ->
                val name = files.filePath(idx).lowercase(Locale.ROOT)
                patterns.any { name.contains(it) }
            }?.let { return it }
        }
        return videos.maxByOrNull { files.fileSize(it) }
    }

    companion object {
        private const val TAG = "TorrentEngine"
        private const val METADATA_TIMEOUT_SEC = 90
        private const val PREFETCH_PIECES = 8
        private const val BUFFER_TIMEOUT_MS = 120_000L
        private val VIDEO_EXTS = listOf(".mkv", ".mp4", ".avi", ".m4v", ".mov", ".ts", ".webm")
        /** Well-known open trackers spliced into every magnet to speed up peer discovery. */
        private val TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://explodie.org:6969/announce",
            "https://tracker.tamersunion.org:443/announce"
        )
    }
}
