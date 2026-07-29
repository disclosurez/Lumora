package com.lumora.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.lumora.BaseApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.BufferedInputStream

private const val TARGET_POSTER_WIDTH_PX = 220
private const val TARGET_POSTER_HEIGHT_PX = 330

/**
 * Shared downsampled poster cache for every poster grid/shelf in the app, so
 * scrolling through Series/Films never decodes a full-resolution (600x900+)
 * TMDB image just to show it in a small tile - that was the main source of
 * the jank/freeze reported when browsing large VOD catalogs.
 */
object PosterLoader {
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted) oldValue.recycle()
        }
    }

    fun getCached(url: String): Bitmap? = cache.get(url)

    suspend fun fetch(url: String): Bitmap? = withContext(Dispatchers.IO) {
        getCached(url)?.let { return@withContext it }
        val bitmap = runCatching {
            val request = Request.Builder().url(url).build()
            val response = BaseApplication.instance.okHttpClient.newCall(request).execute()
            val body = response.body ?: return@runCatching null
            val stream = body.byteStream().buffered()
            stream.mark(64 * 1024)  // allow reset within 64KB

            // First decode with just bounds
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, boundsOptions)
            stream.reset()

            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                stream.close()
                response.close()
                return@runCatching null
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(boundsOptions, TARGET_POSTER_WIDTH_PX, TARGET_POSTER_HEIGHT_PX)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = BitmapFactory.decodeStream(stream, null, options)
            stream.close()
            response.close()
            bitmap
        }.getOrNull()
        if (bitmap != null) cache.put(url, bitmap)
        bitmap
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
