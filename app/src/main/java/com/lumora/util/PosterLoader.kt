package com.lumora.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.lumora.BaseApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

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
    }

    fun getCached(url: String): Bitmap? = cache.get(url)

    suspend fun fetch(url: String): Bitmap? = withContext(Dispatchers.IO) {
        getCached(url)?.let { return@withContext it }
        val bitmap = runCatching {
            val request = Request.Builder().url(url).build()
            val bytes = BaseApplication.instance.okHttpClient.newCall(request).execute()
                .body?.bytes() ?: return@runCatching null

            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(boundsOptions, TARGET_POSTER_WIDTH_PX, TARGET_POSTER_HEIGHT_PX)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
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
