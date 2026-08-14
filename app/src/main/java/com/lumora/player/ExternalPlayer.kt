package com.lumora.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.lumora.R
import java.io.File

/**
 * Hands the current stream to another video app - VLC, MX Player, Just Player, whatever the
 * user has.
 *
 * Why this exists: the app's own decoding is the device's decoding. A stick with no Dolby
 * licence has no AC3/E-AC3 MediaCodec, so those streams play with picture and no sound, and
 * ExoPlayer reports nothing wrong - an unsupported track is "unsupported", not "failed".
 * Bundling software decoders would fix it, and does, at the cost of tens of megabytes of
 * FFmpeg per ABI. VLC already has all of that installed on the same device, so the cheap fix
 * is to pass the stream over rather than to ship a second copy of FFmpeg.
 *
 * Everything here is best-effort by design: there is no standard for handing a stream to a
 * video player, so the intent carries the union of the extras the common players read and
 * each one picks out what it understands.
 */
object ExternalPlayer {

    /** Players worth naming in a picker, most capable first. Anything else still shows up
     *  through the system chooser - this list only drives the "open in X" shortcut. */
    private val KNOWN_PLAYERS = listOf(
        "org.videolan.vlc" to "VLC",
        "com.mxtech.videoplayer.pro" to "MX Player Pro",
        "com.mxtech.videoplayer.ad" to "MX Player",
        "com.brouken.player" to "Just Player",
        "is.xyz.mpv" to "mpv",
        "com.google.android.videos" to "Google TV",
    )

    data class Candidate(val packageName: String, val label: String)

    /** Installed players from [KNOWN_PLAYERS], in that order. */
    fun installedPlayers(context: Context): List<Candidate> =
        KNOWN_PLAYERS.mapNotNull { (pkg, label) ->
            if (isInstalled(context, pkg)) Candidate(pkg, label) else null
        }

    private fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    /**
     * True when *anything* on the device can open a video URL. Checked before offering the
     * suggestion, so a device with no other video app never gets told to try one.
     *
     * Note this needs the `<queries>` entry in the manifest to see other packages at all on
     * API 30+; without it the resolve comes back empty however many players are installed.
     */
    fun canHandleVideo(context: Context): Boolean {
        val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("http://example.com/a.mkv"), "video/*")
        return context.packageManager.queryIntentActivities(probe, 0).isNotEmpty()
    }

    /**
     * Build the hand-off intent.
     *
     * [positionMs] is passed in every dialect the common players accept - VLC reads
     * "position" (ms, long), MX Player reads "position" (int) - so a resumed title mostly
     * resumes on the other side too. A local download is passed as a `content://` URI through
     * the app's FileProvider: a raw `file://` path either throws FileUriExposedException on
     * the way out or is simply unreadable to the receiving app.
     */
    fun buildIntent(
        context: Context,
        url: String,
        title: String?,
        userAgent: String? = null,
        headers: Map<String, String>? = null,
        positionMs: Long = 0L,
        packageName: String? = null,
    ): Intent {
        val uri = toShareableUri(context, url)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndTypeAndNormalize(uri, mimeTypeFor(url))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            packageName?.let { setPackage(it) }
        }
        title?.takeIf { it.isNotBlank() }?.let {
            intent.putExtra("title", it)
            intent.putExtra("secure_uri", true)
        }
        if (positionMs > 0) {
            intent.putExtra("position", positionMs)
            // MX Player's own key is an int; sending both keeps either happy.
            intent.putExtra("position_ms", positionMs.toInt())
            intent.putExtra("from_start", false)
        }
        // Headers, in the two shapes players accept: MX Player/Just Player take a flat
        // key,value,key,value array; VLC reads the user agent on its own extra.
        val allHeaders = buildMap {
            headers?.let { putAll(it) }
            userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        }
        if (allHeaders.isNotEmpty()) {
            intent.putExtra("headers", allHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray())
        }
        userAgent?.takeIf { it.isNotBlank() }?.let { intent.putExtra("http-user-agent", it) }
        return intent
    }

    /**
     * Launch [intent], falling back to the system chooser when the named package turns out
     * not to handle it. Returns false when nothing on the device would take the stream, so
     * the caller can say so rather than failing silently.
     */
    fun launch(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(
                    Intent.createChooser(intent.apply { setPackage(null) }, context.getString(R.string.ui_play_with))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
    }

    /** A `file://` download becomes a `content://` URI the receiving app is allowed to read;
     *  anything else (http, rtsp, the local torrent HTTP server) is already shareable. */
    private fun toShareableUri(context: Context, url: String): Uri {
        if (!url.startsWith("file://")) return Uri.parse(url)
        val file = File(Uri.parse(url).path ?: return Uri.parse(url))
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrElse { Uri.parse(url) }
    }

    /**
     * A concrete type rather than a generic video wildcard where the extension gives one:
     * VLC and MX Player
     * both pick their demuxer off the MIME when the URL has no usable extension, and an HLS
     * playlist announced as a generic video file is the case that most often opens as a
     * garbled single segment.
     */
    private fun mimeTypeFor(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> "application/x-mpegurl"
            path.endsWith(".mpd") -> "application/dash+xml"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
            path.endsWith(".ts") -> "video/mp2t"
            path.endsWith(".avi") -> "video/x-msvideo"
            else -> "video/*"
        }
    }

    /** PackageManager lookups need a package-visibility declaration on API 30+; this is the
     *  label to show for a package once it is known to be there. */
    fun labelFor(context: Context, packageName: String): String =
        KNOWN_PLAYERS.firstOrNull { it.first == packageName }?.second
            ?: runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)).toString()
            }.getOrDefault(packageName)
}
