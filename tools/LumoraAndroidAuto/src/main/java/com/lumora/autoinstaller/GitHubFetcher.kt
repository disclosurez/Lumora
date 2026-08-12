package com.lumora.autoinstaller

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fetches the latest Lumora release metadata from GitHub and streams the APK
 * asset down to local storage.
 */
object GitHubFetcher {

    private const val LATEST_URL = "https://api.github.com/repos/disclosurez/Lumora/releases/latest"
    private const val ASSET_NAME = "Lumora.apk"
    private const val USER_AGENT = "LumoraAndroidAuto"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Fetches the latest release's tag and the browser download URL of the
     * [ASSET_NAME] asset. Throws [IOException] with a clear message if the
     * repo has no release yet or the asset is missing.
     */
    suspend fun latestReleaseInfo(): Pair<String, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()

        val response = client.newCall(request).execute()
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw IOException(
                    "GitHub API returned ${it.code} for latest release: $body"
                )
            }
            val json = JSONObject(body)
            if (!json.has("tag_name")) {
                throw IOException("Repository has no releases yet (no latest release found)")
            }
            val tag = json.getString("tag_name")
            val assets = json.optJSONArray("assets") ?: JSONObject.NULL.let { null }
            if (assets == null) {
                throw IOException("Release $tag has no assets; expected an asset named $ASSET_NAME")
            }
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name") == ASSET_NAME) {
                    val url = asset.optString("browser_download_url")
                    if (url.isEmpty()) {
                        throw IOException("Asset $ASSET_NAME in release $tag has no download URL")
                    }
                    return@withContext Pair(tag, url)
                }
            }
            throw IOException("Release $tag does not contain an asset named $ASSET_NAME")
        }
    }

    /**
     * Streams the APK body at [url] into [target], reporting
     * (bytesRead, contentLength) via [onProgress]. Writes to a `.tmp` file
     * first and renames to [target] only once the download fully succeeds.
     */
    suspend fun download(url: String, target: File, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .build()

            val response = client.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) {
                    throw IOException("Download failed with HTTP ${it.code}")
                }
                val body = it.body ?: throw IOException("Download response has no body")
                val contentLength = body.contentLength()
                val tmp = File(target.parentFile, target.name + ".tmp")
                var total = 0L
                try {
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var bytesRead = 0L
                            var lastReport = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n == -1) break
                                output.write(buffer, 0, n)
                                bytesRead += n
                                total = bytesRead
                                if (bytesRead - lastReport >= 64 * 1024 || contentLength >= 0 && bytesRead >= contentLength) {
                                    onProgress(bytesRead, contentLength)
                                    lastReport = bytesRead
                                }
                            }
                        }
                    }
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    onProgress(total, contentLength)
                } catch (e: Exception) {
                    tmp.delete()
                    throw e
                }
            }
        }
    }
}
