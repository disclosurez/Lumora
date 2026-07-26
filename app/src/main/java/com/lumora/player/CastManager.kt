package com.lumora.player

import android.content.Context
import com.lumora.model.Channel
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadOptions
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
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
            sessionListener = object : SessionManagerListener<CastSession> {
                override fun onSessionStarted(session: CastSession, sessionId: String) {
                    castSession = session
                    onCastSessionConnected?.invoke(session)
                }
                override fun onSessionEnded(session: CastSession, error: Int) {
                    castSession = null
                    onCastSessionDisconnected?.invoke()
                }
                override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                    castSession = session
                    onCastSessionConnected?.invoke(session)
                }
                override fun onSessionSuspended(session: CastSession, reason: Int) {}
                override fun onSessionStarting(session: CastSession) {}
                override fun onSessionStartFailed(session: CastSession, error: Int) {}
                override fun onSessionEnding(session: CastSession) {}
                override fun onSessionResuming(session: CastSession, sessionId: String) {}
                override fun onSessionResumeFailed(session: CastSession, error: Int) {}
            }
            sessionManager?.addSessionManagerListener(sessionListener!!, CastSession::class.java)
        } catch (e: Exception) {
            // Google Play Services may not be available
        }
    }

    /**
     * Whether a cast session is active.
     */
    fun isConnected(): Boolean = castSession?.isConnected == true

    /**
     * Cast a channel to the connected device.
     */
    fun castChannel(channel: Channel, title: String? = null): Boolean {
        val session = castSession ?: return false
        val remoteMediaClient = session.remoteMediaClient ?: return false

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title ?: channel.name)
            channel.logoUrl?.let { addImage(WebImage(Uri.parse(it))) }
        }

        val mediaInfo = MediaInfo.Builder(channel.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType("application/x-mpegURL")
            .setMetadata(metadata)
            .build()

        val loadOptions = MediaLoadOptions.Builder()
            .setAutoplay(true)
            .build()

        remoteMediaClient.load(mediaInfo, loadOptions)
        return true
    }

    /**
     * Stop casting.
     */
    fun stopCasting() {
        val session = castSession ?: return
        session.remoteMediaClient?.stop()
    }

    /**
     * Release resources.
     */
    fun release() {
        try {
            castContext?.sessionManager?.removeSessionManagerListener(
                sessionListener!!, CastSession::class.java
            )
        } catch (_: Exception) {}
        castSession = null
        castContext = null
    }
}
