package com.lumora.player

import android.content.Context
import android.net.Uri
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.lumora.model.Channel
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Multi-View / Split Screen manager.
 * Supports up to 4 simultaneous live TV streams in independent players.
 * Each slot has its own ExoPlayer instance with its own TextureView.
 */
class MultiViewManager(private val context: Context) {

    companion object {
        const val MAX_SLOTS = 4
    }

    data class MultiViewSlot(
        val index: Int,
        val channel: Channel? = null,
        val player: ExoPlayer? = null,
        val textureView: TextureView? = null,
        val isPlaying: Boolean = false,
        val isMuted: Boolean = false
    )

    private val slots = Array<MultiViewSlot?>(MAX_SLOTS) { null }
    private val listeners = CopyOnWriteArrayList<(MultiViewSlot) -> Unit>()

    /**
     * Initialize a slot with a TextureView for rendering.
     */
    fun initSlot(index: Int, textureView: TextureView): Boolean {
        if (index < 0 || index >= MAX_SLOTS) return false
        val player = ExoPlayer.Builder(context).build()
        player.setVideoTextureView(textureView)
        slots[index] = MultiViewSlot(
            index = index,
            player = player,
            textureView = textureView,
            isMuted = index > 0 // Only first slot has audio by default
        )
        if (index > 0) player.volume = 0f
        return true
    }

    /**
     * Play a channel in a specific slot.
     */
    fun playInSlot(index: Int, channel: Channel) {
        val slot = slots[index] ?: return
        val player = slot.player ?: return

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(channel.url))
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(channel.name)
                    .build()
            )
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()

        slots[index] = slot.copy(channel = channel, isPlaying = true)
        notifySlotChanged(index)
    }

    /**
     * Stop playback in a slot.
     */
    fun stopSlot(index: Int) {
        val slot = slots[index] ?: return
        slot.player?.stop()
        slot.player?.clearMediaItems()
        slots[index] = slot.copy(channel = null, isPlaying = false)
        notifySlotChanged(index)
    }

    /**
     * Mute/unmute a specific slot.
     */
    fun setSlotMuted(index: Int, muted: Boolean) {
        val slot = slots[index] ?: return
        slot.player?.volume = if (muted) 0f else 1f
        slots[index] = slot.copy(isMuted = muted)
        notifySlotChanged(index)
    }

    /**
     * Get all current slots.
     */
    fun getSlots(): List<MultiViewSlot> = slots.filterNotNull()

    /**
     * Get a specific slot.
     */
    fun getSlot(index: Int): MultiViewSlot? = slots[index]

    /**
     * Add a state change listener.
     */
    fun addListener(listener: (MultiViewSlot) -> Unit) {
        listeners.add(listener)
    }

    /**
     * Remove a state change listener.
     */
    fun removeListener(listener: (MultiViewSlot) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Number of active (playing) slots.
     */
    fun activeCount(): Int = slots.count { it?.isPlaying == true }

    /**
     * Whether multi-view is active (at least one slot playing).
     */
    fun isActive(): Boolean = activeCount() > 0

    /**
     * Release all players and resources.
     */
    fun release() {
        slots.forEachIndexed { index, slot ->
            slot?.player?.release()
            slots[index] = null
        }
    }

    private fun notifySlotChanged(index: Int) {
        val slot = slots[index] ?: return
        listeners.forEach { it(slot) }
    }
}
