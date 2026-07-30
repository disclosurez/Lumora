package com.lumora.torrent

import android.util.Log
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
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
 * Built on libtorrent4j (libtorrent 2.x), not FrostWire's jlibtorrent (1.2): 1.2 decides whether
 * it may reach an address from the routing table it reads over netlink, and Android exposes an app
 * only LAN + loopback routes - no default route - so every listen socket was treated as
 * local-network-only and the engine refused to announce or dial a single peer. 2.x ignores that.
 *
 * One engine instance serves one magnet at a time; [stop] tears everything down. Nothing here is
 * thread safe beyond the single start/stop lifecycle the caller drives.
 */
class TorrentEngine(private val context: android.content.Context) {

    private val cacheDir: File get() = context.cacheDir
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
        startSession()

        val work = File(cacheDir, "torrent-stream").apply { deleteRecursively(); mkdirs() }
        downloadDir = work

        // Append open trackers to the magnet string so `session.download` bakes them into the
        // native params via libtorrent's own magnet URI parser. This is more reliable than adding
        // trackers post-hoc via `th.addTracker()`, which races with the session thread during the
        // torrent's initial state machine setup.
        val enriched = addTrackers(magnet)
        val hash = AddTorrentParams.parseMagnetUri(enriched).infoHashes.best
        step("Adding magnet")
        // Real flags, not null: libtorrent 2.x's download() takes the add_torrent_params flags
        // directly, and handing it null adds nothing - the handle never appears and the engine
        // fails with "Could not add torrent". AUTO_MANAGED + UPDATE_SUBSCRIBE is what the library's
        // own default add uses; sequential is set here rather than via a follow-up setFlags.
        session.download(
            enriched,
            work,
            TorrentFlags.AUTO_MANAGED
                .or_(TorrentFlags.UPDATE_SUBSCRIBE)
                .or_(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        )

        val th = awaitHandleByHash(hash) ?: throw IllegalStateException("Could not add torrent")
        handle = th
        th.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        runCatching { th.resume() }

        // Wait for metadata on this same handle (peers connected here keep serving the data).
        step("Fetching torrent metadata")
        val metaDeadline = System.currentTimeMillis() + METADATA_TIMEOUT_SEC * 1000L
        var tick = 0
        while (true) {
            val ready = synchronized(nativeLock) {
                if (cancelled || !th.isValid) throw IllegalStateException("Stream stopped")
                th.status().hasMetadata()
            }
            if (ready) break
            // libtorrent's per-torrent tracker/DHT announce depends on route data Android doesn't
            // give the app, so the torrent finds no peers on its own. Session-level DHT does work,
            // so peers are looked up there and handed straight to the torrent. Re-kicked while the
            // swarm is empty because the first lookup can land before the DHT has enough nodes.
            if (tick > 0 && tick % REKICK_EVERY_TICKS == 0) kickPeerDiscovery(th, hash)
            if (tick % DHT_LOOKUP_EVERY_TICKS == 0) injectDhtPeers(th, hash)
            tick++
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

        val pieceLength = info.pieceLength()
        val fileOffset = files.fileOffset(fileIndex)
        val firstPiece = (fileOffset / pieceLength).toInt()
        val lastPiece = ((fileOffset + fileSize - 1) / pieceLength).toInt()

        // Park every piece at IGNORE - the PieceGate re-enables only a small rolling window around
        // the playhead, so the whole movie never lands on the tiny Fire TV disk at once. Done with
        // piece priorities, never prioritizeFiles(): a file-priority update is applied
        // asynchronously on libtorrent's own thread, so it landed after the window priorities and
        // wiped them, leaving the torrent wanting nothing (which it reports as finished). The
        // prefetch head is set in the same array for the same reason - two overlapping priority
        // updates race each other.
        val prefetchEnd = (firstPiece + PREFETCH_PIECES).coerceAtMost(lastPiece)
        synchronized(nativeLock) {
            th.prioritizePieces(
                Array(info.numPieces()) { piece ->
                    if (piece in firstPiece..prefetchEnd) Priority.TOP_PRIORITY else Priority.IGNORE
                }
            )
        }
        runCatching { th.forceReannounce() }
        runCatching { th.forceDHTAnnounce() }

        val gate = PieceGate(th, firstPiece, lastPiece, pieceLength, fileOffset, nativeLock)
        step("Buffering (pieces $firstPiece..$lastPiece)")

        // Wait for the first piece.
        val bufferDeadline = System.currentTimeMillis() + BUFFER_TIMEOUT_MS
        var bufferTick = 0
        while (true) {
            val have = synchronized(nativeLock) {
                if (cancelled || !th.isValid) throw IllegalStateException("Stream stopped")
                val s = th.status()
                onProgress("Buffering… ${s.numPeers()} peers, ${s.downloadRate() / 1024} KB/s")
                th.havePiece(firstPiece)
            }
            if (have) break
            // Peers are only ever learned through the injection path, and connections do drop -
            // without topping the swarm up, a download that started fine can stall at 0 peers and
            // never recover on its own.
            if (bufferTick % DHT_LOOKUP_EVERY_TICKS == 0 &&
                synchronized(nativeLock) { th.isValid && th.status().numPeers() == 0 }
            ) {
                injectDhtPeers(th, hash)
            }
            bufferTick++
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

    private fun startSession() {
        session.start()

        val name = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .firstOrNull { it.isUp && !it.isLoopback && it.inetAddresses.toList().any { a -> a is Inet4Address } }
                ?.name
        }.getOrNull()
        // The port is randomised per session because two Lumora installs (release + debug) can be
        // on the same device, and a fixed one would make whichever bound second fail.
        val port = LISTEN_PORT_BASE + (Math.random() * LISTEN_PORT_SPAN).toInt()
        val interfaces = "0.0.0.0:$port,[::]:$port"

        // Booleans go through setBoolean, not setInteger: libtorrent encodes the setting's type in
        // its name bits and silently discards the wrong setter. Without announce_to_all_*,
        // libtorrent announces to only one tracker per tier.
        session.applySettings(
            SettingsPack()
                .setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
                .listenInterfaces(interfaces)
                .setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
                .setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
                .setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), DHT_BOOTSTRAP)
                .setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
                .setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
                .setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
        )

        // Block until sockets actually bind. If the wildcard form binds nothing (it has, on some
        // devices), fall back to binding by interface name - a socket that still runs the DHT the
        // peer injection depends on.
        var fellBack = false
        val deadline = System.currentTimeMillis() + LISTEN_BIND_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!runCatching { session.listenEndpoints() }.getOrNull().isNullOrEmpty()) break
            if (!fellBack && name != null &&
                System.currentTimeMillis() > deadline - LISTEN_BIND_TIMEOUT_MS / 2
            ) {
                fellBack = true
                runCatching { session.applySettings(SettingsPack().listenInterfaces("$name:$port")) }
            }
            Thread.sleep(200)
        }
        runCatching { session.startDht() }
    }

    /** Re-asks trackers and the DHT for peers. Cheap, and safe to repeat while the swarm is empty. */
    private fun kickPeerDiscovery(th: TorrentHandle, hash: Sha1Hash) {
        synchronized(nativeLock) {
            if (cancelled || !th.isValid) return
            runCatching { th.forceReannounce() }
            runCatching { th.forceDHTAnnounce() }
        }
        runCatching { session.dhtAnnounce(hash) }
    }

    /**
     * Looks the info-hash up in the DHT at *session* level and hands every peer it finds straight
     * to the torrent with `connect_peer`.
     *
     * This exists because the torrent's own peer discovery is dead on this platform while the
     * session's is fine: libtorrent's per-torrent tracker and DHT announces depend on the route
     * table it builds by enumerating netlink, which Android doesn't expose to the app, so the
     * torrent never learns a peer address on its own. Feeding it addresses directly sidesteps that
     * - once connected, those peers serve metadata and data over the normal protocol.
     *
     * Runs on its own thread - the lookup blocks for its whole timeout.
     */
    private fun injectDhtPeers(th: TorrentHandle, hash: Sha1Hash) {
        Thread {
            val peers = runCatching { session.dhtGetPeers(hash, DHT_LOOKUP_TIMEOUT_SEC) }.getOrNull()
            if (peers.isNullOrEmpty()) return@Thread
            for (peer in peers) {
                synchronized(nativeLock) {
                    if (cancelled || !th.isValid) return@Thread
                    runCatching { th.swig().connect_peer(peer.swig()) }
                }
            }
        }.start()
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
        /** How long to wait for the session's listen sockets before adding the torrent anyway. */
        private const val LISTEN_BIND_TIMEOUT_MS = 6_000L
        /** Ticks between re-asking trackers/DHT for peers while the swarm is still empty. */
        private const val REKICK_EVERY_TICKS = 10
        /** Ticks between session-level DHT lookups that feed peers into the torrent. */
        private const val DHT_LOOKUP_EVERY_TICKS = 15
        private const val DHT_LOOKUP_TIMEOUT_SEC = 12
        private const val LISTEN_PORT_BASE = 30_000
        private const val LISTEN_PORT_SPAN = 20_000
        /** Explicit DHT bootstrap nodes - the session pack replaces libtorrent's defaults. */
        private const val DHT_BOOTSTRAP =
            "dht.libtorrent.org:25401,router.bittorrent.com:6881," +
                "router.utorrent.com:6881,dht.transmissionbt.com:6881"
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
