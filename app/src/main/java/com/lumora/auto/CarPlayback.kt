package com.lumora.auto

import android.content.Context
import android.view.Surface
import androidx.media3.common.Player
import com.lumora.cache.ChannelCache
import com.lumora.cache.FavoritesStore
import com.lumora.cache.RecentlyPlayedStore
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.player.PlayerManager

/**
 * The car session's own player and channel list.
 *
 * Separate from the phone Activity's [PlayerManager] on purpose: the two screens are
 * independent surfaces with independent lifecycles, and the car session routinely outlives
 * (or precedes) the Activity. Audio focus arbitrates if both ever run at once.
 *
 * The catalogue comes from the on-disk channel cache rather than a fresh provider fetch -
 * the car session can start with no Activity ever having run, and a cold multi-provider
 * fetch is tens of seconds of work behind a driver waiting for a picture.
 */
class CarPlayback(private val context: Context) {

    private val player = PlayerManager(context)

    var channels: List<Channel> = emptyList()
        private set

    var current: Channel? = null
        private set

    val isPlaying: Boolean get() = player.isPlaying

    /**
     * Live channels that can be played from a URL alone. Stalker commands, plugin tokens and
     * the media servers' negotiated streams (Jellyfin, Plex) all need a round trip through
     * code that lives in the Activity, so they are left out rather than offered as rows that
     * fail on tap. Plex additionally has no live channels at all - see MainActivityPlex.
     *
     * Synchronized: Media-browse callbacks now load this on background threads, and concurrent
     * calls must not double-read the disk cache or interleave writes to [channels].
     */
    @Synchronized
    fun loadCatalog(): List<Channel> {
        val cached = ChannelCache.load(context).orEmpty()
        channels = cached.filter {
            it.mediaType == MediaType.LIVE && it.url.isNotBlank() &&
                it.stalkerCmd.isNullOrBlank() && it.pluginToken.isNullOrBlank()
        }
        return channels
    }

    fun favourites(): List<Channel> {
        val ids = FavoritesStore.getFavoriteChannelIds(context)
        return channels.filter { it.id in ids }
    }

    /** Recently played, in the order they were played, limited to what the car list shows. */
    fun recents(limit: Int = 20): List<Channel> {
        val ids = RecentlyPlayedStore.getRecentIds(context)
        val byId = channels.associateBy { it.id }
        return ids.mapNotNull { byId[it] }.take(limit)
    }

    /** Category name -> channels, for the browse list. Uncategorised channels are grouped
     *  under one heading rather than dropped. */
    fun categories(): Map<String, List<Channel>> =
        channels.groupBy { it.categoryName?.takeIf { name -> name.isNotBlank() } ?: context.getString(com.lumora.R.string.ui_other) }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)

    fun play(channel: Channel) {
        current = channel
        RecentlyPlayedStore.recordPlayed(context, channel.id)
        player.playUrl(
            url = channel.url,
            userAgent = channel.streamUserAgent,
            headers = channel.streamHeaders,
        )
    }

    /** Next/previous within whatever list the user was browsing, wrapping at the ends -
     *  a driver pressing skip should never land on "nothing happened". */
    fun step(from: List<Channel>, delta: Int) {
        if (from.isEmpty()) return
        val index = from.indexOfFirst { it.id == current?.id }
        val next = if (index < 0) 0 else ((index + delta) % from.size + from.size) % from.size
        play(from[next])
    }

    fun togglePlayPause() = player.togglePlayPause()

    /**
     * Where the picture goes. The car surface is turned into a VirtualDisplay carrying a
     * Presentation (see CarPlayerScreen), so what the player actually attaches to is an
     * ordinary SurfaceView inside that window - the same thing it uses on the phone.
     */
    fun setSurfaceView(surfaceView: android.view.SurfaceView) = player.setSurfaceView(surfaceView)

    /** Detach, when the car screen goes away. */
    fun setSurface(surface: Surface?) = player.setVideoSurface(surface)

    fun addListener(listener: Player.Listener) = player.addListener(listener)

    fun release() {
        player.setVideoSurface(null)
        player.release()
    }
}
