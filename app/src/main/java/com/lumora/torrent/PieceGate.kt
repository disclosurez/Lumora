package com.lumora.torrent

import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentHandle

/**
 * Maps a position inside the chosen file to libtorrent pieces, and keeps only a small rolling
 * window of pieces around the read head actually being downloaded. This is what lets a large
 * torrent stream on a device with very little free space: the whole file is never fetched at
 * once - only [AHEAD_PIECES] beyond wherever the player is reading, plus a little kept behind for
 * short back-seeks. Everything outside the window is set back to IGNORE so libtorrent stops
 * pulling it.
 *
 * A reader blocks in [awaitByte] until the piece it needs is present; a seek moves the window.
 */
class PieceGate(
    private val handle: TorrentHandle,
    private val firstPiece: Int,
    private val lastPiece: Int,
    private val pieceLength: Int,
    private val fileOffset: Long,
    // Shared with TorrentEngine so native calls here never race session teardown.
    private val nativeLock: Any
) {
    // Lowest piece currently inside the active window; everything below it has been demoted.
    private var windowStart = firstPiece

    private fun pieceOf(posInFile: Long): Int =
        ((fileOffset + posInFile) / pieceLength).toInt().coerceIn(firstPiece, lastPiece)

    /**
     * Centres the download window on [posInFile]: enables the pieces from here to [AHEAD_PIECES]
     * beyond it (with tight deadlines for order), and demotes pieces well behind the read head
     * back to IGNORE so they stop occupying bandwidth. The window size is always [AHEAD_PIECES].
     */
    fun prioritizeFrom(posInFile: Long) = synchronized(nativeLock) {
        if (!handle.isValid) return
        val start = pieceOf(posInFile)
        val end = (start + AHEAD_PIECES).coerceAtMost(lastPiece)
        var deadline = 0
        for (p in start..end) {
            handle.piecePriority(p, Priority.TOP_PRIORITY)
            handle.setPieceDeadline(p, deadline)
            deadline += 80
        }
        // Demote pieces that fell behind the keep-behind margin so we don't keep fetching them.
        val demoteUntil = start - KEEP_BEHIND_PIECES
        if (demoteUntil > windowStart) {
            for (p in windowStart until demoteUntil) {
                handle.piecePriority(p, Priority.IGNORE)
                handle.resetPieceDeadline(p)
            }
            windowStart = demoteUntil
        }
    }

    /** Blocks until the piece covering [posInFile] is downloaded, keeping the window primed. */
    fun awaitByte(posInFile: Long) {
        val piece = pieceOf(posInFile)
        prioritizeFrom(posInFile)
        while (true) {
            val have = synchronized(nativeLock) {
                if (!handle.isValid) throw IllegalStateException("Stream stopped")
                handle.havePiece(piece)
            }
            if (have) return
            Thread.sleep(50)
        }
    }

    companion object {
        /** Pieces to keep downloading ahead of the read head. */
        private const val AHEAD_PIECES = 24
        /** Pieces to keep available behind the read head for short back-seeks. */
        private const val KEEP_BEHIND_PIECES = 8
    }
}
