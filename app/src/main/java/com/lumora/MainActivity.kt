package com.lumora

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.net.Uri
import android.os.Build
import java.io.File
import android.util.Rational
import android.util.TypedValue
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.PixelCopy
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lumora.adapter.CategoryAdapter
import com.lumora.adapter.DYNAMIC_BUCKET_ID_PREFIX
import com.lumora.adapter.DownloadAdapter
import com.lumora.adapter.EpisodeAdapter
import com.lumora.adapter.LiveGuideAdapter
import com.lumora.adapter.PosterGridAdapter
import com.lumora.adapter.ShelfAdapter
import com.lumora.download.DownloadRecord
import com.lumora.download.DownloadStatus
import com.lumora.download.DownloadStore
import com.lumora.download.VodDownloader
import com.lumora.cache.ChannelCache
import com.lumora.cache.ProgramReminder
import com.lumora.cache.ReminderStore
import com.lumora.reminder.ReminderScheduler
import com.lumora.cache.FavoritesStore
import com.lumora.cache.PlaybackPositionStore
import com.lumora.cache.RecentlyPlayedStore
import com.lumora.model.CategoryFilter
import com.lumora.databinding.ActivityMainBinding
import com.lumora.model.Channel
import com.lumora.model.ContentShelf
import com.lumora.model.MediaType
import com.lumora.model.Provider
import com.lumora.model.ProviderType
import com.lumora.model.IptvProviderConfig
import com.lumora.data.IptvProviderStore
import com.lumora.pairing.QrPairingManager
import com.lumora.plugin.DiscoveredProvider
import com.lumora.plugin.DiscoveryResult
import com.lumora.plugin.PluginSubtitle
import com.lumora.plugin.ResolveResult
import com.lumora.plugin.SearchResult
import com.lumora.plugin.js.JsPluginEngine
import com.lumora.plugin.js.PluginScript
import com.lumora.plugin.js.PluginScriptManager
import com.lumora.plugin.js.PluginStore
import com.lumora.plugin.js.PluginStoreManager
import com.lumora.plugin.js.StoreScript
import com.lumora.torrent.TorrentEngine
import com.lumora.torrent.TorrentForegroundService
import com.lumora.anime.AnimeCatalogClient
import com.lumora.plugin.TorrentResult
import com.lumora.parser.M3uParser
import com.lumora.parser.XtreamClient
import com.lumora.player.PlayerManager
import com.lumora.player.PlayerTrackController
import com.lumora.player.VideoAspectFrameLayout
import com.lumora.util.extractLeadingTag
import com.lumora.util.deriveBrandCategories
import com.lumora.util.groupCategories
import com.lumora.util.groupSeriesFilmCategories
import com.lumora.util.CategoryGroup
import com.lumora.util.newestByDate
import com.lumora.util.cleanVodCategoryLabel
import com.lumora.util.isAdultCategory
import com.lumora.util.isTvDevice
import com.lumora.util.normalizeServerUrl
import com.lumora.util.groupDuplicateMovies
import com.lumora.util.groupDuplicateSeries
import com.lumora.util.groupLiveQualityVersions
import com.lumora.util.isNonEnglishTitle
import com.lumora.util.withResolvedYear
import com.lumora.data.local.LumoraDatabase
import com.lumora.data.local.entity.EpgSourceEntity
import com.lumora.data.backup.BackupManager
import com.lumora.data.remote.stalker.StalkerProvider
import com.lumora.data.remote.jellyfin.JellyfinProvider
import com.lumora.player.playback.PlayerDiagnostics
import com.lumora.data.update.AppUpdateChecker
import com.lumora.data.update.AppUpdateInstaller
import com.lumora.data.domain.CombinedM3uProfile
import com.lumora.data.domain.CombinedM3uRepository
import com.lumora.player.playback.AvOffsetManager
import com.lumora.player.playback.PlayerErrorClassifier
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

private const val PREF_HIDE_NON_ENGLISH = "hide_non_english_vod"
private const val PREF_HIDE_ADULT = "hide_adult_categories"
private const val PREF_PARENTAL_PIN = "parental_pin"
private const val PREF_ASPECT_MODE = "player_aspect_mode"
private const val PREF_CLASSIC_CATEGORY_LAYOUT = "classic_category_layout"
// The live channel that was playing when the app was last closed, reopened on next launch,
// plus the exact version of it that was on - restoring the channel alone re-runs the
// quality/dead-stream auto-pick and can land on a different (often broken) stream.
private const val PREF_LAST_LIVE_CHANNEL = "last_live_channel_id"
private const val PREF_LAST_LIVE_VERSION = "last_live_version_id"
// When the catalog was last fetched from the network; the cache serves every launch until
// this is CATALOG_TTL_MS old (a provider change force-refreshes regardless).
private const val PREF_CATALOG_REFRESHED_AT = "catalog_refreshed_at"
private const val CATALOG_TTL_MS = 12 * 60 * 60 * 1000L
private const val SEARCH_BATCH_SIZE = 50

// Free-TV/IPTV: a community-maintained list of publicly available free-to-air streams.
// Used by the empty state's "Try the Demo" so the app can be exercised before any
// credentials exist. Nothing else references it - it is an ordinary M3U url handed to the
// ordinary M3U provider path, not a special-cased content source.
// Generic User-Agent for stream HTTP requests.
private const val STREAM_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
private const val FAVOURITES_CATEGORY_ID = "__favourites__"
private const val CLASSIC_LAYOUT_TOGGLE_ID = "__classic_layout_toggle__"
/** Films/Series sidebar row that filters the tab down to Jellyfin-sourced items only.
 *  Only built when the tab actually contains Jellyfin content. */
private const val JELLYFIN_CATEGORY_ID = "__jellyfin__"
/** Series sidebar row for the plugin-gated anime catalog. Expandable: its children are the
 *  catalog's sections (Trending Now, Currently Airing, one per genre, ...). Built explicitly
 *  rather than derived from the channels' own category name, because anime titles carry a
 *  single "Anime" category and the sections they belong to overlap. */
private const val ANIME_CATEGORY_ID = "__anime__"
// Live TV sidebar leads with these dynamic buckets (Sports/News/Music/Cinema),
// each vacuuming up every matching provider category *and* brand cluster
// regardless of where it lives in the raw catalog; everything left over cascades
// below in the usual priority/alpha order, same as before this existed.
private val LIVE_DYNAMIC_BUCKETS = listOf(
    "Sports" to listOf("sport"),
    "News" to listOf("news"),
    "Music" to listOf("music"),
    "Cinema" to listOf("cinema", "movie", "film")
)

// The same idea for Films/Series, where the equivalent of a channel genre is the genre a
// provider names its VOD categories after ("EN | ACTION", "4K ACTION & ADVENTURE", ...).
// Without these, those two tabs had no dynamic rows at all - only the provider's own
// category list - so the sidebar there looked nothing like Live TV's.
// First match wins, so keep the more specific keywords above the general ones.
private val VOD_DYNAMIC_BUCKETS = listOf(
    "Kids & Family" to listOf("kids", "family", "cartoon", "anime", "animation"),
    "Action" to listOf("action", "adventure", "martial"),
    "Comedy" to listOf("comedy"),
    "Horror & Thriller" to listOf("horror", "thriller", "suspense"),
    "Sci-Fi & Fantasy" to listOf("sci-fi", "scifi", "science fiction", "fantasy"),
    "Crime & Mystery" to listOf("crime", "mystery", "detective"),
    "Documentary" to listOf("documentar", "docu"),
    "Romance" to listOf("romance", "romantic"),
    "Drama" to listOf("drama")
)
// Auto-failover to the next quality/source version of a live channel triggers on
// either a single long stall or several shorter stalls close together - a lone
// hiccup shouldn't cause a switch, but a stream that keeps stuttering should.
private const val STALL_LONG_MS = 15_000L
private const val STALL_WINDOW_MS = 45_000L
private const val STALL_COUNT_THRESHOLD = 3

