package com.lumora.tv

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvInputInfo
import android.media.tv.TvInputService
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.lumora.R
import com.lumora.data.local.LumoraDatabase
import com.lumora.data.local.entity.ChannelEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers

/**
 * Android TV Input Framework service.
 * Makes Lumora channels available in the system TV channel list
 * so users can browse and watch them from the TV's built-in input selector.
 */
class TvInputService : TvInputService() {

    private val TAG = "TvInputService"
    private var player: ExoPlayer? = null
    private var sessionImpl: TvSessionImpl? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "TV Input Service created")
    }

    override fun onCreateSession(inputId: String): Session {
        sessionImpl = TvSessionImpl(this)
        return sessionImpl!!
    }

    inner class TvSessionImpl(context: Context) : Session(context) {

        private var currentSurface: Surface? = null

        override fun onRelease() {
            player?.release()
            player = null
        }

        override fun onSetSurface(surface: Surface?): Boolean {
            currentSurface = surface
            player?.setVideoSurface(surface)
            return true
        }

        override fun onSetCaptionEnabled(enabled: Boolean) {
            // No in-app caption rendering for the TV-input passthrough session yet.
        }

        override fun onTune(channelUri: Uri): Boolean {
            val channelId = channelUri.lastPathSegment ?: return false

            // Signal tuning in progress so the user sees buffering indicator
            notifyVideoUnavailable(UNAVAILABLE_BUFFERING)

            scope.launch {
                val db = LumoraDatabase.getInstance(this@TvInputService)
                val channel = withContext(Dispatchers.IO) {
                    // Try direct ID lookup first, then fall back to tvgId lookup
                    db.channelDao().getById(channelId)
                        ?: db.channelDao().getByTvgId(channelId, "m3u")
                }
                if (channel != null) {
                    playChannel(channel)
                } else {
                    notifyVideoUnavailable(0)
                    return@launch
                }
            }

            return true
        }

        override fun onSetStreamVolume(volume: Float) {
            player?.volume = volume
        }

        private fun playChannel(channel: ChannelEntity) {
            player?.release()
            player = ExoPlayer.Builder(this@TvInputService).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            notifyVideoAvailable()
                        } else if (state == Player.STATE_BUFFERING) {
                            // Keep current frame
                        }
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.w(TAG, "TV playback error: ${error.message}")
                        notifyVideoUnavailable(ERROR_CONNECTION_LOST)
                    }
                })

                val surface = currentSurface ?: return@apply
                setVideoSurface(surface)

                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.parse(channel.url))
                    .build()
                setMediaItem(mediaItem)
                prepare()
                play()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ERROR_CONNECTION_LOST = 1
        private const val UNAVAILABLE_BUFFERING = 2
        const val SERVICE_META_DATA = "com.lumora.tv.TvInputService"
    }
}
