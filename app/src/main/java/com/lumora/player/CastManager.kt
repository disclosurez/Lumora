package com.lumora.player

import android.content.Context
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadOptions
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.images.WebImage
import android.net.Uri

/**
 * Manages Google Cast (Chromecast) playback.
 * Handles session lifecycle and media loading to cast devices.
 */
class CastManager(private val context: Context) {

    private var castContext: CastContext? = null
    private var castSession: CastSession? = null
    private var sessionListener: SessionManagerListener<CastSession>? = null

    var onCastSessionConnected: ((CastSession) -> Unit)? = null
    var onCastSessionDisconnected: (() -> Unit)? = null

    /**
     * Initialize the Cast framework.
     */
    fun init() {
        try {
            castContext = CastContext.getSharedInstance(context)
            val sessionManager = castContext?.sessionManager
            val listener = object : SessionManagerListener<CastSession> {
                override fun onSessionStarted(session: CastSession, sessionId: String) {
                    castSession = session
                    onCastSessionConnected?.invoke(session)
                }
                override fun onSessionEnded(session: CastSession, error: Int) {
                    onCastSessionDisconnected?.invoke()
                    castSession = null
                }
                override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                    castSession = session
                    onCastSessionConnected?.invoke(session)
                }
                override fun onSessionSuspended(session: CastSession, reason: Int) {}
                override fun onSessionStarting(session: CastSession) {}
                override fun onSessionStartFailed(session: CastSession, error: Int) {
                    castSession = null
                }
                override fun onSessionEnding(session: CastSession) {}
                override fun onSessionResuming(session: CastSession, sessionId: String) {}
                override fun onSessionResumeFailed(session: CastSession, error: Int) {}
            }
            sessionListener = listener
            // Guard against a partial init: CastContext may be present while its sessionManager
            // is null, and the listener must never be !!-forced at a call site that could run
            // without one being registered.
            sessionManager?.addSessionManagerListener(listener, CastSession::class.java)
        } catch (e: Exception) {
            // Google Play Services may not be available
        }
    }

    /**
     * Determine MIME type from a stream URL extension.
     */
    private fun guessContentType(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") || lower.contains(".m3u") -> "application/x-mpegURL"
            lower.contains(".mp4") -> "video/mp4"
            lower.contains(".ts") -> "video/mp2t"
            lower.contains(".mp3") -> "audio/mpeg"
            lower.contains(".webm") -> "video/webm"
            lower.contains(".mkv") -> "video/x-matroska"
            // HLS-style query params or dash
            lower.contains("dash") || lower.contains(".mpd") -> "application/dash+xml"
            else -> "application/x-mpegURL" // best guess for IPTV
        }
    }

    /**
     * Cast a channel to the connected device.
     */
    fun castChannel(channel: Channel, title: String? = null): Boolean {
        val session = castSession ?: return false
        val remoteMediaClient = session.remoteMediaClient ?: return false
        val url = channel.url.ifBlank { return false }

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title ?: channel.name)
            channel.logoUrl?.let { addImage(WebImage(Uri.parse(it))) }
        }

        val streamType = if (channel.mediaType == MediaType.LIVE)
            MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED

        val contentType = guessContentType(url)

        val mediaInfo = MediaInfo.Builder(url)
            .setStreamType(streamType)
            .setContentType(contentType)
            .setMetadata(metadata)
            .build()

        val loadOptions = MediaLoadOptions.Builder()
            .setAutoplay(true)
            .build()

        try {
            remoteMediaClient.load(mediaInfo, loadOptions)
            android.util.Log.d("CastManager", "Loaded $url (type=$contentType, stream=$streamType)")
            return true
        } catch (e: Exception) {
            android.util.Log.e("CastManager", "Failed to load media", e)
            return false
        }
    }

    fun stopCasting() {
        val session = castSession ?: return
        session.remoteMediaClient?.stop()
    }

    fun release() {
        try {
            castContext?.sessionManager?.let { manager ->
                sessionListener?.let { listener ->
                    manager.removeSessionManagerListener(listener, CastSession::class.java)
                }
            }
        } catch (_: Exception) {}
        castSession = null
        castContext = null
    }
}
