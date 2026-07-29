package com.lumora.player

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lightweight wrapper around Media3 ExoPlayer.
 * Manages player lifecycle and provides simple playback control.
 */
class PlayerManager(
    private val context: Context
) {
    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true /* handleAudioFocus */
        )
        .build()
        .also { it.setHandleAudioBecomingNoisy(true) }
    private val listeners = CopyOnWriteArrayList<Player.Listener>()
    private var released = false

    val isPlaying: Boolean
        get() = player.isPlaying

    val currentPosition: Long
        get() = player.currentPosition

    val duration: Long
        get() = player.duration

    val playbackState: Int
        get() = player.playbackState

    /** Build a data source factory with optional custom headers. */
    private fun buildDataSourceFactory(userAgent: String? = null): DataSource.Factory {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        if (!userAgent.isNullOrBlank()) {
            httpFactory.setUserAgent(userAgent)
        }

        return DefaultDataSource.Factory(
            context,
            httpFactory
        )
    }

    /** Prepare and start playing a stream URL. */
    fun playUrl(
        url: String,
        userAgent: String? = null
    ) {
        val dataSourceFactory = buildDataSourceFactory(userAgent)

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(url)
                    .build()
            )

        val mediaItem = mediaItemBuilder.build()

        // Auto-detects HLS/DASH/SmoothStreaming/progressive (mp4, mkv, ts...) from the
        // URI extension or response content-type instead of assuming everything is HLS.
        val mediaSource = DefaultMediaSourceFactory(dataSourceFactory)
            .createMediaSource(mediaItem)

        player.setMediaSource(mediaSource)
        player.prepare()
        player.play()
    }

    /** Attach to a SurfaceView for video rendering. */
    fun setSurfaceView(surfaceView: SurfaceView) {
        player.setVideoSurfaceView(surfaceView)
    }

    /**
     * Attach to a TextureView instead. Used for the small inline preview pane:
     * a SurfaceView is a hardware overlay that can leave stale/ghosted frames
     * behind when repeatedly resized or hidden/shown; TextureView is a normal
     * composited View and doesn't have that failure mode. Costs a bit more
     * power/perf than SurfaceView, which is why the main fullscreen player
     * still uses setSurfaceView() above.
     */
    fun setTextureView(textureView: android.view.TextureView) {
        player.setVideoTextureView(textureView)
    }

    /** Toggle play/pause. */
    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun play() = player.play()
    fun pause() = player.pause()
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    /** Seek forward/backward by a relative delta, clamped to [0, duration]. */
    fun seekBy(deltaMs: Long) {
        val dur = player.duration
        if (dur <= 0) return
        val pos = player.currentPosition
        if (pos == C.TIME_UNSET || pos < 0) return
        val target = (pos + deltaMs).coerceIn(0L, dur)
        player.seekTo(target)
    }

    fun stop() { player.stop() }

    /** Add a player event listener. */
    fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        player.addListener(listener)
    }

    /** Remove a player event listener. */
    fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        player.removeListener(listener)
    }

    /** Release all player resources. Must be called when done. */
    fun release() {
        if (released) return
        released = true
        listeners.forEach { player.removeListener(it) }
        listeners.clear()
        player.release()
    }

    fun setVolume(volume: Float) = player.setVolume(volume)

    /** Get the underlying ExoPlayer instance for advanced use. */
    fun getExoPlayer(): ExoPlayer = player
}
