package com.lumora.auto

import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.lumora.model.Channel
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Lumora as a media app: the browse tree Android Auto (and Assistant, and Wear, and anything
 * else that speaks MediaBrowser) reads, plus the player behind it.
 *
 * This exists alongside [LumoraCarAppService] rather than instead of it. The two are listed
 * by different halves of the car host and do different jobs:
 *
 *  - Media is the category Android Auto has always honoured, including for sideloaded apps.
 *    It is a list of channels and audio - no video, ever, by design of the category.
 *  - The Car App Library service draws actual video onto the navigation surface, but whether
 *    the host lists a sideloaded navigation app is up to the host, and a version that
 *    declines to leaves the user with nothing.
 *
 * So: media guarantees the app is usable in the car, and the template app is the upgrade when
 * the host allows it.
 */
class LumoraMediaService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private val catalog by lazy { CarPlayback(this) }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** mediaId -> channel, filled as the tree is browsed so playback can resolve an id back
     *  to a real stream (a controller sends back the id alone, never the URI). Concurrent map:
     *  browse callbacks populate it on background threads now. */
    private val byMediaId = ConcurrentHashMap<String, Channel>()

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus= */ true
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory()))
            .build()
            .also { it.setHandleAudioBecomingNoisy(true) }
        // Nothing here can show a picture, and decoding one to throw it away costs battery
        // and heat on a phone that is also driving the car screen.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()

        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
    }

    /**
     * Per-stream User-Agent, resolved at request time. Providers hand out a User-Agent per
     * channel (Stalker sends the MAC, M3U playlists carry their own), and a MediaSource is
     * built from a single factory - so the header is looked up from the URL being fetched
     * rather than fixed when the factory is built.
     */
    private fun dataSourceFactory(): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(60_000)
        return DefaultDataSource.Factory(this, http)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onDestroy() {
        session.release()
        player.release()
        ioScope.cancel()
        super.onDestroy()
    }

    /** Runs a callback's heavy work (catalog disk read + list scans) off the service main
     *  thread and completes the returned future when it's done. The Media framework consumes
     *  the future on its own executor, so no main-thread delivery is needed. */
    private fun <T> asyncIo(block: () -> T): ListenableFuture<T> {
        val future: SettableFuture<T> = SettableFuture.create()
        ioScope.launch {
            try {
                future.set(block())
            } catch (e: Exception) {
                future.setException(e)
            }
        }
        return future
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return asyncIo {
                if (catalog.channels.isEmpty()) catalog.loadCatalog()
                LibraryResult.ofItem(browsableItem(ROOT, "Lumora"), params)
            }
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return asyncIo {
                if (catalog.channels.isEmpty()) catalog.loadCatalog()
                val children = when {
                    parentId == ROOT -> rootChildren()
                    parentId == FAVOURITES -> catalog.favourites().map(::playableItem)
                    parentId == RECENT -> catalog.recents().map(::playableItem)
                    parentId.startsWith(CATEGORY_PREFIX) -> {
                        val name = parentId.removePrefix(CATEGORY_PREFIX)
                        catalog.categories()[name].orEmpty().map(::playableItem)
                    }
                    else -> emptyList()
                }
                // The car host pages its lists; handing back everything at once is what makes a
                // 4000-channel category hang the browser.
                val paged = children.drop(page * pageSize).take(pageSize)
                LibraryResult.ofItemList(ImmutableList.copyOf(paged), params)
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val channel = byMediaId[mediaId]
                ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(playableItem(channel), null))
        }

        /**
         * Voice search: "play BBC News on Lumora". Matched loosely on the channel name, since
         * speech recognition will not reproduce a provider's punctuation or its "UK| " prefix.
         */
        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            asyncIo {
                if (catalog.channels.isEmpty()) catalog.loadCatalog()
                session.notifySearchResultChanged(browser, query, matches(query).size, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return asyncIo {
                val results = matches(query).map(::playableItem).drop(page * pageSize).take(pageSize)
                LibraryResult.ofItemList(ImmutableList.copyOf(results), params)
            }
        }

        /**
         * A controller plays by sending back a media id with no URI attached - this is where
         * that becomes a real stream again. Anything unknown is dropped rather than passed on
         * as an item the player would fail to open.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            return asyncIo {
                if (catalog.channels.isEmpty()) catalog.loadCatalog()
                val resolved = mediaItems.mapNotNull { item ->
                    val channel = byMediaId[item.mediaId] ?: return@mapNotNull null
                    item.buildUpon().setUri(channel.url).build()
                }.toMutableList()
                resolved
            }
        }
    }

    private fun matches(query: String): List<Channel> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return catalog.channels.filter { it.name.lowercase().contains(needle) }.take(50)
    }

    private fun rootChildren(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        if (catalog.favourites().isNotEmpty()) items += browsableItem(FAVOURITES, "Favourites")
        if (catalog.recents().isNotEmpty()) items += browsableItem(RECENT, "Recent")
        for (category in catalog.categories().keys) {
            items += browsableItem(CATEGORY_PREFIX + category, category)
        }
        return items
    }

    private fun browsableItem(mediaId: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setExtras(Bundle.EMPTY)
                    .build()
            )
            .build()

    private fun playableItem(channel: Channel): MediaItem {
        val mediaId = CHANNEL_PREFIX + channel.id.ifBlank { channel.url }
        byMediaId[mediaId] = channel
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(channel.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(channel.name)
                    .setSubtitle(channel.categoryName)
                    .setArtworkUri(channel.logoUrl?.let { android.net.Uri.parse(it) })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
    }

    private companion object {
        const val ROOT = "root"
        const val FAVOURITES = "favourites"
        const val RECENT = "recent"
        const val CATEGORY_PREFIX = "cat:"
        const val CHANNEL_PREFIX = "ch:"
    }
}
