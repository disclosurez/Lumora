package com.lumora.torrent

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Serves the downloading video file over HTTP on localhost with byte-range support, so the
 * player can seek. Every read is funnelled through a [GatedInputStream] that waits on libtorrent
 * pieces, and a seek (Range request) reprioritises the download around the new offset.
 *
 * Bound to loopback and a random free port - only this app, on this same device, can reach it.
 */
class StreamHttpServer(
    private val file: File,
    private val fileSize: Long,
    private val gate: PieceGate
) : NanoHTTPD("127.0.0.1", 0) {

    override fun serve(session: IHTTPSession): Response {
        val range = session.headers["range"]
        val (start, end) = parseRange(range, fileSize)
        val length = end - start + 1

        // Pull the download head to the seek point before handing back the stream.
        gate.prioritizeFrom(start, 8)
        val body: InputStream = GatedInputStream(file, gate, start, length)

        val status = if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT
        val response = newFixedLengthResponse(status, MIME, body, length)
        response.addHeader("Accept-Ranges", "bytes")
        if (range != null) {
            response.addHeader("Content-Range", "bytes $start-$end/$fileSize")
        }
        return response
    }

    /** Parses a single "bytes=start-end" range; defaults to the whole file. */
    private fun parseRange(header: String?, total: Long): Pair<Long, Long> {
        if (header == null || !header.startsWith("bytes=")) return 0L to (total - 1)
        val spec = header.removePrefix("bytes=").substringBefore(",")
        val dash = spec.indexOf('-')
        if (dash < 0) return 0L to (total - 1)
        val startStr = spec.substring(0, dash).trim()
        val endStr = spec.substring(dash + 1).trim()
        val start = startStr.toLongOrNull() ?: 0L
        val end = endStr.toLongOrNull() ?: (total - 1)
        return start.coerceIn(0, total - 1) to end.coerceIn(start, total - 1)
    }

    /** InputStream over the on-disk file that blocks until libtorrent has each piece it reads. */
    private class GatedInputStream(
        file: File,
        private val gate: PieceGate,
        start: Long,
        private val length: Long
    ) : InputStream() {
        private val raf = RandomAccessFile(file, "r")
        private var pos = start
        private var served = 0L

        init { raf.seek(start) }

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= length) return -1
            gate.awaitByte(pos)
            val want = minOf(len.toLong(), length - served, CHUNK).toInt()
            raf.seek(pos)
            val n = raf.read(b, off, want)
            if (n <= 0) return -1
            pos += n
            served += n
            return n
        }

        override fun close() {
            runCatching { raf.close() }
        }

        companion object { private const val CHUNK = 64L * 1024 }
    }

    companion object { private const val MIME = "video/mp4" }
}
