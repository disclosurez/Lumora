package com.lumora.player

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import com.lumora.model.Channel

/**
 * Manages the Android media session for lock-screen playback controls.
 * Allows users to control playback from the lock screen and notification shade.
 */
class PlayerMediaSession(private val context: Context) {

    private var mediaSession: MediaSession? = null
    private var activeChannel: Channel? = null

    /**
     * Create and register the media session.
     */
    fun create() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSession = MediaSession(context, "Lumora")
            mediaSession?.isActive = true
            updatePlaybackState(PlaybackState.STATE_PLAYING)
        }
    }

    /**
     * Update the media session with the currently playing channel.
     */
    fun setActiveChannel(channel: Channel?) {
        activeChannel = channel
        if (channel != null && mediaSession != null) {
            val metadata = android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, channel.name)
                .putString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE, channel.name)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "Lumora")
                .build()
            mediaSession?.setMetadata(metadata)
        }
    }

    /**
     * Update playback state.
     */
    fun updatePlaybackState(state: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val pbState = PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_FAST_FORWARD or
                    PlaybackState.ACTION_REWIND or
                    PlaybackState.ACTION_SEEK_TO
                )
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
            mediaSession?.setPlaybackState(pbState)
        }
    }

    /**
     * Release the media session.
     */
    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
        }
    }
}
