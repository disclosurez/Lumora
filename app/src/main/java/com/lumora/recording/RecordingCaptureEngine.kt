package com.lumora.recording

import android.content.Context
import android.net.Uri
import android.util.Log
import com.lumora.data.local.entity.RecordingEntity
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures live TV streams to local files for DVR/recording.
 * Uses OkHttp streaming to record TS/HLS segments to disk.
 * Runs background coroutine-based capture with periodic progress reporting.
 */
class RecordingCaptureEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val TAG = "RecordingCapture"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS) // No read timeout for streaming
        .writeTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    data class CaptureProgress(
        val bytesWritten: Long,
        val durationMs: Long,
        val isActive: Boolean
    )

    /**
     * Start recording a live stream to file.
     * @return the output file path
     */
    suspend fun startRecording(recording: RecordingEntity, streamUrl: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val outputDir = getRecordingDirectory()
                if (outputDir == null) {
                    return@withContext Result.failure(Exception("No recording storage configured"))
                }

                // Check available disk space before starting
                val usableSpace = outputDir.usableSpace
                val estimatedBytes = (recording.stopTimeUtc - recording.startTimeUtc) * 2_500_000L
                if (usableSpace < estimatedBytes) {
                    Log.w(TAG, "Insufficient disk space for recording: ${usableSpace / 1_000_000}MB available, ~${estimatedBytes / 1_000_000}MB needed")
                    return@withContext Result.failure(Exception("Insufficient disk space for recording"))
                }

                val fileName = generateFileName(recording)
                val outputFile = File(outputDir, fileName)
                val outputStream = FileOutputStream(outputFile)

                val job = scope.launch {
                    try {
                        captureStream(streamUrl, outputStream, recording)
                    } catch (e: Exception) {
                        Log.w(TAG, "Capture failed: ${e.message}")
                    }
                }

                activeJobs[recording.id] = job
                return@withContext Result.success(outputFile.absolutePath)
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }
    }

    /**
     * Stop an active recording.
     */
    suspend fun stopRecording(recordingId: String) {
        withContext(Dispatchers.IO) {
            activeJobs[recordingId]?.cancel()
            activeJobs.remove(recordingId)
        }
    }

    /**
     * Check if a recording is still active.
     */
    fun isRecording(recordingId: String): Boolean {
        return activeJobs[recordingId]?.isActive == true
    }

    /**
     * Check if we've hit the max simultaneous recording limit.
     */
    fun canStartRecording(maxSimultaneous: Int): Boolean {
        return activeJobs.count { it.value.isActive } < maxSimultaneous
    }

    /**
     * Stop all active recordings.
     */
    fun stopAll() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
    }

    /**
     * Release all resources.
     */
    fun release() {
        stopAll()
        scope.cancel()
    }

    // ── Internal ─────────────────────────────

    private suspend fun captureStream(url: String, outputStream: FileOutputStream, recording: RecordingEntity) {
        try {
            val request = Request.Builder().url(url)
                .header("User-Agent", "Lumora/2.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} for recording stream")
                outputStream.close()
                return
            }

            val body = response.body ?: run {
                outputStream.close()
                return
            }

            val inputStream = body.byteStream()
            val buffer = ByteArray(8192)
            var bytesRead: Int
            val startTime = System.currentTimeMillis()
            val maxDuration = recording.stopTimeUtc * 1000 - startTime

            while (isActive() && System.currentTimeMillis() - startTime < maxDuration) {
                bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break
                outputStream.write(buffer, 0, bytesRead)
            }

            inputStream.close()
            outputStream.flush()
            outputStream.close()
            Log.d(TAG, "Recording completed: ${outputStream.fd}")
        } catch (e: IOException) {
            if (isActive()) {
                Log.w(TAG, "Stream ended: ${e.message}")
            }
            outputStream.close()
        } catch (e: Exception) {
            Log.w(TAG, "Capture error: ${e.message}")
            outputStream.close()
        }
    }

    private fun isActive(): Boolean = scope.isActive

    private fun getRecordingDirectory(): File? {
        val dir = File(context.filesDir, "recordings")
        if (!dir.exists()) dir.mkdirs()
        return if (dir.canWrite()) dir else null
    }

    private fun generateFileName(recording: RecordingEntity): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
        val datePart = dateFormat.format(Date(recording.startTimeUtc * 1000))
        val safeTitle = recording.programTitle
            .replace(Regex("""[^\p{L}\p{N} _-]"""), "")
            .take(50)
            .trim()
        return "${safeTitle}_${datePart}.ts"
    }
}
