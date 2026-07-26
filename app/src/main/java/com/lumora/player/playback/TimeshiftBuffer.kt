package com.lumora.player.playback

import android.content.Context
import android.util.Log
import java.io.*
import java.util.concurrent.LinkedBlockingDeque

/**
 * Timeshift buffer for live TV playback.
 * Captures the live stream to a temporary ring buffer on disk,
 * allowing the user to pause and rewind live TV.
 *
 * The buffer is a sliding window of TS segments stored as a ring buffer
 * of files. Oldest segments are deleted as new ones arrive.
 */
class TimeshiftBuffer(private val context: Context) {

    private val TAG = "TimeshiftBuffer"
    private var bufferDir: File? = null
    private var maxBufferSize = 30 * 60 * 1000L // 30 minutes default
    private var segmentDurationMs = 10_000L // 10 second segments
    private var totalBufferedMs = 0L

    data class TimeshiftSegment(
        val file: File,
        val startOffsetMs: Long,
        val durationMs: Long
    )

    private val segments = LinkedBlockingDeque<TimeshiftSegment>()
    private var isPaused = false
    private var currentOffsetMs = 0L
    private var captureStartTimeMs = 0L
    private var segmentCounter = 0

    /**
     * Initialize the timeshift buffer directory.
     */
    fun init(maxDurationMinutes: Int = 30) {
        maxBufferSize = maxDurationMinutes * 60 * 1000L
        val dir = File(context.cacheDir, "timeshift")
        if (!dir.exists()) dir.mkdirs()
        bufferDir = dir
        cleanBuffer()
        Log.d(TAG, "Timeshift buffer initialized: ${dir.absolutePath}, max=${maxDurationMinutes}min")
    }

    /**
     * Start a new recording segment.
     * Returns the output stream to write TS data into.
     */
    fun startSegment(): OutputStream? {
        val dir = bufferDir ?: return null
        val segmentFile = File(dir, "ts_segment_${segmentCounter++}.ts")
        segmentFile.createNewFile()

        if (captureStartTimeMs == 0L) {
            captureStartTimeMs = System.currentTimeMillis()
        }

        val offset = currentOffsetMs
        currentOffsetMs += segmentDurationMs

        val segment = TimeshiftSegment(segmentFile, offset, 0)
        segments.add(segment)

        // Evict old segments if over max buffer size
        evictOldSegments()

        return FileOutputStream(segmentFile)
    }

    /**
     * Finalize the current segment with its actual duration.
     */
    fun finalizeSegment(actualDurationMs: Long) {
        val last = segments.peekLast() ?: return
        segments.remove(last)
        segments.add(last.copy(durationMs = actualDurationMs.coerceAtMost(segmentDurationMs)))
        totalBufferedMs += actualDurationMs.coerceAtMost(segmentDurationMs)
    }

    /**
     * Get the playback position for timeshift seek.
     * Offset=0 means live, positive offset means further back in time.
     */
    fun getPlaybackPosition(seekBackMs: Long): TimeshiftSegment? {
        if (segments.isEmpty()) return null

        var accumulated = 0L
        val targetOffset = currentOffsetMs - seekBackMs.coerceAtMost(maxBufferSize)

        // Walk forward from oldest to find the segment containing targetOffset
        for (segment in segments) {
            if (segment.startOffsetMs + segment.durationMs > targetOffset) {
                return segment
            }
        }

        return segments.peekFirst()
    }

    /**
     * Read data from a specific segment at a specific offset.
     */
    fun readSegment(segment: TimeshiftSegment, offsetMs: Long): ByteArray? {
        return try {
            val file = segment.file
            if (!file.exists()) return null

            val offsetBytes = (offsetMs * file.length() / segment.durationMs.coerceAtLeast(1)).toInt().toLong()
            val input = FileInputStream(file)
            input.skip(offsetBytes.coerceAtLeast(0))
            input.readBytes().also { input.close() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Pause/unpause the timeshift (stops/starts buffering).
     */
    fun setPaused(paused: Boolean) {
        isPaused = paused
    }

    fun isPaused(): Boolean = isPaused

    /**
     * Get current buffer duration.
     */
    fun getBufferDurationMs(): Long = totalBufferedMs

    /**
     * Release all resources.
     */
    fun release() {
        cleanBuffer()
        segments.clear()
        totalBufferedMs = 0L
        currentOffsetMs = 0L
        captureStartTimeMs = 0L
    }

    private fun evictOldSegments() {
        while (!segments.isEmpty() && segments.first.startOffsetMs < currentOffsetMs - maxBufferSize) {
            val old = segments.pollFirst()
            old?.file?.delete()
            totalBufferedMs -= old?.durationMs ?: 0
        }
    }

    private fun cleanBuffer() {
        bufferDir?.listFiles()?.forEach { it.delete() }
    }
}
