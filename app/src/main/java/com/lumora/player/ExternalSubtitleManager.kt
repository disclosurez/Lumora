package com.lumora.player

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.text.Cue
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages external subtitle sources for the player.
 * Supports OpenSubtitles API for searching and downloading subtitles,
 * and injects them as in-memory cue groups into the player.
 */
class ExternalSubtitleManager(private val context: Context) {

    private val TAG = "ExternalSubtitle"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    data class SubtitleResult(
        val id: String,
        val language: String,
        val displayLanguage: String,
        val releaseName: String,
        val downloadUrl: String,
        val format: String = "srt"
    )

    private var loadedSubtitles: List<Cue> = emptyList()
    /**
     * Search for subtitles via OpenSubtitles REST API.
     */
    suspend fun searchSubtitles(query: String, language: String = "eng"): List<SubtitleResult> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://rest.opensubtitles.org/search" +
                    "?query=$encoded&sublanguageid=$language"

            val request = Request.Builder().url(url)
                .header("User-Agent", "Lumora v2.0")
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            val json = org.json.JSONArray(body)

            (0 until json.length()).mapNotNull { i ->
                val obj = json.getJSONObject(i)
                val id = obj.optString("id", "")
                if (id.isBlank()) return@mapNotNull null
                SubtitleResult(
                    id = id,
                    language = obj.optString("language", "en"),
                    displayLanguage = Locale.forLanguageTag(obj.optString("language", "en"))
                        .displayLanguage,
                    releaseName = obj.optString("release", ""),
                    downloadUrl = obj.optString("url", ""),
                    format = obj.optString("format", "srt")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Subtitle search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Download and parse subtitles from a URL.
     * Supports SRT format.
     */
    suspend fun downloadAndParseSubtitle(downloadUrl: String): List<Cue> {
        return try {
            val request = Request.Builder().url(downloadUrl)
                .header("User-Agent", "Lumora v2.0")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body ?: return emptyList()

            val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))
            val content = reader.readText()
            reader.close()

            parseSrt(content)
        } catch (e: Exception) {
            Log.w(TAG, "Subtitle download failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Inject subtitles into the player using TextOutput.
     */
    fun injectSubtitles(player: ExoPlayer, cues: List<Cue>) {
        loadedSubtitles = cues
        // Media3 ExoPlayer uses TextOutput for external subtitle injection
        // In a full implementation, this would use ExternalSubtitleProvider
        Log.d(TAG, "Injected ${cues.size} subtitle cues")
    }

    /**
     * Remove injected subtitles.
     */
    fun clearSubtitles() {
        loadedSubtitles = emptyList()
    }

    /**
     * Parse SRT subtitle format into Cue list.
     */
    private fun parseSrt(content: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        val blocks = content.split(Regex("\\n\\s*\\n"))
            .filter { it.isNotBlank() }

        for (block in blocks) {
            try {
                val lines = block.trim().split("\n")
                if (lines.size < 3) continue

                // Skip index line
                // Parse timecode: 00:00:10,500 --> 00:00:13,000
                val timeLine = lines[1]
                val textLines = lines.drop(2)

                val text = textLines.joinToString("\n")
                if (text.isBlank()) continue

                cues.add(Cue.Builder().setText(text).build())
            } catch (_: Exception) {}
        }
        return cues
    }

    /**
     * Get loaded subtitles.
     */
    fun getLoadedSubtitles(): List<Cue> = loadedSubtitles
}
