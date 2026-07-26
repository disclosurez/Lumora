package com.lumora.tv

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.lumora.MainActivity
import java.util.UUID

/**
 * Android TV Watch Next integration.
 * Adds programs to the "Watch Next" row on the Android TV launcher home screen,
 * allowing users to resume watching content directly from the launcher.
 */
class WatchNextManager(private val context: Context) {

    private val TAG = "WatchNext"

    /**
     * Add a program to the Watch Next row.
     */
    fun addToWatchNext(
        title: String,
        description: String? = null,
        posterUrl: String? = null,
        channelId: String? = null,
        positionMs: Long = 0,
        durationMs: Long = 0
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        try {
            val programId = "watch_next_${channelId ?: UUID.randomUUID()}"

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                channelId?.let { putExtra("open_channel_id", it) }
            }

            val builder = WatchNextProgram.Builder()
                .setInternalProviderId(programId)
                .setTitle(title)
                .setDescription(description ?: title)
                .setIntent(intent)
                .setLastEngagementTimeUtcMillis(System.currentTimeMillis())

            if (durationMs > 0) {
                builder.setDurationMillis(durationMs.toInt())
            }

            // Poster art takes a Uri, not a Bitmap - the launcher fetches/caches it itself.
            if (!posterUrl.isNullOrBlank()) {
                runCatching { builder.setPosterArtUri(Uri.parse(posterUrl)) }
            }

            val program = builder.build()

            context.contentResolver.insert(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                program.toContentValues()
            )

            android.util.Log.d(TAG, "Added to Watch Next: $title")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to add: ${e.message}")
        }
    }

    /**
     * Remove a program from Watch Next.
     */
    fun removeFromWatchNext(channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val programId = "watch_next_$channelId"
        try {
            context.contentResolver.delete(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                "${TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID} = ?",
                arrayOf(programId)
            )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to remove: ${e.message}")
        }
    }
}