// A dead IPTV feed sometimes never stalls or errors at all - the server just serves a
// technically-valid, steadily-decoding encode of a blank black frame instead. Neither
// onPlayerError nor the buffer-stall watchdog above ever fires for that case, so the
// actual rendered surface gets sampled periodically and sustained near-black output is
// treated as a dead feed too.
private const val BLACK_FRAME_INITIAL_DELAY_MS = 3_000L
private const val BLACK_FRAME_CHECK_INTERVAL_MS = 2_000L
private const val BLACK_FRAME_LUMA_THRESHOLD = 10 // 0-255 average brightness
private const val BLACK_FRAME_STREAK_THRESHOLD = 2
private const val DEAD_STREAM_COOLDOWN_MS = 60 * 60 * 1000L
// Dead marks, persisted so a cooldown survives the app being closed and reopened.
private const val PREF_DEAD_STREAMS = "dead_streams_until"
// How long a freshly-tuned stream is exempt from stall/black-frame failover. Startup and a
// channel change both have a slow first buffer fill; without this the app walks the whole
// version group in the first few seconds and marks each one dead for DEAD_STREAM_COOLDOWN_MS,
// so the best version stays skipped for hours afterwards.
private const val FAILOVER_GRACE_MS = 12_000L

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var playerManager: PlayerManager
    private lateinit var castManager: com.lumora.player.CastManager
    private lateinit var prefs: SharedPreferences
    private lateinit var playerDiagnostics: PlayerDiagnostics
    private lateinit var database: LumoraDatabase
    private lateinit var speedController: com.lumora.player.playback.PlaybackSpeedController
    private lateinit var sleepTimer: com.lumora.player.playback.SleepTimer
    private val trackController = PlayerTrackController()
    private val qrManager by lazy { QrPairingManager(this) }
    private var activeSettingsOverlay: FullScreenOverlay? = null
    /** Set by showProviderSettings to its local renderIptvProviderList, so the plugin-discovery
     *  pane (a sibling scope in the same settings screen) can refresh the provider list after it
     *  adds a discovered provider - otherwise the new provider is saved but the list stays stale. */
    private var refreshIptvProviderList: (() -> Unit)? = null
    private var activeSearchOverlay: FullScreenOverlay? = null

    // Live TV inline preview: a separate, muted player instance so browsing the
    // channel list doesn't touch the main PlayerManager used for fullscreen playback.
    private var previewPlayerManager: PlayerManager? = null
    private var previewChannelId: String? = null
    private var previewLoadRunnable: Runnable? = null
    private var previewVersionGroup: List<Channel> = emptyList()
    private var previewVersionIndex = 0
    private var previewBlackFrameStreak = 0
    private val previewBlackFrameCheckRunnable = Runnable { checkForPreviewBlackFrame() }

    private var allChannels = listOf<Channel>()
    private var liveChannels = listOf<Channel>()
    private var seriesList = listOf<Channel>()
    private var filmList = listOf<Channel>()
    private var filmVersions: Map<String, List<Channel>> = emptyMap()
    // Duplicate series copies keyed by the representative's id - unlike films these aren't
    // alternate streams of one item, they're each provider's own separate episode list
    // (see groupDuplicateSeries), so the detail screen switches between them rather than
    // playing one directly.
    private var seriesVersions: Map<String, List<Channel>> = emptyMap()
    private var liveVersions: Map<String, List<Channel>> = emptyMap()
    private var filmShelves: List<ContentShelf> = emptyList()
    private var seriesShelves: List<ContentShelf> = emptyList()
    private var currentVersionGroup: List<Channel> = emptyList()
    private var currentVersionIndex = 0
    /** The series a currently-playing episode came from, paired with every provider's copy of
     *  that series (see seriesVersions). An episode Channel carries no link back to its show,
     *  so the in-player version picker can't find the alternatives without this. */
    private var currentSeriesVersionContext: Pair<Channel, List<Channel>>? = null
    private var bufferingStartMs = 0L
    // When the current stream was handed to the player, and whether it ever reached READY -
    // the two things every automatic failover has to know before it condemns a stream.
    private var currentStreamStartMs = 0L
    private var currentStreamPlayed = false
    private val stallTimestamps = mutableListOf<Long>()
    private val longStallCheckRunnable = Runnable { attemptBufferFailover() }
    private var blackFrameStreak = 0
    private val blackFrameCheckRunnable = Runnable { checkForBlackFrame() }
    // Keyed by stream key (id, or url when id is blank) - a version that just failed over
    // out of is skipped by both fullscreen and preview auto-pick/failover for a cooldown
    // window instead of being retried again a few seconds later.
    private val deadStreamUntil = mutableMapOf<String, Long>()

    private var provider: Provider = Provider()
    // Every configured Xtream provider, keyed by IptvProviderConfig.id - detail/EPG calls
    // resolve the right one per-Channel via Channel.sourceProviderId instead of assuming
    // whichever Xtream provider loaded last (the old single `provider` field above).
    private var xtreamProviderConfigs: Map<String, IptvProviderConfig> = emptyMap()
    /** IptvProviderConfig id -> display name, for showing which provider an item came from. */
    private var providerNamesById: Map<String, String> = emptyMap()
    /** The in-flight plugin discovery run, if any - one at a time, cancelled when Settings closes. */
    private var pluginDiscoveryJob: Job? = null
    /** Installed JS plugin scripts - see PluginScriptManager. Discovered once at startup and
     *  refreshed whenever Settings > Plugins is opened. */
    private val pluginScriptManager by lazy { PluginScriptManager(this, prefs) }
    private val pluginStoreManager by lazy { PluginStoreManager(prefs) }
    private val jsPluginEngine by lazy { JsPluginEngine() }
    /** Backs whatever's currently playing via a resolvesNatively plugin - the
     *  local HTTP server it owns must stay alive for the life of playback. See showStreamSearchDialog. */
    private var activeTorrentSession: TorrentEngine? = null
    /** The film/series whose detail page a VOD playback was started from, so backing out of the
     *  player returns to that poster rather than dumping the user in the grid they had to walk
     *  to reach it. Set right before showPlayerFor by every detail-originated play path, and
     *  consumed (and cleared) by hidePlayer. Null for live TV and for anything played straight
     *  from a shelf, which have no detail page behind them. */
    private var detailReturnItem: Channel? = null
    /** The version group [detailReturnItem] was opened with, so re-opening its detail page shows
     *  the same set of alternate versions/episodes rather than re-deriving a narrower one. */
    private var detailReturnGroup: List<Channel>? = null
    private var animeCatalog: AnimeCatalogClient? = null
    /** Section membership from the last anime catalog fetch (Trending Now, Action, ...), used to
     *  build the Series sidebar's Anime parent and its child rows. A title belongs to several
     *  sections at once, so these are ids into the tab's channel list, not separate channels. */
    private var animeSections: List<AnimeCatalogClient.Section> = emptyList()
    // Kept around after a successful Jellyfin content load so a series' detail page can
    // fetch its episodes without re-authenticating - Jellyfin's episode API has no
    // Xtream equivalent, so this is the only path a Jellyfin series' episodes ever load through.
    private var jellyfinClient: JellyfinProvider? = null
    private var currentIndex = -1
    // Which episode queue (if any) is currently playing, so Next/Prev and
    // auto-advance-on-end know what "next episode" means. -1 = not playing an episode.
    private var currentEpisodeQueue: List<Channel> = emptyList()
    private var currentEpisodeQueueIndex: Int = -1
    private var isPlayerVisible = false
    private var isContentDetailVisible = false
    private var nowShowingDetailId: String? = null
    /** The season chip matching the episode list currently on screen - where UP from the
     *  list's first row lands. Kept pointed at the *selected* chip (updated on every season
     *  change) because default focus search would otherwise pick whichever chip is
     *  geometrically nearest, which can be a different season entirely. */
    private var selectedSeasonChip: View? = null
    private var activeTab = 0
    private var showingHome = true
    private var showingDownloads = false
    private var showingDiscover = false
    private val isTv by lazy { isTvDevice(this) }
    // Edge-swipe-to-back tracking (phone only - see dispatchTouchEvent). Only armed when
    // the gesture *starts* within EDGE_SWIPE_ZONE_DP of the left edge, so it can't be
    // confused with the horizontal shelf/episode-row scrolling used throughout the UI.
    private var edgeSwipeTracking = false
    private var edgeSwipeStartX = 0f
    private var edgeSwipeStartY = 0f
    private var selectedCategoryIds: Set<String>? = null
    private var selectedBrandChannelIds: Set<String>? = null
    private var selectedRowId: String? = null
    private var selectedCategoryLabel: String? = null
    // "See All" on a Films/Series shelf header - shows that exact shelf's items in the
    // grid, bypassing the sidebar's category-id matching entirely (a shelf's grouping by
    // exact category name doesn't necessarily line up with the sidebar's merged rows).
    // Takes priority over selectedCategoryIds in applyCategoryFilter when set.
    private var selectedShelfItems: List<Channel>? = null
    private val expandedGroupKeys = mutableSetOf<String>()
    /** Set while the search overlay is open. Receives a typed character, or null for
     *  backspace, from a real keyboard - the query field itself isn't focusable. */
    private var searchKeyHandler: ((String?) -> Unit)? = null
    /** Child rows for every expandable sidebar parent, from the last category build. Lets
     *  expanding a row splice its children in rather than rerunning the whole build, which
     *  rescans every channel in the tab. Refreshed on every build, so it can't outlive the
     *  catalog/filters it was derived from. */
    private var categoryChildrenCache: Map<String, List<CategoryFilter>> = emptyMap()
    private var nowPlayingChannel: Channel? = null
    private var resumePromptShown = false
    private var progressTickCount = 0

    // ── Jellyfin server-side state ──────────────
    /** The server's own Continue Watching / Next Up, refreshed with the catalog. Kept apart
     *  from [allChannels] because these are ordered *views* of items already in the catalog,
     *  not extra content - merging them in would duplicate every partly-watched title. */
    private var jellyfinResumeItems: List<Channel> = emptyList()
    private var jellyfinNextUpItems: List<Channel> = emptyList()
    /** The negotiated stream for whatever Jellyfin item is playing (see
     *  JellyfinProvider.resolveStream). Its PlaySessionId is what ties every progress report
     *  to this play, and what lets the server tear a transcode down when it ends. */
    private var jellyfinPlaySession: JellyfinProvider.ResolvedStream? = null
    private var jellyfinPlayingItemId: String? = null
    private var jellyfinChapters: List<JellyfinProvider.Chapter> = emptyList()
    private var jellyfinTrickplay: JellyfinProvider.TrickplayInfo? = null
    /** Last decoded trickplay sprite sheet, kept so scrubbing within one sheet (~100
     *  thumbnails) doesn't re-download it on every seek step. */
    private var trickplayTileCache: Pair<Int, android.graphics.Bitmap>? = null
    private var trickplayLoadJob: kotlinx.coroutines.Job? = null

    // ── A/V Sync Offset ─────────────────────────
    private val avOffsetManager by lazy { AvOffsetManager(this) }

    // ── Picture-in-Picture video size cache ──────
    private var lastVideoWidth = 16
    private var lastVideoHeight = 9

    // ── Numeric Remote Input ────────────────────
    private val digitInputBuffer = StringBuilder(6)
    private var isDigitEntryActive = false
    private val digitInputTimeoutRunnable = Runnable { resolveDigitInput() }

    // ── Up Next / Auto-Advance ──────────────────
    private var upNextEpisode: Channel? = null
    private var upNextCountdown = 10
    private var upNextActive = false
    private val upNextTickRunnable = object : Runnable {
        override fun run() {
            if (upNextActive) {
                upNextCountdown--
                updateUpNextOverlay()
                if (upNextCountdown <= 0) {
                    executeUpNextAdvance()
                } else {
                    mainHandler.postDelayed(this, 1000)
                }
            }
        }
    }

    // ── Incremental Search ──────────────────────
    private var searchAllResults: List<Channel> = emptyList()
    private var searchDisplayedCount = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingBackupManager: BackupManager? = null

    companion object {
        private const val REQUEST_EXPORT_BACKUP = 2001
        private const val REQUEST_IMPORT_BACKUP = 2002
        private const val EDGE_SWIPE_ZONE_DP = 24f
        private const val EDGE_SWIPE_THRESHOLD_DP = 64f
    }

    private val liveAdapter = LiveGuideAdapter(
        onChannelClick = { channel -> playItem(channel) },
        onChannelFocused = { channel -> requestPreviewLoad(channel) },
        onChannelLongPress = { channel -> toggleFavoriteChannel(channel) },
        onProgramLongPress = { channel, program -> toggleProgramReminder(channel, program) },
        isReminderSet = { key -> ReminderStore.get(this, key) != null },
        fetchPrograms = { channelId -> resolveEpgPrograms(channelId) }
    )
    private val seriesShelfAdapter = ShelfAdapter(
        onItemClick = { item -> playItem(item) },
        onPinClick = { shelf -> togglePinnedShelf(1, shelf.title) },
        onHideClick = { shelf -> toggleHiddenShelf(1, shelf.title) },
        onSeeAllClick = { shelf -> showSeeAll(shelf) }
    )
    private val filmsShelfAdapter = ShelfAdapter(
        onItemClick = { item -> playItem(item) },
        onPinClick = { shelf -> togglePinnedShelf(2, shelf.title) },
        onHideClick = { shelf -> toggleHiddenShelf(2, shelf.title) },
        onSeeAllClick = { shelf -> showSeeAll(shelf) }
    )
    private val homeShelfAdapter = ShelfAdapter(
        onItemClick = { item -> onHomeItemClick(item) },
        onHideClick = { shelf -> toggleHiddenHomeShelf(shelf.title) },
        showPinButton = false
    )
    // Single-category selection swaps to these - a vertical, scrollable grid instead of
    // the shelves' horizontal strip, since one category's whole catalog doesn't fit a
    // single row.
    private val seriesGridAdapter = com.lumora.adapter.PosterGridAdapter { item -> playItem(item) }
    private val filmsGridAdapter = com.lumora.adapter.PosterGridAdapter { item -> playItem(item) }
    private val tmdbClient = com.lumora.data.remote.tmdb.TmdbClient()
    private val discoverGridAdapter = com.lumora.adapter.PosterGridAdapter { item -> onDiscoverItemClick(item) }
    private var discoverSearchJob: Job? = null
    private var providerLoadJob: Job? = null
    private val categoryAdapter = CategoryAdapter(
        onCategoryClick = { category -> onCategorySelected(category) },
        onCategoryLongClick = { category ->
            // Live TV's sidebar has other long-press-worthy stuff going on (brand/bucket
            // rows) - keep it a plain pin toggle there. Films/Series get a small menu so
            // hide is reachable too.
            if (activeTab == 0) togglePinCategory(category) else showCategoryContextMenu(category)
        }
    )
    private val downloadAdapter = DownloadAdapter(
        onClick = { record -> playDownload(record) },
        onDelete = { record -> deleteDownload(record) }
    )
    private val hideControlsRunnable = Runnable { hideControls() }
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (playerManager.isPlaying) {
                updateProgress()
                checkUpNextTrigger()
                mainHandler.postDelayed(this, 1000)
            }
        }
    }
    private val downloadsProgressRunnable = object : Runnable {
        override fun run() {
            if (!showingDownloads) return
            refreshDownloadsList()
            mainHandler.postDelayed(this, 1000)
        }
    }
    private val downloadCompleteReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            if (showingDownloads) refreshDownloadsList()
        }
    }

    // ── Lifecycle ──────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        prefs = getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        // Cheap (no network) - just parses each script's PLUGIN manifest header - but async
        // since it runs the JS engine, so kick it off early rather than on first use.
        // loadSavedProvider()'s gate (see loadAllConfiguredProviders) checks enabledStreamSearchPlugin(),
        // which reads this discovery's cached result - awaited here so a plugin-only setup
        // (no traditional provider) isn't wrongly bounced to "Add a Provider" on cold start
        // because that check ran against the still-empty pre-discovery cache.
        val pluginDiscoveryOnStart = scope.launch { pluginScriptManager.discoverScripts() }
        playerManager = PlayerManager(this)
        playerDiagnostics = PlayerDiagnostics(playerManager.getExoPlayer())
        playerManager.getExoPlayer().addAnalyticsListener(playerDiagnostics.getAnalyticsListener())
        database = LumoraDatabase.getInstance(this)

        // Initialize background sync
        com.lumora.data.sync.BackgroundWorkEnabler.initialize(this)

        setupChannelList()
        setupTabs()
        setupPlayerControls()
        setupToolbar()
        // Consumed once, by the first catalog load of this process. Read here (not at the
        // point of use) so a rotation/recreate with the same catalog doesn't re-trigger it,
        // and so a later reload - toggling a provider in Settings, say - doesn't yank the
        // user back into the player.
        pendingLiveResumeId = if (savedInstanceState == null) prefs.getString(PREF_LAST_LIVE_CHANNEL, null) else null
        loadDeadStreams()
        scope.launch { pluginDiscoveryOnStart.join(); loadSavedProvider() }
        requestNotificationPermissionIfNeeded()
        checkAndPromptUpdate()

        // Downloads are a mobile-only affordance - a TV box has nowhere meaningful to
        // browse a downloaded file, and it's not what "download for offline" means there.
        if (!isTv) {
            binding.tabDownloads.visibility = View.VISIBLE
            val filter = android.content.IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            ContextCompat.registerReceiver(this, downloadCompleteReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            // The XML D-pad chain routes Films -> Discover -> Downloads -> Home, but Downloads
            // stays View.GONE on TV - an explicit nextFocus target that's GONE just eats the
            // key press instead of falling through. Re-route around the hidden Downloads tab so
            // the ring becomes Films -> Discover -> Home -> ... on TV.
            binding.tabFilms.nextFocusRightId = R.id.tabDiscover
            binding.tabDiscover.nextFocusRightId = R.id.tabHome
            binding.tabHome.nextFocusLeftId = R.id.tabDiscover
        }
    }

    /** Needed on API 33+ for reminder notifications to actually show; older Fire OS builds don't gate on it. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    /** Checked once per launch, straight off GitHub Releases - not tucked inside Settings. */
    private fun checkAndPromptUpdate() {
        scope.launch {
            val updater = AppUpdateChecker(this@MainActivity)
            val info = withContext(Dispatchers.IO) { updater.checkForUpdate() } ?: return@launch
            if (!info.isUpdateAvailable || info.downloadUrl.isBlank()) return@launch
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Update available")
                .setMessage("Lumora v${info.latestVersion} is available.\nCurrent: v${info.currentVersion}\n\n${info.releaseNotes.take(200)}")
                .setPositiveButton("Update") { _, _ -> downloadAndInstallUpdate(info.downloadUrl, info.latestVersion) }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    /** Downloads the release APK via DownloadManager, then hands it to the system package
     *  installer as soon as the download finishes - no separate "tap to install" step. */
    private fun downloadAndInstallUpdate(downloadUrl: String, versionName: String) {
        val installer = AppUpdateInstaller(this)
        val downloadId = installer.downloadApk(downloadUrl, versionName)
        Toast.makeText(this, "Downloading update…", Toast.LENGTH_SHORT).show()
        scope.launch {
            while (true) {
                delay(1000)
                if (installer.isDownloadFailed(downloadId)) {
                    Toast.makeText(this@MainActivity, "Update download failed", Toast.LENGTH_SHORT).show()
                    break
                }
                if (installer.isDownloadComplete(downloadId)) {
                    val path = installer.getDownloadedFilePath(downloadId)
                    if (path != null) {
                        // If the user had to be sent to grant "install unknown apps",
                        // installApk() returns false - retry once automatically after
                        // they've had time to flip it, instead of making them come back
                        // and press Update again themselves.
                        if (!installer.installApk(path)) {
                            delay(30_000)
                            installer.installApk(path)
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Update download failed", Toast.LENGTH_SHORT).show()
                    }
                    break
                }
            }
        }
    }

    /**
     * Insets the app's chrome out from under the system bars.
     *
     * With targetSdk 36 the window is laid out edge-to-edge on Android 15+, so without this
     * the toolbar draws *behind* the status bar: on a portrait phone the settings/refresh
     * buttons sat under the clock and signal icons, and couldn't be tapped at all because the
     * status bar takes those touches first (landscape "worked" only because the bar is shorter
     * there and the buttons cleared it).
     *
     * Applied to the chrome layers rather than the window root so video keeps filling the
     * screen behind them - the player's controls overlay gets the same padding, so its own
     * buttons stay clear of the bars, while the surface underneath stays full-bleed. The
     * cutout inset is included for phones with a camera notch in the status bar area.
     */
    private fun applySystemBarInsets() {
        val targets = listOf(binding.mainContent, binding.contentDetailLayout, binding.controlsOverlay)
        val basePadding = targets.map { intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            targets.forEachIndexed { index, view ->
                val base = basePadding[index]
                view.setPadding(
                    base[0] + insets.left,
                    base[1] + insets.top,
                    base[2] + insets.right,
                    base[3] + insets.bottom
                )
            }
            windowInsets
        }
    }

    override fun onResume() {
        super.onResume()
        if (isPlayerVisible && playerManager.playbackState == Player.STATE_READY) playerManager.play()
        else if (activeTab == 0) showLivePreviewPane()
    }

    override fun onPause() {
        super.onPause()
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        // Entering PiP also triggers onPause() - don't pause playback or we'd defeat the point of PiP.
        if (!inPip) {
            if (isPlayerVisible) {
                saveCurrentPlaybackPosition()
                playerManager.pause()
                // After the pause, so it reports the paused state: the play is still open
                // (onResume resumes it), but the server's resume point should already be
                // current if the process is killed while backgrounded and no stop ever lands.
                reportJellyfinProgress()
            }
            releaseLivePreview()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isPlayerVisible && playerManager.isPlaying && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val aspectRatio = if (lastVideoWidth > 0 && lastVideoHeight > 0) {
                    Rational(lastVideoWidth, lastVideoHeight)
                } else {
                    Rational(16, 9)
                }
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(aspectRatio).build()
                )
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            mainHandler.removeCallbacks(hideControlsRunnable)
            binding.controlsOverlay.visibility = View.GONE
        } else if (isPlayerVisible) {
            showControls()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        qrManager.stop()
        playerManager.release()
        if (::sleepTimer.isInitialized) sleepTimer.stop()
        if (::castManager.isInitialized) castManager.release()
        activeTorrentSession?.let { engine -> Thread { runCatching { engine.stop() } }.start() }
        activeTorrentSession = null
        TorrentForegroundService.stop(this)
        releaseLivePreview()
        if (!isTv) runCatching { unregisterReceiver(downloadCompleteReceiver) }
    }

    override fun onBackPressed() {
        if (activeSettingsOverlay != null) activeSettingsOverlay?.dismiss()
        else if (activeSearchOverlay != null) activeSearchOverlay?.dismiss()
        else if (isPlayerVisible) hidePlayer()
        else if (isContentDetailVisible) hideContentDetail()
        else super.onBackPressed()
    }

    /** True while there's actually an overlay/screen for swipe-back to close - guards edge-swipe
     *  so it doesn't fall through to super.onBackPressed() (exiting the app) on a stray swipe. */
    private fun hasDismissibleScreen(): Boolean =
        activeSettingsOverlay != null || activeSearchOverlay != null || isPlayerVisible || isContentDetailVisible

    /** Phone-only edge-swipe-to-back: a left-to-right swipe starting within the leftmost
     *  [EDGE_SWIPE_ZONE_DP] of the screen closes whatever's on top, mirroring the system
     *  gesture-nav back swipe. Started from the edge (not anywhere on screen) specifically
     *  so it can't be triggered by scrolling a shelf/episode row, which are horizontal
     *  RecyclerViews spanning the full width and would otherwise fire this constantly.
     *  Observes via dispatchTouchEvent rather than consuming, so normal clicks/scrolls are
     *  untouched - it never returns true from here, just calls onBackPressed() as a side effect. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (!isTv) {
            val density = resources.displayMetrics.density
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    edgeSwipeTracking = ev.x <= EDGE_SWIPE_ZONE_DP * density && hasDismissibleScreen()
                    edgeSwipeStartX = ev.x
                    edgeSwipeStartY = ev.y
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (edgeSwipeTracking) {
                        val dx = ev.x - edgeSwipeStartX
                        val dy = kotlin.math.abs(ev.y - edgeSwipeStartY)
                        if (dx >= EDGE_SWIPE_THRESHOLD_DP * density && dy < dx * 0.5f) {
                            edgeSwipeTracking = false
                            onBackPressed()
                        }
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> edgeSwipeTracking = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Walks the episode list one adapter position per UP/DOWN press instead of letting the
     *  framework's FocusFinder choose.
     *
     *  `detailItemsList` is a wrap_content RecyclerView with nestedScrollingEnabled=false
     *  inside the detail ScrollView, so it never scrolls itself and default focus search runs
     *  over the whole screen's geometry rather than staying inside the list - from a row part
     *  way down a season it would resolve UP to the season chip row instead of the episode
     *  directly above.
     *
     *  This lives in dispatchKeyEvent rather than an OnKeyListener on the row (the pattern the
     *  poster/shelf adapters use) because a row's listener only fires when the row itself holds
     *  focus and only sees a hit when the neighbour is already bound: on phones focus can sit on
     *  the row's download button instead, and an unresolved neighbour there falls through to the
     *  same broken default search. Activity-level dispatch always sees the key, and resolving the
     *  holder from whatever view actually has focus covers both cases. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Real-keyboard typing while search is open. The query field is deliberately not
        // focusable (see dialog_search.xml), so nothing else would receive these.
        val onSearchKey = searchKeyHandler
        if (onSearchKey != null && event.action == android.view.KeyEvent.ACTION_DOWN) {
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DEL) {
                onSearchKey(null)
                return true
            }
            val typed = event.unicodeChar.takeIf { it != 0 }?.toChar()
            if (typed != null && !Character.isISOControl(typed)) {
                onSearchKey(typed.uppercase())
                return true
            }
        }
        if (event.action == android.view.KeyEvent.ACTION_DOWN && isContentDetailVisible && !isPlayerVisible) {
            val step = when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> -1
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 1
                else -> 0
            }
            val list = binding.detailItemsList
            val focused = currentFocus
            if (step != 0 && focused != null && list.visibility == View.VISIBLE) {
                val holder = runCatching { list.findContainingViewHolder(focused) }.getOrNull()
                val pos = holder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
                if (pos != RecyclerView.NO_POSITION) {
                    val target = pos + step
                    val count = list.adapter?.itemCount ?: 0
                    when {
                        // Escaping upward off the first row - go to the chip for the season
                        // actually on screen, not whatever is geometrically closest.
                        target < 0 -> selectedSeasonChip?.takeIf { it.isShown }?.let {
                            it.requestFocus()
                            return true
                        }
                        target < count -> {
                            val targetView = list.layoutManager?.findViewByPosition(target)
                            if (targetView != null) {
                                targetView.requestFocus()
                            } else {
                                // Not laid out yet (long season scrolled far from the
                                // viewport) - scroll it in, then focus once it exists.
                                list.scrollToPosition(target)
                                list.post { list.layoutManager?.findViewByPosition(target)?.requestFocus() }
                            }
                            return true
                        }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** DPAD up/down channel-surfs while fullscreen on a live channel, without needing the on-screen controls. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        // Numeric remote input for direct channel entry - only while fullscreen on LIVE.
        // Buffer up to 6 digits, timeout after 1.5s of inactivity to resolve the channel.
        if (isPlayerVisible && nowPlayingChannel?.mediaType == MediaType.LIVE) {
            val digit = when (keyCode) {
                android.view.KeyEvent.KEYCODE_0, android.view.KeyEvent.KEYCODE_NUMPAD_0 -> 0
                android.view.KeyEvent.KEYCODE_1, android.view.KeyEvent.KEYCODE_NUMPAD_1 -> 1
                android.view.KeyEvent.KEYCODE_2, android.view.KeyEvent.KEYCODE_NUMPAD_2 -> 2
                android.view.KeyEvent.KEYCODE_3, android.view.KeyEvent.KEYCODE_NUMPAD_3 -> 3
                android.view.KeyEvent.KEYCODE_4, android.view.KeyEvent.KEYCODE_NUMPAD_4 -> 4
                android.view.KeyEvent.KEYCODE_5, android.view.KeyEvent.KEYCODE_NUMPAD_5 -> 5
                android.view.KeyEvent.KEYCODE_6, android.view.KeyEvent.KEYCODE_NUMPAD_6 -> 6
                android.view.KeyEvent.KEYCODE_7, android.view.KeyEvent.KEYCODE_NUMPAD_7 -> 7
                android.view.KeyEvent.KEYCODE_8, android.view.KeyEvent.KEYCODE_NUMPAD_8 -> 8
                android.view.KeyEvent.KEYCODE_9, android.view.KeyEvent.KEYCODE_NUMPAD_9 -> 9
                else -> -1
            }
            if (digit >= 0) {
                handleDigitInput(digit)
                return true
            }
        }

        // Dedicated transport keys. Nothing in the Activity claimed these, so a remote's
        // play/pause reached the media session (or nothing at all) and the overlay never
        // appeared - no visible response to the press, and the button's icon stayed stale.
        // Handled here so the on-screen controls react the same way they do to a click.
        if (isPlayerVisible) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                android.view.KeyEvent.KEYCODE_HEADSETHOOK -> {
                    playerManager.togglePlayPause(); updatePlayPauseIcon(); showControls(); return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    playerManager.play(); updatePlayPauseIcon(); showControls(); return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    playerManager.pause(); updatePlayPauseIcon(); showControls(); return true
                }
            }
        }

        // Live channel-surf is a blind shortcut only while the controls are hidden - once
        // they're showing, UP/DOWN needs to navigate between buttons (transport row ->
        // seek bar -> Speed/Sleep/Cast/...) instead of surfing channels out from under
        // whatever the user's trying to select.
        if (isPlayerVisible && nowPlayingChannel?.mediaType == MediaType.LIVE && binding.controlsOverlay.visibility != View.VISIBLE) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> { navigateChannel(-1); return true }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { navigateChannel(1); return true }
            }
        }
        // Any other D-pad press reveals the controls when they're hidden - was
        // center-only, so a movie/series (no channel-surf shortcut to fall back on) had
        // literally no key that showed them at all. First press just reveals; doesn't
        // also perform whatever that direction would otherwise do, same as it not also
        // clicking the button it lands focus on.
        val isDirectionalKey = keyCode in intArrayOf(
            android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER
        )
        if (isPlayerVisible && isDirectionalKey) {
            if (binding.controlsOverlay.visibility != View.VISIBLE) {
                showControls()
                return true
            }
            // Controls are already up and this key is about to move focus between their
            // buttons (transport row -> seek bar -> Speed/Sleep/Cast/...) - refresh the
            // auto-hide timer so navigating around inside them doesn't get cut off by the
            // same 4s countdown that started when they first appeared.
            mainHandler.removeCallbacks(hideControlsRunnable)
            mainHandler.postDelayed(hideControlsRunnable, 4000)
        }
        return super.onKeyDown(keyCode, event)
    }

    // ── Provider Loading ───────────────────────────

    /** Fixed, no-picker-required fallback location for devices (Fire TV, most Android TV
     *  boxes) with no Storage Access Framework document picker installed at all. */
    private fun localBackupFile(): File {
        val dir = File(getExternalFilesDir(null), "backups").apply { mkdirs() }
        return File(dir, "lumora_backup.json")
    }

    /** IPTV (Xtream/M3U/Stalker - still mutually exclusive among themselves, that's what
     *  [provider] represents) and Jellyfin are independent provider slots that can each be
     *  configured and enabled at the same time - their catalogs get merged. */
    private fun hasIptvConfigured(): Boolean = IptvProviderStore.load(prefs).isNotEmpty()

    private fun hasJellyfinConfigured(): Boolean =
        !prefs.getString("jellyfin_url", null).isNullOrBlank()

    private fun isJellyfinEnabled(): Boolean = prefs.getBoolean("jellyfin_provider_enabled", true)

    private fun hasProviderConfigured(): Boolean = hasIptvConfigured() || hasJellyfinConfigured()

    /** Configured *and* switched on. A provider can exist but be disabled (its row unticked
     *  in Settings, or the demo auto-disabled), which leaves nothing to browse - distinct
     *  from hasProviderConfigured(), which only asks whether any entry exists at all. */
    private fun hasProviderEnabled(): Boolean =
        IptvProviderStore.load(prefs).any { it.enabled } ||
            (hasJellyfinConfigured() && isJellyfinEnabled())

    /** Search and the tab bar are useful once there's either an enabled provider to browse, or
     *  an enabled stream_search plugin to find content through (Discover/anime tabs, Find
     *  Stream) - with neither, they point at nothing, so hide them and leave just the Settings
     *  button as the way in. The empty state carries its own Settings/Demo actions. Settings and
     *  refresh stay visible so the user can always get back to configuring one. */
    private fun updateTopChromeVisibility() {
        val enabled = hasProviderEnabled() || enabledStreamSearchPlugin() != null
        binding.tabBar.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.btnSearch.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) binding.homeSearchBar.visibility = View.GONE
    }

    /** Shows the "no provider" empty state and, crucially, moves focus onto one of its
     *  buttons. With the tab bar and search hidden and the content panes gone, nothing else
     *  on screen is focusable - so without this the D-pad had nothing to land on and stopped
     *  responding entirely (the "can't navigate when nothing's returned" trap). */
    private fun showEmptyState() {
        binding.contentRow.visibility = View.GONE
        binding.homeContent.visibility = View.GONE
        binding.homeSearchBar.visibility = View.GONE
        // The status row shares this weight=1 slot; leaving it up splits the screen and
        // buries the empty-state buttons under it.
        binding.statusRow.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE
        updateTopChromeVisibility()
        // Focus a button so the D-pad has somewhere to land - without this nothing is
        // focused and centre-press does nothing. Retried once on the next frame because the
        // very first post can land before the row is laid out (requestFocus then no-ops).
        fun focusFirstAction(): Boolean {
            val target = binding.emptyQrPair.takeIf { it.isShown }
                ?: return false
            return target.requestFocus()
        }
        binding.emptyState.post { if (!focusFirstAction()) binding.emptyState.post { focusFirstAction() } }
    }

    private fun loadSavedProvider() {
        loadAllConfiguredProviders()
    }

    /** Reacts to a provider being switched on or off in Settings.
     *
     *  Switching one *off* needs no network at all: its items are already in memory and
     *  carry their own provenance, so they're just dropped. Re-fetching every other provider
     *  to achieve that meant a full "Connecting to ..." reload - visible behind the settings
     *  dialog - and left the catalog at the mercy of a provider that happened to be down.
     *  Switching one *on* genuinely needs its catalog, so that still refreshes. */
    private fun applyProviderToggle(enabled: Boolean, belongsToProvider: (Channel) -> Boolean) {
        if (enabled) { loadAllConfiguredProviders(forceRefresh = true); return }
        scope.launch {
            allChannels = allChannels.filterNot(belongsToProvider)
            classifyAndShow()
            withContext(Dispatchers.IO) { ChannelCache.save(this@MainActivity, allChannels) }
        }
    }

    /** True when the cached catalog is old enough to be worth re-fetching. A missing stamp
     *  counts as stale so a cache written by an older build refreshes once, then follows
     *  the TTL like everything else. */
    private fun isCatalogStale(): Boolean {
        val last = prefs.getLong(PREF_CATALOG_REFRESHED_AT, 0L)
        return last <= 0L || System.currentTimeMillis() - last >= CATALOG_TTL_MS
    }

    private sealed class FetchResult {
        data class Success(val channels: List<Channel>) : FetchResult()
        data class Failure(val message: String) : FetchResult()
    }

    /** The one place that loads whatever's configured+enabled across every IPTV provider
     *  (any number of them now, not just one) plus Jellyfin, and merges the result - every
     *  settings Save/Quick Connect/reload call site routes through here instead of assuming
     *  a single active provider. [forceRefresh] skips the on-disk cache (used after a
     *  settings change, where showing stale content would be actively wrong) and re-fetches
     *  from the network(s) directly. */
    private fun loadAllConfiguredProviders(forceRefresh: Boolean = false) {
        // A stream_search plugin (anime catalog, Discover/Find Stream) still has work to do here
        // even with zero traditional providers configured - bailing out before reaching the
        // anime-catalog fetch below meant a plugin-only setup never populated anything.
        if (!hasProviderConfigured() && enabledStreamSearchPlugin() == null) { showProviderSettings(); return }
        // Raised for the cached path too: reading and re-deriving a big catalog still takes
        // a few seconds, and with no status up the app just looks frozen.
        setStatus("Loading...", visible = true)
        xtreamProviderConfigs = IptvProviderStore.load(prefs).filter { it.enabled && it.type == "xtream" }.associateBy { it.id }
        // Every type, not just Xtream, and regardless of enabled state - a cached catalog can
        // still contain items from a provider that's since been switched off, and their chips
        // should still say where they came from.
        providerNamesById = IptvProviderStore.load(prefs).associate { it.id to it.name }
        // Toggling/adding/removing providers in quick succession each calls this with no
        // ordering guarantee between the launched coroutines - without cancelling the
        // previous one, whichever network fetch happens to finish last wins and gets written
        // to allChannels/ChannelCache, which can silently persist a stale provider list.
        providerLoadJob?.cancel()
        providerLoadJob = scope.launch {
            // The cached catalog is authoritative until it goes stale: re-fetching every
            // launch means several seconds of "Loading..." and, on a large catalog, real
            // work for a result that is almost always identical. Providers change rarely,
            // so the network is only worth hitting once every CATALOG_TTL_MS - or right
            // away when the user changes a provider, which force-refreshes.
            if (!forceRefresh) {
                val cached = withContext(Dispatchers.IO) { ChannelCache.load(this@MainActivity) }
                if (!cached.isNullOrEmpty() && !isCatalogStale()) {
                    // If an anime stream-search plugin is enabled but cache predates it
                    // (no "anime:"-prefixed channels), skip cache so the catalog is fetched.
                    val hasAnimePlugin = pluginScriptManager.getDiscoveredScripts().any {
                        it.enabled && it.supportsStreamSearch && "anime" in it.contentTypes
                    }
                    val cachedHasAnime = cached.any { it.id.startsWith(AnimeCatalogClient.ID_PREFIX) }
                    if (!hasAnimePlugin || cachedHasAnime) {
                        allChannels = cached
                        classifyAndShow()
                        setStatus("", visible = false)
                        return@launch
                    }
                }
            }

            val combined = mutableListOf<Channel>()
            val errors = mutableListOf<String>()
            var expiryText: String? = null

            for (config in IptvProviderStore.load(prefs).filter { it.enabled }) {
                setStatus("Connecting to ${config.name}...", visible = true)
                val result = when (config.type) {
                    "xtream" -> fetchXtreamChannels(config) { expiryText = it }
                    "stalker" -> fetchStalkerChannels(config)
                    else -> fetchM3uChannels(config)
                }
                when (result) {
                    is FetchResult.Success -> combined += result.channels
                    is FetchResult.Failure -> errors += "${config.name}: ${result.message}"
                }
            }
            if (hasJellyfinConfigured() && isJellyfinEnabled()) {
                setStatus("Connecting to Jellyfin...", visible = true)
                when (val result = fetchJellyfinChannels()) {
                    is FetchResult.Success -> combined += result.channels
                    is FetchResult.Failure -> errors += result.message
                }
            }

            // Anime catalog: only fetched when a stream_search plugin is installed & enabled.
            // The plugin handles stream resolution; this provides the browse-layer catalog.
            if (enabledStreamSearchPlugin() != null) {
                // On Dispatchers.IO: fetchAnimeChannels does synchronous OkHttp calls, and this
                // loader coroutine runs on Main - calling it directly threw NetworkOnMainThreadException
                // (caught, so anime silently came back empty every time).
                val animeChannels = withContext(Dispatchers.IO) { fetchAnimeChannels() }
                if (animeChannels.isNotEmpty()) {
                    combined += animeChannels
                }
            }

            allChannels = combined
            classifyAndShow()
            withContext(Dispatchers.IO) { ChannelCache.save(this@MainActivity, allChannels) }
            // Only a load that actually produced a catalog resets the TTL - stamping it on a
            // total failure would leave the app sitting on an empty catalog for 12 hours.
            if (combined.isNotEmpty()) {
                prefs.edit().putLong(PREF_CATALOG_REFRESHED_AT, System.currentTimeMillis()).apply()
            }

            if (combined.isEmpty()) {
                // Don't raise the status row here - it lives in the same weight=1 slot as the
                // empty state, so showing both splits the screen in half and the relayout
                // steals focus off the empty-state buttons. The empty state is the message;
                // surface any real fetch errors as a toast instead of a persistent bar.
                setStatus("", visible = false)
                if (errors.isNotEmpty()) {
                    Toast.makeText(this@MainActivity, errors.joinToString(" · "), Toast.LENGTH_LONG).show()
                }
            } else {
                val summary = "${combined.size} items" +
                    (expiryText?.let { "  ·  $it" } ?: "") +
                    (errors.takeIf { it.isNotEmpty() }?.let { "  ·  ⚠ " + it.joinToString(", ") } ?: "")
                setStatus(summary, visible = true)
                if (errors.isEmpty()) mainHandler.postDelayed({ setStatus("", visible = false) }, 4000)
            }
        }
    }

    private suspend fun fetchStalkerChannels(config: IptvProviderConfig): FetchResult {
        return try {
            val mac = config.userAgent ?: return FetchResult.Failure("no MAC address")
            val stalkerProvider = Provider(
                name = config.name, type = ProviderType.M3U,
                serverUrl = config.url?.let { normalizeServerUrl(it) }, userAgent = mac
            )
            val stalker = StalkerProvider(BaseApplication.instance.okHttpClient)
            val result = withContext(Dispatchers.IO) { stalker.loadContent(stalkerProvider) }
            if (result.isFailure) return FetchResult.Failure(result.exceptionOrNull()?.message?.take(60) ?: "error")
            val content = result.getOrThrow()
            // sourceProviderId ties each item back to this portal config, so the play step
            // can re-auth against the right one to resolve a Stalker VOD create_link.
            FetchResult.Success((content.live + content.films + content.series).map {
                it.copy(streamUserAgent = mac, sourceProviderId = config.id)
            })
        } catch (e: Exception) {
            FetchResult.Failure(e.message?.take(60) ?: "error")
        }
    }

    /** The configured Jellyfin server URL, normalized, or null when the slot is empty. */
    private fun jellyfinServerUrl(): String? =
        prefs.getString("jellyfin_url", null)?.takeIf { it.isNotBlank() }
            ?.let { normalizeServerUrl(it, defaultScheme = "https") }

    /** toChannel() only reads serverUrl off a Provider - a minimal stand-in instead of the
     *  shared `provider` field, which now belongs solely to the IPTV slots. Passing that
     *  field here builds episode stream URLs against the *Xtream* host (or an empty one when
     *  Jellyfin is the only provider configured), which is unplayable. */
    private fun jellyfinProviderStub(url: String?): Provider =
        Provider(name = "Jellyfin", type = ProviderType.M3U, serverUrl = url)

    /** Authenticates (or restores) a Jellyfin session against [url]. Failure message is
     *  already user-facing. */
    private suspend fun connectJellyfin(url: String): Result<JellyfinProvider> {
        val jellyfin = JellyfinProvider(BaseApplication.instance.okHttpClient)
        val savedToken = prefs.getString("jellyfin_token", null)
        val savedUserId = prefs.getString("jellyfin_userid", null)
        if (!savedToken.isNullOrBlank() && !savedUserId.isNullOrBlank()) {
            // Quick Connect never yields a password to re-authenticate with later -
            // reuse the session it already gave us instead.
            jellyfin.restoreSession(url, savedToken, savedUserId)
        } else {
            val username = prefs.getString("jellyfin_user", null)
                ?: return Result.failure(Exception("Jellyfin: no username"))
            val password = prefs.getString("jellyfin_pass", null).orEmpty()
            val authResult = withContext(Dispatchers.IO) { jellyfin.authenticate(url, username, password) }
            if (authResult.isFailure) {
                return Result.failure(Exception("Jellyfin: ${authResult.exceptionOrNull()?.message?.take(60)}"))
            }
        }
        return Result.success(jellyfin)
    }

    /** The live Jellyfin session, reconnecting on demand. A cold start that hits the channel
     *  cache returns from loadAllConfiguredProviders() before any Jellyfin fetch runs, so
     *  jellyfinClient is null while Jellyfin series are already on screen - without this a
     *  series detail page silently showed "No episodes found" on every cached launch. */
    private suspend fun jellyfinClientOrConnect(): JellyfinProvider? {
        jellyfinClient?.let { return it }
        val url = jellyfinServerUrl() ?: return null
        return connectJellyfin(url).getOrNull()?.also { jellyfinClient = it }
    }

    /** Kept alive post-load for fetching a Jellyfin series' episodes when its detail page
     *  opens - that has no Xtream equivalent path to fall back to. */
    private suspend fun fetchJellyfinChannels(): FetchResult {
        val url = jellyfinServerUrl() ?: return FetchResult.Failure("Jellyfin: no server URL")
        return try {
            val jellyfin = connectJellyfin(url).getOrElse {
                return FetchResult.Failure(it.message ?: "Jellyfin: auth failed")
            }
            val stub = jellyfinProviderStub(url)
            val items: List<Channel> = withContext(Dispatchers.IO) {
                val liveItems = jellyfin.getLiveTvChannels().map { JellyfinProvider.toChannel(it, stub) }
                val movies = jellyfin.getMovies()
                val series = jellyfin.getSeries()
                importJellyfinUserState(movies + series)
                liveItems +
                    movies.map { JellyfinProvider.toChannel(it, stub) } +
                    series.map { JellyfinProvider.toChannel(it, stub) }
            }
            jellyfinClient = jellyfin
            refreshJellyfinRows(jellyfin, stub)
            FetchResult.Success(items)
        } catch (e: Exception) {
            FetchResult.Failure("Jellyfin: ${e.message?.take(60)}")
        }
    }

    // ── Anime catalog (gated on plugin) ─────────────

    /**
     * Fetches trending anime from the public anime database. Returns Channel objects
     * (mediaType=SERIES) that populate the Series section. Playback is handled by the
     * stream_search plugin — these channels have no direct URL, just metadata for browsing.
     */
    private fun fetchAnimeChannels(): List<Channel> {
        return try {
            val client = AnimeCatalogClient(BaseApplication.instance.okHttpClient)
            val catalog = client.fetchCatalog()
            // Sections are only meaningful alongside the channels they point at - a stale set
            // left over from a previous load would build sidebar rows whose ids aren't in the
            // tab any more, so it's replaced (or cleared) on every fetch.
            animeSections = catalog.sections
            catalog.channels
        } catch (e: Exception) {
            animeSections = emptyList()
            emptyList()
        }
    }

    // ── Jellyfin server-side state sync ────────────

    /**
     * Pulls the server's per-user state (UserData) into the local stores, so watched marks
     * and resume points made in *any* Jellyfin client show up here. Without this the app
     * treated a personal media server like a plain catalogue: every title looked unwatched
     * no matter what had already been seen elsewhere.
     *
     * Local progress is only overwritten when the server is *ahead* (or when nothing local
     * exists). A resume point written here and not yet reported - the app was offline, or the
     * report failed - is still the more recent truth, and clobbering it would rewind the
     * user to where the server last heard about.
     */
    private fun importJellyfinUserState(
        items: List<JellyfinProvider.JellyfinItem>,
        includePlayed: Boolean = false
    ) {
        val stub = jellyfinProviderStub(jellyfinServerUrl())
        for (item in items) {
            if (item.mediaType == "Series") {
                // Favourites are reconciled to the server both ways for Jellyfin items:
                // un-favouriting on the server has to be able to clear the local star too,
                // which a toggle-only import could never do.
                FavoritesStore.setFavoriteSeries(this, item.id, item.favorite)
                continue
            }
            val runtime = item.runtimeMs ?: continue
            when {
                // Watched, not cleared: a full-duration entry is exactly what EpisodeAdapter
                // reads as "watched" (PlaybackPosition.isNearComplete) to dim the row and show
                // its badge, and getAllInProgress excludes it from Continue Watching for the
                // same reason. Clearing it instead would leave a watched episode looking
                // untouched.
                //
                // Only imported where something actually renders it (an episode list), because
                // the position store holds 500 entries and evicts the oldest: seeding a watched
                // mark for every film in a large library would push out the resume points that
                // Continue Watching is built from, to show a badge nothing displays.
                item.played && includePlayed -> PlaybackPositionStore.save(
                    this,
                    item.id,
                    runtime,
                    runtime,
                    JellyfinProvider.toChannel(item, stub, prefixSeriesName = true)
                )
                item.resumePositionMs > 0 -> {
                    val local = PlaybackPositionStore.get(this, item.id)
                    if (local == null || item.resumePositionMs > local.positionMs) {
                        PlaybackPositionStore.save(
                            this,
                            item.id,
                            item.resumePositionMs,
                            runtime,
                            JellyfinProvider.toChannel(item, stub, prefixSeriesName = true)
                        )
                    }
                }
            }
        }
    }

    /** The server's own Resume and Next Up lists, for the Home rows. Also seeds resume
     *  positions for the items in them - these are the partly-watched titles, so they carry
     *  the positions worth having even when the catalog fetch didn't include them. */
    private suspend fun refreshJellyfinRows(jellyfin: JellyfinProvider, stub: Provider) {
        val (resume, nextUp) = withContext(Dispatchers.IO) {
            jellyfin.getResumeItems() to jellyfin.getNextUp()
        }
        importJellyfinUserState(resume)
        jellyfinResumeItems = resume.map { JellyfinProvider.toChannel(it, stub, prefixSeriesName = true) }
        jellyfinNextUpItems = nextUp.map { JellyfinProvider.toChannel(it, stub, prefixSeriesName = true) }
    }

    // ── Jellyfin playback reporting ────────────────

    /** Media3 mime type for a Jellyfin subtitle codec, or null for the image-based formats
     *  (PGS/VOBSUB) that have no Media3 renderer - those are left to the server to burn in,
     *  never sideloaded as a track that would silently render nothing. */
    private fun subtitleMimeType(codec: String?): String? = when (codec?.lowercase()) {
        "vtt", "webvtt" -> androidx.media3.common.MimeTypes.TEXT_VTT
        "srt", "subrip" -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
        "ass", "ssa" -> androidx.media3.common.MimeTypes.TEXT_SSA
        "ttml" -> androidx.media3.common.MimeTypes.APPLICATION_TTML
        // The server hands extracted text tracks over as WebVTT regardless of their original
        // codec, so anything else that came back with a URL is treated as VTT.
        else -> androidx.media3.common.MimeTypes.TEXT_VTT
    }

    private fun externalSubtitlesFor(resolved: JellyfinProvider.ResolvedStream): List<PlayerManager.ExternalSubtitle> =
        resolved.subtitles.mapNotNull { stream ->
            val url = stream.url ?: return@mapNotNull null
            val mime = subtitleMimeType(stream.codec) ?: return@mapNotNull null
            PlayerManager.ExternalSubtitle(
                uri = url,
                mimeType = mime,
                language = stream.language,
                label = stream.title ?: stream.language,
                isDefault = stream.isDefault,
                isForced = stream.isForced
            )
        }

    /** A plugin's sidecar subtitle URL carries no codec metadata the way a Jellyfin MediaStream
     *  does, so the format is taken from the file extension (query string stripped - these URLs
     *  are often signed). WebVTT is the fallback: it's what every source seen so far serves. */
    private fun externalSubtitleFor(subtitle: PluginSubtitle): PlayerManager.ExternalSubtitle {
        val path = subtitle.url.substringBefore('?').substringBefore('#')
        val mime = when {
            path.endsWith(".srt", ignoreCase = true) -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
            path.endsWith(".ass", ignoreCase = true) ||
                path.endsWith(".ssa", ignoreCase = true) -> androidx.media3.common.MimeTypes.TEXT_SSA
            path.endsWith(".ttml", ignoreCase = true) ||
                path.endsWith(".xml", ignoreCase = true) -> androidx.media3.common.MimeTypes.APPLICATION_TTML
            else -> androidx.media3.common.MimeTypes.TEXT_VTT
        }
        return PlayerManager.ExternalSubtitle(
            uri = subtitle.url,
            mimeType = mime,
            language = subtitle.language,
            label = subtitle.label ?: subtitle.language,
            isDefault = subtitle.isDefault
        )
    }

    /** Reports a Jellyfin play as started, so the server opens a session for it (and knows
     *  not to reap the transcode it just set up). */
    private fun reportJellyfinStart(itemId: String, resolved: JellyfinProvider.ResolvedStream?, positionMs: Long) {
        val client = jellyfinClient ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                client.reportPlaybackStart(
                    itemId,
                    resolved?.playSessionId,
                    positionMs,
                    resolved?.playMethod ?: "DirectPlay"
                )
            }
        }
    }

    /** Progress heartbeat for the Jellyfin item playing, if any. Called off the same 1s
     *  progress tick the local position save uses, throttled to ~10s. */
    private fun reportJellyfinProgress() {
        val itemId = jellyfinPlayingItemId ?: return
        val client = jellyfinClient ?: return
        val position = playerManager.currentPosition.takeIf { it >= 0 } ?: return
        val paused = !playerManager.isPlaying
        val session = jellyfinPlaySession
        scope.launch(Dispatchers.IO) {
            runCatching {
                client.reportPlaybackProgress(itemId, session?.playSessionId, position, paused, session?.playMethod ?: "DirectPlay")
            }
        }
    }

    /** End of a Jellyfin play. The position reported here is what the server turns into a
     *  watched mark or a resume point, so this runs before the player state is torn down. */
    private fun reportJellyfinStopped(): Boolean {
        val itemId = jellyfinPlayingItemId ?: return false
        val client = jellyfinClient
        val session = jellyfinPlaySession
        val position = playerManager.currentPosition.takeIf { it >= 0 } ?: 0L
        jellyfinPlayingItemId = null
        jellyfinPlaySession = null
        jellyfinChapters = emptyList()
        jellyfinTrickplay = null
        trickplayTileCache = null
        trickplayLoadJob?.cancel()
        if (client == null) return false
        scope.launch(Dispatchers.IO) {
            runCatching { client.reportPlaybackStopped(itemId, session?.playSessionId, position) }
        }
        return true
    }

    /** Re-pulls Resume/Next Up after a Jellyfin play ends, so finishing an episode advances
     *  the Next Up row instead of leaving it stale until the next catalog reload. Waits a
     *  beat first - the lists are derived from the stop we just reported, and asking before
     *  the server has recorded it hands back the pre-play state. */
    private fun refreshJellyfinRowsAfterPlayback() {
        val client = jellyfinClient ?: return
        scope.launch {
            delay(1500)
            val stub = jellyfinProviderStub(jellyfinServerUrl())
            runCatching { refreshJellyfinRows(client, stub) }
            if (showingHome) homeShelfAdapter.submitList(buildHomeShelves())
        }
    }

    /** Chapter markers and trickplay tiles for the Jellyfin item now playing - both are
     *  per-item and only ever needed for the one title on screen, so they're fetched at play
     *  time rather than carried on every catalog entry. */
    private fun loadJellyfinPlaybackExtras(itemId: String) {
        val client = jellyfinClient ?: return
        scope.launch {
            val (chapters, trickplay) = withContext(Dispatchers.IO) {
                runCatching { client.getChapters(itemId) }.getOrDefault(emptyList()) to
                    runCatching { client.getTrickplay(itemId) }.getOrNull()
            }
            if (jellyfinPlayingItemId != itemId) return@launch
            jellyfinChapters = chapters
            jellyfinTrickplay = trickplay
            updateChaptersButtonVisibility()
        }
    }

    private fun updateChaptersButtonVisibility() {
        binding.btnChapters.visibility = if (jellyfinChapters.size > 1) View.VISIBLE else View.GONE
    }

    /** Chapter picker - jumps straight to a chapter's start. Only reachable when the item
     *  actually has chapters (see updateChaptersButtonVisibility). */
    private fun showChapterPicker() {
        val chapters = jellyfinChapters
        if (chapters.isEmpty()) return
        val position = playerManager.currentPosition
        val currentIdx = chapters.indexOfLast { it.positionMs <= position }.coerceAtLeast(0)
        val labels = chapters.mapIndexed { index, chapter ->
            val marker = if (index == currentIdx) "▶ " else ""
            "$marker${chapter.name}  ·  ${formatTime(chapter.positionMs)}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Chapters")
            .setItems(labels) { _, which ->
                playerManager.seekTo(chapters[which].positionMs)
                showControls()
            }
            .show()
    }

    /** Seek-preview thumbnail from the trickplay sprite sheets, shown while scrubbing a
     *  Jellyfin item. Sheets are downloaded whole and cropped locally - one sheet covers
     *  ~100 thumbnails, so scrubbing within it costs nothing after the first fetch. */
    private fun showTrickplayPreview(targetMs: Long) {
        val info = jellyfinTrickplay ?: return
        val itemId = jellyfinPlayingItemId ?: return
        val client = jellyfinClient ?: return
        val thumbIndex = (targetMs / info.intervalMs).toInt().coerceAtLeast(0)
        if (info.thumbnailCount > 0 && thumbIndex >= info.thumbnailCount) return
        val tileIndex = thumbIndex / info.perTile
        val withinTile = thumbIndex % info.perTile

        fun render(sheet: android.graphics.Bitmap) {
            val cellWidth = sheet.width / info.tileWidth.coerceAtLeast(1)
            val cellHeight = sheet.height / info.tileHeight.coerceAtLeast(1)
            if (cellWidth <= 0 || cellHeight <= 0) return
            val col = withinTile % info.tileWidth.coerceAtLeast(1)
            val row = withinTile / info.tileWidth.coerceAtLeast(1)
            val x = col * cellWidth
            val y = row * cellHeight
            if (x + cellWidth > sheet.width || y + cellHeight > sheet.height) return
            val crop = runCatching {
                android.graphics.Bitmap.createBitmap(sheet, x, y, cellWidth, cellHeight)
            }.getOrNull() ?: return
            binding.trickplayPreview.setImageBitmap(crop)
            binding.trickplayPreview.visibility = View.VISIBLE
        }

        trickplayTileCache?.takeIf { it.first == tileIndex }?.let { render(it.second); return }

        trickplayLoadJob?.cancel()
        trickplayLoadJob = scope.launch {
            val url = client.trickplayTileUrl(itemId, info, tileIndex) ?: return@launch
            val sheet = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder().url(url).build()
                    BaseApplication.instance.okHttpClient.newCall(request).execute()
                        .body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            } ?: return@launch
            if (jellyfinPlayingItemId != itemId) return@launch
            trickplayTileCache = tileIndex to sheet
            render(sheet)
        }
    }

    private fun hideTrickplayPreview() {
        trickplayLoadJob?.cancel()
        binding.trickplayPreview.visibility = View.GONE
        binding.trickplayPreview.setImageDrawable(null)
    }

    private suspend fun fetchM3uChannels(config: IptvProviderConfig): FetchResult {
        val url = config.url ?: return FetchResult.Failure("no URL")
        return try {
            val result = withContext(Dispatchers.IO) { M3uParser.parseFromUrl(url, BaseApplication.instance.okHttpClient) }
            // sourceProviderId isn't needed for playback here (an M3U item's url is already
            // final), but it's what names the provider a duplicate came from on the detail
            // screen's version chips - without it every M3U copy is an anonymous "Version N".
            FetchResult.Success(result.channels.map { it.copy(streamUserAgent = config.userAgent, sourceProviderId = config.id) })
        } catch (e: Exception) {
            FetchResult.Failure(e.message?.take(60) ?: "error")
        }
    }

    private suspend fun fetchXtreamChannels(config: IptvProviderConfig, onExpiry: (String?) -> Unit): FetchResult {
        return try {
            val xtreamProvider = Provider(
                name = config.name, type = ProviderType.XTREAM,
                serverUrl = config.url?.let { normalizeServerUrl(it) },
                username = config.username, password = config.password
            )
            val client = XtreamClient(BaseApplication.instance.okHttpClient)
            val authResult = withContext(Dispatchers.IO) { client.authenticate(xtreamProvider) }
            val auth = authResult.getOrElse { return FetchResult.Failure(it.message?.take(60) ?: "auth error") }
            if (!auth.valid) return FetchResult.Failure("auth failed - check server URL and credentials")
            // Remembered for EPG lookups and the subscription-expiry line, both inherently
            // "the" Xtream account concepts - first enabled Xtream provider wins if there's
            // more than one configured.
            provider = xtreamProvider

            val live: List<Channel>
            val films: List<Channel>
            val series: List<Channel>
            withContext(Dispatchers.IO) {
                val liveCatsDeferred = async { runCatching { client.getLiveCategories(xtreamProvider) }.getOrDefault(emptyList()) }
                val vodCatsDeferred = async { runCatching { client.getVodCategories(xtreamProvider) }.getOrDefault(emptyList()) }
                val seriesCatsDeferred = async { runCatching { client.getSeriesCategories(xtreamProvider) }.getOrDefault(emptyList()) }
                val liveDeferred = async { client.getLiveStreams(xtreamProvider) }
                val filmsDeferred = async { client.getVodStreams(xtreamProvider) }
                val seriesDeferred = async { client.getSeries(xtreamProvider) }

                val liveCatNames = liveCatsDeferred.await().toMap()
                val vodCatNames = vodCatsDeferred.await().toMap()
                val seriesCatNames = seriesCatsDeferred.await().toMap()

                // Resolve each stream's category name from the authoritative category list.
                // get_vod_streams (and often get_live_streams) carries only a numeric category_id
                // and no category_name, and some panels tag streams with category_ids that never
                // appear in get_*_categories at all - 860 VOD items on one live provider. Left
                // as-is those rendered as a sidebar row literally titled "1411"/"1071". When the id
                // can't be resolved and the stream has no name of its own, fold it into a single
                // "Uncategorised" row (shared id) rather than one bare-number row per orphan id.
                fun withCategory(ch: Channel, names: Map<String, String>, uncatId: String): Channel {
                    val resolved = names[ch.categoryId]
                    val mapped = when {
                        resolved != null -> ch.copy(categoryName = resolved)
                        !ch.categoryName.isNullOrBlank() -> ch
                        else -> ch.copy(categoryId = uncatId, categoryName = "Uncategorised")
                    }
                    return mapped.copy(sourceProviderId = config.id)
                }
                live = liveDeferred.await().map { withCategory(it, liveCatNames, "uncat_live") }
                films = filmsDeferred.await().map { withCategory(it, vodCatNames, "uncat_vod") }
                series = seriesDeferred.await().map { withCategory(it, seriesCatNames, "uncat_series") }
            }

            onExpiry(formatSubscriptionStatus(auth.expDateSeconds, auth.isTrial))
            FetchResult.Success(live + films + series)
        } catch (e: Exception) {
            FetchResult.Failure(e.message?.take(60) ?: "error")
        }
    }

    /** Human-readable Xtream subscription status, or null if the provider doesn't report an expiry. */
    private fun formatSubscriptionStatus(expDateSeconds: Long?, isTrial: Boolean): String? {
        if (expDateSeconds == null || expDateSeconds <= 0) return null
        val fmt = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
        val dateStr = fmt.format(java.util.Date(expDateSeconds * 1000))
        val nowSeconds = System.currentTimeMillis() / 1000
        val trialPrefix = if (isTrial) "Trial · " else ""
        return if (expDateSeconds < nowSeconds) "⚠ $trialPrefix Expired $dateStr" else "$trialPrefix Active until $dateStr"
    }

    private data class DerivedContent(
        val liveChannels: List<Channel>,
        val liveVersions: Map<String, List<Channel>>,
        val filmList: List<Channel>,
        val filmVersions: Map<String, List<Channel>>,
        val filmShelves: List<ContentShelf>,
        val seriesList: List<Channel>,
        val seriesVersions: Map<String, List<Channel>>,
        val seriesShelves: List<ContentShelf>
    )

    /**
     * Filtering, dedup-grouping, sorting, and shelf-building over the whole catalog
     * (tens of thousands of items on a big provider) is real CPU work - it must run
     * off the main thread or the UI stalls/looks hung on every load and refresh.
     */
    private suspend fun classifyAndShow() {
        val snapshot = allChannels
        val hideNonEnglish = prefs.getBoolean(PREF_HIDE_NON_ENGLISH, true)
        val hideAdult = prefs.getBoolean(PREF_HIDE_ADULT, true)
        val derived = withContext(Dispatchers.Default) { computeDerivedContent(snapshot, hideNonEnglish, hideAdult) }

        liveChannels = derived.liveChannels
        liveVersions = derived.liveVersions
        filmList = derived.filmList
        filmVersions = derived.filmVersions
        filmShelves = derived.filmShelves
        seriesList = derived.seriesList
        seriesVersions = derived.seriesVersions
        seriesShelves = derived.seriesShelves

        // Discover (TMDB browsing) and any stream_search plugin's Find Stream flow need no
        // provider-sourced channels at all - gating the whole content area on allChannels alone
        // trapped a plugin-only setup (e.g. just torrent-search enabled, which contributes no
        // catalog entries) behind the "No provider configured" empty state for no reason.
        val hasContent = allChannels.isNotEmpty() || enabledStreamSearchPlugin() != null

        // Adapters are safe to (re)bind under an open overlay - only the visible screen
        // must not be, since it lives behind the overlay. Bind first, then swap visibility
        // only when nothing is covering the content.
        if (hasContent) {
            binding.liveContent.adapter = liveAdapter
            binding.seriesContent.adapter = seriesShelfAdapter
            binding.filmsContent.adapter = filmsShelfAdapter
            binding.categorySidebar.adapter = categoryAdapter
            binding.homeContent.adapter = homeShelfAdapter
            seriesShelfAdapter.submitList(seriesShelves)
            filmsShelfAdapter.submitList(filmShelves)
        }

        // A settings/search overlay owns the whole content slot while it's up. Toggling a
        // provider off in Settings reloads through here, and flipping the empty state and
        // chrome underneath the overlay is what left "half the settings menu" showing when
        // it closed. Defer that to the overlay's own dismiss handler (selectHome/selectTab),
        // which re-derives all of it against the now-current state.
        if (activeSettingsOverlay != null || activeSearchOverlay != null) return

        if (hasContent) {
            binding.emptyState.visibility = View.GONE
            binding.contentRow.visibility = View.VISIBLE
            updateTopChromeVisibility()
            if (showingHome) selectHome() else if (showingDiscover) selectDiscover() else selectTab(activeTab)
            resumeLastLiveChannelIfPending()
        } else {
            showEmptyState()
        }
    }

    /** Persists what to reopen on next launch: the channel *and* the exact version of it
     *  that's playing. Written on every tune and every version switch rather than at exit,
     *  since the process can be killed outright from the launcher with no further callback.
     *  Skips adult channels - they shouldn't auto-reopen on next launch. */
    private fun rememberLastLiveTune(channel: Channel) {
        if (isAdultCategory(channel.categoryName, channel.group)) return
        prefs.edit()
            .putString(PREF_LAST_LIVE_CHANNEL, channel.id)
            .putString(PREF_LAST_LIVE_VERSION, currentVersionGroup.getOrNull(currentVersionIndex)?.id)
            .apply()
    }

    /** Reopens whatever live channel was playing when the app was last closed, once the
     *  catalog it lives in is actually loaded. Looks through the version lists too, since a
     *  channel that was tuned from a dynamic/brand row can be a non-representative copy that
     *  never appears in [liveChannels] itself.
     *  Skips adult channels so they don't auto-resume on next launch. */
    private fun resumeLastLiveChannelIfPending() {
        val id = pendingLiveResumeId ?: return
        pendingLiveResumeId = null
        if (isPlayerVisible) return
        val channel = liveChannels.firstOrNull { it.id == id }
            ?: liveVersions.values.firstNotNullOfOrNull { versions -> versions.firstOrNull { it.id == id } }
            ?: return
        if (isAdultCategory(channel.categoryName, channel.group)) return
        selectTab(0)
        currentIndex = liveChannels.indexOf(channel)
        // Stash the resumed channel so hidePlayer() can return to it rather than
        // the first channel in the list (which applyCategoryFilter with
        // focusFirstLiveChannel true just loaded into the live preview).
        lastFocusedLiveChannel = channel
        showPlayerFor(channel, preferredVersionId = prefs.getString(PREF_LAST_LIVE_VERSION, null))
    }

    private fun computeDerivedContent(allChannels: List<Channel>, hideNonEnglish: Boolean, hideAdult: Boolean): DerivedContent {
        fun isAdult(ch: Channel) = hideAdult && isAdultCategory(ch.categoryName, ch.group)

        val rawLive = allChannels.filter { it.mediaType == MediaType.LIVE && !it.name.contains("##") }
            .filterNot { isAdult(it) }
        val useClassic = prefs.getBoolean(PREF_CLASSIC_CATEGORY_LAYOUT, false)
        val (groupedLive, liveVers) = if (useClassic) {
            // Classic: no quality version merging — show every channel as-is from the provider.
            // Version map is empty since every variant appears as its own channel entry.
            rawLive to emptyMap()
        } else {
            groupLiveQualityVersions(rawLive)
        }

        val rawFilms = allChannels.filter { it.mediaType == MediaType.MOVIE }
            .filterNot { hideNonEnglish && isNonEnglishTitle(it.name) }
            .filterNot { isAdult(it) }
            .map { it.withResolvedYear() }
        val (groupedFilms, versions) = groupDuplicateMovies(rawFilms)
        val films = groupedFilms.sortedByDescending { it.year?.toIntOrNull() ?: -1 }
        // "Newest" pools the most recent releases (by date, not rating) into one shelf
        // pinned at the top, sorted by release date descending regardless of category.
        val newestFilms = newestByDate(films)
        val filmShelvesLocal = buildShelves(films, tab = 2).let { shelves ->
            jellyfinShelf(films, versions, tab = 2)?.let { listOf(it) + shelves } ?: shelves
        }.let { shelves ->
            if (newestFilms.isEmpty()) shelves else listOf(ContentShelf("Newest", newestFilms)) + shelves
        }

        val rawSeries = allChannels.filter { it.mediaType == MediaType.SERIES }
            .filterNot { hideNonEnglish && isNonEnglishTitle(it.name) }
            .filterNot { isAdult(it) }
            .map { it.withResolvedYear() }
        // Real release date (from the provider's bulk series list) sorts more precisely
        // than year alone; falls back to year for anything that came back without one.
        val (groupedSeries, seriesVers) = groupDuplicateSeries(rawSeries)
        val series = groupedSeries
            .sortedWith(compareByDescending<Channel> { it.releaseDate ?: "" }.thenByDescending { it.year?.toIntOrNull() ?: -1 })
        // Favourited series get their own shelf pinned above everything else in the
        // Series tab itself, not just on Home.
        val favoriteSeriesIds = FavoritesStore.getFavoriteSeriesIds(this)
        val favoriteSeries = series.filter { it.id in favoriteSeriesIds }
        val newestSeries = newestByDate(series)
        val seriesShelvesLocal = buildShelves(series, tab = 1).let { shelves ->
            jellyfinShelf(series, seriesVers, tab = 1)?.let { listOf(it) + shelves } ?: shelves
        }.let { shelves ->
            (if (newestSeries.isEmpty()) shelves else listOf(ContentShelf("Newest", newestSeries)) + shelves)
        }.let { shelves ->
            if (favoriteSeries.isEmpty()) shelves else listOf(ContentShelf("Favourites", favoriteSeries)) + shelves
        }

        return DerivedContent(groupedLive, liveVers, films, versions, filmShelvesLocal, series, seriesVers, seriesShelvesLocal)
    }

    /** The "Jellyfin" shelf for a Series/Films tab - the personal library browsed as a
     *  library, sitting directly under "Newest" while its titles stay merged into the
     *  genre/provider shelves below like any other item.
     *
     *  Matches on any version in a deduped group, not just the representative's own
     *  isJellyfin flag: a title both the library and an IPTV provider carry is one card,
     *  and the copy that wins it is whichever had a poster - often the IPTV one - so flag-
     *  only matching drops library titles out of the shelf. Same reasoning as the sidebar's
     *  Jellyfin row (see JELLYFIN_CATEGORY_ID), and it honours the same hidden-shelf pref. */
    private fun jellyfinShelf(
        list: List<Channel>,
        versionsById: Map<String, List<Channel>>,
        tab: Int
    ): ContentShelf? {
        if ("Jellyfin" in getHiddenCategories(tab)) return null
        val items = list.filter { ch ->
            ch.isJellyfin || versionsById[ch.id]?.any { it.isJellyfin } == true
        }
        return if (items.isEmpty()) null else ContentShelf("Jellyfin", items)
    }

    /**
     * Groups already-sorted content into category-based shelves for Series/Films, in the
     * same blocks the sidebar uses: genre buckets, then the provider's own categories
     * biggest-first. Pinned categories stay above all of it.
     *
     * A raw category joins exactly one shelf, so nothing is double-listed: pinned wins,
     * then genre, then it stays as itself.
     */
    private fun buildShelves(list: List<Channel>, tab: Int): List<ContentShelf> {
        val pinned = getPinnedCategories(tab)
        val hidden = getHiddenCategories(tab)
        val groups = LinkedHashMap<String, MutableList<Channel>>()
        for (ch in list) {
            val key = ch.categoryName?.takeIf { it.isNotBlank() } ?: ch.group?.takeIf { it.isNotBlank() } ?: "Other"
            if (key in hidden) continue
            groups.getOrPut(key) { mutableListOf() }.add(ch)
        }

        fun genreFor(name: String): String? {
            val lower = name.lowercase()
            return VOD_DYNAMIC_BUCKETS.firstOrNull { (_, keywords) -> keywords.any { lower.contains(it) } }?.first
        }

        val pinnedShelves = mutableListOf<ContentShelf>()
        val brandGroups = LinkedHashMap<String, MutableList<Channel>>()
        val genreGroups = LinkedHashMap<String, MutableList<Channel>>()
        val plainShelves = mutableListOf<ContentShelf>()
        for ((title, items) in groups) {
            val genre = genreFor(title)
            when {
                title in pinned -> pinnedShelves.add(ContentShelf(title, items, pinned = true))
                genre != null -> genreGroups.getOrPut(genre) { mutableListOf() }.addAll(items)
                else -> plainShelves.add(ContentShelf(title, items))
            }
        }

        val brandShelves = brandGroups.entries.sortedBy { it.key.lowercase() }
            .map { ContentShelf(it.key.uppercase(), it.value, pinned = it.key.uppercase() in pinned) }
        // Bucket declaration order, not size - it's a fixed, familiar list of genres.
        val genreShelves = VOD_DYNAMIC_BUCKETS.mapNotNull { (label, _) ->
            genreGroups[label]?.let { ContentShelf(label.uppercase(), it, pinned = label.uppercase() in pinned) }
        }
        // The hidden check above is keyed on raw category names, so it can't catch a
        // brand/genre shelf being hidden by its own (synthesised) title - filter those here.
        return (pinnedShelves.sortedByDescending { it.items.size } +
            brandShelves + genreShelves +
            plainShelves.sortedByDescending { it.items.size })
            .filterNot { it.title in hidden }
    }

    // ── Downloads (mobile only) ────────────────────

    /** Kicks off a system DownloadManager job for a movie or single episode; no-op if already queued/downloaded. */
    private fun downloadItem(channel: Channel) {
        if (channel.id.isBlank() || channel.url.isBlank()) return
        if (DownloadStore.get(this, channel.id) != null) {
            Toast.makeText(this, "Already in Downloads", Toast.LENGTH_SHORT).show()
            return
        }
        VodDownloader.enqueue(this, channel)
        Toast.makeText(this, "Downloading \"${channel.name}\"", Toast.LENGTH_SHORT).show()
        if (showingDownloads) refreshDownloadsList()
    }

    private fun playDownload(record: DownloadRecord) {
        if (record.status != DownloadStatus.COMPLETE || record.localFilePath.isNullOrBlank()) {
            Toast.makeText(this, "Still downloading…", Toast.LENGTH_SHORT).show()
            return
        }
        val local = Channel(
            id = record.id,
            name = record.title,
            url = "file://${record.localFilePath}",
            posterUrl = record.posterUrl,
            mediaType = runCatching { MediaType.valueOf(record.mediaType) }.getOrDefault(MediaType.MOVIE)
        )
        currentIndex = -1
        showPlayerFor(local)
    }

    private fun deleteDownload(record: DownloadRecord) {
        AlertDialog.Builder(this)
            .setTitle("Delete download?")
            .setMessage("\"${record.title}\" will be removed from this device.")
            .setPositiveButton("Delete") { _, _ ->
                VodDownloader.delete(this, record)
                refreshDownloadsList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshDownloadsList() {
        scope.launch {
            val records = withContext(Dispatchers.IO) {
                DownloadStore.getAll(this@MainActivity).map { rec ->
                    if (rec.status == DownloadStatus.COMPLETE) rec else VodDownloader.refreshStatus(this@MainActivity, rec)
                }
            }
            downloadAdapter.submitList(records)
            binding.downloadsEmptyText.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
            binding.downloadsContent.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    // ── Categories ─────────────────────────────────

    /** A channel's filter key: Xtream category id, or M3U group name as a fallback.
     *  Falls back to categoryName as a last resort so channels always have a category
     *  to group under, even when the provider doesn't assign a numeric category id. */
    private fun Channel.filterKey(): String? =
        categoryId?.takeIf { it.isNotBlank() }
            ?: group?.takeIf { it.isNotBlank() }
            ?: categoryName?.takeIf { it.isNotBlank() }

    private fun activeFullList(): List<Channel> = when (activeTab) {
        0 -> liveChannels
        1 -> seriesList
        2 -> filmList
        else -> emptyList()
    }

    private fun pinnedCategoriesPrefsKey(tab: Int = activeTab): String = when (tab) {
        0 -> "pinned_categories_live"
        1 -> "pinned_categories_series"
        else -> "pinned_categories_films"
    }

    private fun getPinnedCategories(tab: Int = activeTab): MutableSet<String> =
        prefs.getStringSet(pinnedCategoriesPrefsKey(tab), emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun hiddenCategoriesPrefsKey(tab: Int = activeTab): String = when (tab) {
        0 -> "hidden_categories_live"
        1 -> "hidden_categories_series"
        else -> "hidden_categories_films"
    }

    private fun getHiddenCategories(tab: Int = activeTab): MutableSet<String> =
        prefs.getStringSet(hiddenCategoriesPrefsKey(tab), emptySet())?.toMutableSet() ?: mutableSetOf()

    // Films/Series shelves aren't sidebar rows, so pinning/hiding a shelf works directly off
    // its title - same "★ pin = surfaces first" idea as the Live TV sidebar's category pin.
    private fun togglePinnedShelf(tab: Int, title: String) {
        val pinned = getPinnedCategories(tab)
        if (!pinned.remove(title)) pinned.add(title)
        prefs.edit().putStringSet(pinnedCategoriesPrefsKey(tab), pinned).apply()
        scope.launch { classifyAndShow() }
    }

    private fun toggleHiddenShelf(tab: Int, title: String) {
        val hidden = getHiddenCategories(tab)
        if (!hidden.remove(title)) hidden.add(title)
        prefs.edit().putStringSet(hiddenCategoriesPrefsKey(tab), hidden).apply()
        val label = if (title in getHiddenCategories(tab)) "Hidden \"$title\"" else "Unhidden \"$title\""
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        scope.launch { classifyAndShow() }
    }

    private fun togglePinCategory(category: CategoryFilter) {
        val id = category.id ?: return
        val pinned = getPinnedCategories()
        if (!pinned.remove(id)) pinned.add(id)
        prefs.edit().putStringSet(pinnedCategoriesPrefsKey(), pinned).apply()
        scope.launch { rebuildCategoriesForActiveTab() }
    }

    /** Hides a sidebar category row - a merged "group:" parent hides every raw category
     *  folded into it (matchIds), a plain leaf just hides itself. */
    private fun toggleHiddenSidebarCategory(category: CategoryFilter) {
        val ids = category.matchIds.ifEmpty { category.id?.let { setOf(it) } ?: return }
        val hidden = getHiddenCategories()
        val hidingNow = ids.none { it in hidden }
        if (hidingNow) hidden.addAll(ids) else hidden.removeAll(ids)
        prefs.edit().putStringSet(hiddenCategoriesPrefsKey(), hidden).apply()
        Toast.makeText(this, if (hidingNow) "Hidden \"${category.name}\"" else "Unhidden \"${category.name}\"", Toast.LENGTH_SHORT).show()
        scope.launch { rebuildCategoriesForActiveTab() }
    }

    /** Films/Series long-press menu - sidebar row is a single TextView with no room for
     *  inline icon buttons like the shelf headers have, so pin/hide live behind a chooser. */
    private fun showCategoryContextMenu(category: CategoryFilter) {
        val id = category.id ?: return
        // The Jellyfin row is always first by construction, so "Pin to top" would be a
        // no-op - hiding it is the only meaningful action.
        if (id == JELLYFIN_CATEGORY_ID) {
            AlertDialog.Builder(this)
                .setTitle(category.name)
                .setItems(arrayOf("Hide")) { _, _ -> toggleHiddenSidebarCategory(category) }
                .show()
            return
        }
        val isPinned = id in getPinnedCategories()
        val hideIds = category.matchIds.ifEmpty { setOf(id) }
        val isHidden = hideIds.any { it in getHiddenCategories() }
        val options = arrayOf(
            if (isPinned) "Unpin" else "Pin to top",
            if (isHidden) "Unhide" else "Hide"
        )
        AlertDialog.Builder(this)
            .setTitle(category.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> togglePinCategory(category)
                    1 -> toggleHiddenSidebarCategory(category)
                }
            }
            .show()
    }

    private fun toggleFavoriteChannel(channel: Channel) {
        if (channel.id.isBlank()) return
        val nowFavorite = FavoritesStore.toggleFavoriteChannel(this, channel.id)
        Toast.makeText(
            this,
            if (nowFavorite) "Added to Favourites" else "Removed from Favourites",
            Toast.LENGTH_SHORT
        ).show()
        if (activeTab == 0) scope.launch { rebuildCategoriesForActiveTab() }
    }

    /** Long-press a future guide block to arm/disarm a 5-min-before notification for it. */
    private fun toggleProgramReminder(channel: Channel, program: XtreamClient.EpgProgram) {
        if (channel.id.isBlank()) return
        val reminder = ProgramReminder(channel.id, channel.name, program.title, program.startTimestamp)
        if (ReminderScheduler.isScheduled(this, reminder.key)) {
            ReminderScheduler.cancel(this, reminder)
            Toast.makeText(this, "Reminder cancelled", Toast.LENGTH_SHORT).show()
        } else {
            ReminderScheduler.schedule(this, reminder)
            Toast.makeText(this, "Reminder set for \"${program.title}\"", Toast.LENGTH_SHORT).show()
        }
        liveAdapter.notifyDataSetChanged()
    }

    private fun liveCategoryPriority(name: String): Int {
        if (isAdultCategory(name)) return 3
        val lower = name.lowercase()
        return when {
            lower.contains("sport") -> 0
            lower.contains("uk") -> 1
            else -> 2
        }
    }

    /** Building the category list scans the whole active tab's content - real work on a big catalog. */
    /** rebuildCategoriesForActiveTab() plus submitting the result to the sidebar - split out
     *  so callers that need to inspect/compute a target category *before* anything renders
     *  (see selectTab()'s default-Sports-category lookup) can do so without each intermediate
     *  lookup flashing onto the sidebar as a real, visible submitList(). */
    private suspend fun rebuildCategoriesForActiveTab(): List<CategoryFilter> {
        val categories = buildCategoriesForActiveTab()
        submitCategories(categories)
        return categories
    }

    /** Just the sidebar-render step, split out so a caller that already has a freshly-built
     *  list (selectTab()'s default-category lookup) can render it without recomputing -
     *  buildCategoriesForActiveTab() rescans every channel in the tab (brand clustering in
     *  particular is O(channel count)), so on a large catalog that's real time saved. */
    private fun submitCategories(categories: List<CategoryFilter>) {
        binding.categorySidebar.visibility = if (categories.size > 1) View.VISIBLE else View.GONE
        // submitList uses AsyncListDiffer which commits the list asynchronously.
        // Set the selected highlight only after the list is committed, otherwise
        // the diff callback can reset the adapter's selected state.
        categoryAdapter.submitList(categories) {
            if (selectedRowId != null) {
                categoryAdapter.setSelected(selectedRowId)
            }
        }
    }

    private suspend fun buildCategoriesForActiveTab(): List<CategoryFilter> {
        val list = activeFullList()
        val pinned = getPinnedCategories()
        val hiddenIds = getHiddenCategories()
        val tab = activeTab
        val expandedSnapshot = expandedGroupKeys.toSet()
        val favoriteChannelIds = if (tab == 0) FavoritesStore.getFavoriteChannelIds(this) else emptySet()
        val animeSectionsSnapshot = animeSections
        // Snapshot on the caller's thread - the block below runs on Dispatchers.Default.
        val versionsById = when (tab) {
            1 -> seriesVersions
            2 -> filmVersions
            else -> emptyMap()
        }
        val categories = withContext(Dispatchers.Default) {
            val useClassicLayout = tab == 0 && prefs.getBoolean(PREF_CLASSIC_CATEGORY_LAYOUT, false)
            val names = LinkedHashMap<String, String>()
            val counts = LinkedHashMap<String, Int>()
            for (ch in list) {
                // Anime gets its own explicit parent row further down; leaving it in here as an
                // ordinary category would also file it under the "Kids & Family" genre bucket
                // (which matches on the word "anime"), so the catalog would appear twice, once
                // buried two levels deep.
                if (ch.id.startsWith(AnimeCatalogClient.ID_PREFIX)) continue
                val key = ch.filterKey() ?: continue
                if (key in hiddenIds) continue
                val rawLabel = ch.categoryName?.takeIf { it.isNotBlank() } ?: ch.group?.takeIf { it.isNotBlank() } ?: key
                // Films/Series only: strip leading provider decoration ("VOD | ", "EN - ",
                // "4K-D+ - ") so noisy panels get a clean, consistent sidebar and more categories
                // fall into the genre buckets. Live keeps its raw names - its leading country tags
                // ("UK|", "US:") are the grouping people actually want there.
                val label = if (tab != 0) cleanVodCategoryLabel(rawLabel) else rawLabel
                names.putIfAbsent(key, label)
                counts[key] = (counts[key] ?: 0) + 1
            }
            val leaves = names.entries.map { (key, label) ->
                CategoryFilter(id = key, name = label, count = counts[key] ?: 0, pinned = pinned.contains(key), matchIds = setOf(key))
            }

            // Each "unit" is one sidebar row candidate paired with its raw (ungrouped)
            // members, so it can be expanded into isChild rows later whether it ends up
            // top-level (classic layout) or nested under a dynamic bucket.
            fun groupUnit(group: CategoryGroup): Pair<CategoryFilter, List<CategoryFilter>> {
                if (group.members.size == 1 && !group.isCluster) return group.members.first() to group.members
                val groupId = "group:${group.label}"
                val parent = CategoryFilter(
                    id = groupId,
                    name = group.label,
                    count = group.members.sumOf { it.count },
                    pinned = pinned.contains(groupId),
                    matchIds = group.members.flatMap { it.matchIds }.toSet(),
                    isParent = true,
                    expanded = expandedSnapshot.contains(groupId),
                    // Clustered groups use a merged label; a plain merged group is still
                    // the provider's own category name, just deduplicated.
                    isDynamic = group.isCluster
                )
                return parent to group.members
            }

            // Every parent's child rows, whether or not it's currently expanded, so a later
            // expand can splice them straight in instead of rebuilding (see onCategoryClick).
            val childrenByParent = mutableMapOf<String, List<CategoryFilter>>()

            fun expandUnit(unit: Pair<CategoryFilter, List<CategoryFilter>>): List<CategoryFilter> {
                val (row, rawMembers) = unit
                if (!row.isParent) return listOf(row)
                val children = rawMembers.sortedBy { it.name.lowercase() }.map { it.copy(isChild = true) }
                row.id?.let { childrenByParent[it] = children }
                return if (row.expanded) listOf(row) + children else listOf(row)
            }

            // Categories are frequently the same content repeated per quality tier
            // ("Sport HD"/"Sport SD"/"Sport RAW") or near-duplicate spellings - merge
            // those into expandable parents on every tab, not just Live TV, or picking
            // one from the Films/Series sidebar only grabs one narrow raw slice instead
            // of the full category.
            // Brand/franchise clusters cut across whatever provider category each channel is
            // actually filed under, so they're synthesized from the raw channel list
            // directly rather than from the grouped category rows. Live TV only - a
            // "brand" concept doesn't map onto film/series categories.
            val groupUnits = if (useClassicLayout) {
                // Classic: show every raw provider category individually, no merging
                leaves.map { it to emptyList<CategoryFilter>() }
            } else {
                // A pin is recorded against a *raw* category id (togglePinCategory stores
                // category.id, and in the classic list that's the leaf key). Grouping then
                // folded that exact leaf into a "group:<label>" parent whose own id was
                // never pinned, so pinning something in the classic view and switching back
                // here made it disappear: the pin still existed, but nothing at top level
                // carried it and the leaf itself only reappeared if you expanded the parent.
                // Holding pinned leaves out of the merge keeps them as their own rows, which
                // is what a pin means - and they then land in pinnedRows, directly beneath
                // the dynamic buckets.
                val (pinnedLeaves, groupableLeaves) = leaves.partition { it.id in pinned }
                val grouped = if (tab != 0) groupSeriesFilmCategories(groupableLeaves) else groupCategories(groupableLeaves)
                grouped.map(::groupUnit) + pinnedLeaves.map { it to emptyList<CategoryFilter>() }
            }
            val brandUnits = if (tab == 0 && !useClassicLayout) {
                deriveBrandCategories(list).map { (label, members) ->
                    val brandId = "brand:$label"
                    CategoryFilter(
                        id = brandId,
                        name = label,
                        count = members.size,
                        pinned = pinned.contains(brandId),
                        channelIds = members.map { it.id }.toSet(),
                        isDynamic = true
                    ) to emptyList<CategoryFilter>()
                }
            } else {
                emptyList()
            }
            val allUnits = groupUnits + brandUnits

            // Every tab leads with a handful of dynamic buckets - Sports/News/Music/Cinema on
            // Live TV, genres on Films/Series - that vacuum up every matching category/brand
            // row regardless of which raw provider category it actually lives in; everything
            // left over cascades below, same priority order as before this existed. The
            // classic pref (Live TV only) bypasses this and shows the old flat/grouped list.
            val dynamicBuckets = if (tab == 0) LIVE_DYNAMIC_BUCKETS else VOD_DYNAMIC_BUCKETS
            val (bucketRows, allUnitsEnhanced) = if (!useClassicLayout) {
                fun bucketFor(name: String): String? {
                    val lower = name.lowercase()
                    return dynamicBuckets.firstOrNull { (_, keywords) -> keywords.any { lower.contains(it) } }?.first
                }
                val bucketed = LinkedHashMap<String, MutableList<Pair<CategoryFilter, List<CategoryFilter>>>>()
                // Pinned categories are exempt. A pin is an explicit "keep this one where I
                // can reach it", and a bucket swallowed it into a collapsed parent - pin a
                // category from the classic list, switch back to the grouped view, and it
                // was gone from the top of the sidebar entirely. Skipping them here leaves
                // them in the remainder, which lands them in pinnedRows directly beneath
                // the buckets.
                // Brand rows are exempt for the same reason: they're a row in their own
                // right, listed above the buckets, not something to fold into a genre.
                allUnits.forEach { unit ->
                    if (unit.first.pinned || (tab != 0 && unit.first.isDynamic)) return@forEach
                    bucketFor(unit.first.name)?.let { bucketed.getOrPut(it) { mutableListOf() }.add(unit) }
                }
                val rows = dynamicBuckets.mapNotNull { (label, _) ->
                    val members = bucketed[label] ?: return@mapNotNull null
                    val bucketId = "$DYNAMIC_BUCKET_ID_PREFIX$label"
                    val expanded = expandedSnapshot.contains(bucketId)
                    val channelIds = members.flatMap { (row, _) ->
                        if (row.channelIds.isNotEmpty()) {
                            row.channelIds
                        } else {
                            val byKey = list.filter { ch -> ch.filterKey() in row.matchIds }.map { it.id }
                            // Backup: match by categoryName in case filterKey is unreachable
                            val byName = if (byKey.isEmpty() && row.name.isNotBlank()) {
                                list.filter { ch ->
                                    ch.categoryName?.let { it.equals(row.name, ignoreCase = true) } == true
                                }.map { it.id }
                            } else emptyList()
                            if (byName.isNotEmpty()) byName else byKey
                        }
                    }.toSet()
                    val parent = CategoryFilter(
                        id = bucketId,
                        name = label,
                        count = members.sumOf { it.first.count },
                        pinned = pinned.contains(bucketId),
                        channelIds = channelIds,
                        isParent = true,
                        expanded = expanded,
                        isDynamic = true
                    )
                    // Channel sort — reserved for future use.
                    // Built even while collapsed, and cached, so expanding is a splice
                    // rather than a full rescan of the tab.
                    val children = members.map { it.first.copy(isChild = true, isParent = false, expanded = false) }
                        .sortedBy { it.name.lowercase() }
                    childrenByParent[bucketId] = children
                    if (expanded) listOf(parent) + children else listOf(parent)
                }.flatten()
                // Brand-row channels from a bucket should also be reachable from that
                // bucket's classic provider categories. For each classic leaf inside a
                // bucket that has brand rows, add the brand channel IDs to the leaf's
                // channelIds so both the bucket AND the classic category show them.
                val enhancedUnits = if (tab == 0) {
                    val bucketExtra = mutableMapOf<String, MutableSet<String>>()
                    for ((label, _) in dynamicBuckets) {
                        val bucketMembers = bucketed[label] ?: continue
                        val brandIds = bucketMembers.filter { (row, _) -> row.channelIds.isNotEmpty() }
                            .flatMap { (row, _) -> row.channelIds ?: emptySet() }.toSet()
                        if (brandIds.isEmpty()) continue
                        for ((row, _) in bucketMembers.filter { (row, _) -> row.channelIds.isNullOrEmpty() }) {
                            row.id?.let { id -> bucketExtra.getOrPut(id) { mutableSetOf() }.addAll(brandIds) }
                        }
                    }
                    if (bucketExtra.isNotEmpty()) {
                        allUnits.map { unit ->
                            val (row, children) = unit
                            val extra = bucketExtra[row.id] ?: return@map unit
                            row.copy(channelIds = (row.channelIds ?: emptySet()) + extra) to children
                        }
                    } else allUnits
                } else allUnits
                rows to enhancedUnits
            } else {
                emptyList<CategoryFilter>() to allUnits
            }
            // Series/Films: merged (grouped) categories surface above plain single-provider
            // leaves, alphabetical within each cluster - sorted here, at the unit level,
            // so an expanded parent's own children stay adjacent to it (sorting the already-
            // flattened rows would scatter them back in with unrelated leaves by name).
            // Channels in dynamic buckets should also appear in their original provider
            // categories below the buckets - don't filter out bucketed units here.
            val leftoverUnits = allUnitsEnhanced
            // Series/Films: clustered service categories go above the genre buckets -
            // they're the rows people go looking for by name - and the provider's own
            // categories below both. Splitting them out here rather than
            // sorting them to the front keeps the three blocks separable at assembly time.
            val (serviceUnits, plainUnits) =
                if (tab != 0) leftoverUnits.partition { it.first.isDynamic } else emptyList<Pair<CategoryFilter, List<CategoryFilter>>>() to leftoverUnits
            val remainderUnits = plainUnits
                .let { units ->
                    if (tab != 0) units.sortedWith(
                        compareBy(
                            { if (isAdultCategory(it.first.name)) 1 else 0 },
                            { if (it.first.isParent) 0 else 1 },
                            { it.first.name.lowercase() }
                        )
                    )
                    else units
                }
            val brandRows = serviceUnits.sortedBy { it.first.name.lowercase() }.flatMap(::expandUnit)
            val cascadeRows = remainderUnits.flatMap(::expandUnit)

            val (pinnedRows, unpinnedRows) = cascadeRows.partition { it.pinned }
            val allRow = CategoryFilter(id = null, name = "All", count = list.size)
            // Live TV sorts "All" below the dynamic buckets - Favourites/pinned/buckets
            // are what people actually want first there. Other tabs have no buckets, so
            // "All" just stays at the top like before.
            val result = mutableListOf<CategoryFilter>()
            // Films/Series lead with a "Jellyfin" row when this tab has any Jellyfin-sourced
            // items - a personal library is browsed as a library, not hunted for across the
            // IPTV providers' categories it gets merged into. Carries explicit channelIds
            // (same mechanism as a brand row) because provenance is per-Channel, not a
            // provider category anything is filed under.
            if (tab != 0 && JELLYFIN_CATEGORY_ID !in hiddenIds) {
                // A title the Jellyfin library *and* an IPTV provider both carry is one
                // deduped card, and the representative that wins the card is whichever copy
                // had a poster - often the IPTV one. Matching on the representative's own
                // isJellyfin flag alone dropped those titles out of the Jellyfin row even
                // though the library has them, so match on any version in the group.
                val jellyfinIds = list.filter { ch ->
                    ch.isJellyfin || versionsById[ch.id]?.any { it.isJellyfin } == true
                }.map { it.id }.toSet()
                if (jellyfinIds.isNotEmpty()) {
                    result.add(
                        CategoryFilter(
                            id = JELLYFIN_CATEGORY_ID,
                            name = "Jellyfin",
                            count = jellyfinIds.size,
                            channelIds = jellyfinIds,
                            isDynamic = true
                        )
                    )
                }
            }
            // Series: the anime catalog is one expandable row whose children are the catalog's
            // sections, so browsing it is "Anime > Currently Airing" rather than one 500-title
            // heap. Cached cold starts restore the channels but not the sections (they aren't
            // in the flat channel cache), so the row degrades to a plain, unexpandable Anime
            // category until the next catalog refresh rather than vanishing.
            if (tab == 1 && ANIME_CATEGORY_ID !in hiddenIds) {
                val animeIds = list.filter { it.id.startsWith(AnimeCatalogClient.ID_PREFIX) }
                    .map { it.id }.toSet()
                if (animeIds.isNotEmpty()) {
                    val children = animeSectionsSnapshot.mapNotNull { section ->
                        val ids = section.channelIds.filterTo(mutableSetOf()) { it in animeIds }
                        if (ids.isEmpty()) return@mapNotNull null
                        CategoryFilter(
                            id = "$ANIME_CATEGORY_ID:${section.label}",
                            name = section.label,
                            count = ids.size,
                            pinned = pinned.contains("$ANIME_CATEGORY_ID:${section.label}"),
                            isChild = true,
                            channelIds = ids
                        )
                    }
                    val expanded = expandedSnapshot.contains(ANIME_CATEGORY_ID)
                    val parent = CategoryFilter(
                        id = ANIME_CATEGORY_ID,
                        name = "Anime",
                        count = animeIds.size,
                        pinned = pinned.contains(ANIME_CATEGORY_ID),
                        channelIds = animeIds,
                        isParent = children.isNotEmpty(),
                        expanded = expanded && children.isNotEmpty(),
                        isDynamic = true
                    )
                    if (children.isNotEmpty()) childrenByParent[ANIME_CATEGORY_ID] = children
                    result.add(parent)
                    if (parent.expanded) result.addAll(children)
                }
            }
            if (tab != 0) result.add(allRow)
            if (tab == 0) {
                val favoriteCount = list.count { it.id in favoriteChannelIds }
                if (favoriteCount > 0) {
                    result.add(CategoryFilter(id = FAVOURITES_CATEGORY_ID, name = "Favourites", count = favoriteCount))
                    result.add(
                        CategoryFilter(
                            id = CLASSIC_LAYOUT_TOGGLE_ID,
                            name = if (useClassicLayout) "Group into categories" else "Show all categories (classic list)",
                            count = -1
                        )
                    )
                } else {
                    result.add(
                        CategoryFilter(
                            id = CLASSIC_LAYOUT_TOGGLE_ID,
                            name = if (useClassicLayout) "Group into categories" else "Show all categories (classic list)",
                            count = -1
                        )
                    )
                }
            }
            // Pinned (favourite) categories always come first - above dynamic clusters,
            // genre buckets, and everything else - so the user's pinned items are always
            // one D-pad press away regardless of how the sidebar otherwise arranges itself.
            result += pinnedRows.sortedBy { it.name.lowercase() }
            // Series/Films: clustered service categories, then genre buckets, then the
            // provider's own list. Live TV has no brand block here (its brand rows come from
            // deriveBrandCategories and go through bucketing), so it just leads with buckets.
            result += brandRows
            result += bucketRows
            if (tab == 0) result.add(allRow)
            // Live TV is mainly watched for sport, then UK channels - surface those first.
            result += if (tab == 0) {
                unpinnedRows.sortedWith(compareBy({ liveCategoryPriority(it.name) }, { it.name.lowercase() }))
            } else {
                // Already unit-sorted above (grouped categories first, then leaves,
                // alphabetical within each) - re-sorting here would undo that.
                unpinnedRows
            }
            result to childrenByParent.toMap()
        }
        categoryChildrenCache = categories.second
        return categories.first
    }

    /** Column count for the single-category poster grid, sized off the RecyclerView's actual
     *  width where possible (it's already laid out by the time a category gets picked). */
    private fun gridSpanCount(recyclerView: RecyclerView): Int {
        val widthPx = recyclerView.width.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - resources.getDimensionPixelSize(R.dimen.category_sidebar_width))
        val widthDp = widthPx / resources.displayMetrics.density
        // Both bounds come from resources so each device class tunes its own grid: the
        // minimum column width drops on a portrait phone (which would otherwise fit only one
        // poster beside the sidebar), and the max span caps a TV at 5 rather than the 6+ its
        // width alone would allow.
        val minColumnDp = resources.getDimension(R.dimen.poster_grid_min_column_width) /
            resources.displayMetrics.density
        return (widthDp / minColumnDp).toInt()
            .coerceIn(1, resources.getInteger(R.integer.poster_grid_max_span))
    }

    /** Sets up a GridLayoutManager on [recyclerView] and tells [adapter] its span count and
     *  where D-pad UP from the top row should land ([topRowFocusUpTargetId], e.g. the active
     *  tab button) - see PosterGridAdapter.topRowFocusUpTargetId for why that can't just be
     *  left to automatic focus search. */
    private fun setGridSpan(recyclerView: RecyclerView, adapter: PosterGridAdapter, topRowFocusUpTargetId: Int) {
        val span = gridSpanCount(recyclerView)
        recyclerView.layoutManager = GridLayoutManager(this, span)
        adapter.spanCount = span
        adapter.topRowFocusUpTargetId = topRowFocusUpTargetId
    }

    /** "See All" on a shelf header - same vertical grid a sidebar category pick opens,
     *  just seeded directly from that shelf's own items instead of matching category ids. */
    private fun showSeeAll(shelf: ContentShelf) {
        selectedShelfItems = shelf.items
        selectedCategoryLabel = shelf.title
        selectedRowId = null
        selectedCategoryIds = null
        selectedBrandChannelIds = null
        scope.launch { applyCategoryFilter() }
    }

    // Series/Films normally use category-based shelves; picking one
    // specific category from the sidebar swaps that tab's RecyclerView to a vertical,
    // scrollable poster grid instead - a horizontal strip isn't enough room to browse
    // a whole category in. Filtering the full catalog is real work, so it runs off-main.
    private suspend fun applyCategoryFilter(focusFirstLiveChannel: Boolean = false) {
        val matchIds = selectedCategoryIds
        val tab = activeTab
        when (tab) {
            0 -> {
                val source = liveChannels
                val isFavourites = selectedRowId == FAVOURITES_CATEGORY_ID
                val favoriteIds = if (isFavourites) FavoritesStore.getFavoriteChannelIds(this) else emptySet()
                val brandIds = selectedBrandChannelIds
                val filtered = withContext(Dispatchers.Default) {
                    when {
                        isFavourites -> source.filter { it.id in favoriteIds }
                        brandIds != null && matchIds != null ->
                            source.filter { it.id in brandIds || it.filterKey() in matchIds }
                        brandIds != null -> source.filter { it.id in brandIds }
                        matchIds == null -> source
                        else -> source.filter { it.filterKey() in matchIds }
                    }
                }
                liveAdapter.submitList(filtered) {
                    if (!focusFirstLiveChannel) return@submitList
                    val first = filtered.firstOrNull() ?: return@submitList
                    requestPreviewLoad(first)
                    // submitList's commit callback fires once the diff is applied, but the
                    // row's ViewHolder isn't necessarily laid out yet on this same frame - a
                    // single post() still occasionally lands before RecyclerView's own
                    // pending layout pass, so nest two: the first just waits for that layout
                    // request to be queued, the second runs after it's actually done.
                    binding.liveContent.post {
                        binding.liveContent.post {
                            (binding.liveContent.findViewHolderForAdapterPosition(0) as? LiveGuideAdapter.RowViewHolder)
                                ?.requestChannelFocus()
                        }
                    }
                }
                binding.liveContent.scrollToPosition(0)
            }
            1 -> {
                val source = seriesList
                val shelfItems = selectedShelfItems
                // A dynamic row (genre bucket or streaming service) carries an explicit set
                // of channel ids rather than provider category ids, because it deliberately
                // spans several of them. Only Live TV honoured that, so on Series/Films
                // picking one set matchIds to null and fell straight through to the "no
                // filter, show the shelves" branch - which looked like the click did nothing.
                val brandIds = selectedBrandChannelIds
                if (shelfItems != null) {
                    setGridSpan(binding.seriesContent, seriesGridAdapter, R.id.tabSeries)
                    binding.seriesContent.adapter = seriesGridAdapter
                    seriesGridAdapter.submitList(shelfItems)
                } else if (brandIds != null) {
                    val filtered = withContext(Dispatchers.Default) { source.filter { it.id in brandIds } }
                    setGridSpan(binding.seriesContent, seriesGridAdapter, R.id.tabSeries)
                    binding.seriesContent.adapter = seriesGridAdapter
                    seriesGridAdapter.submitList(filtered)
                } else if (matchIds == null) {
                    binding.seriesContent.layoutManager = LinearLayoutManager(this)
                    binding.seriesContent.adapter = seriesShelfAdapter
                    seriesShelfAdapter.submitList(seriesShelves)
                } else {
                    val filtered = withContext(Dispatchers.Default) { source.filter { it.filterKey() in matchIds } }
                    setGridSpan(binding.seriesContent, seriesGridAdapter, R.id.tabSeries)
                    binding.seriesContent.adapter = seriesGridAdapter
                    seriesGridAdapter.submitList(filtered)
                }
                binding.seriesContent.scrollToPosition(0)
            }
            2 -> {
                val source = filmList
                val shelfItems = selectedShelfItems
                val brandIds = selectedBrandChannelIds // see the Series branch above
                if (shelfItems != null) {
                    setGridSpan(binding.filmsContent, filmsGridAdapter, R.id.tabFilms)
                    binding.filmsContent.adapter = filmsGridAdapter
                    filmsGridAdapter.submitList(shelfItems)
                } else if (brandIds != null) {
                    val filtered = withContext(Dispatchers.Default) { source.filter { it.id in brandIds } }
                    setGridSpan(binding.filmsContent, filmsGridAdapter, R.id.tabFilms)
                    binding.filmsContent.adapter = filmsGridAdapter
                    filmsGridAdapter.submitList(filtered)
                } else if (matchIds == null) {
                    binding.filmsContent.layoutManager = LinearLayoutManager(this)
                    binding.filmsContent.adapter = filmsShelfAdapter
                    filmsShelfAdapter.submitList(filmShelves)
                } else {
                    val filtered = withContext(Dispatchers.Default) { source.filter { it.filterKey() in matchIds } }
                    setGridSpan(binding.filmsContent, filmsGridAdapter, R.id.tabFilms)
                    binding.filmsContent.adapter = filmsGridAdapter
                    filmsGridAdapter.submitList(filtered)
                }
                binding.filmsContent.scrollToPosition(0)
            }
        }
    }

    private fun onCategorySelected(category: CategoryFilter) {
        if (category.id == CLASSIC_LAYOUT_TOGGLE_ID) {
            val useClassic = prefs.getBoolean(PREF_CLASSIC_CATEGORY_LAYOUT, false)
            prefs.edit().putBoolean(PREF_CLASSIC_CATEGORY_LAYOUT, !useClassic).apply()
            val newClassic = !useClassic
            scope.launch {
                // Classic mode shows ALL live channels flat (no version grouping), so
                // channels like SD variants that were collapsed into a higher-quality
                // representative are individually visible and contribute to their own
                // provider categories. Re-derive liveChannels/liveVersions before
                // rebuilding the sidebar so categories reflect the full channel list.
                val hideAdult = prefs.getBoolean(PREF_HIDE_ADULT, true)
                val snapshot = allChannels
                val rawLive = snapshot.filter { it.mediaType == MediaType.LIVE && !it.name.contains("##") }
                    .filterNot { hideAdult && isAdultCategory(it.categoryName, it.group) }
                if (newClassic) {
                    liveChannels = rawLive
                    liveVersions = emptyMap()
                } else {
                    val (grouped, vers) = groupLiveQualityVersions(rawLive)
                    liveChannels = grouped
                    liveVersions = vers
                }
                rebuildCategoriesForActiveTab()
            }
            return
        }
        // A tap on a parent row always toggles its expansion (and selects it) - the old
        // select-first-toggle-on-second-tap scheme read as "collapse needs a double
        // click". category.expanded is the pre-tap state the row was bound with.
        val id = category.id
        val expandChanged = category.isParent && id != null
        if (expandChanged && id != null) {
            if (!expandedGroupKeys.remove(id)) expandedGroupKeys.add(id)
        }
        selectedShelfItems = null
        selectedRowId = category.id
        selectedCategoryLabel = category.name
        selectedBrandChannelIds = category.channelIds.ifEmpty { null }
        selectedCategoryIds = if (category.id == null) null else category.matchIds
        if (expandChanged) {
            if (category.expanded) {
                // Was expanded -> collapsing: just remove child rows from the existing
                // list without a full category rebuild - avoids the expensive channel
                // scan on every tap. (These two branches were inverted before: collapse
                // paid the full rebuild, expand ran this no-op removal and appeared to
                // ignore the first click.)
                val currentList = categoryAdapter.currentList.toMutableList()
                val parentIdx = currentList.indexOfFirst { it.id == id }
                if (parentIdx >= 0) {
                    var removeEnd = parentIdx + 1
                    while (removeEnd < currentList.size && currentList[removeEnd].isChild) removeEnd++
                    if (removeEnd > parentIdx + 1) {
                        currentList.subList(parentIdx + 1, removeEnd).clear()
                    }
                    currentList[parentIdx] = currentList[parentIdx].copy(expanded = false)
                }
                categoryAdapter.submitList(currentList)
                categoryAdapter.setSelected(selectedRowId)
                scope.launch { applyCategoryFilter() }
            } else {
                // Was collapsed -> expanding. The children were already computed by the
                // last build and cached, so splice them in the same way collapse removes
                // them. This used to run a full rebuild - a rescan of every channel in the
                // tab - which is why expanding lagged while collapsing was instant.
                val children = id?.let { categoryChildrenCache[it] }
                if (children != null) {
                    val currentList = categoryAdapter.currentList.toMutableList()
                    val parentIdx = currentList.indexOfFirst { it.id == id }
                    if (parentIdx >= 0) {
                        currentList[parentIdx] = currentList[parentIdx].copy(expanded = true)
                        currentList.addAll(parentIdx + 1, children)
                    }
                    categoryAdapter.submitList(currentList)
                    categoryAdapter.setSelected(selectedRowId)
                    scope.launch { applyCategoryFilter() }
                } else {
                    // No cached children (first build hasn't run, or the row postdates it) -
                    // fall back to the full rebuild rather than silently expanding to nothing.
                    scope.launch {
                        rebuildCategoriesForActiveTab()
                        applyCategoryFilter()
                    }
                }
            }
        } else {
            // Just the highlighted row + filtered content changed, not which rows exist -
            // rebuilding the whole list (rescans every channel in the tab) for that alone
            // is exactly the "picking a category takes forever" complaint. setSelected()
            // already re-renders the sidebar's highlight on its own.
            categoryAdapter.setSelected(selectedRowId)
            scope.launch { applyCategoryFilter(focusFirstLiveChannel = activeTab == 0) }
        }
    }

    private fun setStatus(text: String, visible: Boolean) {
        binding.statusText.text = text
        // A settings/search overlay owns the content slot while it's up - a status raised
        // underneath it shows through as loose text floating on the dialog's backdrop.
        val hiddenByOverlay = activeSettingsOverlay != null || activeSearchOverlay != null
        binding.statusRow.visibility = if (visible && !hiddenByOverlay) View.VISIBLE else View.GONE
        // In-progress messages ("Loading...", "Connecting...") get a spinner; final
        // results ("N items", errors) don't - "..." is what already distinguishes them
        // at every call site, no need for a second parameter everywhere.
        binding.statusSpinner.visibility = if (visible && text.trimEnd().endsWith("...")) View.VISIBLE else View.GONE
    }

    // ── Tabs ───────────────────────────────────────

    private fun setupTabs() {
        binding.tabHome.setOnClickListener { selectHome() }
        binding.tabLive.setOnClickListener { selectTab(0) }
        binding.tabSeries.setOnClickListener { selectTab(1) }
        binding.tabFilms.setOnClickListener { selectTab(2) }
        binding.tabDiscover.setOnClickListener { showingHome = false; selectDiscover() }
        binding.tabDownloads.setOnClickListener { showingHome = false; selectDownloads() }
        setupDiscover()
        // D-pad focus moving between tabs leaves a stale sliver of the previous tab's
        // rounded-border background behind on some TV-stick GPUs - the view's own
        // self-invalidate on unfocus doesn't always clear it. Forcing the whole bar to
        // redraw on every focus change is a blunt but reliable fix.
        val invalidateBarOnFocus = View.OnFocusChangeListener { _, _ -> binding.tabBar.invalidate() }
        for (tv in listOf(binding.tabHome, binding.tabLive, binding.tabSeries, binding.tabFilms, binding.tabDiscover, binding.tabDownloads)) {
            tv.onFocusChangeListener = invalidateBarOnFocus
        }
        // Hide tab bar + search until an enabled provider exists
        updateTopChromeVisibility()
    }

    private fun updateTabStyles(selected: View) {
        for (tv in listOf(binding.tabHome, binding.tabLive, binding.tabSeries, binding.tabFilms, binding.tabDiscover, binding.tabDownloads)) {
            val isSelected = tv === selected
            tv.isSelected = isSelected
            tv.setTextColor(getColor(if (isSelected) R.color.text_primary else R.color.text_secondary))
            tv.setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        // The "selected" pill styling looks identical to real focus, which masked this:
        // nothing ever actually requested Android focus onto a tab, so D-pad had no
        // valid starting point to navigate from once content loaded.
        selected.requestFocus()
    }

    private fun selectHome() {
        activeSettingsOverlay?.dismiss()
        activeSearchOverlay?.dismiss()
        showingHome = true
        showingDownloads = false
        showingDiscover = false
        releaseLivePreview()
        binding.discoverContent.visibility = View.GONE
        binding.contentRow.visibility = View.GONE
        binding.homeContent.visibility = View.VISIBLE
        // Search on Home is only useful with something to search; with no enabled provider
        // updateTopChromeVisibility() keeps it hidden. selectHome used to force it visible
        // unconditionally, which is why it lingered on the empty first screen.
        binding.homeSearchBar.visibility = if (hasProviderEnabled()) View.VISIBLE else View.GONE
        applyPanelWidth(binding.homeSearchBar, R.dimen.home_search_bar_width)
        updateTabStyles(binding.tabHome)
        homeShelfAdapter.submitList(buildHomeShelves())
    }

    /** Applies [widthDimen] as an explicit width, treating 0 as "leave it as laid out".
     *  Lets a phone keep a match_parent panel while a TV gets a fixed, centred one, without
     *  a second copy of the layout - a dimen resource can't itself hold match_parent. */
    private fun applyPanelWidth(view: View, widthDimen: Int) {
        val width = resources.getDimensionPixelSize(widthDimen)
        if (width <= 0) return
        view.layoutParams = view.layoutParams?.also { it.width = width } ?: return
    }

    /** Downloads reuses the contentRow's FrameLayout but skips the category sidebar and
     *  the live/series/films lists entirely - it's not part of the categorized catalog. */
    private fun selectDownloads() {
        activeSettingsOverlay?.dismiss()
        activeSearchOverlay?.dismiss()
        showingDownloads = true
        showingDiscover = false
        releaseLivePreview()
        binding.discoverContent.visibility = View.GONE
        binding.contentRow.visibility = View.VISIBLE
        binding.homeContent.visibility = View.GONE
        binding.homeSearchBar.visibility = View.GONE
        binding.categorySidebar.visibility = View.GONE
        binding.liveRow.visibility = View.GONE
        binding.seriesContent.visibility = View.GONE
        binding.filmsContent.visibility = View.GONE
        updateTabStyles(binding.tabDownloads)
        refreshDownloadsList()
        mainHandler.post(downloadsProgressRunnable)
    }

    // ── Discover (TMDB browse + plugin playback) ────

    private fun setupDiscover() {
        setGridSpan(binding.discoverGrid, discoverGridAdapter, R.id.tabDiscover)
        // setGridSpan only wires the layout manager/span; the adapter still has to be attached.
        binding.discoverGrid.adapter = discoverGridAdapter
        binding.discoverSearchButton.setOnClickListener { runDiscoverSearch() }
        binding.discoverSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                runDiscoverSearch(); true
            } else false
        }
    }

    /** Discover is its own pane (like Downloads): browse/search TMDB, no category sidebar. */
    private fun selectDiscover() {
        activeSettingsOverlay?.dismiss()
        activeSearchOverlay?.dismiss()
        showingHome = false
        showingDownloads = false
        showingDiscover = true
        releaseLivePreview()
        binding.contentRow.visibility = View.GONE
        binding.homeContent.visibility = View.GONE
        binding.homeSearchBar.visibility = View.GONE
        binding.discoverContent.visibility = View.VISIBLE
        updateTabStyles(binding.tabDiscover)
        // Recompute span now the pane is on-screen and actually has a width.
        binding.discoverGrid.post { setGridSpan(binding.discoverGrid, discoverGridAdapter, R.id.tabDiscover) }
        if (!tmdbClient.hasKey()) {
            setDiscoverStatus("Discover is unavailable (no TMDB key configured).")
        } else if (discoverGridAdapter.itemCount == 0) {
            loadDiscover(null)
        }
    }

    private fun runDiscoverSearch() {
        val query = binding.discoverSearchInput.text?.toString()?.trim().orEmpty()
        loadDiscover(query.takeIf { it.isNotEmpty() })
    }

    /** Loads trending (null query) or search results into the Discover grid. */
    private fun loadDiscover(query: String?) {
        if (!tmdbClient.hasKey()) return
        discoverSearchJob?.cancel()
        setDiscoverStatus(if (query == null) "Loading trending…" else "Searching \"$query\"…")
        discoverSearchJob = scope.launch {
            val results = if (query == null) tmdbClient.trending() else tmdbClient.search(query)
            discoverGridAdapter.submitList(results)
            setDiscoverStatus(
                when {
                    results.isNotEmpty() -> null
                    query == null -> "Couldn't load titles. Check your connection."
                    else -> "No results for \"$query\"."
                }
            )
        }
    }

    private fun setDiscoverStatus(text: String?) {
        binding.discoverStatus.text = text ?: ""
        binding.discoverStatus.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    /** Discover pick opens an info screen: overview + poster, then either play a matching catalog
     *  item (if this title is already served by a provider) or find a torrent stream for it. */
    private fun onDiscoverItemClick(item: Channel) {
        val match = findCatalogMatch(item)

        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val backdrop = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (200 * density).toInt()
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        loadDetailImage(item.backdropUrl ?: item.posterUrl, backdrop)

        val meta = listOfNotNull(
            if (item.mediaType == MediaType.SERIES) "Series" else "Movie",
            item.year,
            item.rating?.let { "★ $it" }
        ).joinToString("   ·   ")

        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setPadding(0, (6 * density).toInt(), 0, 0)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(this@MainActivity).apply {
                text = item.name
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(label(meta))
            item.description?.let { addView(label(it)) }
            match?.let {
                addView(label("✓ Available in your ${if (it.isJellyfin) "Jellyfin" else "provider"} library"))
            }
        }
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(pad, (12 * density).toInt(), pad, pad)
        }
        fun actionButton(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (8 * density).toInt() }
            setOnClickListener { onClick() }
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(backdrop)
            addView(content)
            addView(buttonRow)
        }
        val scroll = ScrollView(this).apply { addView(body) }

        val dialog = AlertDialog.Builder(this).setView(scroll).create()
        // Prefer the already-owned copy; the torrent path is offered too, but only when a
        // stream-search plugin is actually enabled to serve it.
        if (match != null) {
            buttonRow.addView(actionButton("Play") {
                dialog.dismiss()
                showContentDetail(match)
            })
        }
        if (enabledStreamSearchPlugin() != null) {
            buttonRow.addView(actionButton("Find stream") {
                dialog.dismiss()
                startDiscoverStreamSearch(item)
            })
        }
        if (tmdbClient.hasKey()) {
            buttonRow.addView(actionButton("Trailer") {
                dialog.dismiss()
                showTrailerForDiscoverItem(item)
            })
        }
        buttonRow.addView(actionButton("Close") { dialog.dismiss() })
        dialog.show()
    }

    /** Kicks off a stream-search plugin for a Discover title (episode picker for series). */
    private fun startDiscoverStreamSearch(item: Channel) {
        val plugin = enabledStreamSearchPlugin(item)
        if (plugin == null) {
            Toast.makeText(
                this,
                "Enable a stream plugin in Settings → Plugins to play Discover titles.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (item.mediaType == MediaType.SERIES) showSeriesEpisodePicker(plugin, item)
        else showStreamSearchDialog(plugin, item)
    }

    /** Finds an already-configured provider item matching a Discover (TMDB) title, if any. */
    private fun findCatalogMatch(item: Channel): Channel? {
        val target = normalizeMatchTitle(item.name)
        if (target.isBlank()) return null
        return allChannels.firstOrNull { c ->
            c.mediaType == item.mediaType && run {
                val name = normalizeMatchTitle(c.name)
                (name == target || name.startsWith("$target ") || name.contains(target)) &&
                    (item.year == null || c.year == null || c.year == item.year)
            }
        }
    }

    private fun normalizeMatchTitle(title: String): String =
        title.lowercase(Locale.US).replace(Regex("\\(\\d{4}\\)"), " ")
            .replace(Regex("[^a-z0-9]+"), " ").trim()

    /** Fetches the show's seasons from TMDB, then lets the user pick season → episode to search. */
    private fun showSeriesEpisodePicker(plugin: PluginScript, item: Channel) {
        val tvId = item.id.substringAfterLast(':').toIntOrNull()
        if (tvId == null) { showStreamSearchDialog(plugin, item); return }
        val loading = AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage("Loading episodes…")
            .setNegativeButton("Cancel", null)
            .create()
        loading.show()
        scope.launch {
            val seasons = tmdbClient.tvSeasons(tvId)
            loading.dismiss()
            if (seasons.isEmpty()) {
                // No season data - fall back to searching the title as a whole.
                showStreamSearchDialog(plugin, item)
                return@launch
            }
            val seasonLabels = seasons.map { "${it.name} (${it.episodeCount} eps)" }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("${item.name} — choose a season")
                .setItems(seasonLabels) { _, si ->
                    val season = seasons[si]
                    val epLabels = (1..season.episodeCount).map { "Episode $it" }.toTypedArray()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(season.name)
                        .setItems(epLabels) { _, ei ->
                            showStreamSearchDialog(plugin, item, season.number, ei + 1)
                        }
                        .setNegativeButton("Back") { _, _ -> showSeriesEpisodePicker(plugin, item) }
                        .show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun onHomeItemClick(channel: Channel) {
        when (channel.mediaType) {
            MediaType.LIVE -> playItem(channel)
            MediaType.MOVIE -> {
                currentIndex = filmList.indexOf(channel)
                showPlayerFor(channel)
                // Back out to the film's own poster, same as playing it from its detail page.
                // Not for a plugin-resolved entry: its id is a resolve token, not a catalog
                // item, so there is no detail page to return it to.
                if (channel.pluginToken == null) detailReturnItem = channel
            }
            MediaType.SERIES -> {
                // A Continue Watching tile reconstructs one specific episode, complete
                // with a real stream url - resume it directly instead of opening the
                // series' detail page. A top-level series entry (e.g. from Favorites)
                // never has a url of its own (see XtreamClient.parseSeriesItem), so that
                // case still falls through to the detail page as normal.
                if (channel.url.isNotBlank()) showPlayerFor(channel) else showContentDetail(channel)
            }
            else -> {}
        }
    }

    private fun getHiddenHomeShelves(): MutableSet<String> =
        prefs.getStringSet("hidden_home_shelves", emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun toggleHiddenHomeShelf(title: String) {
        val hidden = getHiddenHomeShelves()
        if (!hidden.remove(title)) hidden.add(title)
        prefs.edit().putStringSet("hidden_home_shelves", hidden).apply()
        homeShelfAdapter.submitList(buildHomeShelves())
    }

    /** Adult content never reaches a Home shelf, regardless of the "Hide adult categories"
     *  setting. That setting governs *browsing* - somebody who unlocks it with the PIN is
     *  choosing to go and look. Continue Watching and Recently Played are different: they
     *  render unprompted on the first screen after launch, in front of whoever happens to
     *  be in the room, so they stay filtered either way.
     *
     *  Three signals, in order of trust: the catalog entry for this id (authoritative, but
     *  only for items still in the catalog - a series episode never is), the category/group
     *  snapshotted at save time, then the title itself as a last resort for entries written
     *  before that snapshot existed. The title check can over-match a legitimate film with
     *  "adult" in its name, which is the right way round to be wrong here. */
    private fun isAdultHomeItem(item: Channel): Boolean {
        val catalog = item.id.takeIf { it.isNotBlank() }?.let { id -> allChannels.firstOrNull { it.id == id } }
        return isAdultCategory(catalog?.categoryName ?: item.categoryName, catalog?.group ?: item.group) ||
            isAdultCategory(item.name)
    }

    private fun buildHomeShelves(): List<ContentShelf> {
        val shelves = mutableListOf<ContentShelf>()
        val hidden = getHiddenHomeShelves()

        // Jellyfin's own resume list leads Continue Watching: the server knows about playback
        // from every other client, which a purely local position store never can. Local
        // entries follow, minus anything the server already covered (same item, one card).
        val localContinue = PlaybackPositionStore.getAllInProgress(this)
        val serverContinue = jellyfinResumeItems
        val serverIds = serverContinue.map { it.id }.toSet()
        val continueItems = (serverContinue + localContinue.filterNot { it.id in serverIds })
            .filterNot(::isAdultHomeItem)
        if (continueItems.isNotEmpty()) shelves.add(ContentShelf("Continue Watching", continueItems))

        // "Next Up" is the row that makes a series library usable - the next unwatched episode
        // of everything in flight, straight from the server's own tracking.
        val nextUpItems = jellyfinNextUpItems.filterNot(::isAdultHomeItem)
        if (nextUpItems.isNotEmpty()) shelves.add(ContentShelf("Next Up", nextUpItems))

        val recentItems = RecentlyPlayedStore.getRecentIds(this)
            .mapNotNull { id -> liveChannels.firstOrNull { it.id == id } }
            .filterNot(::isAdultHomeItem)
        if (recentItems.isNotEmpty()) shelves.add(ContentShelf("Recently Played", recentItems))

        val favIds = FavoritesStore.getFavoriteSeriesIds(this)
        val favItems = seriesList.filter { it.id in favIds }
        if (favItems.isNotEmpty()) shelves.add(ContentShelf("Favorites", favItems))

        return shelves.filter { it.title !in hidden }
    }

    private fun selectTab(index: Int) {
        activeSettingsOverlay?.dismiss()
        activeSearchOverlay?.dismiss()
        activeTab = index
        showingDownloads = false
        showingDiscover = false
        // Owned here rather than by each caller. Every tab-bar handler already paired
        // "showingHome = false" with this call, so any *other* entry point (the launch
        // resume, which opens Live TV directly) left the flag set - and every later rebuild
        // that routes on it then bounced back to Home, tearing the guide and its live
        // preview down again right after they were built.
        showingHome = false
        binding.discoverContent.visibility = View.GONE
        binding.contentRow.visibility = View.VISIBLE
        binding.homeContent.visibility = View.GONE
        binding.homeSearchBar.visibility = View.GONE
        binding.liveRow.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.seriesContent.visibility = if (index == 1) View.VISIBLE else View.GONE
        binding.filmsContent.visibility = if (index == 2) View.VISIBLE else View.GONE
        binding.downloadsContent.visibility = View.GONE
        binding.downloadsEmptyText.visibility = View.GONE
        mainHandler.removeCallbacks(downloadsProgressRunnable)
        if (index == 0) {
            // releaseLivePreview() (called when leaving Live TV for any other tab) stops
            // and releases the preview player entirely - just re-showing the pane here
            // left it empty/stopped forever until something else happened to trigger a
            // reload. Explicitly reload whatever channel was last focused.
            showLivePreviewPane()
            buildGuideHeader()
            lastFocusedLiveChannel?.let { requestPreviewLoad(it) }
        } else {
            releaseLivePreview()
        }

        updateTabStyles(listOf(binding.tabLive, binding.tabSeries, binding.tabFilms)[index])

        selectedCategoryIds = null
        selectedBrandChannelIds = null
        selectedRowId = null
        selectedCategoryLabel = null
        selectedShelfItems = null
        expandedGroupKeys.clear()
        // Nothing from the outgoing tab stays on screen while the new one builds. The
        // sidebar and the content pane were both left up until submitCategories() replaced
        // them, so switching tabs showed the previous tab's categories - and, for a moment,
        // its posters - under the new tab's highlight. Both come back below, populated.
        binding.categorySidebar.visibility = View.GONE
        binding.contentRow.visibility = View.GONE
        scope.launch {
            // Building categories/filtering thousands of channels can take a couple of
            // seconds on a large catalog - show the same loading indicator as app startup
            // instead of leaving the tab looking empty/frozen while it works.
            setStatus("Loading...", visible = true)
            // Pre-expand the Sports bucket so its children are visible when the user
            // scrolls down to it, regardless of what's selected at the top.
            if (index == 0) expandedGroupKeys.add("${DYNAMIC_BUCKET_ID_PREFIX}Sports")
            val categories = buildCategoriesForActiveTab()
            if (index == 0) {
                // Land on the topmost row the user actually curated: the Favourites channel
                // row, else their highest pinned category, and only then fall back to a
                // dynamic bucket (Sports etc). Pinned rows used to be skipped entirely
                // whenever no channel was favourited, which dropped the user into Sports
                // past the categories they'd deliberately pinned to the top.
                val hasFavourites = com.lumora.cache.FavoritesStore.getFavoriteChannelIds(this@MainActivity).isNotEmpty()
                val target = categories.firstOrNull { it.id == FAVOURITES_CATEGORY_ID }?.takeIf { hasFavourites }
                    ?: categories.firstOrNull { it.pinned }
                    ?: categories.firstOrNull { it.id?.startsWith(DYNAMIC_BUCKET_ID_PREFIX) == true }
                if (target != null) {
                    selectedRowId = target.id
                    selectedCategoryLabel = target.name
                    selectedBrandChannelIds = target.channelIds.ifEmpty { null }
                    selectedCategoryIds = if (target.channelIds.isNotEmpty()) null else target.matchIds
                }
            }
            submitCategories(categories)
            applyCategoryFilter(focusFirstLiveChannel = index == 0)
            // Always scroll back to the very top of the sidebar when switching tabs, so the
            // first row (Live TV's "Show all categories" toggle, Films/Series' first row) is
            // what's on screen rather than wherever the previous tab was scrolled to - and,
            // on Live TV, rather than the auto-selected Favourites/Sports row further down,
            // which hid every row above it. The selection below it is unchanged; only the
            // scroll position is. The adapter's submitList() is async (ListAdapter diff), so
            // post() ensures the RecyclerView has laid out the new items before we scroll.
            binding.categorySidebar.post { binding.categorySidebar.scrollToPosition(0) }
            // Only now, with rows and content both in place. submitCategories() decides
            // whether the sidebar is warranted at all (a single row isn't worth one), so
            // don't override its call here.
            binding.contentRow.visibility = View.VISIBLE
            setStatus("", visible = false)
        }
    }

    // ── Lists ──────────────────────────────────────

    private fun buildGuideHeader() {
        val density = resources.displayMetrics.density
        val slotWidthPx = (30 * LiveGuideAdapter.MINUTE_WIDTH_DP * density).toInt()
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val calendar = java.util.Calendar.getInstance()

        binding.guideHeaderRow.removeAllViews()
        repeat(10) { index ->
            val label = TextView(this).apply {
                text = if (index == 0) "Now" else timeFmt.format(calendar.time)
                setTextColor(getColor(R.color.text_tertiary))
                // Built in code, so it needs the dimen read explicitly to pick up the
                // large-screen tier that the XML-inflated guide rows below it get for free.
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.guide_program_text))
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((6 * density).toInt(), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(slotWidthPx, LinearLayout.LayoutParams.MATCH_PARENT)
            }
            binding.guideHeaderRow.addView(label)
            calendar.add(java.util.Calendar.MINUTE, 30)
        }
        liveAdapter.attachHeader(binding.guideHeaderScroll)
    }

    private fun setupChannelList() {
        binding.liveContent.layoutManager = LinearLayoutManager(this)
        binding.liveContent.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { updateGuideRowWrap() }
        })
        binding.liveContent.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) { binding.liveContent.post { updateGuideRowWrap() } }
            override fun onChildViewDetachedFromWindow(view: View) {}
        })
        binding.seriesContent.layoutManager = LinearLayoutManager(this)
        binding.filmsContent.layoutManager = LinearLayoutManager(this)
        binding.categorySidebar.layoutManager = LinearLayoutManager(this)
        binding.homeContent.layoutManager = LinearLayoutManager(this)
        binding.downloadsContent.layoutManager = LinearLayoutManager(this)
        binding.downloadsContent.adapter = downloadAdapter
    }

    private fun playItem(channel: Channel) {
        when (channel.mediaType) {
            MediaType.SERIES -> { showContentDetail(channel); return }
            MediaType.MOVIE -> { showContentDetail(channel); return }
            else -> {}
        }
        val idx = liveChannels.indexOf(channel)
        if (idx >= 0) { currentIndex = idx; showPlayerFor(channel) }
    }

    /** One series' details and season/episode lists, whichever backend it came from. Jellyfin
     *  and Stalker carry plot/date/rating on the catalog item itself (no per-series detail
     *  endpoint); Xtream's get_series_info returns both in one call.
     *
     *  Shared by the detail screen and the in-player version picker - the picker has to pull
     *  a *different* provider's copy of the same show on demand to find its matching episode,
     *  which is exactly this call against a different Channel. */
    private suspend fun loadSeriesContent(
        item: Channel
    ): Pair<XtreamClient.ContentDetails?, List<Pair<String, List<Channel>>>> {
        val itemDetails = XtreamClient.ContentDetails(
            plot = item.description,
            genre = item.categoryName,
            rating = item.rating,
            backdropUrl = item.backdropUrl,
            releaseDate = item.releaseDate
        )
        val stalkerConfig = stalkerConfigFor(item)
        return when {
            // Anime catalog: build a flat episode list from the total episode count
            // carried on the Channel. Each episode click triggers a plugin stream search
            // for that specific episode (see showContentDetail's onEpisodeClick).
            item.id.startsWith(AnimeCatalogClient.ID_PREFIX) -> {
                val epCount = item.episodeNum?.coerceAtLeast(1) ?: 12
                val episodes = (1..epCount).map { epNum ->
                    Channel(
                        id = "${item.id}:ep$epNum",
                        name = "Episode $epNum",
                        url = "",
                        posterUrl = item.posterUrl,
                        backdropUrl = item.backdropUrl,
                        mediaType = MediaType.SERIES,
                        episodeNum = epNum,
                        categoryName = item.categoryName,
                        group = item.group
                    )
                }
                itemDetails to listOf("Season 1" to episodes)
            }
            item.isJellyfin -> {
                val jellyfin = jellyfinClientOrConnect()
                val (episodes, seasons) = if (jellyfin != null) {
                    withContext(Dispatchers.IO) { jellyfin.getEpisodes(item.id) to jellyfin.getSeasons(item.id) }
                } else {
                    emptyList<JellyfinProvider.JellyfinItem>() to emptyList()
                }
                val stub = jellyfinProviderStub(jellyfinServerUrl())
                // Watched/resume state for these episodes comes from the same UserData the
                // catalog fetch reads, so an episode list opened here shows progress made in
                // any other client (EpisodeAdapter reads it out of PlaybackPositionStore).
                importJellyfinUserState(episodes, includePlayed = true)
                // Season *names* come from the server - grouping on ParentIndexNumber alone
                // can only ever produce "Season 0" for specials, which is not what any
                // Jellyfin library calls that row.
                val seasonNames = seasons.mapNotNull { season ->
                    season.indexNumber?.let { it to season.name }
                }.toMap()
                itemDetails to episodes
                    .groupBy { it.seasonNumber ?: 0 }
                    .toSortedMap()
                    .map { (num, eps) ->
                        val label = seasonNames[num] ?: if (num == 0) "Specials" else "Season $num"
                        label to eps.map { JellyfinProvider.toChannel(it, stub) }
                    }
            }
            stalkerConfig != null -> {
                val stalker = StalkerProvider(BaseApplication.instance.okHttpClient)
                itemDetails to withContext(Dispatchers.IO) {
                    stalker.getEpisodes(stalkerProviderStub(stalkerConfig), item.id, item.categoryId)
                        .map { (label, eps) ->
                            label to eps.map { it.copy(streamUserAgent = stalkerConfig.userAgent, sourceProviderId = stalkerConfig.id) }
                        }
                }
            }
            else -> {
                val client = XtreamClient(BaseApplication.instance.okHttpClient)
                val info = withContext(Dispatchers.IO) { client.getSeriesFull(xtreamProviderFor(item) ?: provider, item.id) }
                info.details to info.seasons
            }
        }
    }

    /** Chip label for one version of a duplicated title: which provider it came from first,
     *  since that's what actually distinguishes two copies once several providers are merged,
     *  then the title's own source/quality tag ("4K-D+") when it has one. */
    private fun versionChipLabel(version: Channel, index: Int): String =
        listOfNotNull(providerNameFor(version), extractLeadingTag(version.name))
            .joinToString(" · ")
            .ifBlank { "Version ${index + 1}" }

    /** One version-picker chip, styled to sit inline next to Play. item_category's own text
     *  size is the sidebar's, so it's stepped down to the general caption dimen (still scales
     *  up on a large screen). */
    private fun inflateVersionChip(parent: ViewGroup, label: String): TextView {
        val chip = layoutInflater.inflate(R.layout.item_category, parent, false) as TextView
        chip.text = label
        chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_caption))
        val padH = (12 * resources.displayMetrics.density).toInt()
        val padV = (8 * resources.displayMetrics.density).toInt()
        chip.setPadding(padH, padV, padH, padV)
        chip.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
        return chip
    }

    /** Unified detail screen for a movie or series: poster/plot/cast plus its versions or
     *  episode list. [versionGroup] carries the duplicate set through when the screen re-opens
     *  on a sibling copy (series only - see the series version chips below), since the map is
     *  keyed by the group's representative and a sibling isn't in it. */
    private fun showContentDetail(item: Channel, versionGroup: List<Channel>? = null) {
        isContentDetailVisible = true
        binding.mainContent.visibility = View.GONE
        binding.contentDetailLayout.visibility = View.VISIBLE

        val backdrop = binding.detailBackdrop
        val titleText = binding.detailTitle
        val metaText = binding.detailMeta
        val plotText = binding.detailPlot
        val castText = binding.detailCast
        val plotLabel = binding.detailPlotLabel
        val castLabel = binding.detailCastLabel
        val releaseDateText = binding.detailReleaseDate
        val sectionLabel = binding.detailSectionLabel
        val statusText = binding.detailStatus
        val itemsList = binding.detailItemsList
        val seasonScroll = binding.detailSeasonScroll
        val seasonRow = binding.detailSeasonRow
        val playButton = binding.detailPlayButton
        val playButtonLabel = binding.detailPlayButtonLabel
        val favoriteButton = binding.detailFavoriteButton
        val favoriteIcon = binding.detailFavoriteIcon
        val versionsScroll = binding.detailVersionsScroll
        val versionsRow = binding.detailVersionsRow
        val downloadButton = binding.detailDownloadButton

        // These views are reused across opens now (no longer a fresh dialog each time) -
        // reset everything a previous item may have left behind before showing new data.
        backdrop.setImageDrawable(null)
        plotText.visibility = View.GONE
        castText.visibility = View.GONE
        plotLabel.visibility = View.GONE
        castLabel.visibility = View.GONE
        releaseDateText.visibility = View.GONE
        playButton.visibility = View.GONE
        // Only the series path (wirePlayButton) ever writes this, tagging it with the
        // episode it would resume - "Resume S1E1". These views are reused across opens, so
        // without a reset that tag stayed on the button when a *film* was opened next.
        playButtonLabel.text = "Play"
        favoriteButton.visibility = View.GONE
        downloadButton.visibility = View.GONE
        downloadButton.setOnClickListener(null)
        seasonScroll.visibility = View.GONE
        seasonRow.removeAllViews()
        selectedSeasonChip = null
        versionsScroll.visibility = View.GONE
        versionsRow.removeAllViews()
        sectionLabel.text = ""
        itemsList.visibility = View.GONE
        itemsList.adapter = null
        statusText.text = getString(R.string.loading)
        statusText.visibility = View.VISIBLE
        playButton.setOnClickListener(null)
        binding.detailBackButton.setOnClickListener { hideContentDetail() }
        // Nothing requests focus just because contentDetailLayout became visible - without
        // this the D-pad has no reliable starting point on this screen (same class of bug
        // fixed elsewhere via restoreTabFocus()). Landing on Play once it loads is more
        // useful than the back button, so this gets overridden below once it's known visible.
        binding.detailBackButton.requestFocus()

        val isSeries = item.mediaType == MediaType.SERIES
        titleText.text = item.name
        metaText.text = listOfNotNull(
            item.year,
            item.rating?.takeIf { it.isNotBlank() }?.let { "★ $it" },
            item.categoryName?.takeIf { it.isNotBlank() }
        ).joinToString("  ·  ")
        itemsList.layoutManager = LinearLayoutManager(this)
        loadDetailImage(item.posterUrl ?: item.logoUrl, backdrop)
        wireFindStreamButton(item)
        wireTrailerButton(item)

        // Series version chips. A film's versions are alternate streams of one thing, so its
        // chips play directly; a series' are whole separate episode lists, one per provider
        // that carries the title, so picking one re-opens this screen on that copy instead.
        // Before this, every duplicate but the representative was dropped at grouping time -
        // if the copy that won the card had a thin or broken episode list, the other
        // provider's was unreachable.
        val seriesGroup = if (isSeries) versionGroup ?: seriesVersions[item.id] else null
        if (seriesGroup != null && seriesGroup.size > 1) {
            versionsScroll.visibility = View.VISIBLE
            seriesGroup.forEachIndexed { index, version ->
                val chip = inflateVersionChip(versionsRow, versionChipLabel(version, index))
                chip.isSelected = version.id == item.id
                chip.setOnClickListener {
                    if (version.id != item.id) showContentDetail(version, seriesGroup)
                }
                versionsRow.addView(chip)
            }
        }

        if (isSeries && item.id.isNotBlank()) {
            favoriteButton.visibility = View.VISIBLE
            fun refreshFavoriteIcon() {
                favoriteIcon.text = if (FavoritesStore.isFavoriteSeries(this, item.id)) "★" else "☆"
            }
            refreshFavoriteIcon()
            favoriteButton.setOnClickListener {
                val nowFavorite = FavoritesStore.toggleFavoriteSeries(this, item.id)
                refreshFavoriteIcon()
                // A Jellyfin item's favourite state belongs to the server - push it so the
                // star shows up in every other client, and survives a reinstall here.
                if (item.isJellyfin && item.id.isNotBlank()) {
                    scope.launch {
                        val client = jellyfinClientOrConnect() ?: return@launch
                        withContext(Dispatchers.IO) { runCatching { client.setFavorite(item.id, nowFavorite) } }
                    }
                }
                scope.launch { classifyAndShow() }
            }
        }

        lateinit var itemAdapter: EpisodeAdapter
        itemAdapter = EpisodeAdapter(
            onEpisodeClick = { chosen ->
                hideContentDetail()
                // Anime items have no direct stream URL — route through plugin.
                if (item.id.startsWith(AnimeCatalogClient.ID_PREFIX)) {
                    val plugin = enabledStreamSearchPlugin(item)
                    if (plugin != null) {
                        showStreamSearchDialog(plugin, item, season = null, episode = chosen.episodeNum)
                    }
                } else {
                    currentIndex = if (isSeries) -1 else filmList.indexOf(item)
                    val queue = if (isSeries) itemAdapter.currentList else emptyList()
                    showPlayerFor(chosen)
                    detailReturnItem = item
                    detailReturnGroup = seriesGroup
                    if (isSeries) {
                        currentEpisodeQueue = queue
                        currentEpisodeQueueIndex = queue.indexOf(chosen)
                        currentSeriesVersionContext = item to (seriesGroup ?: listOf(item))
                    }
                }
            },
            showDownloadButton = !isTv,
            onDownloadClick = { episode -> downloadItem(episode) },
            isDownloaded = { episode -> DownloadStore.get(this, episode.id) != null },
            seriesName = if (isSeries) item.name else null
        )
        itemsList.adapter = itemAdapter

        fun applyDetails(details: XtreamClient.ContentDetails?) {
            if (details == null) return
            if (!details.releaseDate.isNullOrBlank()) {
                releaseDateText.text = "Released: ${details.releaseDate}"
                releaseDateText.visibility = View.VISIBLE
            }
            if (!details.plot.isNullOrBlank()) {
                plotText.text = details.plot
                plotText.visibility = View.VISIBLE
                plotLabel.visibility = View.VISIBLE
            }
            val castLine = listOfNotNull(
                details.genre?.takeIf { it.isNotBlank() }?.let { "Genre: $it" },
                details.director?.takeIf { it.isNotBlank() }?.let { "Director: $it" },
                details.cast?.takeIf { it.isNotBlank() }?.let { "Cast: $it" }
            ).joinToString("\n")
            if (castLine.isNotBlank()) {
                castText.text = castLine
                castText.visibility = View.VISIBLE
                castLabel.visibility = View.VISIBLE
            }
            if (!details.backdropUrl.isNullOrBlank()) loadDetailImage(details.backdropUrl, backdrop)
        }

        fun showSeason(seasons: List<Pair<String, List<Channel>>>, index: Int) {
            for (i in 0 until seasonRow.childCount) {
                val chip = seasonRow.getChildAt(i)
                chip.isSelected = i == index
                // The focus "pop" is a stateListAnimator, so a chip that loses focus in the
                // same frame it's clicked can keep the scaled-up transform the animator was
                // mid-way through - leaving a visibly enlarged leftover chip behind after
                // switching seasons. Snap every chip back to rest; the animator re-applies
                // from there on the next real focus change.
                chip.animate().cancel()
                chip.scaleX = 1f
                chip.scaleY = 1f
            }
            // UP escaping the episode list's first row jumps straight to this chip rather
            // than through default focus search - see dispatchKeyEvent's episode-list block.
            selectedSeasonChip = seasonRow.getChildAt(index)
            itemAdapter.submitList(seasons[index].second)
        }

        // Series had no equivalent of the film branch's Play button below - the only
        // action on the whole screen was the small favorite star, with no way to jump
        // straight into playback without first picking a season/episode manually. Finds
        // whichever episode was left in progress most recently (across every season, not
        // just the one currently shown), or falls back to the very first episode if
        // nothing's been started yet.
        fun wirePlayButton(seasons: List<Pair<String, List<Channel>>>) {
            val allEpisodes = seasons.flatMap { it.second }
            val inProgress = allEpisodes.mapNotNull { ep ->
                val key = ep.id.ifBlank { ep.url }
                if (key.isBlank()) return@mapNotNull null
                PlaybackPositionStore.get(this, key)
                    ?.takeIf { !it.isNearComplete && it.positionMs > 0 }
                    ?.let { ep to it }
            }.maxByOrNull { it.second.updatedAt }
            val target = inProgress?.first ?: allEpisodes.firstOrNull() ?: return
            val seasonPair = seasons.firstOrNull { (_, eps) -> eps.any { it.id == target.id } }
            val seasonNum = seasonPair?.first?.let { Regex("""\d+""").find(it)?.value }
            // "Play"/"Resume" alone didn't say *which* episode - with several seasons in
            // play this was a guessing game before committing to it.
            val tag = if (seasonNum != null && target.episodeNum != null) "S${seasonNum}E${target.episodeNum}" else null
            playButtonLabel.text = listOfNotNull(if (inProgress != null) "Resume" else "Play", tag).joinToString(" ")
            playButton.visibility = View.VISIBLE
            playButton.requestFocus()
            playButton.setOnClickListener {
                hideContentDetail()
                // Anime items route through the plugin instead of direct playback.
                if (item.id.startsWith(AnimeCatalogClient.ID_PREFIX)) {
                    val plugin = enabledStreamSearchPlugin(item)
                    if (plugin != null) {
                        showStreamSearchDialog(plugin, item, season = null, episode = target.episodeNum)
                    }
                } else {
                    currentIndex = -1
                    val queue = seasonPair?.second ?: allEpisodes
                    showPlayerFor(target)
                    detailReturnItem = item
                    detailReturnGroup = seriesGroup
                    currentEpisodeQueue = queue
                    currentEpisodeQueueIndex = queue.indexOf(target)
                    currentSeriesVersionContext = item to (seriesGroup ?: listOf(item))
                }
            }
        }

        val requestedItemId = item.id
        val isJellyfin = item.isJellyfin
        scope.launch {
            try {
                val client = XtreamClient(BaseApplication.instance.okHttpClient)
                if (isSeries) {
                    sectionLabel.text = "Episodes"
                    val (details, seasons) = loadSeriesContent(item)
                    if (nowShowingDetailId != requestedItemId) return@launch
                    applyDetails(details)
                    if (seasons.all { it.second.isEmpty() }) {
                        statusText.text = "No episodes found"
                    } else {
                        statusText.visibility = View.GONE
                        itemsList.visibility = View.VISIBLE
                        if (seasons.size > 1) {
                            seasonScroll.visibility = View.VISIBLE
                            seasons.forEachIndexed { index, (label, _) ->
                                val chip = layoutInflater.inflate(R.layout.item_season_chip, seasonRow, false) as TextView
                                chip.text = label
                                chip.layoutParams = LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                                ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
                                chip.setOnClickListener { showSeason(seasons, index) }
                                seasonRow.addView(chip)
                            }
                        }
                        showSeason(seasons, 0)
                        wirePlayButton(seasons)
                    }
                } else {
                    // Xtream has a separate get_vod_info call for a film's plot/cast/genre;
                    // Jellyfin has no equivalent (nor any need for one) - the item already
                    // carries all of that from the catalog fetch, same as the series branch
                    // above. Calling getVodInfo() here regardless of provider used to send
                    // an Xtream-shaped request with a Jellyfin item id to an Xtream-only
                    // endpoint, which is why overview/cast/genre came back empty for every
                    // Jellyfin film.
                    // getVodInfo is an Xtream-only call. Jellyfin and Stalker both carry the
                    // film's plot/date/rating on the catalog item itself, so use that - sending
                    // an Xtream-shaped get_vod_info for a Stalker item hit the wrong endpoint
                    // and came back empty, which is why overview/release date were blank.
                    val itemXtream = xtreamProviderFor(item)
                    val details = if (isJellyfin || itemXtream == null) {
                        XtreamClient.ContentDetails(
                            plot = item.description,
                            genre = item.categoryName,
                            rating = item.rating,
                            backdropUrl = item.backdropUrl,
                            releaseDate = item.releaseDate
                        )
                    } else {
                        withContext(Dispatchers.IO) { client.getVodInfo(itemXtream, item.id) }
                    }
                    if (nowShowingDetailId != requestedItemId) return@launch
                    applyDetails(details)
                    val versions = filmVersions[item.id] ?: listOf(item)
                    statusText.visibility = View.GONE

                    // The obvious action for a film is "play it" - a button, not a list
                    // labeled "Versions" with one cryptically-named entry in it.
                    // No episode tag to add here (that's series-only), but a part-watched
                    // film should still read "Resume" rather than "Play", same as one does
                    // in the Continue Watching shelf it was probably reached from.
                    val filmKey = item.id.ifBlank { item.url }
                    val filmProgress = filmKey.takeIf { it.isNotBlank() }
                        ?.let { PlaybackPositionStore.get(this@MainActivity, it) }
                        ?.takeIf { !it.isNearComplete && it.positionMs > 0 }
                    playButtonLabel.text = if (filmProgress != null) "Resume" else "Play"
                    playButton.visibility = View.VISIBLE
                    playButton.requestFocus()
                    playButton.setOnClickListener {
                        hideContentDetail()
                        currentIndex = filmList.indexOf(item)
                        showPlayerFor(versions.first())
                        detailReturnItem = item
                        detailReturnGroup = versionGroup
                    }
                    if (!isTv) {
                        downloadButton.visibility = View.VISIBLE
                        downloadButton.setOnClickListener { downloadItem(versions.first()) }
                    }
                    // Version picker sits right next to Play as small chips, not buried
                    // in a full-width list below the plot/cast - tapping one plays that
                    // specific version directly instead of requiring a second Play tap.
                    if (versions.size > 1) {
                        versionsScroll.visibility = View.VISIBLE
                        versions.forEachIndexed { index, version ->
                            val chip = inflateVersionChip(versionsRow, versionChipLabel(version, index))
                            chip.isSelected = index == 0
                            chip.setOnClickListener {
                                hideContentDetail()
                                currentIndex = filmList.indexOf(item)
                                showPlayerFor(version)
                                detailReturnItem = item
                                detailReturnGroup = versionGroup
                            }
                            versionsRow.addView(chip)
                        }
                    }
                }
            } catch (e: Exception) {
                if (nowShowingDetailId == requestedItemId) statusText.text = "Failed to load details: ${e.message?.take(60)}"
            }
        }
        nowShowingDetailId = item.id
    }

    private fun hideContentDetail() {
        isContentDetailVisible = false
        nowShowingDetailId = null
        binding.contentDetailLayout.visibility = View.GONE
        binding.mainContent.visibility = View.VISIBLE
        restoreTabFocus()
    }

    /**
     * Whenever a fullscreen overlay (player, content detail) closes, or a tab/category
     * switches, something must explicitly claim Android focus again or D-pad input goes
     * inert with no visible sign why - the previously-focused view is very often gone
     * (recycled) by the time we get back here. Re-focusing the active tab is a safe,
     * always-valid fallback regardless of what closed.
     */
    // ── EPG Source List Dialog ──────────────────────

    private fun showEpgSourceListDialog() {
        scope.launch {
            val sources = withContext(Dispatchers.IO) { database.epgSourceDao().getAll() }
            if (sources.isEmpty()) {
                Toast.makeText(this@MainActivity, "No EPG sources. Add one.", Toast.LENGTH_SHORT).show()
                showAddEpgSourceDialog()
                return@launch
            }
            val names = sources.map { "${it.name} (${it.url.take(40)}...)" }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("EPG Sources")
                .setItems(names) { _, which ->
                    val source = sources[which]
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Delete source?")
                        .setMessage("Remove \"${source.name}\"?")
                        .setPositiveButton("Delete") { _, _ ->
                            scope.launch { database.epgSourceDao().delete(source) }
                            Toast.makeText(this@MainActivity, "EPG source deleted", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                .setPositiveButton("Add", { _, _ -> showAddEpgSourceDialog() })
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun restoreTabFocus() {
        val target = when {
            showingHome -> binding.tabHome
            showingDiscover -> binding.tabDiscover
            showingDownloads -> binding.tabDownloads
            activeTab == 0 -> binding.tabLive
            activeTab == 1 -> binding.tabSeries
            else -> binding.tabFilms
        }
        target.post { target.requestFocus() }
    }

    private fun loadDetailImage(url: String?, imageView: ImageView) {
        if (url.isNullOrBlank()) return
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder().url(url).build()
                    BaseApplication.instance.okHttpClient.newCall(request).execute()
                        .body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (bitmap != null) imageView.setImageBitmap(bitmap)
        }
    }

    // ── Track selection ─────────────────────────────

    private fun showTrackPicker(isAudio: Boolean) {
        val player = playerManager.getExoPlayer()
        val tracks = if (isAudio) trackController.audioTracks(player) else trackController.subtitleTracks(player)

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (!isAudio) {
            labels.add("Off")
            actions.add { trackController.selectSubtitleTrack(player, null) }
        }
        tracks.forEach { track ->
            labels.add(track.name)
            actions.add {
                if (isAudio) trackController.selectAudioTrack(player, track.id)
                else trackController.selectSubtitleTrack(player, track.id)
            }
        }

        if (tracks.isEmpty()) {
            Toast.makeText(this, if (isAudio) "No alternate audio tracks" else "No subtitles available", Toast.LENGTH_SHORT).show()
            return
        }

        val checkedIndex = if (isAudio) {
            tracks.indexOfFirst { it.isSelected }
        } else {
            val selected = tracks.indexOfFirst { it.isSelected }
            if (selected >= 0) selected + 1 else 0
        }

        AlertDialog.Builder(this)
            .setTitle(if (isAudio) "Audio Track" else "Subtitles")
            .setSingleChoiceItems(labels.toTypedArray(), checkedIndex) { dialog, which ->
                actions[which]()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Toolbar ────────────────────────────────────

    private fun setupToolbar() {
        binding.btnSettings.setOnClickListener { showProviderSettings() }
        binding.btnRefresh.setOnClickListener { reloadCurrentProvider() }
        binding.btnSearch.setOnClickListener { showSearchDialog() }
        binding.emptyQrPair.setOnClickListener { showProviderSettings() }
        binding.homeSearchBar.setOnClickListener { showSearchDialog() }
    }

    // ── Search ───────────────────────────────────────

    private fun showSearchDialog() {
        // Search and Settings are sibling content slots in the same weighted LinearLayout, so if
        // Settings is still up when Search opens, the layout splits the content area between the
        // two and they render on top of each other. The toolbar stays usable over Settings by
        // design, so opening Search here has to close Settings first.
        activeSettingsOverlay?.dismiss()
        val searchView = layoutInflater.inflate(R.layout.dialog_search, null)
        val input = searchView.findViewById<EditText>(R.id.searchInput)
        val statusText = searchView.findViewById<TextView>(R.id.searchStatus)
        val resultsList = searchView.findViewById<RecyclerView>(R.id.searchResults)

        val keyboard = searchView.findViewById<com.lumora.ui.OnScreenKeyboard>(R.id.searchKeyboard)
        applyPanelWidth(searchView.findViewById(R.id.searchPanel), R.dimen.search_panel_width)

        // The platform IME on a Fire TV opens full-screen over the app and takes focus with
        // it, so the query and its results can never be on screen together. Suppress it and
        // drive the field from the on-screen keyboard instead. The field stays focusable so
        // a paired bluetooth/USB keyboard still types into it normally.
        input.showSoftInputOnFocus = false
        fun replaceQuery(text: String) = input.setText(text)
        keyboard.onKey = { ch -> replaceQuery(input.text.toString() + ch) }
        keyboard.onBackspace = { replaceQuery(input.text.toString().dropLast(1)) }
        keyboard.onClear = { replaceQuery("") }
        // A paired bluetooth/USB keyboard can't type into the field any more (it isn't
        // focusable), so the Activity forwards printable keys and backspace here instead.
        searchKeyHandler = { ch -> if (ch == null) keyboard.onBackspace?.invoke() else replaceQuery(input.text.toString() + ch) }

        // Fixed span: the grid shares the panel with the keyboard now, so overall screen
        // width no longer describes the space it actually has.
        val dialogSpanCount = resources.getInteger(R.integer.search_results_span)
        resultsList.layoutManager = GridLayoutManager(this, dialogSpanCount)
        val resultsAdapter = PosterGridAdapter(showTypeBadge = true) { item ->
            activeSearchOverlay?.dismiss()
            if (item.mediaType == MediaType.LIVE) playItem(item) else showContentDetail(item)
        }
        resultsAdapter.spanCount = dialogSpanCount
        resultsAdapter.topRowFocusUpTargetId = R.id.searchInput
        resultsAdapter.posterHeightDimen = R.dimen.search_poster_image_height
        resultsList.adapter = resultsAdapter

        val overlay = FullScreenOverlay(
            binding.searchContainer,
            searchView,
            closeButton = searchView.findViewById(R.id.searchCloseButton),
            // The keyboard, not the input box: with no IME involved, landing on the field
            // gives a caret and nothing to type with, and every session would start with a
            // wasted press down into the keys.
            initialFocus = { keyboard.firstKey() ?: input }
        )
        binding.homeContent.visibility = View.GONE
        binding.homeSearchBar.visibility = View.GONE
        binding.discoverContent.visibility = View.GONE
        binding.contentRow.visibility = View.GONE
        binding.emptyState.visibility = View.GONE

        var searchRunnable: Runnable? = null
        // Incremental scroll: load more results as user scrolls near the bottom.
        resultsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || searchDisplayedCount >= searchAllResults.size) return
                val lm = recyclerView.layoutManager as GridLayoutManager
                val lastVisible = lm.findLastVisibleItemPosition()
                if (lastVisible >= lm.itemCount - 6) {
                    loadMoreSearchResults(resultsAdapter, statusText)
                }
            }
        })

        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchRunnable?.let { mainHandler.removeCallbacks(it) }
                val query = s?.toString()?.trim().orEmpty()
                if (query.length < 2) {
                    statusText.text = "Type to search"
                    statusText.visibility = View.VISIBLE
                    resultsList.visibility = View.GONE
                    searchAllResults = emptyList()
                    searchDisplayedCount = 0
                    return
                }
                // Fires as-you-type with a short debounce, not on submit - a large catalog
                // filter is cheap enough (Dispatchers.Default, a few thousand items) that
                // waiting for the user to stop typing is the only real cost here.
                val runnable = Runnable { runSearch(query, resultsAdapter, statusText, resultsList) }
                searchRunnable = runnable
                mainHandler.postDelayed(runnable, 200)
            }
        })
        overlay.setOnDismissListener {
            searchRunnable?.let { mainHandler.removeCallbacks(it) }
            searchKeyHandler = null
            activeSearchOverlay = null
            if (showingHome) selectHome() else if (showingDiscover) selectDiscover() else if (showingDownloads) selectDownloads() else selectTab(activeTab)
        }
        activeSearchOverlay = overlay
        overlay.show()
    }

    /** Ranks a title's relevance to [query] (lower is better) - exact match first, then
     *  "starts with", then matching at a word boundary ("man" hits "Iron Man" but not
     *  "Batman"), plain substring last. Word-boundary keeps a query like "man" usable on
     *  a catalog with thousands of vaguely-matching substrings instead of it being buried. */
    private fun searchRank(name: String, query: String): Int {
        val lower = name.lowercase()
        return when {
            lower == query -> 0
            lower.startsWith(query) -> 1
            Regex("\\b${Regex.escape(query)}").containsMatchIn(lower) -> 2
            else -> 3
        }
    }

    private fun runSearch(query: String, adapter: PosterGridAdapter, statusText: TextView, resultsList: RecyclerView) {
        statusText.text = "Searching…"
        statusText.visibility = View.VISIBLE
        resultsList.visibility = View.GONE
        searchAllResults = emptyList()
        searchDisplayedCount = 0
        scope.launch {
            val results = withContext(Dispatchers.Default) {
                val lower = query.lowercase()
                (liveChannels + filmList + seriesList)
                    .filter { it.name.lowercase().contains(lower) }
                    .sortedWith(compareBy({ searchRank(it.name, lower) }, { it.name.lowercase() }))
            }
            if (results.isEmpty()) {
                statusText.text = "No results for \"$query\""
                statusText.visibility = View.VISIBLE
                resultsList.visibility = View.GONE
            } else {
                searchAllResults = results
                val total = results.size
                val batchSize = 50
                val firstBatch = results.take(batchSize)
                searchDisplayedCount = firstBatch.size
                statusText.text = "${searchDisplayedCount}/$total results"
                statusText.visibility = View.VISIBLE
                resultsList.visibility = View.VISIBLE
                adapter.submitList(firstBatch)
            }
        }
    }

    private fun loadMoreSearchResults(adapter: PosterGridAdapter, statusText: TextView) {
        val remaining = searchAllResults.size - searchDisplayedCount
        if (remaining <= 0) return
        val batchSize = 50.coerceAtMost(remaining)
        val currentList = adapter.currentList.toMutableList()
        currentList.addAll(searchAllResults.subList(searchDisplayedCount, searchDisplayedCount + batchSize))
        searchDisplayedCount += batchSize
        adapter.submitList(currentList)
        statusText.text = "${searchDisplayedCount}/${searchAllResults.size} results"
    }

    private fun reloadCurrentProvider() {
        loadAllConfiguredProviders(forceRefresh = true)
    }

    // ── Player ─────────────────────────────────────

    private fun setupPlayerControls() {
        binding.btnPlayPause.setOnClickListener { playerManager.togglePlayPause(); updatePlayPauseIcon() }
        binding.btnPrevChannel.setOnClickListener { navigateChannel(-1) }
        binding.btnNextChannel.setOnClickListener { navigateChannel(1) }
        binding.btnBack.setOnClickListener { hidePlayer() }
        binding.btnAudioTrack.setOnClickListener { showTrackPicker(isAudio = true) }
        binding.btnSubtitleTrack.setOnClickListener { showTrackPicker(isAudio = false) }
        binding.btnChapters.setOnClickListener { showChapterPicker() }
        binding.btnLiveVersions.setOnClickListener { showVersionPicker() }
        binding.btnRewind.setOnClickListener { playerManager.seekBy(-15_000); showControls() }
        binding.btnFastForward.setOnClickListener { playerManager.seekBy(30_000); showControls() }
        applyAspectMode(loadSavedAspectMode())
        binding.btnAspectRatio.setOnClickListener { cycleAspectMode() }

        // Speed control
        speedController = com.lumora.player.playback.PlaybackSpeedController(playerManager.getExoPlayer())
        binding.btnSpeed.setOnClickListener {
            val speeds = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
            val currentSpeed = speedController.currentSpeed
            val checkedIndex = when {
                currentSpeed <= 0.5f -> 0; currentSpeed <= 0.75f -> 1; currentSpeed <= 1.0f -> 2
                currentSpeed <= 1.25f -> 3; currentSpeed <= 1.5f -> 4; else -> 5
            }
            AlertDialog.Builder(this)
                .setTitle("Playback Speed")
                .setSingleChoiceItems(speeds, checkedIndex) { dialog, which ->
                    val speed = when (which) { 0 -> 0.5f; 1 -> 0.75f; 2 -> 1.0f; 3 -> 1.25f; 4 -> 1.5f; else -> 2.0f }
                    speedController.setSpeed(speed)
                    binding.btnSpeed.text = String.format("%.1fx", speed)
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Sleep timer
        sleepTimer = com.lumora.player.playback.SleepTimer(playerManager.getExoPlayer()).apply {
            onTickCallback = { display -> binding.btnSleepTimer.text = display }
        }
        binding.btnSleepTimer.setOnClickListener {
            val presets = arrayOf("Off", "15 min", "30 min", "45 min", "60 min", "90 min", "120 min")
            val checkedIndex = sleepTimer.currentPreset.ordinal
            AlertDialog.Builder(this)
                .setTitle("Sleep Timer")
                .setSingleChoiceItems(presets, checkedIndex) { dialog, which ->
                    val preset = com.lumora.player.playback.SleepTimer.Preset.entries[which]
                    sleepTimer.start(preset)
                    binding.btnSleepTimer.text = if (preset == com.lumora.player.playback.SleepTimer.Preset.OFF) "Sleep" else presets[which]
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Up Next - Play Now / Cancel buttons
        binding.upNextPlayNow.setOnClickListener {
            cancelUpNextCountdown()
            executeUpNextAdvance()
        }
        binding.upNextCancel.setOnClickListener {
            cancelUpNext()
        }

        // Cast — uses MediaRouteButton which shows a device picker on tap.
        // Hidden on Android TV because the TV itself is a Cast receiver, not a sender.
        if (isTv) {
            binding.btnCast.visibility = View.GONE
        } else {
            castManager = com.lumora.player.CastManager(this).apply {
                init()
                onCastSessionConnected = { session ->
                    val channel = nowPlayingChannel
                    if (channel != null) {
                        if (castChannel(channel, channel.name)) {
                            playerManager.pause()
                        } else {
                            Toast.makeText(this@MainActivity, "Cast failed: check TV and try again", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Play content first, then Cast", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            try {
                com.google.android.gms.cast.framework.CastButtonFactory.setUpMediaRouteButton(
                    this, binding.btnCast
                )
            } catch (_: Exception) {
                binding.btnCast.visibility = View.GONE
            }
        }

        // Diagnostics
        binding.btnDiagnostics.setOnClickListener {
            val snapshot = playerDiagnostics.getSnapshot()
            val diag = """
                |Decoder: ${snapshot.videoDecoder}
                |Video: ${snapshot.videoFormat}
                |Audio: ${snapshot.audioFormat}
                |Stalls: ${snapshot.stallCount} (${snapshot.totalStallDuration / 1000}s)
                |State: ${snapshot.playbackState}
            """.trimMargin()
            AlertDialog.Builder(this)
                .setTitle("Player Diagnostics")
                .setMessage(diag)
                .setPositiveButton("OK", null)
                .show()
        }

        // Record button
        binding.btnRecord.setOnClickListener {
            if (nowPlayingChannel?.mediaType != MediaType.LIVE) {
                Toast.makeText(this, "Recording is only available for live TV", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val channel = nowPlayingChannel ?: return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Schedule Recording")
                .setMessage("Record \"${channel.name}\"?")
                .setPositiveButton("Record for 2 hours") { _, _ ->
                    val recEntry = com.lumora.recording.RecordingScheduler.createRecording(
                        channelId = channel.id,
                        channelName = channel.name,
                        programTitle = channel.name,
                        startTimeUtc = System.currentTimeMillis() / 1000,
                        stopTimeUtc = (System.currentTimeMillis() / 1000) + 7200
                    )
                    com.lumora.recording.RecordingScheduler.schedule(this, recEntry)
                    scope.launch {
                        database.recordingDao().insert(recEntry)
                    }
                    Toast.makeText(this, "Recording scheduled", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var tracking = false
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                // Preview the frame at the scrub target while the bar is being moved - by the
                // user (touch drag or D-pad, both of which arrive as fromUser) rather than by
                // the 1s progress tick, which would flash a thumbnail during normal playback.
                if (!u) return
                val duration = playerManager.duration
                if (duration <= 0) return
                showTrickplayPreview(duration * p / 100)
            }
            override fun onStartTrackingTouch(s: SeekBar?) { tracking = true }
            override fun onStopTrackingTouch(s: SeekBar?) {
                tracking = false
                if (playerManager.duration > 0) {
                    playerManager.seekTo((playerManager.duration * (s?.progress ?: 0)) / 100)
                    resetStallTracking()
                }
                hideTrickplayPreview()
            }
        })
        // D-pad seeking never goes through onStopTrackingTouch (no touch involved), so the
        // preview has to be dismissed on focus loss too or it stays up over the video.
        binding.seekBar.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) hideTrickplayPreview() }

        binding.playerLayout.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) toggleControls(); true
        }

        playerManager.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                binding.bufferingSpinner.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_BUFFERING) onBufferingStarted() else onBufferingEnded()
                if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                    updateProgress(); updatePlayPauseIcon()
                    if (state == Player.STATE_READY) { currentStreamPlayed = true; maybeShowResumePrompt() }
                if (state == Player.STATE_ENDED) {
                    saveCurrentPlaybackPosition()
                    // If Up Next countdown is already running, it will handle the advance.
                    if (upNextActive) return@onPlaybackStateChanged
                    // Silent fallback auto-advance when Up Next wasn't triggered
                    // (e.g. user seeks to end, skipping the 10s countdown window).
                    val queue = currentEpisodeQueue
                    val nextIdx = currentEpisodeQueueIndex + 1
                    if (nextIdx in queue.indices) {
                        showPlayerFor(queue[nextIdx])
                        currentEpisodeQueue = queue
                        currentEpisodeQueueIndex = nextIdx
                    }
                }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                binding.bufferingSpinner.visibility = View.GONE
                resetStallTracking()
                blackFrameStreak = 0
                if (!tryNextQualityVersion()) {
                    Toast.makeText(this@MainActivity, "Playback error", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseIcon()
                if (isPlaying) mainHandler.post(progressRunnable)
                else mainHandler.removeCallbacks(progressRunnable)
            }
            override fun onCues(cues: androidx.media3.common.text.CueGroup) {
                binding.playerSubtitleView.setCues(cues.cues)
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.height == 0 || videoSize.width == 0) return
                val rotated = videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270
                val w = if (rotated) videoSize.height else videoSize.width
                val h = if (rotated) videoSize.width else videoSize.height
                binding.playerAspectContainer.videoAspectRatio = (w * videoSize.pixelWidthHeightRatio) / h
                lastVideoWidth = w
                lastVideoHeight = h
            }
        })
    }

    // ── Aspect ratio / zoom ─────────────────────────

    private fun loadSavedAspectMode(): VideoAspectFrameLayout.Mode =
        runCatching { VideoAspectFrameLayout.Mode.valueOf(prefs.getString(PREF_ASPECT_MODE, null) ?: "") }
            .getOrDefault(VideoAspectFrameLayout.Mode.FIT)

    private fun applyAspectMode(mode: VideoAspectFrameLayout.Mode) {
        binding.playerAspectContainer.resizeMode = mode
        binding.btnAspectRatio.text = when (mode) {
            VideoAspectFrameLayout.Mode.FIT -> "Fit"
            VideoAspectFrameLayout.Mode.ZOOM -> "Zoom"
            VideoAspectFrameLayout.Mode.FILL -> "Stretch"
        }
        prefs.edit().putString(PREF_ASPECT_MODE, mode.name).apply()
    }

    private fun cycleAspectMode() {
        val modes = VideoAspectFrameLayout.Mode.entries
        val next = modes[(modes.indexOf(binding.playerAspectContainer.resizeMode) + 1) % modes.size]
        applyAspectMode(next)
        Toast.makeText(this, "Video: ${binding.btnAspectRatio.text}", Toast.LENGTH_SHORT).show()
    }

    /** [resumeFromMs] carries the position across a version switch (see showVersionPicker):
     *  the replacement stream is a different item with its own saved-position key, so without
     *  it switching version on a half-watched film restarts it from zero. Also suppresses the
     *  resume prompt - the user just answered that question by switching mid-playback.
     *
     *  [externalSubtitles] are sidecar tracks a caller already resolved (the Find Stream
     *  dialog); [pluginStreamAlreadyResolved] marks that same caller's URL as freshly resolved,
     *  so the plugin branch below doesn't resolve it a second time. */
    private fun showPlayerFor(
        channel: Channel,
        resumeFromMs: Long? = null,
        preferredVersionId: String? = null,
        externalSubtitles: List<PlayerManager.ExternalSubtitle> = emptyList(),
        pluginStreamAlreadyResolved: Boolean = false
    ) {
        // Reset Up Next state on any new playback
        cancelUpNext()
        // Never run the preview decode and the fullscreen decode at once.
        releaseLivePreview()
        // Cleared unconditionally - callers that want episode tracking (Next/Prev,
        // auto-advance) re-set these right after calling this, once playback has
        // actually started for the episode they picked.
        currentEpisodeQueue = emptyList()
        currentEpisodeQueueIndex = -1
        isPlayerVisible = true
        nowPlayingChannel = channel
        // Cleared unconditionally, same as the episode queue above - the series version
        // context only applies to playback started from a series detail screen, which re-sets
        // it right after this call.
        currentSeriesVersionContext = null
        // Live TV has no detail page behind it, so a return target left over from a VOD session
        // must not survive into it. Everything else keeps whatever the caller set: a version
        // switch or an auto-advance to the next episode is still the same title's playback, and
        // backing out of it belongs on the same poster the first episode was started from.
        if (channel.mediaType == MediaType.LIVE) {
            detailReturnItem = null
            detailReturnGroup = null
        }
        resumePromptShown = resumeFromMs != null
        progressTickCount = 0
        binding.mainContent.visibility = View.GONE
        binding.playerLayout.visibility = View.VISIBLE
        binding.playerLayout.keepScreenOn = true
        binding.playerChannelName.text = channel.name
        binding.playerSubtitle.visibility = View.GONE
        binding.playerLiveBadge.visibility = if (channel.mediaType == MediaType.LIVE) View.VISIBLE else View.GONE
        if (channel.mediaType == MediaType.LIVE) {
            // Don't add adult channels to recently played.
            if (!isAdultCategory(channel.categoryName, channel.group)) {
                RecentlyPlayedStore.recordPlayed(this, channel.id)
            }
            speedController.resetSpeed()
        }

        // Live channels get a square logo tile (fitCenter, so a wide/odd-aspect logo
        // doesn't get cropped); movies/series get a poster-shaped tile (centerCrop) -
        // squeezing a 2:3 poster into a 52x52 square looked like a stretched thumbnail.
        val density = resources.displayMetrics.density
        val isPoster = channel.mediaType != MediaType.LIVE
        binding.playerLogoBox.layoutParams = binding.playerLogoBox.layoutParams.apply {
            width = ((if (isPoster) 34 else 36) * density).toInt()
            height = ((if (isPoster) 50 else 36) * density).toInt()
        }
        binding.playerChannelLogo.scaleType = if (isPoster) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        val logoPadding = if (isPoster) 0 else (6 * density).toInt()
        binding.playerChannelLogo.setPadding(logoPadding, logoPadding, logoPadding, logoPadding)
        binding.playerChannelInitial.text = channel.name.firstOrNull()?.uppercase() ?: "?"
        binding.playerChannelInitial.visibility = View.VISIBLE
        binding.playerChannelLogo.visibility = View.GONE
        binding.playerChannelLogo.setImageDrawable(null)
        val logoUrl = channel.logoUrl ?: channel.posterUrl
        if (!logoUrl.isNullOrBlank()) {
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching {
                        val request = Request.Builder().url(logoUrl).build()
                        BaseApplication.instance.okHttpClient.newCall(request).execute()
                            .body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                }
                if (bitmap != null && nowPlayingChannel?.id == channel.id) {
                    binding.playerChannelLogo.setImageBitmap(bitmap)
                    binding.playerChannelLogo.visibility = View.VISIBLE
                    binding.playerChannelInitial.visibility = View.GONE
                }
            }
        }

        if (channel.mediaType == MediaType.LIVE && channel.id.isNotBlank()) {
            scope.launch {
                val program = runCatching { resolveCurrentProgram(channel.id) }.getOrNull()
                if (nowPlayingChannel?.id != channel.id || program == null) return@launch
                binding.playerSubtitle.text = "${program.title}  ·  ${formatEpgTimeRange(program.startTimestamp, program.stopTimestamp)}"
                binding.playerSubtitle.visibility = View.VISIBLE
            }
        }

        // Live channels may have multiple quality versions; start at the best
        // (versions are pre-sorted highest quality first) and keep the group
        // around so onPlayerError can fall back to the next one.
        val versions = liveVersions[channel.id]
        currentVersionGroup = versions ?: listOf(channel)
        // An explicitly requested version wins over the quality/dead-stream auto-pick: it's
        // the one the user was already watching (launch resume), so re-deriving a "best"
        // choice here would start them on a different stream and, when that one doesn't
        // play, walk the whole group by failover before arriving back where they started.
        val preferredIndex = preferredVersionId?.let { id -> currentVersionGroup.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
        currentVersionIndex = preferredIndex
            ?: currentVersionGroup.indexOfFirst { !isStreamDead(it) }.takeIf { it >= 0 }
            ?: 0
        // channel.name is the cleaned/generic representative name (guide/shelf display) -
        // the player card shows the exact raw version actually playing instead, same as
        // switchToVersionIndex() does on failover/manual switch.
        if (channel.mediaType == MediaType.LIVE) {
            binding.playerChannelName.text = currentVersionGroup.getOrNull(currentVersionIndex)?.name ?: channel.name
            rememberLastLiveTune(channel)
        }

        resetStallTracking()
        beginStreamAttempt()
        startBlackFrameWatch()
        binding.playerAspectContainer.videoAspectRatio = 0f
        playerManager.setSurfaceView(binding.playerSurface)
        showControls()
        binding.bufferingSpinner.visibility = View.VISIBLE
        val startVersion = if (channel.mediaType == MediaType.LIVE) currentVersionGroup.getOrNull(currentVersionIndex) ?: channel else channel
        // Stalker VOD carries a base64 play command, not a URL - it must be create_link'd at
        // play time (the resolved link is short-lived and per-session). Everything else has a
        // direct url already.
        // Reset per-play Jellyfin state before anything below can populate it - a chapters
        // button left over from the last title would otherwise seek into the wrong film.
        jellyfinPlaySession = null
        jellyfinPlayingItemId = null
        jellyfinChapters = emptyList()
        jellyfinTrickplay = null
        trickplayTileCache = null
        updateChaptersButtonVisibility()
        hideTrickplayPreview()

        when {
            startVersion.url.isBlank() && !startVersion.stalkerCmd.isNullOrBlank() -> scope.launch {
                val resolved = resolveStalkerPlayUrl(startVersion)
                if (nowPlayingChannel?.id != channel.id) return@launch
                if (resolved.isNullOrBlank()) {
                    binding.bufferingSpinner.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Couldn't open this title", Toast.LENGTH_SHORT).show()
                } else {
                    // Not the MAC (which Stalker channels carry as their UA for the portal API):
                    // the resolved movie.php/live.php stream is plain HTTP and wants a normal
                    // player UA. Sending the MAC as User-Agent is what errored the playback.
                    playerManager.playUrl(resolved, STREAM_USER_AGENT)
                }
                resumeFromMs?.let { playerManager.seekTo(it) }
            }
            // Jellyfin VOD/episodes ask the server how to play them rather than assuming the
            // file is directly playable: `?static=true` hands the raw file over untouched, so
            // anything this device has no decoder for (HEVC 10-bit, TrueHD, DTS) opened to a
            // black screen or silence. PlaybackInfo picks direct play where it fits and an
            // HLS transcode where it doesn't, and brings the subtitle tracks with it.
            startVersion.isJellyfin && startVersion.mediaType != MediaType.LIVE && startVersion.id.isNotBlank() -> scope.launch {
                val startAt = resumeFromMs ?: 0L
                val jellyfin = jellyfinClientOrConnect()
                val resolved = if (jellyfin == null) null else withContext(Dispatchers.IO) {
                    runCatching { jellyfin.resolveStream(startVersion.id, startAt) }.getOrNull()
                }
                if (nowPlayingChannel?.id != channel.id) return@launch
                // A failed negotiation is not a failed play: the plain static URL is what the
                // app always used, and for most files it works - so fall back to it rather
                // than refusing to open the title.
                playerManager.playUrl(
                    resolved?.url ?: startVersion.url,
                    startVersion.streamUserAgent,
                    subtitles = resolved?.let(::externalSubtitlesFor) ?: emptyList(),
                    startPositionMs = startAt
                )
                jellyfinPlaySession = resolved
                jellyfinPlayingItemId = startVersion.id
                reportJellyfinStart(startVersion.id, resolved, startAt)
                loadJellyfinPlaybackExtras(startVersion.id)
            }
            // A plugin-resolved stream cannot be replayed from the URL it was saved with: the
            // CDN signs it with an expiry in the path and gates it behind request headers the
            // URL alone doesn't carry, so a Continue Watching tile replaying yesterday's URL
            // 403s twice over. Re-run the plugin's resolve() for a fresh URL, headers and
            // subtitle tracks instead - that's also what makes resuming an anime episode land
            // on the same episode rather than the series' first one.
            !pluginStreamAlreadyResolved && !startVersion.pluginToken.isNullOrBlank() -> scope.launch {
                val startAt = resumeFromMs ?: 0L
                val plugin = pluginScriptManager.getDiscoveredScripts()
                    .firstOrNull { it.enabled && it.id == startVersion.pluginId }
                val resolved = when {
                    plugin == null -> ResolveResult.Failed("The plugin that played this is no longer enabled")
                    // Same split as the Find Stream dialog: a natively-resolving plugin's token
                    // is a magnet for the built-in torrent engine, not something the JS runtime
                    // can turn into a URL.
                    // Torrent metadata + initial buffering can take minutes with nothing on
                    // screen but a spinner, so the progress lines land on the player's subtitle
                    // row (free for VOD - only live TV puts EPG text there). TorrentEngine
                    // reports from its own IO thread, hence the hop.
                    plugin.resolvesNatively -> resolveTorrentStream(
                        startVersion.pluginToken, null, startVersion.episodeNum
                    ) { line ->
                        runOnUiThread {
                            if (nowPlayingChannel?.id != channel.id) return@runOnUiThread
                            binding.playerSubtitle.text = line
                            binding.playerSubtitle.visibility = View.VISIBLE
                        }
                    }
                    else -> jsPluginEngine.resolve(
                        pluginScriptManager.readSource(plugin),
                        startVersion.pluginToken,
                        null,
                        startVersion.episodeNum
                    )
                }
                if (nowPlayingChannel?.id != channel.id) return@launch
                binding.playerSubtitle.visibility = View.GONE
                when (resolved) {
                    is ResolveResult.Ready -> playerManager.playUrl(
                        resolved.url,
                        startVersion.streamUserAgent,
                        subtitles = resolved.subtitles.map(::externalSubtitleFor),
                        startPositionMs = startAt,
                        headers = resolved.headers.ifEmpty { null }
                    )
                    is ResolveResult.Failed -> {
                        binding.bufferingSpinner.visibility = View.GONE
                        Toast.makeText(this@MainActivity, resolved.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            else -> {
                playerManager.playUrl(
                    startVersion.url,
                    startVersion.streamUserAgent,
                    subtitles = externalSubtitles,
                    headers = startVersion.streamHeaders
                )
                resumeFromMs?.let { playerManager.seekTo(it) }
            }
        }

        // Apply persisted A/V sync offset (per-channel or global)
        val params = avOffsetManager.buildPlaybackParameters(
            playerManager.getExoPlayer().playbackParameters,
            nowPlayingChannel?.id
        )
        playerManager.getExoPlayer().setPlaybackParameters(params)
    }

    /** Resolves a Stalker VOD/series item's base64 command into a playable URL against the
     *  portal it came from (matched by sourceProviderId). Null if the portal's gone or the
     *  config isn't a Stalker one. */
    private fun stalkerConfigFor(channel: Channel): IptvProviderConfig? =
        channel.sourceProviderId?.let { id -> IptvProviderStore.load(prefs).firstOrNull { it.id == id && it.type == "stalker" } }

    private fun stalkerProviderStub(config: IptvProviderConfig): Provider = Provider(
        name = config.name, type = ProviderType.M3U,
        serverUrl = config.url?.let { normalizeServerUrl(it) }, userAgent = config.userAgent
    )

    private suspend fun resolveStalkerPlayUrl(channel: Channel): String? {
        val config = stalkerConfigFor(channel) ?: return null
        val stalker = StalkerProvider(BaseApplication.instance.okHttpClient)
        return withContext(Dispatchers.IO) {
            // A series episode passes its number so create_link picks the right one within the
            // season it shares a cmd with; a film passes none.
            stalker.resolvePlayUrl(
                stalkerProviderStub(config),
                channel.stalkerCmd!!,
                episode = channel.episodeNum?.takeIf { channel.mediaType == MediaType.SERIES }
            )
        }
    }

    /** The Xtream provider a Channel actually came from, for detail/EPG calls that need to
     *  hit the matching server/credentials - not whichever Xtream provider loaded last into
     *  the legacy single `provider` field. Null for non-Xtream items. */
    private fun xtreamProviderFor(channel: Channel): Provider? {
        val config = channel.sourceProviderId?.let { xtreamProviderConfigs[it] } ?: return null
        return Provider(
            name = config.name, type = ProviderType.XTREAM,
            serverUrl = config.url?.let { normalizeServerUrl(it) },
            username = config.username, password = config.password
        )
    }

    /** The name of the provider a Channel came from, for labelling version chips. Jellyfin is
     *  its own fixed slot; everything else is an IptvProviderConfig matched by
     *  sourceProviderId. Null when the config's since been deleted (cached items outlive it). */
    private fun providerNameFor(channel: Channel): String? = when {
        channel.isJellyfin -> "Jellyfin"
        else -> channel.sourceProviderId?.let { providerNamesById[it] }?.takeIf { it.isNotBlank() }
    }

    private fun streamKey(channel: Channel) = channel.id.ifBlank { channel.url }

    private fun markStreamDead(channel: Channel) {
        deadStreamUntil[streamKey(channel)] = System.currentTimeMillis() + DEAD_STREAM_COOLDOWN_MS
        saveDeadStreams()
    }

    /** Dead marks outlive the process: a stream that was broken a minute before the app was
     *  closed is still broken when it reopens, and an in-memory-only map handed it back as a
     *  fresh candidate on every launch. Expired entries are dropped as they're written. */
    private fun saveDeadStreams() {
        val now = System.currentTimeMillis()
        deadStreamUntil.entries.removeAll { it.value <= now }
        val json = org.json.JSONObject()
        deadStreamUntil.forEach { (key, until) -> json.put(key, until) }
        prefs.edit().putString(PREF_DEAD_STREAMS, json.toString()).apply()
    }

    private fun loadDeadStreams() {
        val raw = prefs.getString(PREF_DEAD_STREAMS, null) ?: return
        runCatching {
            val json = org.json.JSONObject(raw)
            val now = System.currentTimeMillis()
            json.keys().forEach { key ->
                val until = json.optLong(key)
                if (until > now) deadStreamUntil[key] = until
            }
        }
    }

    private fun isStreamDead(channel: Channel): Boolean {
        val until = deadStreamUntil[streamKey(channel)] ?: return false
        if (System.currentTimeMillis() >= until) {
            deadStreamUntil.remove(streamKey(channel))
            return false
        }
        return true
    }

    /** Retries playback with the next-best quality version of the current live channel, if
     *  any - the one being left behind failed (that's why this got called), so it's marked
     *  dead for a cooldown window instead of being tried again a few seconds later. */
    private fun tryNextQualityVersion(message: String = "Switching to alternate quality…"): Boolean {
        if (nowPlayingChannel?.mediaType != MediaType.LIVE) return false
        currentVersionGroup.getOrNull(currentVersionIndex)?.let { markStreamDead(it) }
        var nextIndex = currentVersionIndex + 1
        while (nextIndex < currentVersionGroup.size && isStreamDead(currentVersionGroup[nextIndex])) nextIndex++
        if (nextIndex >= currentVersionGroup.size) return false
        switchToVersionIndex(nextIndex, message)
        return true
    }

    /** Swaps playback to an arbitrary version within the current channel's merged quality/source
     *  group - used both for manual picks (showLiveVersionPicker) and auto-failover (above). */
    private fun switchToVersionIndex(index: Int, message: String? = null) {
        if (index !in currentVersionGroup.indices) return
        currentVersionIndex = index
        val next = currentVersionGroup[index]
        resetStallTracking()
        beginStreamAttempt()
        startBlackFrameWatch()
        binding.playerAspectContainer.videoAspectRatio = 0f
        binding.playerChannelName.text = next.name
        Toast.makeText(this, message ?: "Switching to ${extractLeadingTag(next.name) ?: next.name}", Toast.LENGTH_SHORT).show()
        binding.bufferingSpinner.visibility = View.VISIBLE
        playerManager.playUrl(next.url, next.streamUserAgent)
        // Whatever version ends up playing - picked by hand or arrived at by failover - is
        // what should come back on next launch.
        nowPlayingChannel?.takeIf { it.mediaType == MediaType.LIVE }?.let { rememberLastLiveTune(it) }
    }

    /** Lets the user manually pick a specific version of whatever's playing - the auto-picked
     *  one isn't always the working one (a live channel's best quality can be the one that
     *  buffers or is geo-blocked; a film's first source can be dead; one provider's copy of a
     *  show can have a broken or incomplete episode list).
     *
     *  Three shapes behind one button, because "version" means something different per type:
     *  live/film versions are alternate streams of the same item, swapped in place; a series
     *  episode's alternatives live in another provider's separate episode list, which has to
     *  be fetched and matched by season/episode before there's anything to play. */
    private fun showVersionPicker() {
        val playing = nowPlayingChannel ?: return
        when {
            playing.mediaType == MediaType.LIVE -> {
                if (currentVersionGroup.size <= 1) {
                    Toast.makeText(this, "No other versions of this channel", Toast.LENGTH_SHORT).show()
                    return
                }
                val labels = currentVersionGroup.map { it.name }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Channel Version")
                    .setSingleChoiceItems(labels, currentVersionIndex) { dialog, which ->
                        switchToVersionIndex(which)
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            currentSeriesVersionContext != null -> showSeriesVersionPicker(playing)
            else -> showFilmVersionPicker(playing)
        }
    }

    /** Alternate sources for the film that's playing. filmVersions is keyed by the group's
     *  representative, and the thing playing may itself be a non-representative version
     *  (picked from the detail screen's chips), so the group is found by membership. */
    private fun showFilmVersionPicker(playing: Channel) {
        val group = filmVersions[playing.id]
            ?: filmVersions.values.firstOrNull { grp -> grp.any { it.id == playing.id } }
            ?: emptyList()
        if (group.size <= 1) {
            Toast.makeText(this, "No other versions of this title", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = group.mapIndexed { index, version -> versionChipLabel(version, index) }.toTypedArray()
        val currentIndex = group.indexOfFirst { it.id == playing.id }
        // The replacement is a different item with its own saved-position key, so the current
        // position is carried across by hand - otherwise switching source mid-film restarts it.
        val resumeMs = playerManager.currentPosition.takeIf { it > 0 }
        AlertDialog.Builder(this)
            .setTitle("Version")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                dialog.dismiss()
                if (which != currentIndex) showPlayerFor(group[which], resumeFromMs = resumeMs)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Other providers' copies of the show whose episode is playing. Each copy is a separate
     *  catalog entry with its own episode list, so switching means fetching that list and
     *  finding the same season/episode in it - which can legitimately fail (a provider may
     *  simply not carry that episode), hence the explicit message rather than a silent no-op. */
    private fun showSeriesVersionPicker(playing: Channel) {
        val (series, group) = currentSeriesVersionContext ?: return
        if (group.size <= 1) {
            Toast.makeText(this, "No other versions of this series", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = group.mapIndexed { index, version -> versionChipLabel(version, index) }.toTypedArray()
        val currentIndex = group.indexOfFirst { it.id == series.id }
        val episodeNum = playing.episodeNum
        // An episode Channel carries its episode number but not its season, so the season is
        // approximated by the length of the queue it came from (that queue is one season's
        // episodes) - enough to prefer the right season when two carry the same episode
        // number, with a plain episode-number match as the fallback.
        val queueSeasonSize = currentEpisodeQueue.size.takeIf { it > 0 }
        AlertDialog.Builder(this)
            .setTitle("Series Version")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                dialog.dismiss()
                if (which == currentIndex) return@setSingleChoiceItems
                val target = group[which]
                Toast.makeText(this, "Loading ${versionChipLabel(target, which)}…", Toast.LENGTH_SHORT).show()
                scope.launch {
                    val (_, seasons) = runCatching { loadSeriesContent(target) }.getOrElse { null to emptyList() }
                    val match = seasons.firstNotNullOfOrNull { (_, eps) ->
                        eps.firstOrNull { it.episodeNum != null && it.episodeNum == episodeNum && (queueSeasonSize == null || eps.size == queueSeasonSize) }
                    } ?: seasons.firstNotNullOfOrNull { (_, eps) ->
                        eps.firstOrNull { it.episodeNum != null && it.episodeNum == episodeNum }
                    }
                    if (match == null) {
                        Toast.makeText(this@MainActivity, "That provider doesn't have this episode", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val resumeMs = playerManager.currentPosition.takeIf { it > 0 }
                    val newQueue = seasons.firstOrNull { (_, eps) -> eps.any { it.id == match.id } }?.second ?: listOf(match)
                    showPlayerFor(match, resumeFromMs = resumeMs)
                    currentEpisodeQueue = newQueue
                    currentEpisodeQueueIndex = newQueue.indexOfFirst { it.id == match.id }
                    currentSeriesVersionContext = target to group
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Buffer-based auto-failover ─────────────────
    // onPlayerError already fails over on a hard error; this covers the "plays but
    // buffers constantly" case, which ExoPlayer never surfaces as an error at all.

    private fun onBufferingStarted() {
        if (nowPlayingChannel?.mediaType != MediaType.LIVE) return
        // The buffering a stream does before it has ever reached READY is it starting up,
        // not stalling. Counting it meant a launch-time tune - where the app is also parsing
        // the channel cache and building categories, so the first fill is slow - burned
        // through the stall threshold and failed over to version after version.
        if (!currentStreamPlayed) return
        if (bufferingStartMs != 0L) return
        val now = System.currentTimeMillis()
        bufferingStartMs = now
        stallTimestamps.add(now)
        stallTimestamps.removeAll { it < now - STALL_WINDOW_MS }
        if (stallTimestamps.size >= STALL_COUNT_THRESHOLD) {
            attemptBufferFailover()
            return
        }
        mainHandler.postDelayed(longStallCheckRunnable, STALL_LONG_MS)
    }

    private fun onBufferingEnded() {
        bufferingStartMs = 0L
        mainHandler.removeCallbacks(longStallCheckRunnable)
    }

    private fun resetStallTracking() {
        bufferingStartMs = 0L
        stallTimestamps.clear()
        mainHandler.removeCallbacks(longStallCheckRunnable)
    }

    private fun attemptBufferFailover() {
        if (nowPlayingChannel?.mediaType != MediaType.LIVE) return
        if (withinFailoverGrace()) { resetStallTracking(); return }
        resetStallTracking()
        tryNextQualityVersion("Stream buffering, switching version…")
    }

    /** Whether the current stream is still too young to judge. Every automatic failover is a
     *  verdict on a stream that's been given a fair chance to settle; without this the app
     *  cycles through the whole version group in the first few seconds of a tune, each switch
     *  restarting the clock on the next one. Hard playback errors bypass this - those are
     *  conclusive on their own. */
    private fun withinFailoverGrace(): Boolean =
        System.currentTimeMillis() - currentStreamStartMs < FAILOVER_GRACE_MS

    /** Marks the start of a playback attempt for failover purposes. */
    private fun beginStreamAttempt() {
        currentStreamStartMs = System.currentTimeMillis()
        currentStreamPlayed = false
    }

    // ── Black-frame auto-failover ──────────────────
    // A dead feed sometimes never stalls or errors at all - the server just serves a
    // technically-valid, steadily-decoding encode of a blank black frame instead, so
    // neither onPlayerError nor the buffer-stall watchdog above ever fires. Sample the
    // actual rendered surface periodically and treat sustained near-black output as a
    // dead feed too.

    private fun startBlackFrameWatch() {
        if (isDestroyed) return
        blackFrameStreak = 0
        mainHandler.removeCallbacks(blackFrameCheckRunnable)
        mainHandler.postDelayed(blackFrameCheckRunnable, BLACK_FRAME_INITIAL_DELAY_MS)
    }

    private fun checkForBlackFrame() {
        if (nowPlayingChannel?.mediaType != MediaType.LIVE || !isPlayerVisible || !playerManager.isPlaying) {
            mainHandler.postDelayed(blackFrameCheckRunnable, BLACK_FRAME_CHECK_INTERVAL_MS)
            return
        }
        val surfaceView = binding.playerSurface
        if (surfaceView.width <= 0 || surfaceView.height <= 0) {
            mainHandler.postDelayed(blackFrameCheckRunnable, BLACK_FRAME_CHECK_INTERVAL_MS)
            return
        }
        val sample = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(surfaceView, sample, { result ->
                if (isDestroyed || !isPlayerVisible) { sample.recycle(); return@request }
                val isBlack = result == PixelCopy.SUCCESS && averageLuma(sample) < BLACK_FRAME_LUMA_THRESHOLD
                sample.recycle()
                blackFrameStreak = if (isBlack) blackFrameStreak + 1 else 0
                if (blackFrameStreak >= BLACK_FRAME_STREAK_THRESHOLD && !withinFailoverGrace()) {
                    blackFrameStreak = 0
                    if (!tryNextQualityVersion("Channel appears offline, switching version…")) {
                        Toast.makeText(this, "Channel appears offline", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    mainHandler.postDelayed(blackFrameCheckRunnable, BLACK_FRAME_CHECK_INTERVAL_MS)
                }
            }, mainHandler)
        } catch (e: Exception) {
            sample.recycle()
            mainHandler.postDelayed(blackFrameCheckRunnable, BLACK_FRAME_CHECK_INTERVAL_MS)
        }
    }

    private fun averageLuma(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var sum = 0L
        for (p in pixels) {
            sum += (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3
        }
        return if (pixels.isNotEmpty()) (sum / pixels.size).toInt() else 0
    }

    /** Same black-frame detection as fullscreen playback, but for the muted inline preview
     *  player used while browsing the guide - it plays a version group's best entry same as
     *  fullscreen, so it can hit the exact same dead/blank-feed case, silently (no Toast,
     *  nothing to interrupt) skipping to the next non-dead version instead. */
    private fun startPreviewBlackFrameWatch() {
        previewBlackFrameStreak = 0
        mainHandler.removeCallbacks(previewBlackFrameCheckRunnable)
        mainHandler.postDelayed(previewBlackFrameCheckRunnable, BLACK_FRAME_INITIAL_DELAY_MS)
    }

    private fun checkForPreviewBlackFrame() {
        if (activeTab != 0 || isPlayerVisible || binding.livePreviewPane.visibility != View.VISIBLE) return
        val textureView = binding.previewSurface
        if (!textureView.isAvailable) {
            mainHandler.postDelayed(previewBlackFrameCheckRunnable, BLACK_FRAME_CHECK_INTERVAL_MS)
            return
        }
        val sample = runCatching { textureView.getBitmap(32, 18) }.getOrNull()
        val isBlack = sample != null && averageLuma(sample) < BLACK_FRAME_LUMA_THRESHOLD
        sample?.recycle()
        previewBlackFrameStreak = if (isBlack) previewBlackFrameStreak + 1 else 0
        if (previewBlackFrameStreak < BLACK_FRAME_STREAK_THRESHOLD) {
            mainHandler.postDelayed(previewBlackFrameCheckRunnable, BLACK_FRAME_CHECK_INTERVAL_MS)
            return
        }
        previewBlackFrameStreak = 0
        previewVersionGroup.getOrNull(previewVersionIndex)?.let { markStreamDead(it) }
        var nextIndex = previewVersionIndex + 1
        while (nextIndex < previewVersionGroup.size && isStreamDead(previewVersionGroup[nextIndex])) nextIndex++
        if (nextIndex >= previewVersionGroup.size) return // nothing else to try - leave it, stop watching
        previewVersionIndex = nextIndex
        val next = previewVersionGroup[nextIndex]
        ensurePreviewPlayer().playUrl(next.url, next.streamUserAgent)
        mainHandler.postDelayed(previewBlackFrameCheckRunnable, BLACK_FRAME_CHECK_INTERVAL_MS)
    }

    /** Live channels are never resumable; movies/episodes are, once far enough in. */
    private fun maybeShowResumePrompt() {
        if (resumePromptShown) return
        val channel = nowPlayingChannel ?: return
        if (channel.mediaType == MediaType.LIVE) return
        val key = channel.id.ifBlank { channel.url }
        val saved = PlaybackPositionStore.get(this, key) ?: return
        if (saved.isNearComplete || saved.positionMs < 5000) return
        resumePromptShown = true

        playerManager.pause()
        AlertDialog.Builder(this)
            .setTitle("Resume playback?")
            .setMessage("Continue from ${formatTime(saved.positionMs)}?")
            .setPositiveButton("Resume") { _, _ -> playerManager.seekTo(saved.positionMs); playerManager.play() }
            .setNegativeButton("Start Over") { _, _ -> playerManager.seekTo(0); playerManager.play() }
            .setCancelable(false)
            .show()
    }

    private fun saveCurrentPlaybackPosition() {
        val channel = nowPlayingChannel ?: return
        if (channel.mediaType == MediaType.LIVE) return
        if (isAdultCategory(channel.categoryName, channel.group)) return
        val dur = playerManager.duration
        val pos = playerManager.currentPosition
        if (pos == androidx.media3.common.C.TIME_UNSET || pos < 0) return
        if (dur <= 0) return
        val key = channel.id.ifBlank { channel.url }
        PlaybackPositionStore.save(this, key, pos, dur, channel)
    }

    private fun hidePlayer() {
        saveCurrentPlaybackPosition()
        // Before nowPlayingChannel is cleared: the server turns this final position into a
        // watched mark or a resume point, and closes out any transcode it started.
        if (reportJellyfinStopped()) refreshJellyfinRowsAfterPlayback()
        hideTrickplayPreview()
        // What was playing is the best preview target when nothing in the guide was ever
        // focused - a launch that resumes straight into the player never fires a focus
        // event, so lastFocusedLiveChannel is null and the preview pane came back blank.
        val wasPlaying = nowPlayingChannel?.takeIf { it.mediaType == MediaType.LIVE }
        isPlayerVisible = false
        nowPlayingChannel = null
        binding.playerLayout.visibility = View.GONE
        binding.mainContent.visibility = View.VISIBLE
        binding.playerLayout.keepScreenOn = false
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.removeCallbacks(progressRunnable)
        mainHandler.removeCallbacks(longStallCheckRunnable)
        mainHandler.removeCallbacks(blackFrameCheckRunnable)
        mainHandler.removeCallbacks(upNextTickRunnable)
        playerManager.stop()
        sleepTimer.stop()
        // A plugin-served stream (Find Stream) keeps a torrent + local server alive for as long
        // as we're playing its URL - release it now.
        activeTorrentSession?.let { engine -> Thread { runCatching { engine.stop() } }.start() }
        activeTorrentSession = null
        TorrentForegroundService.stop(this)
        if (::castManager.isInitialized) castManager.stopCasting()
        if (activeTab == 0) {
            showLivePreviewPane()
            val previewTarget = lastFocusedLiveChannel ?: wasPlaying
            previewTarget?.let { requestPreviewLoad(it) }
            // Backing out lands in the channel's own dynamic row (Sports/News bucket, brand
            // row, Jellyfin) when it belongs to one - that's the list it was picked from, so
            // returning to whatever filter happened to be selected loses the user's place.
            val dynamicRow = previewTarget?.let { dynamicCategoryFor(it) }
            if (dynamicRow != null && dynamicRow.id != selectedRowId) {
                selectedShelfItems = null
                selectedRowId = dynamicRow.id
                selectedCategoryLabel = dynamicRow.name
                selectedBrandChannelIds = dynamicRow.channelIds.ifEmpty { null }
                selectedCategoryIds = if (dynamicRow.channelIds.isNotEmpty()) null else dynamicRow.matchIds
                // Scroll only once the new filter's list is in place - the position of the
                // channel is meaningless against the outgoing one.
                scope.launch {
                    // The row can be a brand row inside a collapsed bucket, in which case
                    // selecting it without expanding its parent highlights nothing.
                    if (categoryAdapter.currentList.none { it.id == dynamicRow.id }) {
                        parentOfCategoryRow(dynamicRow.id)?.let { parentId ->
                            expandedGroupKeys.add(parentId)
                            rebuildCategoriesForActiveTab()
                        }
                    }
                    categoryAdapter.setSelected(selectedRowId)
                    applyCategoryFilter()
                    previewTarget.let { scrollLiveListTo(it) }
                }
            } else {
                // Scroll the channel list so the last-watched channel is visible when
                // the player closes, instead of showing the first channel (which
                // applyCategoryFilter scrolled to on tab switch). The list may have a
                // category filter active, so find the position in the adapter list
                // rather than assuming liveChannels order.
                previewTarget?.let { scrollLiveListTo(it) }
            }
        }
        // Whatever just finished playing may have changed Continue Watching - refresh
        // Home so it's not stale until the next unrelated rebuild happens to touch it.
        if (showingHome) homeShelfAdapter.submitList(buildHomeShelves())
        // Backing out of a film or an episode lands on that title's poster - the screen it was
        // started from - instead of the grid behind it, which is several D-pad moves and a
        // scroll position away from where the user actually was.
        val returnTo = detailReturnItem
        val returnGroup = detailReturnGroup
        detailReturnItem = null
        detailReturnGroup = null
        if (returnTo != null) {
            showContentDetail(returnTo, returnGroup)
            return
        }
        restoreTabFocus()
    }

    /** The dynamic sidebar row a live channel belongs to (brand row like "Sky Sports", genre
     *  bucket, Jellyfin), or null when it only lives in a plain provider category.
     *
     *  Searches the cached children as well as the visible rows: a brand row is bucketed
     *  under a genre parent on Live TV, so "Sky Sports" isn't in the sidebar list at all
     *  while "Sports" is collapsed. Ties break toward the most specific row - the bucket that
     *  swallowed the brand row also matches the channel, and landing in "Sports" instead of
     *  "Sky Sports" is not where the channel was picked from. */
    private fun dynamicCategoryFor(channel: Channel): CategoryFilter? =
        (categoryAdapter.currentList + categoryChildrenCache.values.flatten())
            .filter { it.isDynamic && channel.id in it.channelIds }
            .minWithOrNull(compareBy({ if (it.isParent) 1 else 0 }, { it.channelIds.size }))

    /** The bucket a (possibly hidden) child row lives under, so it can be expanded into view. */
    private fun parentOfCategoryRow(rowId: String?): String? =
        rowId?.let { id -> categoryChildrenCache.entries.firstOrNull { (_, kids) -> kids.any { it.id == id } }?.key }

    private fun scrollLiveListTo(channel: Channel) {
        val pos = liveAdapter.currentList.indexOfFirst { it.id == channel.id }
        if (pos >= 0) binding.liveContent.post { binding.liveContent.scrollToPosition(pos) }
    }

    // ── EPG ──────────────────────────────────────────

    /** Currently-airing program for a live channel, or null if there's no EPG data for it. */
    /** Every id worth trying for a channel's EPG: itself first, then its merged quality/source siblings - same physical channel, the provider just didn't attach guide data to every feed. */
    /** A channel's own entry plus its merged quality/source siblings - same physical channel,
     *  the provider just didn't attach guide data to every feed. Each carries its own
     *  sourceProviderId, since siblings can come from a different Xtream provider entirely. */
    private fun epgCandidateChannels(channelId: String): List<Channel> {
        val versions = liveVersions[channelId]
        if (versions != null) return versions
        return listOfNotNull(liveChannels.find { it.id == channelId })
    }

    private suspend fun resolveCurrentProgram(channelId: String): XtreamClient.EpgProgram? {
        val client = XtreamClient(BaseApplication.instance.okHttpClient)
        val nowSeconds = System.currentTimeMillis() / 1000
        for (ch in epgCandidateChannels(channelId)) {
            val chProvider = xtreamProviderFor(ch) ?: continue
            val programs = runCatching { client.getShortEpg(chProvider, ch.id, 2) }.getOrDefault(emptyList())
            if (programs.isNotEmpty()) return programs.firstOrNull { it.isNowAiring(nowSeconds) } ?: programs.firstOrNull()
        }
        return null
    }

    /** Next several EPG entries for a channel, used to build one row of the guide timeline. */
    private suspend fun resolveEpgPrograms(channelId: String): List<XtreamClient.EpgProgram>? {
        val client = XtreamClient(BaseApplication.instance.okHttpClient)
        for (ch in epgCandidateChannels(channelId)) {
            val chProvider = xtreamProviderFor(ch) ?: continue
            val programs = runCatching { client.getShortEpg(chProvider, ch.id, 6) }.getOrDefault(emptyList())
            if (programs.isNotEmpty()) return programs
        }
        return null
    }

    // ── Live TV inline preview ──────────────────────

    private var lastFocusedLiveChannel: Channel? = null

    /** Live channel id to reopen on launch (see [PREF_LAST_LIVE_CHANNEL]); null once used. */
    private var pendingLiveResumeId: String? = null

    private fun ensurePreviewPlayer(): PlayerManager {
        previewPlayerManager?.let { return it }
        val manager = PlayerManager(this)
        // Audible. The preview is the only thing playing while the guide is up - the
        // fullscreen player releases it before it starts, so the two never overlap and
        // there's no focus fight to mute this for.
        manager.setVolume(1f)
        manager.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                binding.previewBuffering.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            }
            override fun onPlayerError(error: PlaybackException) {
                binding.previewBuffering.visibility = View.GONE
            }
        })
        previewPlayerManager = manager
        return manager
    }

    private fun showLivePreviewPane() {
        if (liveChannels.isEmpty()) return
        binding.livePreviewGutter.visibility = View.VISIBLE
        binding.livePreviewPane.visibility = View.VISIBLE
        // releaseLivePreview() hides this surface (not just its parent) to stop a stale
        // frame compositing; nothing brought it back, so the pane reopened permanently
        // blank after the first time the preview was ever torn down. Un-hide it here, where
        // the pane is being shown, rather than at the tail of release().
        binding.previewSurface.visibility = View.VISIBLE
        ensurePreviewPlayer().setTextureView(binding.previewSurface)
        binding.liveContent.post { updateGuideRowWrap() }
    }

    /** The preview pane floats over the top-right corner of the guide instead of reserving
     *  a permanent column, so rows read like text wrapping around it: whichever rows are
     *  currently scrolled behind it get a right-side margin to clear it, every row below
     *  goes back to full width. Recomputed on scroll and whenever a row is (re)bound, since
     *  which channel occupies "behind the preview" changes as the guide scrolls. */
    private val previewGlobalRect = android.graphics.Rect()
    private val guideRowGlobalRect = android.graphics.Rect()
    private fun updateGuideRowWrap() {
        val showingPreview = binding.livePreviewGutter.visibility == View.VISIBLE
        val reservedPx = if (showingPreview) {
            // The pane's real on-screen width, exactly - no added buffer. An extra margin
            // here was tried twice (first 16dp, then 32dp) to guard against focus-scale
            // bleed that turned out not to be the real bug, and each just left a visible
            // gap between a reserved row's content and the pane's actual left edge.
            binding.livePreviewPane.getGlobalVisibleRect(previewGlobalRect)
            previewGlobalRect.width()
        } else 0
        for (i in 0 until binding.liveContent.childCount) {
            val child = binding.liveContent.getChildAt(i)
            val overlapsPreview = showingPreview && run {
                child.getGlobalVisibleRect(guideRowGlobalRect)
                guideRowGlobalRect.bottom > previewGlobalRect.top && guideRowGlobalRect.top < previewGlobalRect.bottom
            }
            (binding.liveContent.getChildViewHolder(child) as? LiveGuideAdapter.RowViewHolder)
                ?.setReservedEnd(if (overlapsPreview) reservedPx else 0)
        }
    }

    private fun releaseLivePreview() {
        previewLoadRunnable?.let { mainHandler.removeCallbacks(it) }
        previewLoadRunnable = null
        previewChannelId = null
        mainHandler.removeCallbacks(previewBlackFrameCheckRunnable)
        binding.livePreviewGutter.visibility = View.GONE
        binding.livePreviewPane.visibility = View.GONE
        updateGuideRowWrap()
        // A hardware-overlay SurfaceView can keep compositing its last frame even
        // after the Java view tree is hidden; explicitly stop playback and hide the
        // surface itself (not just its parent) so it actually goes away.
        binding.previewSurface.visibility = View.GONE
        binding.previewBuffering.visibility = View.GONE
        previewPlayerManager?.let { manager ->
            manager.stop()
            manager.release()
        }
        previewPlayerManager = null
    }

    // ── Numeric Remote Input ──────────────────────
    private fun handleDigitInput(digit: Int) {
        if (digitInputBuffer.length >= 6) return
        digitInputBuffer.append(digit)
        isDigitEntryActive = true
        showNumericOverlay()
        // Reset the timeout on every keypress
        mainHandler.removeCallbacks(digitInputTimeoutRunnable)
        mainHandler.postDelayed(digitInputTimeoutRunnable, 1500)
    }

    private fun resolveDigitInput() {
        if (!isDigitEntryActive || digitInputBuffer.isEmpty()) {
            hideNumericOverlay()
            return
        }
        val channelNum = digitInputBuffer.toString()
        val match = liveChannels.firstOrNull { it.tvgChno == channelNum || it.tvgChno?.toIntOrNull()?.toString() == channelNum }
        if (match != null) {
            hideNumericOverlay()
            clearDigitBuffer()
            playItem(match)
        } else {
            // Flash "not found" briefly on the overlay, then dismiss
            binding.numericInputChannelName.text = "Not found"
            binding.numericInputChannelName.visibility = View.VISIBLE
            mainHandler.postDelayed({ hideNumericOverlay(); clearDigitBuffer() }, 800)
        }
    }

    private fun showNumericOverlay() {
        binding.numericInputDigits.text = digitInputBuffer.toString()
        binding.numericInputChannelName.visibility = View.GONE
        binding.numericInputOverlay.visibility = View.VISIBLE
    }

    private fun hideNumericOverlay() {
        binding.numericInputOverlay.visibility = View.GONE
        isDigitEntryActive = false
    }

    private fun clearDigitBuffer() {
        digitInputBuffer.clear()
        isDigitEntryActive = false
        mainHandler.removeCallbacks(digitInputTimeoutRunnable)
    }

    // ── Up Next / Auto-Advance ────────────────────
    private fun checkUpNextTrigger() {
        if (upNextActive) return // already showing
        if (!playerManager.isPlaying) return
        if (currentEpisodeQueueIndex < 0) return // not in an episode queue
        val nextIdx = currentEpisodeQueueIndex + 1
        if (nextIdx !in currentEpisodeQueue.indices) return // no next episode
        val duration = playerManager.duration
        val position = playerManager.currentPosition
        if (duration <= 0 || duration - position > 10000) return // more than 10s left
        upNextEpisode = currentEpisodeQueue[nextIdx]
        showUpNextOverlay()
    }

    private fun showUpNextOverlay() {
        upNextActive = true
        upNextCountdown = 10
        binding.upNextTitle.text = upNextEpisode?.name ?: ""
        binding.upNextCountdown.text = upNextCountdown.toString()
        binding.upNextOverlay.visibility = View.VISIBLE
        binding.upNextPlayNow.requestFocus()
        mainHandler.post(upNextTickRunnable)
    }

    private fun updateUpNextOverlay() {
        binding.upNextCountdown.text = upNextCountdown.toString()
    }

    private fun executeUpNextAdvance() {
        cancelUpNextCountdown()
        val nextEp = upNextEpisode ?: return
        upNextEpisode = null
        upNextActive = false
        binding.upNextOverlay.visibility = View.GONE
        // Stopping the current player triggers STATE_ENDED, which would also try to
        // advance if we don't clear the queue first.
        val queue = currentEpisodeQueue
        val idx = currentEpisodeQueueIndex
        currentEpisodeQueue = emptyList()
        currentEpisodeQueueIndex = -1
        showPlayerFor(nextEp)
        // Restore the queue so Next/Prev work for subsequent episodes
        currentEpisodeQueue = queue
        currentEpisodeQueueIndex = idx + 1
    }

    private fun cancelUpNext() {
        cancelUpNextCountdown()
        upNextEpisode = null
        upNextActive = false
        binding.upNextOverlay.visibility = View.GONE
    }

    private fun cancelUpNextCountdown() {
        mainHandler.removeCallbacks(upNextTickRunnable)
    }

    /** Debounced so fast D-pad scrolling through the list doesn't spawn a load per row. */
    private fun requestPreviewLoad(channel: Channel) {
        lastFocusedLiveChannel = channel
        if (activeTab != 0 || isPlayerVisible) return
        if (channel.id.isNotBlank() && channel.id == previewChannelId) return
        previewLoadRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { loadPreview(channel) }
        previewLoadRunnable = runnable
        mainHandler.postDelayed(runnable, 500)
    }

    private fun loadPreview(channel: Channel) {
        if (activeTab != 0 || isPlayerVisible) return
        previewChannelId = channel.id
        binding.previewChannelName.text = channel.name
        binding.previewBuffering.visibility = View.VISIBLE
        previewVersionGroup = liveVersions[channel.id] ?: listOf(channel)
        previewVersionIndex = previewVersionGroup.indexOfFirst { !isStreamDead(it) }.takeIf { it >= 0 } ?: 0
        val startVersion = previewVersionGroup.getOrNull(previewVersionIndex) ?: channel
        ensurePreviewPlayer().playUrl(startVersion.url, startVersion.streamUserAgent)
        startPreviewBlackFrameWatch()
    }

    private fun formatEpgTimeRange(startSeconds: Long, stopSeconds: Long): String {
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return "${fmt.format(java.util.Date(startSeconds * 1000))} – ${fmt.format(java.util.Date(stopSeconds * 1000))}"
    }

    private fun navigateChannel(dir: Int) {
        val episodeQueue = currentEpisodeQueue
        if (currentEpisodeQueueIndex >= 0 && episodeQueue.isNotEmpty()) {
            val idx = currentEpisodeQueueIndex + dir
            if (idx in episodeQueue.indices) {
                showPlayerFor(episodeQueue[idx])
                currentEpisodeQueue = episodeQueue
                currentEpisodeQueueIndex = idx
            } else {
                Toast.makeText(this, if (dir < 0) "First episode" else "Last episode", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val list = when (activeTab) { 0 -> liveChannels; 1 -> seriesList; 2 -> filmList; else -> liveChannels }
        val idx = currentIndex + dir
        if (idx in list.indices) { currentIndex = idx; showPlayerFor(list[idx]) }
        else { Toast.makeText(this, if (dir < 0) "First" else "Last", Toast.LENGTH_SHORT).show() }
    }

    private fun showControls() {
        // Up Next shares the bottom-right corner with the controls bar's track buttons -
        // don't let both render at once.
        if (upNextActive) binding.upNextOverlay.visibility = View.GONE
        binding.controlsOverlay.visibility = View.VISIBLE
        // Becoming visible doesn't hand D-pad focus to anything by itself - without an
        // explicit request nothing in the overlay is reachable at all, since no view had
        // focus while it was hidden.
        if (!binding.btnPlayPause.isFocused) binding.btnPlayPause.requestFocus()
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, 4000)
    }

    private fun hideControls() {
        binding.controlsOverlay.visibility = View.GONE
        if (upNextActive) binding.upNextOverlay.visibility = View.VISIBLE
    }
    private fun toggleControls() {
        if (binding.controlsOverlay.visibility == View.VISIBLE) hideControls() else showControls()
    }

    private fun updatePlayPauseIcon() {
        binding.btnPlayPause.setImageResource(
            if (playerManager.isPlaying) R.drawable.ic_pause
            else R.drawable.ic_play
        )
    }

    private fun updateProgress() {
        if (!isPlayerVisible) return
        if (binding.seekBar.isPressed) return
        val pos = playerManager.currentPosition
        val dur = playerManager.duration
        binding.currentTime.text = formatTime(pos)
        if (dur > 0) {
            binding.duration.text = formatTime(dur)
            binding.seekBar.progress = ((pos.toFloat() / dur) * 100).toInt()
            binding.seekBar.keyProgressIncrement = maxOf(1, (30_000f / dur * 100).toInt())
        }
        binding.seekBar.isEnabled = dur > 0

        // Ticks every ~1s while playing; persist progress every ~5s instead of every tick.
        progressTickCount++
        if (progressTickCount % 5 == 0) saveCurrentPlaybackPosition()
        // Jellyfin expects a heartbeat roughly every 10s - it's what keeps the server's
        // resume point current and stops it reaping an active transcode as abandoned.
        if (progressTickCount % 10 == 0) reportJellyfinProgress()
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
    }

    // ── EPG Source Management ──────────────────────

    private fun showAddEpgSourceDialog() {
        val input = EditText(this).apply {
            hint = "XMLTV EPG URL"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val nameInput = EditText(this).apply {
            hint = "Source name"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
            addView(nameInput)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Add EPG Source")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val name = nameInput.text.toString().trim().ifBlank { "EPG ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}" }
                val url = input.text.toString().trim()
                if (url.isBlank()) { Toast.makeText(this, "Enter a URL", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                scope.launch {
                    database.epgSourceDao().insert(
                        EpgSourceEntity(
                            id = java.util.UUID.randomUUID().toString(),
                            name = name,
                            url = url
                        )
                    )
                    Toast.makeText(this@MainActivity, "EPG source added", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Backup Activity Result ──────────────────────

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            REQUEST_EXPORT_BACKUP -> {
                scope.launch {
                    val success = pendingBackupManager?.exportTo(uri) == true
                    Toast.makeText(this@MainActivity, if (success) "Backup exported" else "Export failed", Toast.LENGTH_SHORT).show()
                    pendingBackupManager = null
                }
            }
            REQUEST_IMPORT_BACKUP -> {
                scope.launch {
                    val result = pendingBackupManager?.importFrom(uri)
                    val msg = result?.let { "Imported: ${it.providersImported} providers, ${it.epgSourcesImported} EPG sources, ${it.customGroupsImported} groups" } ?: "Import failed"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    pendingBackupManager = null
                }
            }
        }
    }

    // ── Provider Settings (QR + Manual entry) ─────

    /** Lightweight stand-in for AlertDialog that mimics just what showProviderSettings()
     *  needs - dismiss()/setOnDismissListener()/show() plus a Save/Cancel button pair -
     *  while actually adding the content view into [container] (the same "swap the active
     *  tab's content region" slot every other tab uses), so the toolbar + tab bar above
     *  stay visible and usable while Settings is open. A real AlertDialog rendered as a
     *  small centered floating box with the platform's own button panel no matter what
     *  background/size overrides were applied on its Window - not something
     *  window.setLayout(MATCH_PARENT, MATCH_PARENT) can escape - so this skips Dialog
     *  entirely instead of fighting it. */
    private class FullScreenOverlay(
        private val container: FrameLayout,
        val view: View,
        closeButton: View,
        // Lambda, not a captured View - callers like showProviderSettings() may hide/show
        // views (e.g. addIptvProviderButton) between constructing this and show() actually
        // running, so the target must be resolved at show()-time, not construction-time.
        // Resolving it early against a view that's since gone GONE meant requestFocus()
        // silently failed, leaving nothing focused and the d-pad unable to move at all.
        private val initialFocus: (() -> View?)? = null
    ) {
        private var dismissListener: (() -> Unit)? = null

        init {
            closeButton.setOnClickListener { dismiss() }
        }

        fun setOnDismissListener(listener: () -> Unit) { dismissListener = listener }

        fun show() {
            if (view.layoutParams !is FrameLayout.LayoutParams) {
                view.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            container.addView(view)
            container.visibility = View.VISIBLE
            view.post {
                val target = initialFocus?.invoke()
                if (target != null && target.visibility == View.VISIBLE) target.requestFocus() else view.requestFocus()
            }
        }

        fun dismiss() {
            if (view.parent === container) container.removeView(view)
            container.visibility = View.GONE
            dismissListener?.invoke()
        }
    }

    /** Runs the Jellyfin Quick Connect handshake against [url]: starts a code (or reuses
     *  [existing] if the QR flow already started one and showed it on the phone - starting
     *  a second one here would mint a different code than what's on screen there), polls
     *  for server-side approval, then exchanges it for a session. On success, persists the
     *  session - Jellyfin is a fully independent provider slot now (can be configured and
     *  active at the same time as an IPTV one), so this no longer touches the shared
     *  `provider` field at all. Reports progress via [onStatus] so callers (the manual
     *  settings button and the QR-pairing receive handler) can show it wherever's relevant. */
    private suspend fun performJellyfinQuickConnect(
        url: String,
        existing: Pair<String, String>? = null,
        onStatus: (String) -> Unit
    ): Boolean {
        val qc = JellyfinProvider(BaseApplication.instance.okHttpClient)
        val (code, secret) = existing ?: run {
            onStatus("Starting…")
            withContext(Dispatchers.IO) { qc.startQuickConnect(url) }
                ?: run { onStatus(qc.lastQuickConnectError ?: "Couldn't start Quick Connect - check the server URL"); return false }
        }
        onStatus("Enter code $code on your Jellyfin server")
        val deadline = System.currentTimeMillis() + 120_000L
        var approved = false
        while (System.currentTimeMillis() < deadline) {
            delay(2000)
            if (withContext(Dispatchers.IO) { qc.isQuickConnectApproved(url, secret) }) { approved = true; break }
        }
        if (!approved) { onStatus("Quick Connect timed out"); return false }
        onStatus("Signing in…")
        val authResult = withContext(Dispatchers.IO) { qc.completeQuickConnect(url, secret) }
        val auth = authResult.getOrNull()
        if (auth == null || auth.token == null || auth.userId == null) {
            onStatus("Quick Connect sign-in failed")
            return false
        }
        // No password to save here - the token itself is the credential from now on.
        prefs.edit()
            .putString("jellyfin_url", url)
            .putString("jellyfin_token", auth.token)
            .putString("jellyfin_userid", auth.userId)
            .putBoolean("jellyfin_provider_enabled", true)
            .apply()
        return true
    }

    @Suppress("DEPRECATION")
    private fun showProviderSettings() {
        // Close Search if it's open - the two share the weighted content slot and would otherwise
        // render stacked on top of each other (see showSearchDialog).
        activeSearchOverlay?.dismiss()
        val dialogView = layoutInflater.inflate(R.layout.activity_settings, null)
        // Deliberately no width cap here. Settings used to be pinned to 660dp and centred on
        // TV, which left a wide band of the tab background down both sides - it read as a
        // floating pop-out rather than a screen, and squeezed the two-pane layout (nav rail
        // plus content) into a column too narrow for either. It now fills its slot, and
        // reading measure is held by settings_content_inset on the content column instead.
        val typeM3u = dialogView.findViewById<View>(R.id.settingsTypeM3u)
        val typeXtream = dialogView.findViewById<View>(R.id.settingsTypeXtream)
        val typeStalker = dialogView.findViewById<View>(R.id.settingsTypeStalker)
        val typeJellyfin = dialogView.findViewById<View>(R.id.settingsTypeJellyfin)
        val showQrButton = dialogView.findViewById<View>(R.id.settingsShowQrButton)
        val manualDivider = dialogView.findViewById<View>(R.id.settingsManualDivider)
        val nameSection = dialogView.findViewById<View>(R.id.settingsNameSection)
        val qrSection = dialogView.findViewById<View>(R.id.settingsQrSection)
        val qrFrame = dialogView.findViewById<View>(R.id.settingsQrFrame)
        val qrImage = dialogView.findViewById<ImageView>(R.id.settingsQrImage)
        val qrStatus = dialogView.findViewById<TextView>(R.id.settingsQrStatus)
        val qrTimer = dialogView.findViewById<TextView>(R.id.settingsQrTimer)
        val m3uGroup = dialogView.findViewById<View>(R.id.settingsM3uGroup)
        val xtreamGroup = dialogView.findViewById<View>(R.id.settingsXtreamGroup)
        val stalkerGroup = dialogView.findViewById<View>(R.id.settingsStalkerGroup)
        val jellyfinGroup = dialogView.findViewById<View>(R.id.settingsJellyfinGroup)
        val m3uUrl = dialogView.findViewById<EditText>(R.id.settingsM3uUrl)
        val uaInput = dialogView.findViewById<EditText>(R.id.settingsUserAgent)
        val xtreamUrl = dialogView.findViewById<EditText>(R.id.settingsXtreamUrl)
        val xtreamUser = dialogView.findViewById<EditText>(R.id.settingsXtreamUser)
        val xtreamPass = dialogView.findViewById<EditText>(R.id.settingsXtreamPass)
        val stalkerUrl = dialogView.findViewById<EditText>(R.id.settingsStalkerUrl)
        val stalkerMac = dialogView.findViewById<EditText>(R.id.settingsStalkerMac)
        val jellyfinUrl = dialogView.findViewById<EditText>(R.id.settingsJellyfinUrl)
        val jellyfinUser = dialogView.findViewById<EditText>(R.id.settingsJellyfinUser)
        val jellyfinPass = dialogView.findViewById<EditText>(R.id.settingsJellyfinPass)
        val jellyfinQuickConnectLabel = dialogView.findViewById<TextView>(R.id.settingsJellyfinQuickConnectLabel)
        val jellyfinQuickConnectButton = dialogView.findViewById<View>(R.id.settingsJellyfinQuickConnect)
        val hideNonEnglish = dialogView.findViewById<CheckBox>(R.id.settingsHideNonEnglish)
        val clearHistory = dialogView.findViewById<View>(R.id.settingsClearHistory)

        val iptvListSection = dialogView.findViewById<View>(R.id.settingsIptvListSection)
        val iptvProviderListContainer = dialogView.findViewById<LinearLayout>(R.id.settingsIptvProviderList)
        val iptvProviderListEmpty = dialogView.findViewById<View>(R.id.settingsIptvProviderListEmpty)
        val addIptvProviderButton = dialogView.findViewById<View>(R.id.settingsAddIptvProvider)
        val iptvFormSection = dialogView.findViewById<View>(R.id.settingsIptvFormSection)
        val iptvFieldsSection = dialogView.findViewById<View>(R.id.settingsIptvFieldsSection)
        val typePicker = dialogView.findViewById<View>(R.id.settingsTypePicker)
        val typeSummary = dialogView.findViewById<View>(R.id.settingsTypeSummary)
        val typeSummaryLabel = dialogView.findViewById<TextView>(R.id.settingsTypeSummaryLabel)
        val typeSummaryChange = dialogView.findViewById<View>(R.id.settingsTypeSummaryChange)
        val iptvFormTitle = dialogView.findViewById<TextView>(R.id.settingsIptvFormTitle)
        val iptvFormCancel = dialogView.findViewById<View>(R.id.settingsIptvFormCancel)
        val providerNameInput = dialogView.findViewById<EditText>(R.id.settingsProviderName)

        clearHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear watch history?")
                .setMessage("Removes resume positions and recently-played channels. Favorites aren't affected.")
                .setPositiveButton("Clear") { _, _ ->
                    PlaybackPositionStore.clearAll(this)
                    RecentlyPlayedStore.clear(this)
                    Toast.makeText(this, "Watch history cleared", Toast.LENGTH_SHORT).show()
                    if (showingHome) selectHome()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val dialog = FullScreenOverlay(
            binding.settingsContainer,
            dialogView,
            closeButton = dialogView.findViewById(R.id.settingsCancelButton),
            // Resolved lazily at show()-time (see FullScreenOverlay) - if nothing's
            // configured yet, openIptvForm(null) has already run by then and hidden
            // addIptvProviderButton, so fall back to the first type card instead.
            initialFocus = { if (addIptvProviderButton.visibility == View.VISIBLE) addIptvProviderButton else typeM3u }
        )

        jellyfinQuickConnectButton.setOnClickListener {
            val url = jellyfinUrl.text.toString().trim().let { if (it.isBlank()) it else normalizeServerUrl(it, defaultScheme = "https") }
            if (url.isBlank()) {
                Toast.makeText(this, "Enter a server URL first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            scope.launch {
                var lastMsg = ""
                val ok = performJellyfinQuickConnect(url) { msg -> lastMsg = msg; jellyfinQuickConnectLabel.text = msg }
                jellyfinQuickConnectLabel.text = "Sign in with Quick Connect"
                if (ok) {
                    
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "Signed in via Quick Connect", Toast.LENGTH_SHORT).show()
                    loadAllConfiguredProviders(forceRefresh = true)
                } else {
                    Toast.makeText(this@MainActivity, lastMsg, Toast.LENGTH_LONG).show()
                }
            }
        }

        var serverRunning = false
        // One form shared by every provider type, incl. Jellyfin - it used to be a
        // separate always-visible section, but that meant asking for its server/user/pass
        // even when someone only wanted IPTV. Now it's just another type card, and only
        // its fields show once picked. IPTV types share one saved-config list
        // (IptvProviderConfig, see editingProviderId); Jellyfin is still a single fixed
        // slot under the hood (see performJellyfinSave() below), just presented the same way.
        // currentType is null until a card is tapped - the rest of the form (QR button,
        // name, type-specific fields) stays hidden until then.
        var currentType: String? = null
        var editingProviderId: String? = null
        val typeCards = mapOf("m3u" to typeM3u, "xtream" to typeXtream, "stalker" to typeStalker, "jellyfin" to typeJellyfin)
        val typeLabels = mapOf("m3u" to "M3U", "xtream" to "Xtream", "stalker" to "Stalker Portal", "jellyfin" to "Jellyfin")

        // Collapses the 4-card type picker to a one-line "Type: X · Change" summary once
        // picked - keeping all 4 cards on screen while filling in fields pushed the QR
        // code/fields below the fold, forcing a scroll right after tapping "Show QR".
        fun selectType(type: String) {
            currentType = type
            typeCards.forEach { (t, card) ->
                card.setBackgroundResource(if (t == type) R.drawable.bg_type_option_selected else R.drawable.card_surface_background)
            }
            typePicker.visibility = View.GONE
            typeSummary.visibility = View.VISIBLE
            typeSummaryLabel.text = "Type: ${typeLabels[type]}"
            iptvFieldsSection.visibility = View.VISIBLE
            nameSection.visibility = if (type == "jellyfin") View.GONE else View.VISIBLE
            m3uGroup.visibility = if (type == "m3u") View.VISIBLE else View.GONE
            xtreamGroup.visibility = if (type == "xtream") View.VISIBLE else View.GONE
            stalkerGroup.visibility = if (type == "stalker") View.VISIBLE else View.GONE
            jellyfinGroup.visibility = if (type == "jellyfin") View.VISIBLE else View.GONE
            // Stalker portals identify a device by its MAC - leave blank for user to fill.
            val qrEligible = type in listOf("m3u", "xtream", "stalker", "jellyfin")
            showQrButton.visibility = if (qrEligible) View.VISIBLE else View.GONE
            manualDivider.visibility = if (qrEligible) View.VISIBLE else View.GONE
            // The tapped type card just went GONE (typePicker hidden above) - it was
            // holding d-pad focus, and a focused view disappearing leaves nothing
            // focused, so the d-pad stops responding entirely until something explicitly
            // claims focus again.
            typeSummaryChange.post { typeSummaryChange.requestFocus() }
        }

        fun stopQrServer() {
            qrManager.stop()
            serverRunning = false
            qrSection.visibility = View.GONE
            qrFrame.visibility = View.GONE
            qrTimer.visibility = View.GONE
        }

        typeSummaryChange.setOnClickListener {
            if (serverRunning) stopQrServer()
            currentType = null
            typeCards.values.forEach { it.setBackgroundResource(R.drawable.card_surface_background) }
            typeSummary.visibility = View.GONE
            typePicker.visibility = View.VISIBLE
            iptvFieldsSection.visibility = View.GONE
            m3uGroup.visibility = View.GONE
            xtreamGroup.visibility = View.GONE
            stalkerGroup.visibility = View.GONE
            jellyfinGroup.visibility = View.GONE
            // Same reasoning as in selectType() - typeSummary (holding focus) just went
            // GONE, so explicitly hand focus to the now-visible first card.
            typeM3u.post { typeM3u.requestFocus() }
        }

        fun startQrServer(type: String) {
            if (serverRunning) return
            serverRunning = true
            qrSection.visibility = View.VISIBLE
            qrFrame.visibility = View.GONE
            qrTimer.visibility = View.GONE
            qrStatus.text = "Starting server..."

            scope.launch {
                val result = qrManager.start(providerType = type)
                if (result != null) {
                    qrImage.setImageBitmap(result.qrBitmap)
                    qrFrame.visibility = View.VISIBLE
                    qrTimer.visibility = View.VISIBLE
                    qrStatus.text = "Scan QR with your phone"
                    launch {
                        while (qrManager.result != null) {
                            val rem = (result.expiresAtMs - System.currentTimeMillis()) / 1000
                            if (rem <= 0) break
                            qrTimer.text = "Expires in ${rem / 60}:%02d".format(rem % 60)
                            delay(1000)
                        }
                        if (serverRunning) {
                            qrTimer.text = "Expired"
                            stopQrServer()
                        }
                    }
                } else {
                    serverRunning = false
                    qrStatus.text = "Could not start server"
                }
            }
        }

        qrManager.onProviderReceived = { type, form ->
            runOnUiThread {
                qrStatus.text = "Provider received! Loading..."
                when (type) {
                    "m3u" -> {
                        val url = form["m3uUrl"]?.let { normalizeServerUrl(it) } ?: return@runOnUiThread
                        IptvProviderStore.upsert(prefs, IptvProviderConfig(
                            id = IptvProviderStore.newId(), type = "m3u", name = form["name"]?.takeIf { it.isNotBlank() } ?: "QR M3U",
                            enabled = true, url = url, userAgent = form["userAgent"]
                        ))
                        
                        stopQrServer()
                        dialog.dismiss()
                        loadAllConfiguredProviders(forceRefresh = true)
                    }
                    "xtream" -> {
                        val su = form["serverUrl"]?.let { normalizeServerUrl(it) } ?: return@runOnUiThread
                        IptvProviderStore.upsert(prefs, IptvProviderConfig(
                            id = IptvProviderStore.newId(), type = "xtream", name = form["name"]?.takeIf { it.isNotBlank() } ?: "QR Xtream",
                            enabled = true, url = su, username = form["username"], password = form["password"]
                        ))
                        
                        stopQrServer()
                        dialog.dismiss()
                        loadAllConfiguredProviders(forceRefresh = true)
                    }
                    "stalker" -> {
                        val su = form["stalkerUrl"]?.let { normalizeServerUrl(it) } ?: return@runOnUiThread
                        val mac = form["stalkerMac"]?.takeIf { it.isNotBlank() } ?: return@runOnUiThread
                        // MAC rides in userAgent - the same slot Stalker configs use for it
                        // everywhere else (see IptvProviderConfig / loadAllConfiguredProviders).
                        IptvProviderStore.upsert(prefs, IptvProviderConfig(
                            id = IptvProviderStore.newId(), type = "stalker", name = form["name"]?.takeIf { it.isNotBlank() } ?: "QR Stalker",
                            enabled = true, url = su, userAgent = mac
                        ))
                        
                        stopQrServer()
                        dialog.dismiss()
                        loadAllConfiguredProviders(forceRefresh = true)
                    }
                    "jellyfin" -> {
                        // Quick Connect never reaches this branch - QrPairingManager
                        // special-cases it (needs to start the session synchronously, while
                        // still handling the phone's POST, so the code can be shown on both
                        // screens) and calls onProviderReceived with type "jellyfin_quickconnect"
                        // instead. This path is password-only.
                        val url = form["jellyfinServerUrl"]?.let { normalizeServerUrl(it, defaultScheme = "https") } ?: return@runOnUiThread
                        val user = form["jellyfinUsername"]; val pass = form["jellyfinPassword"]
                        prefs.edit().putString("jellyfin_url", url).putString("jellyfin_user", user).putString("jellyfin_pass", pass).putBoolean("jellyfin_provider_enabled", true).apply()
                        
                        stopQrServer()
                        dialog.dismiss()
                        loadAllConfiguredProviders(forceRefresh = true)
                    }
                    "jellyfin_quickconnect" -> {
                        val url = form["serverUrl"] ?: return@runOnUiThread
                        val code = form["code"] ?: return@runOnUiThread
                        val secret = form["secret"] ?: return@runOnUiThread
                        qrStatus.text = "Enter code $code on your Jellyfin server"
                        scope.launch {
                            var lastMsg = ""
                            val ok = performJellyfinQuickConnect(url, existing = code to secret) { msg -> lastMsg = msg; qrStatus.text = msg }
                            if (ok) {
                                
                                stopQrServer()
                                dialog.dismiss()
                                Toast.makeText(this@MainActivity, "Signed in via Quick Connect", Toast.LENGTH_SHORT).show()
                                loadAllConfiguredProviders(forceRefresh = true)
                            } else {
                                qrStatus.text = lastMsg
                            }
                        }
                    }
                }
            }
        }

        qrManager.onJellyfinQuickConnect = { url ->
            // url is already normalized by QrPairingManager before it gets here.
            val qc = JellyfinProvider(BaseApplication.instance.okHttpClient)
            val pair = withContext(Dispatchers.IO) { qc.startQuickConnect(url) }
            if (pair != null) {
                QrPairingManager.QuickConnectStart(pair.first, pair.second, null)
            } else {
                QrPairingManager.QuickConnectStart(null, null, qc.lastQuickConnectError ?: "Couldn't start Quick Connect - check the server URL")
            }
        }

        qrManager.onError = { msg ->
            runOnUiThread { qrStatus.text = msg }
        }

        typeCards.forEach { (type, card) ->
            card.setOnClickListener {
                selectType(type)
                if (serverRunning) {
                    stopQrServer()
                    startQrServer(type)
                }
            }
        }
        showQrButton.setOnClickListener { currentType?.let { startQrServer(it) } }

        fun closeIptvForm() {
            editingProviderId = null
            currentType = null
            typeCards.values.forEach { it.setBackgroundResource(R.drawable.card_surface_background) }
            typeSummary.visibility = View.GONE
            typePicker.visibility = View.VISIBLE
            iptvFieldsSection.visibility = View.GONE
            iptvFormSection.visibility = View.GONE
            addIptvProviderButton.visibility = View.VISIBLE
            iptvListSection.visibility = View.VISIBLE
            if (serverRunning) stopQrServer()
        }

        // Adding new (existing == null) always starts on the type picker with every
        // field hidden - the type cards are the only thing shown until one is tapped.
        // Editing (existing != null) skips straight to that type's fields since it's
        // already known. The "your providers" list collapses while this is open (see
        // settingsIptvListSection) - it's irrelevant mid-add and the space it frees up
        // is what keeps the QR code/fields on screen without a scroll.
        fun openIptvForm(existing: IptvProviderConfig?) {
            editingProviderId = existing?.id
            addIptvProviderButton.visibility = View.GONE
            iptvListSection.visibility = View.GONE
            providerNameInput.setText(existing?.name ?: "")
            if (existing != null) {
                iptvFormTitle.text = "Editing ${existing.name}"
                iptvFormTitle.visibility = View.VISIBLE
                selectType(existing.type)
            } else {
                iptvFormTitle.visibility = View.GONE
                currentType = null
                typeCards.values.forEach { it.setBackgroundResource(R.drawable.card_surface_background) }
                typeSummary.visibility = View.GONE
                typePicker.visibility = View.VISIBLE
                iptvFieldsSection.visibility = View.GONE
                m3uGroup.visibility = View.GONE
                xtreamGroup.visibility = View.GONE
                stalkerGroup.visibility = View.GONE
                jellyfinGroup.visibility = View.GONE
            }
            val type = existing?.type ?: "m3u"
            m3uUrl.setText(if (type == "m3u") existing?.url ?: "" else "")
            uaInput.setText(if (type == "m3u") existing?.userAgent ?: "" else "")
            xtreamUrl.setText(if (type == "xtream") existing?.url ?: "" else "")
            xtreamUser.setText(if (type == "xtream") existing?.username ?: "" else "")
            xtreamPass.setText(if (type == "xtream") existing?.password ?: "" else "")
            stalkerUrl.setText(if (type == "stalker") existing?.url ?: "" else "")
            stalkerMac.setText(if (type == "stalker") existing?.userAgent ?: "" else "")
            iptvFormSection.visibility = View.VISIBLE
        }

        // Jellyfin isn't in IptvProviderStore (single fixed slot, stored as loose prefs -
        // see hasJellyfinConfigured()), so editing it re-uses the same form/type-card UI
        // but pre-fills from those prefs instead of an IptvProviderConfig.
        fun openJellyfinEditForm() {
            editingProviderId = null
            addIptvProviderButton.visibility = View.GONE
            iptvListSection.visibility = View.GONE
            iptvFormTitle.text = "Editing Jellyfin"
            iptvFormTitle.visibility = View.VISIBLE
            providerNameInput.setText("")
            selectType("jellyfin")
            jellyfinUrl.setText(prefs.getString("jellyfin_url", "") ?: "")
            jellyfinUser.setText(prefs.getString("jellyfin_user", "") ?: "")
            jellyfinPass.setText(prefs.getString("jellyfin_pass", "") ?: "")
            iptvFormSection.visibility = View.VISIBLE
        }

        fun renderIptvProviderList() {
            iptvProviderListContainer.removeAllViews()
            val list = IptvProviderStore.load(prefs)
            iptvProviderListEmpty.visibility = if (list.isEmpty() && !hasJellyfinConfigured()) View.VISIBLE else View.GONE
            for (cfg in list) {
                val row = layoutInflater.inflate(R.layout.item_iptv_provider_row, iptvProviderListContainer, false)
                val enabledBox = row.findViewById<CheckBox>(R.id.rowEnabled)
                enabledBox.isChecked = cfg.enabled
                // The checkbox is not clickable/focusable itself - clicking the row is what
                // toggles it, which is the only way a D-pad can reach it at all.
                row.setOnClickListener {
                    val checked = !enabledBox.isChecked
                    enabledBox.isChecked = checked
                    IptvProviderStore.setEnabled(prefs, cfg.id, checked)
                    applyProviderToggle(checked) { it.sourceProviderId == cfg.id }
                }
                row.findViewById<TextView>(R.id.rowName).text = cfg.name
                val typeLabel = when (cfg.type) { "xtream" -> "Xtream"; "stalker" -> "Stalker Portal"; else -> "M3U" }
                row.findViewById<TextView>(R.id.rowDetail).text = "$typeLabel · ${cfg.url ?: ""}"
                row.findViewById<View>(R.id.rowEditButton).setOnClickListener { openIptvForm(cfg) }
                row.findViewById<View>(R.id.rowRemoveButton).setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Remove ${cfg.name}?")
                        .setMessage("This provider's channels will no longer appear.")
                        .setPositiveButton("Remove") { _, _ ->
                            IptvProviderStore.remove(prefs, cfg.id)
                            renderIptvProviderList()
                            loadAllConfiguredProviders(forceRefresh = true)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                iptvProviderListContainer.addView(row)
            }
            if (hasJellyfinConfigured()) {
                val row = layoutInflater.inflate(R.layout.item_iptv_provider_row, iptvProviderListContainer, false)
                val enabledBox = row.findViewById<CheckBox>(R.id.rowEnabled)
                enabledBox.isChecked = isJellyfinEnabled()
                row.setOnClickListener {
                    val checked = !enabledBox.isChecked
                    enabledBox.isChecked = checked
                    prefs.edit().putBoolean("jellyfin_provider_enabled", checked).apply()
                    applyProviderToggle(checked) { it.isJellyfin }
                }
                row.findViewById<TextView>(R.id.rowName).text = "Jellyfin"
                row.findViewById<TextView>(R.id.rowDetail).text = "Jellyfin · ${prefs.getString("jellyfin_url", "") ?: ""}"
                row.findViewById<View>(R.id.rowEditButton).setOnClickListener { openJellyfinEditForm() }
                row.findViewById<View>(R.id.rowRemoveButton).setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Remove Jellyfin?")
                        .setMessage("Its channels will no longer appear.")
                        .setPositiveButton("Remove") { _, _ ->
                            prefs.edit().remove("jellyfin_url").remove("jellyfin_user").remove("jellyfin_pass")
                                .remove("jellyfin_provider_enabled").apply()
                            renderIptvProviderList()
                            loadAllConfiguredProviders(forceRefresh = true)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                iptvProviderListContainer.addView(row)
            }
        }

        addIptvProviderButton.setOnClickListener { openIptvForm(null) }

        iptvFormCancel.setOnClickListener { closeIptvForm() }

        renderIptvProviderList()
        // Exposed so the plugin-discovery pane can refresh this list after adding a provider.
        refreshIptvProviderList = { renderIptvProviderList() }
        // First run, nothing configured at all yet - the empty list + tiny "+ Add" button
        // would leave the user staring at nothing to interact with, so open the form
        // immediately (matches the old single-slot behavior of showing fields right away).
        if (IptvProviderStore.load(prefs).isEmpty() && !hasJellyfinConfigured()) {
            openIptvForm(null)
        }

        // Backup & Restore
        val backupManager = BackupManager(this)
        dialogView.findViewById<View>(R.id.settingsExportBackup).setOnClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(android.content.Intent.EXTRA_TITLE, "lumora_backup.json")
            }
            try {
                startActivityForResult(intent, REQUEST_EXPORT_BACKUP)
                pendingBackupManager = backupManager
            } catch (e: android.content.ActivityNotFoundException) {
                // Fire TV and most Android TV boxes ship no document picker at all - SAF
                // just isn't there to launch. Fall back to a fixed app-storage location
                // that works on every device, no picker required.
                scope.launch {
                    val file = localBackupFile()
                    val success = withContext(Dispatchers.IO) { backupManager.exportTo(Uri.fromFile(file)) }
                    Toast.makeText(
                        this@MainActivity,
                        if (success) "Backup saved to ${file.absolutePath}" else "Export failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        dialogView.findViewById<View>(R.id.settingsImportBackup).setOnClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "application/json"
            }
            try {
                startActivityForResult(intent, REQUEST_IMPORT_BACKUP)
                pendingBackupManager = backupManager
            } catch (e: android.content.ActivityNotFoundException) {
                val file = localBackupFile()
                if (!file.exists()) {
                    Toast.makeText(this@MainActivity, "No backup file found at ${file.absolutePath}", Toast.LENGTH_LONG).show()
                } else {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { backupManager.importFrom(Uri.fromFile(file)) }
                        Toast.makeText(
                            this@MainActivity,
                            "Imported: ${result.providersImported} providers, ${result.epgSourcesImported} EPG sources, ${result.customGroupsImported} groups",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        // EPG Source
        dialogView.findViewById<View>(R.id.settingsAddEpgSource).setOnClickListener {
            showEpgSourceListDialog()
        }

        // Recording storage
        dialogView.findViewById<View>(R.id.settingsRecordingStorage).setOnClickListener {
            Toast.makeText(this, "Recording storage: ${filesDir}/recordings", Toast.LENGTH_SHORT).show()
        }

        // Decoder mode settings button
        val decoderManager = com.lumora.player.playback.DecoderModeManager(this)
        dialogView.findViewById<View>(R.id.settingsDecoderMode).setOnClickListener {
            val settings = decoderManager.getSettings()
            val items = arrayOf(
                "Decoder: ${settings.decoderMode.label}",
                "Buffer: ${settings.bufferMode.label}",
                "Surface: ${settings.surfaceMode.label}",
                "FFmpeg: ${if (settings.enableFfmpeg) "ON" else "OFF"}"
            )
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Playback Settings")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> { val m = decoderManager.cycleDecoderMode(); Toast.makeText(this@MainActivity, "Decoder: ${m.label}", Toast.LENGTH_SHORT).show() }
                        1 -> { val m = decoderManager.cycleBufferMode(); Toast.makeText(this@MainActivity, "Buffer: ${m.label}", Toast.LENGTH_SHORT).show() }
                        2 -> { /* cycle surface mode */ Toast.makeText(this@MainActivity, "Surface mode changed", Toast.LENGTH_SHORT).show() }
                        3 -> { val s = settings.copy(enableFfmpeg = !settings.enableFfmpeg); decoderManager.save(s); Toast.makeText(this@MainActivity, "FFmpeg: ${if (s.enableFfmpeg) "ON" else "OFF"}", Toast.LENGTH_SHORT).show() }
                    }
                }
                .setPositiveButton("Close", null)
                .show()
        }

        // A/V sync offset settings
        dialogView.findViewById<View>(R.id.settingsAvOffset).setOnClickListener {
            val current = avOffsetManager.getOffset()
            val presets = listOf("-500 ms", "-250 ms", "-100 ms", "-50 ms", "0 ms", "+50 ms", "+100 ms", "+250 ms", "+500 ms")
            val values = listOf(-500, -250, -100, -50, 0, 50, 100, 250, 500)
            val checked = values.indexOf(current).coerceAtLeast(0)
            AlertDialog.Builder(this@MainActivity)
                .setTitle("A/V Sync Offset")
                .setSingleChoiceItems(presets.toTypedArray(), checked) { dialog, which ->
                    avOffsetManager.save(values[which])
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Jellyfin is a single independent slot (unlike IPTV, which is a list managed via
        // openIptvForm()/renderIptvProviderList() above), so its fields still pre-fill directly.
        jellyfinUrl.setText(prefs.getString("jellyfin_url", ""))
        jellyfinUser.setText(prefs.getString("jellyfin_user", ""))

        val subscriptionStatus = dialogView.findViewById<TextView>(R.id.settingsSubscriptionStatus)
        val expDate = prefs.getString("xtream_exp_date", null)?.toLongOrNull()
        val isTrial = prefs.getBoolean("xtream_is_trial", false)
        formatSubscriptionStatus(expDate, isTrial)?.let { status ->
            subscriptionStatus.text = status
            subscriptionStatus.setTextColor(getColor(if (status.startsWith("⚠")) R.color.live_red else R.color.success_green))
            subscriptionStatus.visibility = View.VISIBLE
        }

        hideNonEnglish.isChecked = prefs.getBoolean(PREF_HIDE_NON_ENGLISH, true)
        hideNonEnglish.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_HIDE_NON_ENGLISH, checked).apply()
            if (allChannels.isNotEmpty()) scope.launch { classifyAndShow() }
        }

        val hideAdult = dialogView.findViewById<CheckBox>(R.id.settingsHideAdult)
        val parentalPinRow = dialogView.findViewById<View>(R.id.settingsParentalPin)
        val parentalPinLabel = dialogView.findViewById<TextView>(R.id.settingsParentalPinLabel)
        hideAdult.isChecked = prefs.getBoolean(PREF_HIDE_ADULT, true)
        parentalPinLabel.text = if (hasParentalPin()) "Change parental PIN" else "Set parental PIN"

        lateinit var hideAdultListener: CompoundButton.OnCheckedChangeListener
        fun applyHideAdult(checked: Boolean) {
            hideAdult.setOnCheckedChangeListener(null)
            hideAdult.isChecked = checked
            hideAdult.setOnCheckedChangeListener(hideAdultListener)
            prefs.edit().putBoolean(PREF_HIDE_ADULT, checked).apply()
            if (allChannels.isNotEmpty()) scope.launch { classifyAndShow() }
        }
        hideAdultListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            // Turning filtering ON is always allowed. Turning it OFF needs the PIN, if one
            // is set - otherwise the toggle is just a preference with nothing locking it.
            if (!checked && hasParentalPin()) {
                applyHideAdult(true)
                promptForPin("Enter PIN to show adult content") { applyHideAdult(false) }
            } else {
                applyHideAdult(checked)
            }
        }
        hideAdult.setOnCheckedChangeListener(hideAdultListener)
        parentalPinRow.setOnClickListener {
            if (hasParentalPin()) {
                promptForPin("Enter current PIN") { showSetPinDialog(parentalPinLabel) }
            } else {
                showSetPinDialog(parentalPinLabel)
            }
        }

        // StreamVault-style nav rail: one section visible at a time.
        val navRows = listOf(
            R.id.navProviders to R.id.paneProviders,
            R.id.navPlayback to R.id.panePlayback,
            R.id.navFilters to R.id.paneFilters,
            R.id.navPrivacy to R.id.panePrivacy,
            R.id.navBackup to R.id.paneBackup,
            R.id.navEpg to R.id.paneEpg,
            R.id.navDownloads to R.id.paneDownloads,
            R.id.navPlugins to R.id.panePlugins,
            R.id.navAbout to R.id.paneAbout
        ).map { (navId, paneId) -> dialogView.findViewById<View>(navId) to dialogView.findViewById<View>(paneId) }
        fun selectSection(index: Int) {
            navRows.forEachIndexed { i, (row, pane) ->
                row.isSelected = i == index
                pane.visibility = if (i == index) View.VISIBLE else View.GONE
            }
        }
        navRows.forEachIndexed { i, (row, _) -> row.setOnClickListener { selectSection(i) } }
        selectSection(0)

        // A TV box has nowhere meaningful to browse a downloaded file (same reasoning
        // as the Downloads tab being mobile-only).
        dialogView.findViewById<View>(R.id.navDownloads).visibility = if (isTv) View.GONE else View.VISIBLE

        // Downloads pane reuses the exact same adapter/data as the Downloads tab -
        // RecyclerView supports multiple views sharing one adapter instance fine.
        dialogView.findViewById<RecyclerView>(R.id.settingsDownloadsList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = downloadAdapter
        }
        val settingsDownloadsEmptyText = dialogView.findViewById<TextView>(R.id.settingsDownloadsEmptyText)
        if (!isTv) {
            scope.launch {
                val records = withContext(Dispatchers.IO) { DownloadStore.getAll(this@MainActivity) }
                settingsDownloadsEmptyText.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        refreshDownloadsList()

        wirePluginsPane(dialogView)

        // About pane
        dialogView.findViewById<TextView>(R.id.settingsAppVersion).text = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName} (${info.versionCode})"
        } catch (e: Exception) { "unknown" }
        dialogView.findViewById<View>(R.id.settingsGithubLink).setOnClickListener {
            try {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://github.com/disclosurez/Lumora")))
            } catch (e: android.content.ActivityNotFoundException) {
                Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show()
            }
        }
        val checkUpdateLabel = dialogView.findViewById<TextView>(R.id.settingsCheckUpdateLabel)
        dialogView.findViewById<View>(R.id.settingsCheckUpdate).setOnClickListener {
            checkUpdateLabel.text = "Checking…"
            scope.launch {
                val updater = AppUpdateChecker(this@MainActivity)
                val info = withContext(Dispatchers.IO) { updater.checkForUpdate() }
                checkUpdateLabel.text = "Check for Updates"
                when {
                    info == null -> Toast.makeText(this@MainActivity, "Couldn't check for updates", Toast.LENGTH_SHORT).show()
                    info.isUpdateAvailable && info.downloadUrl.isNotBlank() -> {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Update available")
                            .setMessage("Lumora v${info.latestVersion} is available.\nCurrent: v${info.currentVersion}\n\n${info.releaseNotes.take(200)}")
                            .setPositiveButton("Update") { _, _ -> downloadAndInstallUpdate(info.downloadUrl, info.latestVersion) }
                            .setNegativeButton("Later", null)
                            .show()
                    }
                    else -> Toast.makeText(this@MainActivity, "You're on the latest version", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Init UI
        // No selectType() call here - the form starts closed with no type chosen; see
        // openIptvForm()/closeIptvForm() above for how that gets set per add/edit.
        // Hide whatever the active tab is showing so it doesn't render doubled-up behind
        // Settings in the same weight=1 slot - restored on dismiss below. That includes
        // Home's search bar and the Discover pane, which sit outside homeContent/contentRow
        // and so used to stay on screen above Settings as if they belonged to it.
        binding.homeContent.visibility = View.GONE
        binding.homeSearchBar.visibility = View.GONE
        binding.discoverContent.visibility = View.GONE
        binding.contentRow.visibility = View.GONE
        binding.emptyState.visibility = View.GONE
        dialog.setOnDismissListener {
            qrManager.stop()
            // A discovery run only exists to fill in this pane - closing Settings unbinds the
            // plugin rather than leaving another app's service bound with nowhere to report.
            pluginDiscoveryJob?.cancel()
            pluginDiscoveryJob = null
            activeSettingsOverlay = null
            refreshIptvProviderList = null
            // With no content (e.g. the last provider was just disabled), fall back to the
            // empty state rather than a blank Home/tab - selectHome() would show empty
            // shelves and leave the chrome half-populated.
            if (allChannels.isEmpty()) {
                showEmptyState()
            } else if (showingHome) selectHome() else if (showingDiscover) selectDiscover() else if (showingDownloads) selectDownloads() else selectTab(activeTab)
        }
        activeSettingsOverlay = dialog
        dialog.show()

        // The Save button's listener validates and keeps the form open on error instead of
        // dismissing unconditionally. Only acts when the add/edit form is actually open -
        // the same footer button is shared by every nav pane, most of which have nothing
        // for it to save.
        dialogView.findViewById<View>(R.id.settingsSaveButton).setOnClickListener {
            if (iptvFormSection.visibility != View.VISIBLE) return@setOnClickListener
            if (currentType == null) {
                Toast.makeText(this, "Choose a provider type first", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            val name = providerNameInput.text.toString().trim()
            val id = editingProviderId ?: IptvProviderStore.newId()
            when (currentType) {
                "m3u" -> {
                    val url = m3uUrl.text.toString().trim().let { if (it.isBlank()) it else normalizeServerUrl(it) }
                    if (url.isBlank()) {
                        Toast.makeText(this, "Enter an M3U URL", Toast.LENGTH_SHORT).show(); return@setOnClickListener
                    }
                    IptvProviderStore.upsert(prefs, IptvProviderConfig(
                        id = id, type = "m3u", name = name.ifBlank { "M3U Playlist" }, enabled = true,
                        url = url, userAgent = uaInput.text.toString().trim().ifBlank { null }
                    ))
                }
                "xtream" -> {
                    val url = xtreamUrl.text.toString().trim().let { if (it.isBlank()) it else normalizeServerUrl(it) }
                    if (url.isBlank()) { Toast.makeText(this, "Enter a server URL", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                    IptvProviderStore.upsert(prefs, IptvProviderConfig(
                        id = id, type = "xtream", name = name.ifBlank { "Xtream" }, enabled = true,
                        url = url, username = xtreamUser.text.toString().trim(), password = xtreamPass.text.toString().trim()
                    ))
                }
                "stalker" -> {
                    val url = stalkerUrl.text.toString().trim().let { if (it.isBlank()) it else normalizeServerUrl(it) }
                    if (url.isBlank()) { Toast.makeText(this, "Enter a server URL", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                    IptvProviderStore.upsert(prefs, IptvProviderConfig(
                        id = id, type = "stalker", name = name.ifBlank { "Stalker Portal" }, enabled = true,
                        url = url, userAgent = stalkerMac.text.toString().trim()
                    ))
                }
                "jellyfin" -> {
                    // Not part of IptvProviderStore - Jellyfin is still a single fixed
                    // slot under the hood, stored as loose prefs (see hasJellyfinConfigured()).
                    val url = jellyfinUrl.text.toString().trim().let { if (it.isBlank()) it else normalizeServerUrl(it, defaultScheme = "https") }
                    if (url.isBlank()) { Toast.makeText(this, "Enter a server URL", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                    prefs.edit()
                        .putString("jellyfin_url", url)
                        .putString("jellyfin_user", jellyfinUser.text.toString().trim())
                        .putString("jellyfin_pass", jellyfinPass.text.toString().trim())
                        .putBoolean("jellyfin_provider_enabled", true)
                        .apply()
                }
            }
            
            closeIptvForm()
            renderIptvProviderList()
            Toast.makeText(this, "Provider saved. Loading...", Toast.LENGTH_SHORT).show()
            loadAllConfiguredProviders(forceRefresh = true)
        }
    }

    // ── Stream-search plugins (detail screen "Find Stream") ────

    /** Shows the detail screen's "Find Stream" button when a stream-search plugin is enabled,
     *  and only for movies - a series detail screen isn't a single episode, so there's nothing
     *  specific to resolve from here (per-episode search would hang off the episode list). */
    /** Parses a Discover [Channel.id] of the form "tmdb:movie:123" / "tmdb:tv:123". */
    private fun tmdbTypeAndId(id: String): Pair<String, Int>? {
        val parts = id.split(":")
        if (parts.size != 3 || parts[0] != "tmdb") return null
        return parts[1] to (parts[2].toIntOrNull() ?: return null)
    }

    /** Looks up and plays a TMDB trailer for a Discover item (id already carries the TMDB id). */
    private fun showTrailerForDiscoverItem(item: Channel) {
        val (type, id) = tmdbTypeAndId(item.id) ?: run {
            android.util.Log.d("TrailerPlayer", "showTrailerForDiscoverItem: '${item.id}' not a tmdb id")
            return
        }
        scope.launch {
            val key = try {
                tmdbClient.trailerKey(type, id)
            } catch (e: Exception) {
                android.util.Log.e("TrailerPlayer", "trailerKey($type,$id) threw", e)
                null
            }
            android.util.Log.d("TrailerPlayer", "trailerKey($type,$id) = $key")
            if (key == null) {
                Toast.makeText(this@MainActivity, "No trailer found.", Toast.LENGTH_SHORT).show()
            } else {
                showTrailerPlayer(key)
            }
        }
    }

    /** Shows/hides the detail screen's Trailer button, resolving a catalog item to a TMDB id
     *  by title/year search since provider/Jellyfin content carries no TMDB id of its own. */
    private fun wireTrailerButton(item: Channel) {
        val button = binding.detailTrailerButton
        button.visibility = View.GONE
        button.setOnClickListener(null)
        if (!tmdbClient.hasKey()) {
            android.util.Log.d("TrailerPlayer", "wireTrailerButton: no TMDB key configured")
            return
        }
        scope.launch {
            try {
                val direct = tmdbTypeAndId(item.id)
                val resolved = direct ?: tmdbClient.resolveId(item.name, item.year, item.mediaType == MediaType.SERIES)
                android.util.Log.d("TrailerPlayer", "wireTrailerButton('${item.name}', year=${item.year}): resolved=$resolved (direct=${direct != null})")
                val (type, id) = resolved ?: return@launch
                val key = tmdbClient.trailerKey(type, id)
                android.util.Log.d("TrailerPlayer", "wireTrailerButton('${item.name}'): trailerKey=$key")
                if (key == null) return@launch
                button.visibility = View.VISIBLE
                button.setOnClickListener { showTrailerPlayer(key) }
            } catch (e: Exception) {
                android.util.Log.e("TrailerPlayer", "wireTrailerButton('${item.name}') threw", e)
            }
        }
    }

    /** Plays a YouTube trailer in-app, fullscreen, via the standard /embed player - loaded
     *  directly (no hand-built HTML wrapper: that rendered blank with no logged error). */
    private fun showTrailerPlayer(youtubeKey: String) {
        val density = resources.displayMetrics.density
        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true // YouTube's iframe player needs this or it stays blank with no error
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
                // YouTube's watch/embed page top-navigates to plain youtube.com/ as a fallback
                // when an internal resource (e.g. the doubleclick ad request) fails to load -
                // seen on networks that block ad domains. Refuse every main-frame navigation
                // outright: this player never legitimately needs to leave the embed URL.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.isForMainFrame && !request.url.toString().contains("/embed/")) {
                        android.util.Log.d("TrailerPlayer", "blocked main-frame navigation to ${request.url}")
                        return true
                    }
                    return false
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    android.util.Log.e(
                        "TrailerPlayer",
                        "onReceivedError url=${request.url} code=${error.errorCode} desc=${error.description}"
                    )
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    android.util.Log.e(
                        "TrailerPlayer",
                        "onReceivedHttpError url=${request.url} status=${response.statusCode}"
                    )
                }
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    android.util.Log.d("TrailerPlayer", "onPageStarted url=$url")
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    android.util.Log.d("TrailerPlayer", "onPageFinished url=$url")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    android.util.Log.d("TrailerPlayer", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                    return true
                }
            }
        }
        val closeButton = Button(this).apply {
            text = "Close"
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                topMargin = (16 * density).toInt()
                rightMargin = (16 * density).toInt()
            }
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(webView)
            addView(closeButton)
        }
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(root)
        closeButton.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { webView.destroy() }
        // A raw loadUrl only sends the Referer header on the very first request, not on the
        // player's own follow-up calls - got as far as fixing error 153 but still hit 152.
        // Giving the WebView's document itself a youtube-nocookie.com origin (via
        // loadDataWithBaseURL) plus an explicit iframe referrerpolicy covers those too.
        val html = """
            <html><body style="margin:0;padding:0;background:#000;">
            <iframe width="100%" height="100%"
                src="https://www.youtube-nocookie.com/embed/$youtubeKey?autoplay=1&playsinline=1"
                frameborder="0" referrerpolicy="strict-origin-when-cross-origin"
                allow="autoplay; encrypted-media" allowfullscreen></iframe>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "utf-8", null)
        dialog.show()
        closeButton.requestFocus()
    }

    private fun wireFindStreamButton(item: Channel) {
        val button = binding.detailFindStreamButton
        val plugin = enabledStreamSearchPlugin(item)
        val eligible = plugin != null && (item.mediaType == MediaType.MOVIE || item.mediaType == MediaType.SERIES)
        button.visibility = if (eligible) View.VISIBLE else View.GONE
        if (!eligible || plugin == null) {
            button.setOnClickListener(null)
            return
        }
        button.setOnClickListener { showStreamSearchDialog(plugin, item) }
    }

    /**
     * Resolves a magnet token via the native [TorrentEngine] instead of a JS plugin's own
     * `resolve()` - see [PluginScript.resolvesNatively]. Unlike a JS-resolved plain http(s) URL,
     * this one is served by a local HTTP server this engine instance owns, so it's kept alive in
     * [activeTorrentSession] for the life of playback (see [hidePlayer]/`onDestroy`).
     *
     * [activeTorrentSession] is set *before* the blocking [TorrentEngine.start] call, not after -
     * `start()` can take minutes (metadata fetch + buffering), and [TorrentEngine] only stops
     * that wait when its own `cancelled` flag is set by [TorrentEngine.stop] (coroutine
     * cancellation alone doesn't interrupt it - see [TorrentEngine]'s kdoc). Setting the field
     * early lets a caller that cancels mid-resolve (e.g. the Find Stream dialog's cancel
     * listener) actually reach and stop this engine instead of it finishing unattended minutes
     * later and popping up playback for a stream the user already backed out of.
     */
    private suspend fun resolveTorrentStream(
        magnet: String,
        season: Int?,
        episode: Int?,
        onProgress: (String) -> Unit
    ): ResolveResult {
        activeTorrentSession?.let { old -> Thread { runCatching { old.stop() } }.start() }
        TorrentForegroundService.start(this)
        val engine = TorrentEngine(this)
        activeTorrentSession = engine
        return try {
            val url = withContext(Dispatchers.IO) { engine.start(magnet, season, episode, onProgress) }
            ResolveResult.Ready(url)
        } catch (e: Exception) {
            if (activeTorrentSession === engine) activeTorrentSession = null
            withContext(Dispatchers.IO) { runCatching { engine.stop() } }
            TorrentForegroundService.stop(this)
            ResolveResult.Failed(e.message ?: "Could not resolve stream")
        }
    }

    /**
     * The enabled `stream_search` plugin to use for [item], if any. With more than one enabled
     * (e.g. an anime plugin and a general torrent plugin) this picks by declared
     * [PluginScript.contentTypes] instead of an arbitrary one - without [item] (existence-only
     * checks: is *any* stream_search plugin enabled, at all, for gating tabs/chrome) it just
     * returns the first. Anime catalog items carry the "anime:" id prefix set by
     * [fetchAnimeChannels] - the only signal Lumora itself has for "this title is anime",
     * entirely independent of which plugin (if any) declares itself able to handle that.
     */
    /** Stable identity for a plugin-resolved stream. Everything that keys off a channel id -
     *  the saved playback position above all - needs this to come out the same for the same
     *  episode on a later launch, so it's derived from the plugin + token + episode rather than
     *  anything about the particular resolve that produced the URL. */
    private fun pluginChannelId(plugin: PluginScript, token: String, episode: Int?): String =
        "plugin:${plugin.id}:$token" + (episode?.let { ":e$it" } ?: "")

    private fun enabledStreamSearchPlugin(item: Channel? = null): PluginScript? {
        val candidates = pluginScriptManager.getDiscoveredScripts().filter { it.enabled && it.supportsStreamSearch }
        if (item == null) return candidates.firstOrNull()
        val isAnime = item.id.startsWith("anime:")
        return candidates.firstOrNull { isAnime == it.contentTypes.contains("anime") } ?: candidates.firstOrNull()
    }

    /**
     * Runs a plugin stream search for [item], lists what comes back, and on a pick resolves it
     * to a playable URL and starts the player. Unlike the old Messenger plugins, a JS script has
     * no process of its own to keep bound during playback - `resolve()` just returns a plain
     * http(s) URL the player hits directly, so there's nothing to hold open past the pick.
     */
    private fun showStreamSearchDialog(
        plugin: PluginScript,
        item: Channel,
        season: Int? = null,
        episode: Int? = null
    ) {
        val epTag = if (season != null && episode != null)
            " S%02dE%02d".format(season, episode) else ""

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val status = TextView(this).apply {
            text = "Searching…"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        }
        val resultsHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(resultsHost)
        }
        container.addView(status)
        container.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Find Stream — ${item.name}$epTag")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .create()

        val source = pluginScriptManager.readSource(plugin)
        val results = mutableListOf<TorrentResult>()

        fun playResult(result: TorrentResult) {
            status.text = "Loading ${result.title}…"
            resultsHost.removeAllViews()
            scope.launch {
                val resolved = if (plugin.resolvesNatively) {
                    // TorrentEngine.start calls onProgress from its IO thread, so the TextView
                    // update has to hop to the main thread.
                    resolveTorrentStream(result.token, season, episode) { line ->
                        runOnUiThread { status.text = line }
                    }
                } else {
                    jsPluginEngine.resolve(source, result.token, season, episode)
                }
                when (resolved) {
                    is ResolveResult.Ready -> {
                        dialog.dismiss()
                        hideContentDetail()
                        showPlayerFor(
                            Channel(
                                // Derived from the token and episode rather than a hash of the
                                // moment: the saved-position key has to be the same string the
                                // next time this episode is played, or nothing ever resumes.
                                id = pluginChannelId(plugin, result.token, episode),
                                name = item.name + epTag,
                                url = resolved.url,
                                // Carried so the Continue Watching tile isn't a blank card, and
                                // so isAdultHomeItem has the same signals every other entry has.
                                posterUrl = item.posterUrl,
                                logoUrl = item.logoUrl,
                                group = item.group,
                                categoryName = item.categoryName,
                                mediaType = MediaType.MOVIE,
                                episodeNum = episode,
                                // Headers the CDN needs (e.g. a Referer) so the player doesn't 403.
                                streamHeaders = resolved.headers.ifEmpty { null },
                                // What lets a resume re-resolve this instead of replaying a URL
                                // that has since expired (see showPlayerFor's plugin branch).
                                pluginToken = result.token,
                                pluginId = plugin.id
                            ),
                            externalSubtitles = resolved.subtitles.map(::externalSubtitleFor),
                            pluginStreamAlreadyResolved = true
                        )
                        // Back out of a plugin-played episode to the title it was picked from,
                        // the same as any other VOD item (see hidePlayer).
                        detailReturnItem = item
                    }
                    is ResolveResult.Failed -> {
                        Toast.makeText(this@MainActivity, resolved.message, Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                    }
                }
            }
        }

        fun addResultRow(result: TorrentResult) {
            val row = layoutInflater.inflate(R.layout.item_stream_result, resultsHost, false)
            row.findViewById<TextView>(R.id.streamTitle).text = result.title
            row.findViewById<TextView>(R.id.streamMeta).text = listOfNotNull(
                result.quality,
                result.seeders?.let { "$it seeders" },
                result.size,
                result.source
            ).joinToString("  ·  ")
            row.setOnClickListener { playResult(result) }
            resultsHost.addView(row)
        }

        val searchJob = scope.launch {
            val query = item.name
            val year = item.year?.toIntOrNull()
            val outcome = jsPluginEngine.runSearch(
                source = source, query = query, year = year, season = season, episode = episode,
                onProgress = { if (results.isEmpty()) status.text = it },
                onResult = { result ->
                    results.add(result)
                    status.text = "${results.size} result(s)"
                    addResultRow(result)
                }
            )
            if (results.isEmpty()) {
                status.text = when (outcome) {
                    is SearchResult.Finished -> outcome.message ?: "No streams found"
                    is SearchResult.Failed -> outcome.message
                }
            }
        }
        dialog.setOnCancelListener {
            searchJob.cancel()
            // A native-torrent resolve in progress won't stop on its own past this point (see
            // resolveTorrentStream's kdoc) - only reachable while it hasn't succeeded yet, since
            // a successful resolve already dismissed this dialog before the user could cancel it.
            if (plugin.resolvesNatively) {
                activeTorrentSession?.let { engine -> Thread { runCatching { engine.stop() } }.start() }
                activeTorrentSession = null
                TorrentForegroundService.stop(this)
            }
        }
        dialog.show()
    }

    // ── Plugins ────────────────────────────────────

    /**
     * Settings > Plugins. Lists the user's installed JS plugin scripts, lets them switch one on,
     * run its discovery job, and add whatever it proposes.
     *
     * A deliberate gate, because a script's output is still untrusted input proposing servers
     * and credentials to point this app at: no proposal is written to the provider list without
     * a per-item confirmation naming which plugin it came from. [com.lumora.plugin.js.JsHostImpl]
     * does the field validation before any of this sees a candidate.
     */
    private fun wirePluginsPane(dialogView: View) {
        val listContainer = dialogView.findViewById<LinearLayout>(R.id.settingsPluginList)
        val listEmpty = dialogView.findViewById<View>(R.id.settingsPluginListEmpty)
        val runSection = dialogView.findViewById<View>(R.id.settingsPluginRunSection)
        val progress = dialogView.findViewById<View>(R.id.settingsPluginProgress)
        val status = dialogView.findViewById<TextView>(R.id.settingsPluginStatus)
        val candidateList = dialogView.findViewById<LinearLayout>(R.id.settingsPluginCandidateList)
        val manager = pluginScriptManager

        lateinit var renderPluginList: () -> Unit

        fun fetchAndAddPluginScript(url: String) {
            val scheme = url.substringBefore("://", "").lowercase(Locale.US)
            if (url.isBlank() || (scheme != "http" && scheme != "https")) {
                Toast.makeText(this, "Enter a valid http(s) link", Toast.LENGTH_SHORT).show()
                return
            }
            scope.launch {
                val text: String? = try {
                    withContext(Dispatchers.IO) {
                        val request = Request.Builder().url(url).build()
                        OkHttpClient().newCall(request).execute().use { resp ->
                            if (resp.isSuccessful) resp.body?.string() else null
                        }
                    }
                } catch (e: Exception) {
                    null
                }
                if (text.isNullOrBlank()) {
                    Toast.makeText(this@MainActivity, "Couldn't fetch that script", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                when (val result = manager.installScript(text)) {
                    is PluginScriptManager.InstallResult.Installed -> {
                        Toast.makeText(this@MainActivity, "Added ${result.script.label}", Toast.LENGTH_SHORT).show()
                        renderPluginList()
                    }
                    is PluginScriptManager.InstallResult.Rejected ->
                        Toast.makeText(this@MainActivity, result.reason, Toast.LENGTH_LONG).show()
                }
            }
        }

        fun showAddPluginScriptFromUrlDialog() {
            val input = EditText(this).apply {
                hint = "https://example.com/my-plugin.js"
                inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine()
            }
            val pad = (20 * resources.displayMetrics.density).toInt()
            val container = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
            AlertDialog.Builder(this)
                .setTitle("Add plugin script from URL")
                .setMessage("Enter the link to a Lumora plugin script (.js).")
                .setView(container)
                .setPositiveButton("Add") { _, _ -> fetchAndAddPluginScript(input.text.toString().trim()) }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialogView.findViewById<View>(R.id.settingsPluginInstallUrl)?.setOnClickListener {
            showAddPluginScriptFromUrlDialog()
        }
        wirePluginStoresSection(dialogView, manager) { renderPluginList() }

        fun addCandidateRow(plugin: PluginScript, candidate: DiscoveredProvider) {
            val row = layoutInflater.inflate(R.layout.item_plugin_candidate_row, candidateList, false)
            val typeLabel = when (candidate.type) {
                "xtream" -> "Xtream"
                "stalker" -> "Stalker Portal"
                else -> "M3U"
            }
            row.findViewById<TextView>(R.id.candidateName).text = candidate.label
            row.findViewById<TextView>(R.id.candidateDetail).text =
                listOfNotNull("$typeLabel · ${candidate.url}", candidate.detail).joinToString("\n")
            // The plugin's own claim that it tested this, labelled as such - the host hasn't
            // verified anything at this point.
            row.findViewById<View>(R.id.candidateVerified).visibility =
                if (candidate.verified) View.VISIBLE else View.GONE
            val addButton = row.findViewById<View>(R.id.candidateAddButton)
            val addLabel = row.findViewById<TextView>(R.id.candidateAddLabel)
            addButton.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Add ${candidate.label}?")
                    .setMessage(
                        "${plugin.label} found this $typeLabel provider:\n\n${candidate.url}\n\n" +
                            "Adding it saves those details as a provider in Lumora."
                    )
                    .setPositiveButton("Add") { _, _ ->
                        IptvProviderStore.upsert(
                            prefs,
                            IptvProviderConfig(
                                id = IptvProviderStore.newId(),
                                type = candidate.type,
                                name = candidate.label,
                                enabled = true,
                                url = candidate.url,
                                username = candidate.username,
                                password = candidate.password,
                                // Stalker's MAC and M3U's custom UA share this slot everywhere
                                // else in the app (see loadAllConfiguredProviders).
                                userAgent = candidate.userAgent
                            )
                        )
                        addLabel.text = "Added"
                        addButton.isEnabled = false
                        addButton.isFocusable = false
                        // Rebuild the provider list in the same settings screen so the newly
                        // added provider shows up immediately instead of only after reopening.
                        refreshIptvProviderList?.invoke()
                        loadAllConfiguredProviders(forceRefresh = true)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            candidateList.addView(row)
        }

        fun runDiscovery(plugin: PluginScript) {
            pluginDiscoveryJob?.cancel()
            candidateList.removeAllViews()
            runSection.visibility = View.VISIBLE
            progress.visibility = View.VISIBLE
            status.text = "Starting ${plugin.label}…"
            // The Results section can be a full screen below the fold once the plugin/store
            // lists above it grow - without this, "it's running" was only visible on the row's
            // own button, easy to miss if the user then loses focus/scroll position.
            runSection.post {
                runSection.requestRectangleOnScreen(android.graphics.Rect(0, 0, runSection.width, runSection.height), false)
            }
            renderPluginList()
            pluginDiscoveryJob = scope.launch {
                val source = manager.readSource(plugin)
                val result = jsPluginEngine.runDiscovery(
                    source,
                    onProgress = { status.text = it },
                    onCandidate = { addCandidateRow(plugin, it) }
                )
                progress.visibility = View.GONE
                val found = candidateList.childCount
                status.text = when (result) {
                    is DiscoveryResult.Finished ->
                        result.message ?: if (found == 0) "Nothing found" else "Found $found"
                    is DiscoveryResult.Failed -> result.message
                }
                pluginDiscoveryJob = null
                renderPluginList()
            }
        }

        renderPluginList = {
            scope.launch {
                val plugins = manager.discoverScripts()
                listContainer.removeAllViews()
                listEmpty.visibility = if (plugins.isEmpty()) View.VISIBLE else View.GONE
                val running = pluginDiscoveryJob?.isActive == true
                for (plugin in plugins) {
                    val row = layoutInflater.inflate(R.layout.item_plugin_row, listContainer, false)
                    row.findViewById<CheckBox>(R.id.pluginEnabled).apply {
                        setOnCheckedChangeListener(null)
                        isChecked = plugin.enabled
                        setOnCheckedChangeListener { _, checked ->
                            val wasEnabled = plugin.enabled
                            manager.setEnabled(plugin.id, checked)
                            renderPluginList()
                            // Toggling a stream_search plugin affects the anime catalog —
                            // refresh so channels appear/disappear without a manual reload.
                            if (plugin.supportsStreamSearch && wasEnabled != checked) {
                                loadAllConfiguredProviders(forceRefresh = true)
                            }
                        }
                    }
                    row.findViewById<TextView>(R.id.pluginName).text = plugin.label
                    row.findViewById<TextView>(R.id.pluginDetail).text = plugin.description.orEmpty()
                    val runButton = row.findViewById<View>(R.id.pluginRunButton)
                    val runLabel = row.findViewById<TextView>(R.id.pluginRunLabel)
                    // The "Run" button only applies to discovery plugins, which the user kicks off
                    // from here. Stream-search plugins are driven from a title's "Find stream" instead,
                    // so they get enable/disable only - no Run button.
                    if (plugin.supportsDiscovery) {
                        runButton.visibility = View.VISIBLE
                        // One run at a time: the results list below is shared, and two plugins
                        // reporting into it at once would be unattributable.
                        val busy = running
                        runLabel.text = if (busy) "Running…" else "Run"
                        runButton.isEnabled = plugin.enabled && !busy
                        runButton.alpha = if (runButton.isEnabled) 1f else 0.4f
                        runButton.setOnClickListener { runDiscovery(plugin) }
                    } else {
                        runButton.visibility = View.GONE
                        runButton.setOnClickListener(null)
                    }
                    row.findViewById<View>(R.id.pluginRemoveButton).setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Remove ${plugin.label}?")
                            .setMessage("This deletes the installed script. You can reinstall it later from a plugin store or its URL.")
                            .setPositiveButton("Remove") { _, _ ->
                                manager.setEnabled(plugin.id, false)
                                manager.removeUserScript(plugin.fileName)
                                renderPluginList()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    listContainer.addView(row)
                }
            }
            Unit
        }
        renderPluginList()
    }

    /**
     * Settings > Plugins > Plugin Stores. A store is a small JSON catalog listing scripts a user
     * can install with one tap - see [PluginStoreManager]'s kdoc for the schema. The default
     * store (Lumora's own plugin repo) is always present; users can add more (a community repo,
     * their own fork, ...) and remove any they added. [onInstalled] refreshes the plain
     * installed-plugin list above once something new lands.
     */
    private fun wirePluginStoresSection(dialogView: View, manager: PluginScriptManager, onInstalled: () -> Unit) {
        val listContainer = dialogView.findViewById<LinearLayout>(R.id.settingsPluginStoreList)
        val listEmpty = dialogView.findViewById<View>(R.id.settingsPluginStoreListEmpty)

        fun installFromStore(storeScript: StoreScript, onDone: (PluginScriptManager.InstallResult) -> Unit) {
            scope.launch {
                val text = pluginStoreManager.fetchScriptText(storeScript.fileUrl)
                if (text.isNullOrBlank()) {
                    onDone(PluginScriptManager.InstallResult.Rejected("Couldn't download that script"))
                    return@launch
                }
                onDone(manager.installScript(text))
            }
        }

        fun showBrowseStoreDialog(store: PluginStore) {
            val status = TextView(this).apply {
                text = "Loading…"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            }
            val resultsHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val pad = (16 * resources.displayMetrics.density).toInt()
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
                addView(status)
                addView(resultsHost)
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle(store.name ?: store.url)
                .setView(ScrollView(this).apply { addView(container) })
                .setNegativeButton("Close", null)
                .create()
            dialog.show()

            scope.launch {
                val installedIds = manager.discoverScripts().map { it.id }.toSet()
                val result = pluginStoreManager.fetchCatalog(store.url)
                val catalog = result.getOrNull()
                if (catalog == null) {
                    status.text = "Couldn't load this store"
                    return@launch
                }
                if (catalog.isEmpty()) {
                    status.text = "No scripts listed"
                    return@launch
                }
                status.text = "${catalog.size} script${if (catalog.size == 1) "" else "s"}"
                for (storeScript in catalog) {
                    val row = layoutInflater.inflate(R.layout.item_plugin_candidate_row, resultsHost, false)
                    row.findViewById<TextView>(R.id.candidateName).text = storeScript.label
                    row.findViewById<TextView>(R.id.candidateDetail).text = listOfNotNull(
                        storeScript.capabilities.joinToString(", ").takeIf { it.isNotBlank() },
                        storeScript.description
                    ).joinToString("\n")
                    row.findViewById<View>(R.id.candidateVerified).visibility = View.GONE
                    val installButton = row.findViewById<View>(R.id.candidateAddButton)
                    val installLabel = row.findViewById<TextView>(R.id.candidateAddLabel)
                    // Already installed doesn't mean "nothing to do" - re-installing overwrites
                    // in place (see PluginScriptManager.installScript), which is exactly how you
                    // pick up a store update. Stays clickable either way, just relabeled.
                    val alreadyInstalled = storeScript.id in installedIds
                    val idleLabel = if (alreadyInstalled) "Update" else "Install"
                    installLabel.text = idleLabel
                    installButton.setOnClickListener {
                        installButton.isEnabled = false
                        installLabel.text = if (alreadyInstalled) "Updating…" else "Installing…"
                        installFromStore(storeScript) { outcome ->
                            when (outcome) {
                                is PluginScriptManager.InstallResult.Installed -> {
                                    installLabel.text = if (alreadyInstalled) "Updated" else "Installed"
                                    installButton.isEnabled = true
                                    onInstalled()
                                }
                                is PluginScriptManager.InstallResult.Rejected -> {
                                    installLabel.text = idleLabel
                                    installButton.isEnabled = true
                                    Toast.makeText(this@MainActivity, outcome.reason, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    resultsHost.addView(row)
                }
            }
        }

        lateinit var renderStoreList: () -> Unit

        fun showAddStoreDialog() {
            val input = EditText(this).apply {
                hint = "https://example.com/plugins/index.json"
                inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine()
            }
            val pad = (20 * resources.displayMetrics.density).toInt()
            val container = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
            AlertDialog.Builder(this)
                .setTitle("Add plugin store")
                .setMessage("Enter the link to a plugin store's catalog (a small JSON file listing its scripts).")
                .setView(container)
                .setPositiveButton("Add") { _, _ ->
                    val url = input.text.toString().trim()
                    val scheme = url.substringBefore("://", "").lowercase(Locale.US)
                    if (url.isBlank() || (scheme != "http" && scheme != "https")) {
                        Toast.makeText(this, "Enter a valid http(s) link", Toast.LENGTH_SHORT).show()
                    } else {
                        pluginStoreManager.addStore(url)
                        renderStoreList()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        renderStoreList = {
            listContainer.removeAllViews()
            val stores = pluginStoreManager.storeUrls()
            listEmpty.visibility = if (stores.isEmpty()) View.VISIBLE else View.GONE
            for (store in stores) {
                val row = layoutInflater.inflate(R.layout.item_plugin_store_row, listContainer, false)
                row.findViewById<TextView>(R.id.storeName).text = store.name ?: store.url
                row.findViewById<TextView>(R.id.storeUrl).text = store.url
                row.findViewById<View>(R.id.storeBrowseButton).setOnClickListener { showBrowseStoreDialog(store) }
                val removeButton = row.findViewById<View>(R.id.storeRemoveButton)
                if (store.removable) {
                    removeButton.visibility = View.VISIBLE
                    removeButton.setOnClickListener {
                        pluginStoreManager.removeStore(store.url)
                        renderStoreList()
                    }
                } else {
                    removeButton.visibility = View.GONE
                }
                listContainer.addView(row)
                // Fetch the store's self-declared name in the background and fill it in once
                // known - showing the URL immediately means the row isn't empty while loading.
                if (store.name == null) {
                    scope.launch {
                        pluginStoreManager.fetchStoreName(store.url)?.let { name ->
                            row.findViewById<TextView>(R.id.storeName).text = name
                        }
                    }
                }
            }
        }
        dialogView.findViewById<View>(R.id.settingsPluginAddStore)?.setOnClickListener { showAddStoreDialog() }
        renderStoreList()
    }

    // ── Parental PIN ───────────────────────────────

    private fun hasParentalPin(): Boolean = !prefs.getString(PREF_PARENTAL_PIN, null).isNullOrBlank()

    /** 4-digit PIN entry. Calls onCorrect only if it matches the saved PIN. */
    private fun promptForPin(title: String, onCorrect: () -> Unit) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == prefs.getString(PREF_PARENTAL_PIN, null)) {
                    onCorrect()
                } else {
                    Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Sets (or changes) the 4-digit PIN - entered twice so a typo doesn't lock the user out. */
    private fun showSetPinDialog(label: TextView) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            hint = "4-digit PIN"
        }
        AlertDialog.Builder(this)
            .setTitle("Set parental PIN")
            .setView(input)
            .setPositiveButton("Next") { _, _ ->
                val pin = input.text.toString()
                if (pin.length != 4) {
                    Toast.makeText(this, "PIN must be 4 digits", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val confirm = EditText(this).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    filters = arrayOf(android.text.InputFilter.LengthFilter(4))
                    hint = "Confirm PIN"
                }
                AlertDialog.Builder(this)
                    .setTitle("Confirm PIN")
                    .setView(confirm)
                    .setPositiveButton("Save") { _, _ ->
                        if (confirm.text.toString() == pin) {
                            prefs.edit().putString(PREF_PARENTAL_PIN, pin).apply()
                            label.text = "Change parental PIN"
                            Toast.makeText(this, "Parental PIN set", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "PINs didn't match", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
