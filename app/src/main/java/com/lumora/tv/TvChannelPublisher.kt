package com.lumora.tv

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.tvprovider.media.tv.TvContractCompat
import android.net.Uri
import android.os.Build
import android.util.Log
import com.lumora.data.local.LumoraDatabase
import com.lumora.data.local.entity.ChannelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Publishes Lumora channels to the Android TV channel list
 * so they appear in the system TV app's channel guide.
 */
class TvChannelPublisher(private val context: Context) {

    private val TAG = "TvChannelPublisher"
    private val INPUT_ID = "${context.packageName}/.tv.TvInputService"

    /**
     * Publish all live channels to the TV channel list.
     */
    suspend fun publishChannels(): Result<Int> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            return@withContext Result.success(0)
        }

        try {
            val db = LumoraDatabase.getInstance(context)
            val channels = db.channelDao().getByProviderAndType("m3u", "LIVE")

            var count = 0
            for (channel in channels) {
                try {
                    publishChannel(channel)
                    count++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to publish ${channel.name}: ${e.message}")
                }
            }
            Log.d(TAG, "Published $count channels to TV list")
            Result.success(count)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load channels for publishing: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Publish a single channel to the TV channel list.
     */
    private fun publishChannel(channel: ChannelEntity) {
        try {
            // Check if channel already exists
            if (channelExists(channel.id)) return

            val values = ContentValues().apply {
                put(TvContractCompat.Channels.COLUMN_INPUT_ID, INPUT_ID)
                put(TvContractCompat.Channels.COLUMN_DISPLAY_NUMBER, channel.tvgChno ?: channel.id.take(4))
                put(TvContractCompat.Channels.COLUMN_DISPLAY_NAME, channel.name)
                put(TvContractCompat.Channels.COLUMN_SERVICE_ID, channel.id)
                put(TvContractCompat.Channels.COLUMN_ORIGINAL_NETWORK_ID, channel.id.hashCode())
                put(TvContractCompat.Channels.COLUMN_TRANSPORT_STREAM_ID, 1)
            }

            context.contentResolver.insert(TvContractCompat.Channels.CONTENT_URI, values)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to publish channel ${channel.name}: ${e.message}")
        }
    }

    /**
     * Remove all Lumora channels from the TV list.
     */
    fun unpublishChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return
        try {
            context.contentResolver.delete(
                TvContractCompat.Channels.CONTENT_URI,
                "${TvContractCompat.Channels.COLUMN_INPUT_ID} = ?",
                arrayOf(INPUT_ID)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unpublish channels: ${e.message}")
        }
    }

    private fun channelExists(channelId: String): Boolean {
        return try {
            val cursor: Cursor? = context.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf(TvContractCompat.Channels._ID),
                "${TvContractCompat.Channels.COLUMN_SERVICE_ID} = ? AND ${TvContractCompat.Channels.COLUMN_INPUT_ID} = ?",
                arrayOf(channelId, INPUT_ID),
                null
            )
            cursor?.use { it.moveToFirst() } == true
        } catch (e: Exception) {
            false
        }
    }
}
