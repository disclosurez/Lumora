package com.lumora

import android.Manifest
import android.animation.AnimatorInflater
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
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
import android.graphics.Typeface
import android.view.PixelCopy
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
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
// Dub handling: prefer dub-flagged search results, and keep sideloaded subtitles on when a
// stream plays back with its dubbed audio track (both default off).
private const val PREF_PREFER_DUB_AUDIO = "prefer_dub_audio"
private const val PREF_SUBTITLES_WITH_DUB = "subtitles_with_dub"
// Sidecar subtitles are opt-in: off by default, and PlayerManager reads this to decide
// whether DEFAULT-flagged subtitle tracks auto-select on playback.
private const val PREF_SUBTITLES_ENABLED = "subtitles_enabled"
private const val PREF_PARENTAL_PIN = "parental_pin"
private const val PREF_ASPECT_MODE = "player_aspect_mode"
private const val PREF_CLASSIC_CATEGORY_LAYOUT = "classic_category_layout"
private const val PREF_SIMPLE_MODE = "simple_mode"
private const val PREF_DISABLE_VOD = "disable_vod"
// When the catalog was last fetched from the network; the cache serves every launch until
// this is CATALOG_TTL_MS old (a provider change force-refreshes regardless).
private const val PREF_CATALOG_REFRESHED_AT = "catalog_refreshed_at"
private const val CATALOG_TTL_MS = 24 * 60 * 60 * 1000L
/** Per-provider ceiling on a catalogue fetch. Deliberately far above what a healthy provider
 *  needs: this exists to stop a *dead* entry starving the providers queued behind it, not to
 *  discipline a slow one. A real portal measured here streams 67MB of live channels in 4s and
 *  then pages VOD and series 14 items at a time - two minutes was inside that envelope, and
 *  because a timeout fails the whole provider it threw away the 51,545 live channels it had
 *  already fetched along with the rest. An unreachable host is now identified in seconds by
 *  isRetryable()/hostUnreachable, so this only has to be an outer backstop. */
private const val PROVIDER_FETCH_TIMEOUT_MS = 360_000L
private const val SEARCH_BATCH_SIZE = 50

// Free-TV/IPTV: a community-maintained list of publicly available free-to-air streams.
// Used by the empty state's "Try the Demo" so the app can be exercised before any
// credentials exist. Nothing else references it - it is an ordinary M3U url handed to the
// ordinary M3U provider path, not a special-cased content source.
// Generic User-Agent for stream HTTP requests.
private const val STREAM_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
private const val FAVOURITES_CATEGORY_ID = "__favourites__"
/** Films/Series sidebar row pooling the tab's most recent releases by date - mirrors the
 *  "Newest" content shelf that already led the Films/Series poster. */
private const val NEWEST_CATEGORY_ID = "__newest__"
/** Series sidebar row listing in-progress series - mirrors the Home "Continue Watching"
 *  shelf, filtered to series entries. Renders its own grid because the episodes it carries
 *  are not seriesList members (a grid-filter on seriesList would come up empty). */
private const val CONTINUE_WATCHING_CATEGORY_ID = "__continue_watching__"
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

// Phone touch gestures on the player: double-tap seek step and pinch-zoom range.
private const val GESTURE_SEEK_MS = 10_000L
private const val ZOOM_MIN = 1.0f
private const val ZOOM_MAX = 3.0f

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
    private var refreshIptvProviderList: () -> Unit = {}
    private var activeSearchOverlay: FullScreenOverlay? = null

    // Live TV inline preview: a separate, muted player instance so browsing the
    // channel list doesn't touch the main PlayerManager used for fullscreen playback.
    private var previewPlayerManager: PlayerManager? = null
    private var previewChannelId: String? = null
    // The channel the user last committed to the preview pane (first OK press, or any
    // auto-load). A second OK on the same channel opens it fullscreen.
    private var previewTargetChannel: Channel? = null
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
    /** The Series sidebar's category rows, cached at derive time so refreshSeriesShelvesIfShowing()
     *  can rebuild the Series shelf list (favourites/newest/continue move after playback) without
     *  re-running the expensive buildCategoryRows() pass. */
    private var cachedSeriesCategoryRows: List<CategoryFilter> = emptyList()
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
    /** The plugin whose page is open in Settings > Plugins, or null on the list. Held here
     *  rather than on the views because the page is rebuilt from scratch on any change (enable,
     *  update, remove, a discovery run's progress), and it has to know what it is showing. */
    private var openPluginId: String? = null
    /** Returns from an open plugin page to the plugin list. Held so Back can go up one level
     *  inside Settings instead of closing the whole overlay from two screens deep. */
    private var closeOpenPluginPage: (() -> Unit)? = null
    /** The plugin whose run output the rows below belong to, and that output. Same reason as
     *  above: a re-render must be able to put the results back where they were, so they live
     *  outside the views. Cleared when a different plugin is run. */
    private var pluginDiscoveryPluginId: String? = null
    private var pluginDiscoveryStatus: String? = null
    private val pluginDiscoveryCandidates = mutableListOf<DiscoveredProvider>()
    /** Candidate URLs already added as providers, so a re-render keeps showing "Added" rather
     *  than offering to add the same one twice. */
    private val pluginDiscoveryAdded = mutableSetOf<String>()
    /** The views of the currently-running plugin's results block, so a progress line or a new
     *  candidate can be written straight into them. Re-rendering the whole pane per line would
     *  rebuild every row - and every row is focusable, so it would also move the user's focus
     *  mid-run. Null while nothing is running, or before the row exists. */
    private var liveDiscoveryStatusView: TextView? = null
    private var liveDiscoveryCandidateList: LinearLayout? = null
    private var liveDiscoveryPlugin: PluginScript? = null
    /** Which plugin's row, and which view inside it, should take focus once the plugin list is
     *  next rebuilt. Every interaction in that pane re-renders the whole list, which destroys
     *  the view the user was on - without this, ticking Enabled dropped focus out of the
     *  section entirely and there was no way to reach Run below it. */
    private var pluginFocusRequestId: String? = null
    private var pluginFocusRequestViewId: Int = View.NO_ID
    /** Opens a plugin's section in the Plugins pane and puts focus on it. Set by
     *  [wirePluginsPane] while the settings overlay is up, so the nav rail's plugin rows can
     *  drive the pane. Null when settings isn't open. */
    private var revealPluginInPane: ((String) -> Unit)? = null
    /** What the last setStatus() asked for, kept because whether it can actually be shown
     *  depends on screen state that changes after the fact - see applyStatus(). */
    private var statusText = ""
    private var statusWanted = false
    /** Whether the nav rail's Plugins row is showing its installed-plugin children. */
    private var navPluginsExpanded = false
    /** Rebuilds those child rows - the pane calls it after anything that changes a plugin's
     *  enabled state or removes one, so the rail doesn't go stale behind it. */
    private var refreshPluginNavRows: (() -> Unit)? = null
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
    // Live TV is the landing screen: this is a TV app first, and Home's shelves are only
    // meaningful once there's watch history to fill them. The first render after a catalog
    // load routes on this flag (see the tail of classifyAndShow).
    private var showingHome = false
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
    /** Set right before an auto-advanced episode starts so its STATE_READY does not throw a
     *  "Resume playback?" dialog at the top of a brand-new episode; consumed and cleared in
     *  maybeShowResumePrompt, and cleared again by every user-initiated play entry point so a
     *  stale value (playback errored before STATE_READY) never suppresses a real prompt. */
    private var skipResumePrompt = false
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
    // One-shot fresh-URL retry guard for Jellyfin direct-play: a transient server timeout or
    // expired direct-play URL gets one re-resolve before the generic "Playback error".
    private var jellyfinRetryAttempted = false
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
    private var upNextCountdown = UP_NEXT_COUNTDOWN_SECONDS
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
    /** First-paint flag for the progressive render path (paint Live ASAP once, then surgical
     *  partial re-renders) - see renderLivePartial(). */
    private var uiPainted: Boolean = false
    /** The in-flight films/series derive launched by deriveFilmsSeries(), if any - cancelled
     *  on a new provider load and joined before tab switches that need it. */
    private var filmsSeriesDeriveJob: Job? = null
    /** The in-flight surgical live re-render launched by renderLivePartial(), if any -
     *  coalesces the near-simultaneous provider-completion re-renders into one pass. */
    private var liveRenderJob: Job? = null

    companion object {
        private const val REQUEST_EXPORT_BACKUP = 2001
        private const val REQUEST_IMPORT_BACKUP = 2002
        private const val EDGE_SWIPE_ZONE_DP = 24f
        private const val EDGE_SWIPE_THRESHOLD_DP = 64f
        private const val UP_NEXT_COUNTDOWN_SECONDS = 30
    }

    private val liveAdapter = LiveGuideAdapter(
        onChannelClick = { channel -> onChannelOkPress(channel) },
        onChannelFocused = { channel -> lastFocusedLiveChannel = channel },
        onChannelLongPress = { channel -> toggleFavoriteChannel(channel) },
        onChannelFavClick = { channel -> toggleFavoriteChannel(channel) },
        isChannelFavourite = { id -> FavoritesStore.getFavoriteChannelIds(this).contains(id) },
        onProgramLongPress = { channel, program -> toggleProgramReminder(channel, program) },
        isReminderSet = { key -> ReminderStore.get(this, key) != null },
        fetchPrograms = { channelId -> resolveEpgPrograms(channelId) }
    )
    private val seriesShelfAdapter = ShelfAdapter(
        // onHomeItemClick, not playItem: the Continue Watching shelf row holds EPISODES,
        // and playItem's SERIES branch would open the episode itself as a dead detail page.
        // onHomeItemClick resolves an episode to its series (with direct-play fallback).
        onItemClick = { item -> onHomeItemClick(item) },
        onItemLongClick = { item -> toggleFavoriteVodItem(item) },
        onPinClick = { shelf -> togglePinShelfCategory(1, shelf) },
        onHideClick = { shelf -> if (shelf.title == "Continue Watching") clearContinueWatching() else toggleHiddenShelfCategory(1, shelf) },
        onSeeAllClick = { shelf -> showSeeAll(shelf) }
    )
    private val filmsShelfAdapter = ShelfAdapter(
        onItemClick = { item -> playItem(item) },
        onItemLongClick = { item -> toggleFavoriteVodItem(item) },
        onPinClick = { shelf -> togglePinShelfCategory(2, shelf) },
        onHideClick = { shelf -> if (shelf.title == "Continue Watching") clearContinueWatching() else toggleHiddenShelfCategory(2, shelf) },
        onSeeAllClick = { shelf -> showSeeAll(shelf) }
    )
    private val homeShelfAdapter = ShelfAdapter(
        onItemClick = { item -> onHomeItemClick(item) },
        onItemLongClick = { item -> toggleFavoriteVodItem(item) },
        onHideClick = { shelf -> if (shelf.title == "Continue Watching") clearContinueWatching() else toggleHiddenHomeShelf(shelf.title) },
        showPinButton = false
    )
    // Single-category selection swaps to these - a vertical, scrollable grid instead of
    // the shelves' horizontal strip, since one category's whole catalog doesn't fit a
    // single row.
    private val seriesGridAdapter = com.lumora.adapter.PosterGridAdapter(
        onItemLongClick = { item -> toggleFavoriteVodItem(item) }
    ) { item -> onHomeItemClick(item) }
    private val filmsGridAdapter = com.lumora.adapter.PosterGridAdapter(
        onItemLongClick = { item -> toggleFavoriteVodItem(item) }
    ) { item -> playItem(item) }
    private val tmdbClient = com.lumora.data.remote.tmdb.TmdbClient()
    private val discoverGridAdapter = com.lumora.adapter.PosterGridAdapter { item -> onDiscoverItemClick(item) }
    private var discoverSearchJob: Job? = null
    private var providerLoadJob: Job? = null
    private val categoryAdapter = CategoryAdapter(
        onCategoryClick = { category -> onCategorySelected(category) },
        onCategoryStarClick = { category -> togglePinCategory(category) },
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
    // Phone touch gestures on the player. TV sends no touch events, so these are inert there -
    // D-pad/remote KEYCODE handling is untouched. Single tap toggles play/pause and flips the
    // controls overlay; double-tap seeks ±10s by screen half; pinch zooms the surface 1-3x.
    //
    // Built in setupPlayerControls(), NOT as field initializers: GestureDetector's constructor
    // calls context.getResources(), and an Activity's base Context is still null during <init> -
    // constructing one as a field initializer crashed every launch with a NullPointerException.
    private lateinit var gestureDetector: GestureDetector
    private lateinit var scaleDetector: ScaleGestureDetector
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
        loadDeadStreams()
        // Shown immediately rather than waiting for loadSavedProvider(): that call sits behind
        // pluginDiscoveryOnStart.join() below, which is real async work (runs the JS engine over
        // every installed plugin's manifest header) - without this the screen was blank for that
        // whole stretch, then jumped straight to content with no loading state ever having been
        // visible, which read as the app hanging rather than working.
        //
        // contentRow has no android:visibility in the layout, so it inflates VISIBLE - applyStatus()
        // reads that as "a pane already owns the screen" and refuses to show the status row at
        // all until something else explicitly hides it first.
        binding.contentRow.visibility = View.GONE
        setStatus("Loading...", visible = true)
        // Serve the cached catalog without waiting for plugin discovery: the JS-engine
        // scan of every installed script's manifest (discoverScripts) is real async work
        // that used to gate loadSavedProvider() entirely, so a warm cache still spent
        // seconds on "Loading..." before it could render. Discovery only matters for the
        // plugin-only gate at the top of loadAllConfiguredProviders and the anime-cache
        // re-check - both handled by the follow-up below.
        scope.launch {
            // A configured provider means the gate passes regardless of discovery, so the
            // cache can render immediately. Plugin-only setups must wait for discovery's
            // result or the gate would wrongly bounce them to "Add a Provider".
            if (hasProviderConfigured()) loadSavedProvider()
            else { pluginDiscoveryOnStart.join(); loadSavedProvider() }
        }
        requestNotificationPermissionIfNeeded()
        checkAndPromptUpdate()

        // Downloads are a mobile-only affordance - a TV box has nowhere meaningful to
        // browse a downloaded file, and it's not what "download for offline" means there.
        if (!isTv) {
            binding.tabDownloads.visibility = View.VISIBLE
            val filter = android.content.IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            ContextCompat.registerReceiver(this, downloadCompleteReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            // The XML D-pad chain routes Discover -> Downloads -> Live, but Downloads stays
            // View.GONE on TV - an explicit nextFocus target that's GONE just eats the key
            // press instead of falling through. Re-route around the hidden Downloads tab so
            // the ring closes Discover -> Live directly.
            binding.tabDiscover.nextFocusRightId = R.id.tabLive
            binding.tabLive.nextFocusLeftId = R.id.tabDiscover
        }

        onBackPressedDispatcher.addCallback(this, backCallback)
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

    /** Registered in onCreate. Everything back-related goes through the dispatcher rather
     *  than `onBackPressed()`: at targetSdk 36 the platform drives back through
     *  OnBackInvokedCallback and never calls the legacy override, so on a phone the system
     *  gesture bypassed all of the navigation below and closed the Activity outright. TV
     *  remotes still went through the old path, which is why it only misbehaved on phones. */
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (handleBackNavigation()) return
            // Nothing left to unwind - hand this press back to the system (finishing the
            // Activity, or running the predictive-back animation) by standing down for the
            // duration of that one dispatch.
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            isEnabled = true
        }
    }

    /** Unwinds one level of navigation. Returns false when there's nothing left above the
     *  current screen, i.e. Back should leave the app. */
    private fun handleBackNavigation(): Boolean {
        // A plugin's page is a level inside Settings, not a screen of its own - Back goes up to
        // the plugin list first rather than dropping the user out of Settings entirely.
        if (activeSettingsOverlay != null && openPluginId != null) closeOpenPluginPage?.invoke()
        else if (activeSettingsOverlay != null) activeSettingsOverlay?.dismiss()
        else if (activeSearchOverlay != null) activeSearchOverlay?.dismiss()
        else if (isPlayerVisible) hidePlayer()
        else if (isContentDetailVisible) hideContentDetail()
        // Back walks back up the way the user came in rather than dropping straight out of
        // the app. Inside a section (Live/Series/Films/Discover/Downloads) the first press
        // goes to the top of that section - a Films/Series category grid up to that tab's
        // shelves, otherwise the first category with both lists scrolled back to the top -
        // and only once already at the top does the next press go Home. Back on Home itself
        // exits. Leaving the app was previously one press from anywhere, which on a remote
        // is very easy to do by accident.
        else if (showingHome) return false
        else if (!isAtSectionTop()) goToSectionTop()
        // Simple mode has no Home level above the section - Live TV at its top IS the
        // top, so Back leaves the app from there instead of bouncing into a hidden Home.
        else if (isSimpleMode()) return false
        else goHomeFromBack()
        return true
    }

    /** The list filling the content area of whatever section is on screen. */
    private fun activeContentList(): RecyclerView = when {
        showingDiscover -> binding.discoverGrid
        showingDownloads -> binding.downloadsContent
        activeTab == 1 -> binding.seriesContent
        activeTab == 2 -> binding.filmsContent
        else -> binding.liveContent
    }

    private fun isListAtTop(list: RecyclerView): Boolean {
        // GridLayoutManager is a LinearLayoutManager, so this covers the poster grids too.
        val lm = list.layoutManager as? LinearLayoutManager ?: return true
        return lm.findFirstCompletelyVisibleItemPosition() <= 0
    }

    /** "Top of the section": nothing drilled into, both the sidebar and the content list
     *  scrolled to their first row. Anything else means there's somewhere above the user to
     *  go before leaving for Home. */
    private fun isAtSectionTop(): Boolean {
        if (isTabDrilledIn()) return false
        if (!isListAtTop(activeContentList())) return false
        if (showingDiscover || showingDownloads) return true
        if (!isListAtTop(binding.categorySidebar)) return false
        // No "first row selected" requirement on purpose: on Live TV the first sidebar row
        // is the classic-layout control, not a category - walking the selection up to it
        // flipped the layout on every Back and never satisfied the check, so Back got stuck
        // at the top of a category. The auto-selected row already IS this section's top, and
        // Films/Series at their shelves have nothing selected at all.
        return true
    }

    private fun goToSectionTop() {
        if (isTabDrilledIn()) {
            resetTabToShelves()
            return
        }
        val content = activeContentList()
        content.scrollToPosition(0)
        if (showingDiscover || showingDownloads) {
            focusFirstItemWhenReady(content)
            return
        }
        binding.categorySidebar.scrollToPosition(0)
        focusFirstItemWhenReady(binding.categorySidebar)
    }

    /** True when a Films/Series tab is showing one category's (or one See All row's) grid
     *  rather than its shelves. Live TV is excluded on purpose - it always has a row
     *  selected (see selectTab), so there's no shelf level there to go back up to. */
    private fun isTabDrilledIn(): Boolean =
        !showingHome && !showingDiscover && !showingDownloads && activeTab != 0 &&
            (selectedShelfItems != null || selectedRowId != null ||
                selectedCategoryIds != null || selectedBrandChannelIds != null)

    /** Clears the current category selection, putting the tab back on its shelf list - the
     *  same state selectTab() leaves Films/Series in. */
    private fun resetTabToShelves() {
        selectedShelfItems = null
        selectedRowId = null
        selectedCategoryIds = null
        selectedBrandChannelIds = null
        selectedCategoryLabel = null
        categoryAdapter.setSelected(null)
        scope.launch {
            applyCategoryFilter()
            // The grid holding focus has just been swapped for the shelf list, and a focused
            // view disappearing leaves nothing focused at all - the D-pad would stop
            // responding until something else claimed focus.
            focusFirstItemWhenReady(if (activeTab == 1) binding.seriesContent else binding.filmsContent)
        }
    }

    private fun goHomeFromBack() {
        selectHome()
        // Same focus-handoff reason as above: whatever was focused belonged to the tab that
        // just went GONE. The Home tab button is always present and is where a user landing
        // on Home by pressing the tab would be anyway.
        binding.tabHome.post {
            if (!binding.tabHome.requestFocus()) binding.homeContent.requestFocus()
        }
    }

    /** Focuses a list's first row once it has been laid out - a single requestFocus() right
     *  after submitList() lands before the new items exist and silently no-ops. */
    private fun focusFirstItemWhenReady(list: RecyclerView) {
        fun attempt(): Boolean =
            list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() == true
        list.post { if (!attempt()) list.post { attempt() } }
    }

    /** True while Back has somewhere to go - guards edge-swipe so a stray swipe can't exit
     *  the app. Anything but Home qualifies now that Back unwinds tabs too (see
     *  handleBackNavigation). */
    private fun hasDismissibleScreen(): Boolean =
        activeSettingsOverlay != null || activeSearchOverlay != null || isPlayerVisible ||
            isContentDetailVisible || !showingHome

    /** Phone-only edge-swipe-to-back: a left-to-right swipe starting within the leftmost
     *  [EDGE_SWIPE_ZONE_DP] of the screen closes whatever's on top, mirroring the system
     *  gesture-nav back swipe. Started from the edge (not anywhere on screen) specifically
     *  so it can't be triggered by scrolling a shelf/episode row, which are horizontal
     *  RecyclerViews spanning the full width and would otherwise fire this constantly.
     *  Observes via dispatchTouchEvent rather than consuming, so normal clicks/scrolls are
     *  untouched - it never returns true from here, just dispatches Back as a side effect. */
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
                            onBackPressedDispatcher.onBackPressed()
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
        applySimpleModeUi()
    }

    /** Simple mode hides the whole tab bar - the only tab it would hold is Live TV, whose
     *  row the bar exists to keep, so the EPG/live content shifts up into the freed space.
     *  The toolbar above it stays, so Settings remains reachable. */
    private fun isSimpleMode(): Boolean = prefs.getBoolean(PREF_SIMPLE_MODE, false)

    /** VOD is dropped at fetch time; the manual toggle and simple mode both turn it off,
     *  and simple mode never writes the manual pref so turning it off re-enables VOD. */
    private fun isVodDisabled(): Boolean = isSimpleMode() || prefs.getBoolean(PREF_DISABLE_VOD, false)

    private fun applySimpleModeUi() {
        val simple = isSimpleMode()
        // Chrome up = something to browse, so the tab bar would be showing in normal mode.
        // Simple mode hides it regardless; the flag still gates the forced tab switch below
        // (with no providers the empty state owns the screen and selectTab would fight it).
        val chromeUp = hasProviderEnabled() || enabledStreamSearchPlugin() != null
        if (simple) {
            binding.tabBar.visibility = View.GONE
            // The toolbar's Search chain points left into the hidden bar - a LEFT press
            // there would target a GONE tab and eat the key. Re-route onto itself.
            binding.btnSearch.nextFocusLeftId = R.id.btnSearch
            // Home/Discover/Downloads (and any Series/Movies drill) aren't reachable in
            // simple mode - land back on Live TV instead of leaving a hidden pane on screen.
            if (chromeUp && (showingHome || showingDiscover || showingDownloads || activeTab != 0)) {
                selectTab(0)
            }
        } else {
            binding.tabBar.visibility = if (chromeUp) View.VISIBLE else View.GONE
            binding.btnSearch.nextFocusLeftId = R.id.tabFilms
        }
    }

    /** Re-runs the provider load so the VOD gate takes effect - VOD is skipped at fetch
     *  time (and filtered out of a cached cold start), so either toggle needs a reload. */
    private fun vodStateChanged() {
        if (hasProviderConfigured()) scope.launch { loadAllConfiguredProviders(forceRefresh = true) }
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
        //
        // Waited on, not just cancelled: cancel() only sets a flag and returns, so the outgoing
        // load carried on fetching while the new one started. Against one Stalker portal that
        // meant several handshakes and two 70MB catalogue streams in flight at once - enough on
        // its own to trip the portal's rate limit (every call after the handshake came back
        // "Connection reset") and to double the peak memory of the load.
        val previousLoad = providerLoadJob
        providerLoadJob = scope.launch {
            previousLoad?.cancelAndJoin()
            filmsSeriesDeriveJob?.cancel()
            // The cached catalog is authoritative until it goes stale: re-fetching every
            // launch means several seconds of "Loading..." and, on a large catalog, real
            // work for a result that is almost always identical. Providers change rarely,
            // so the network is only worth hitting once every CATALOG_TTL_MS - or right
            // away when the user changes a provider, which force-refreshes.
            var renderedStaleCache = false
            var cached: List<Channel>? = null
            if (!forceRefresh) {
                cached = withContext(Dispatchers.IO) { ChannelCache.load(this@MainActivity) }
                if (!cached.isNullOrEmpty()) {
                    // The VOD gate applies to a cached cold start too - the cache is saved
                    // unfiltered, so a cache written with VOD on would resurrect it here.
                    if (isVodDisabled()) cached = cached.filter { it.mediaType == MediaType.LIVE }
                    // Paint the cached catalog immediately (Live first, films/series in background),
                    // then only hit the network when the cache is stale - a non-stale cache returns
                    // here; a stale one falls through and refreshes silently under the content.
                    allChannels = cached
                    classifyAndShowLiveFirst()
                    uiPainted = true
                    deriveFilmsSeries()
                    setStatus("", visible = false)
                    if (!isCatalogStale()) return@launch
                    renderedStaleCache = true
                }
            }

            val combined = mutableListOf<Channel>()
            val errors = mutableListOf<String>()
            var expiryText: String? = null

            val enabledConfigs = IptvProviderStore.load(prefs).filter { it.enabled }
            if (!uiPainted && enabledConfigs.isNotEmpty()) {
                setStatus(
                    if (enabledConfigs.size == 1) "Connecting to ${enabledConfigs.first().name}..."
                    else "Connecting to ${enabledConfigs.size} providers...",
                    visible = true
                )
            }
            val animeDeferred = if (enabledStreamSearchPlugin() != null) {
                // fetchAnimeChannels does synchronous OkHttp calls, and this loader coroutine
                // runs on Main - the Dispatchers.IO hop mirrors the sequential version below.
                async { withContext(Dispatchers.IO) { fetchAnimeChannels() } }
            } else null
            // Fetched concurrently, not one after another - they used to run sequentially, so
            // a single dead/slow provider (up to PROVIDER_FETCH_TIMEOUT_MS - a Stalker portal
            // alone walks up to 200 live pages plus 50 each of VOD and series, each with its own
            // retries and backoff) held up every provider after it in the list. A routine
            // remove/toggle that left one stale provider behind therefore read as the whole app
            // freezing for minutes. Each is still individually bounded by the same timeout and
            // reported as failed on its own if it can't answer in time.
            val fetchResults = enabledConfigs.map { config ->
                async {
                    val result = withTimeoutOrNull(PROVIDER_FETCH_TIMEOUT_MS) {
                        when (config.type) {
                            "xtream" -> fetchXtreamChannels(config) { expiryText = it }
                            "stalker" -> fetchStalkerChannels(config) { live ->
                                mergeProviderPartial(config.id, live)
                                renderLivePartial()
                            }
                            else -> fetchM3uChannels(config)
                        }
                    } ?: FetchResult.Failure("timed out")
                    if (result is FetchResult.Success) {
                        mergeProviderPartial(config.id, result.channels)
                        renderLivePartial()
                    }
                    config to result
                }
            }.awaitAll()
            // Enabled ids are re-read here rather than reusing the pre-loop snapshot: toggling
            // a provider off mid-refresh drops its id from this set, so the channels it just
            // fetched can't slip back into the catalog. Items with no sourceProviderId
            // (Jellyfin/anime) always pass.
            val enabledProviderIds = IptvProviderStore.load(prefs).filter { it.enabled }.map { it.id }.toSet()
            for ((config, result) in fetchResults) {
                when (result) {
                    is FetchResult.Success ->
                        combined += result.channels.filter { it.sourceProviderId == null || it.sourceProviderId in enabledProviderIds }
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
            val animeChannels = animeDeferred?.await()
            if (!animeChannels.isNullOrEmpty()) {
                combined += animeChannels
            }

            // A stale-cache refresh that failed completely must not wipe the cached catalog off
            // the screen: keep the cached allChannels, surface the errors, and don't stamp the
            // TTL (a stamp here would leave the app on stale data for the whole TTL window).
            if (combined.isEmpty() && renderedStaleCache) {
                // The progressive partial merges above already mutated allChannels to a
                // partial-only catalog - revert to the full cached list before bailing out.
                if (cached != null) allChannels = cached
                setStatus("", visible = false)
                if (errors.isNotEmpty()) {
                    Toast.makeText(this@MainActivity, errors.joinToString(" · "), Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            allChannels = combined
            filmsSeriesDeriveJob?.cancel()
            classifyAndShow(preserveUi = uiPainted)
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
                // A refresh over existing content can't use the status row (suppressed while a
                // pane owns the slot), so the outcome would otherwise be silent - and a failed
                // provider is exactly what the user needs told.
                if (binding.statusRow.visibility != View.VISIBLE) {
                    Toast.makeText(this@MainActivity, summary, Toast.LENGTH_LONG).show()
                }
                if (errors.isEmpty()) mainHandler.postDelayed({ setStatus("", visible = false) }, 4000)
            }
        }
    }

    /** [onLive] fires with the live channels alone as soon as they land, before VOD/series are
     *  even requested - a portal with tens of thousands of live channels plus a large VOD/series
     *  library used to hold all three in memory at once before anything was shown, which is
     *  what ran a low-RAM box out of heap (lowmemorykiller killing the process) and read as the
     *  whole app freezing. Splitting the fetch also gets Live TV on screen while VOD/series -
     *  the slower, bulkier part - are still loading. */
    private suspend fun fetchStalkerChannels(config: IptvProviderConfig, onLive: suspend (List<Channel>) -> Unit): FetchResult {
        return try {
            val mac = config.userAgent ?: return FetchResult.Failure("no MAC address")
            val stalkerProvider = Provider(
                name = config.name, type = ProviderType.M3U,
                serverUrl = config.url?.let { normalizeServerUrl(it) }, userAgent = mac
            )
            val stalker = StalkerProvider(BaseApplication.instance.okHttpClient)
            // sourceProviderId ties each item back to this portal config, so the play step
            // can re-auth against the right one to resolve a Stalker VOD create_link.
            fun tag(channels: List<Channel>) = channels.map { it.copy(streamUserAgent = mac, sourceProviderId = config.id) }

            val liveResult = withContext(Dispatchers.IO) { stalker.loadLiveChannels(stalkerProvider) }
            if (liveResult.isFailure) return FetchResult.Failure(liveResult.exceptionOrNull()?.message?.take(60) ?: "error")
            val live = tag(liveResult.getOrThrow())
            onLive(live)

            // VOD gate: a live-only portal never touches the (slow) VOD/series fetch.
            if (isVodDisabled()) return FetchResult.Success(live)

            val vodSeriesResult = withContext(Dispatchers.IO) { stalker.loadVodAndSeries(stalkerProvider) }
            if (vodSeriesResult.isFailure) return FetchResult.Failure(vodSeriesResult.exceptionOrNull()?.message?.take(60) ?: "error")
            val (films, series) = vodSeriesResult.getOrThrow()
            FetchResult.Success(live + tag(films) + tag(series))
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
                // VOD gate: only live TV is fetched - no movies/series crawl, no user-state
                // import to seed their resume rows.
                if (isVodDisabled()) {
                    liveItems
                } else {
                    val movies = jellyfin.getMovies()
                    val series = jellyfin.getSeries()
                    importJellyfinUserState(movies + series)
                    liveItems +
                        movies.map { JellyfinProvider.toChannel(it, stub) } +
                        series.map { JellyfinProvider.toChannel(it, stub) }
                }
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
            // VOD gate: an M3U file lists live and VOD in one parse - drop the VOD entries.
            val channels = result.channels
                .let { list -> if (isVodDisabled()) list.filter { it.mediaType == MediaType.LIVE } else list }
            // sourceProviderId isn't needed for playback here (an M3U item's url is already
            // final), but it's what names the provider a duplicate came from on the detail
            // screen's version chips - without it every M3U copy is an anonymous "Version N".
            FetchResult.Success(channels.map { it.copy(streamUserAgent = config.userAgent, sourceProviderId = config.id) })
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
                // VOD gate: the (slow) VOD/series category+stream fetches are skipped entirely.
                val vodDisabled = isVodDisabled()
                val liveCatsDeferred = async { runCatching { client.getLiveCategories(xtreamProvider) }.getOrDefault(emptyList()) }
                val vodCatsDeferred: Deferred<List<Pair<String, String>>>? = if (vodDisabled) null else async { runCatching { client.getVodCategories(xtreamProvider) }.getOrDefault(emptyList()) }
                val seriesCatsDeferred: Deferred<List<Pair<String, String>>>? = if (vodDisabled) null else async { runCatching { client.getSeriesCategories(xtreamProvider) }.getOrDefault(emptyList()) }
                val liveDeferred = async { client.getLiveStreams(xtreamProvider) }
                val filmsDeferred: Deferred<List<Channel>>? = if (vodDisabled) null else async { client.getVodStreams(xtreamProvider) }
                val seriesDeferred: Deferred<List<Channel>>? = if (vodDisabled) null else async { client.getSeries(xtreamProvider) }

                val liveCatNames = liveCatsDeferred.await().toMap()
                val vodCatNames = vodCatsDeferred?.await()?.toMap() ?: emptyMap()
                val seriesCatNames = seriesCatsDeferred?.await()?.toMap() ?: emptyMap()

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
                films = filmsDeferred?.await()?.map { withCategory(it, vodCatNames, "uncat_vod") } ?: emptyList()
                series = seriesDeferred?.await()?.map { withCategory(it, seriesCatNames, "uncat_series") } ?: emptyList()
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

    /** The films/series half of a derive pass, returned whole so callers can assign the
     *  fields on the thread of their choosing (side-effect assignment on a cancellable
     *  Default-thread job could land after a newer load's fresh write). */
    private data class FilmsSeriesContent(
        val filmList: List<Channel>,
        val filmVersions: Map<String, List<Channel>>,
        val filmShelves: List<ContentShelf>,
        val seriesList: List<Channel>,
        val seriesVersions: Map<String, List<Channel>>,
        val seriesShelves: List<ContentShelf>,
        val seriesCategoryRows: List<CategoryFilter>
    )

    /**
     * Filtering, dedup-grouping, sorting, and shelf-building over the whole catalog
     * (tens of thousands of items on a big provider) is real CPU work - it must run
     * off the main thread or the UI stalls/looks hung on every load and refresh.
     */
    private suspend fun classifyAndShow(preserveUi: Boolean = false) {
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
            if (preserveUi) {
                // Surgical refresh of the current tab: the fresh data above already landed in
                // every adapter, but instead of the selectTab/selectHome/selectDiscover dispatch
                // (which resets scroll, position and focus) only the pane actually on screen is
                // re-fed in place. The hasContent branch set contentRow visible for the
                // categorized layout, so the panes that own the slot outright (Home, Discover)
                // put it back before their own refresh.
                if (showingHome) {
                    binding.contentRow.visibility = View.GONE
                    homeShelfAdapter.submitList(buildHomeShelves())
                } else if (showingDiscover) {
                    // Discover owns the slot and has no catalog chrome to refresh - the
                    // refreshed catalog is picked up when the user switches back.
                    binding.contentRow.visibility = View.GONE
                } else if (!showingDownloads) {
                    scope.launch {
                        // Guard mirrors selectTab's: the category build is seconds' work on a
                        // large catalog, and the user can leave this tab while it runs - never
                        // land the sidebar over a pane that moved in.
                        if (showingHome || showingDiscover || showingDownloads) {
                            setStatus("", visible = false)
                            return@launch
                        }
                        if (activeTab != 0) filmsSeriesDeriveJob?.join()
                        val categories = buildCategoriesForActiveTab()
                        // Validate the preserved row against the freshly rebuilt categories -
                        // a row whose id no longer exists (provider dropped the group, the
                        // filter hid it) must not keep pointing at nothing. Falls back to the
                        // same default target selectTab would have picked on a fresh entry.
                        if (selectedRowId != null && categories.none { it.id == selectedRowId }) {
                            selectedRowId = null
                            selectedCategoryLabel = null
                            selectedBrandChannelIds = null
                            selectedCategoryIds = null
                            if (activeTab == 0) {
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
                        }
                        // A category-grid filter whose matchIds now match no item would leave an
                        // empty pane - fall back to the All view instead of showing nothing.
                        val matchIds = selectedCategoryIds
                        if (matchIds != null) {
                            val source = when (activeTab) { 0 -> liveChannels; 1 -> seriesList; else -> filmList }
                            val empty = withContext(Dispatchers.Default) { source.none { it.filterKey() in matchIds } }
                            if (empty) selectedCategoryIds = null
                        }
                        submitCategories(categories)
                        applyCategoryFilter(focusFirstLiveChannel = false)
                        binding.contentRow.visibility = View.VISIBLE
                        setStatus("", visible = false)
                        applyStatus()
                    }
                }
                // Keep the preview pointed at the current channel's fresh incarnation (null
                // when the refresh removed it). No requestPreviewLoad - the running preview
                // keeps playing, and the next focus pick-up resolves liveVersions[channel.id]
                // against the new map.
                lastFocusedLiveChannel = liveChannels.firstOrNull { it.id == lastFocusedLiveChannel?.id }
            } else {
                if (showingHome) selectHome() else if (showingDiscover) selectDiscover() else selectTab(activeTab)
            }
        } else {
            showEmptyState()
        }
    }

    /** Paint-the-Live-ASAP path: derive only the live half off the main thread, then run the
     *  same first-paint sequence classifyAndShow() uses for the Live tab. If the user has
     *  already left for another tab, only the live side is filled in - selectTab and other
     *  tabs' state are left to their own render path, never touched from here. */
    private fun classifyAndShowLiveFirst() {
        scope.launch {
            withContext(Dispatchers.Default) { deriveLiveHalf(allChannels) }
            // Mirror of classifyAndShow()'s first-paint bind block, live side only - the
            // other tabs' adapters and shelves belong to their own render path.
            val hasContent = allChannels.isNotEmpty() || enabledStreamSearchPlugin() != null
            if (hasContent) {
                // Mirror of classifyAndShow()'s adapter-bind block (all five, not just the
                // live side): on warm/stale-cache starts this is the only bind that runs, so
                // the sidebar and Home/shelf panes would otherwise stay blank.
                binding.liveContent.adapter = liveAdapter
                binding.seriesContent.adapter = seriesShelfAdapter
                binding.filmsContent.adapter = filmsShelfAdapter
                binding.categorySidebar.adapter = categoryAdapter
                binding.homeContent.adapter = homeShelfAdapter
            }
            // Settings/search overlays own the whole content slot while up - defer chrome
            // swaps to their dismiss handlers, same as classifyAndShow().
            if (activeSettingsOverlay != null || activeSearchOverlay != null) return@launch
            if (hasContent) {
                binding.emptyState.visibility = View.GONE
                binding.contentRow.visibility = View.VISIBLE
                updateTopChromeVisibility()
                when {
                    showingHome -> selectHome()
                    showingDiscover -> selectDiscover()
                    activeTab == 0 -> selectTab(activeTab)
                    // Guard: user already moved to another tab - live side only, no selectTab.
                    else -> Unit
                }
            } else {
                showEmptyState()
            }
        }
    }

    /** The expensive films/series pass (dedup/sort/category-row/shelf build), off the main
     *  thread, with a late-write guard: if allChannels was swapped while the snapshot was
     *  being derived, the result is dropped - a newer load owns the render. Never calls
     *  selectTab and never raises status; the caller decides when the result shows. */
    private fun deriveFilmsSeries() {
        val snapshot = allChannels
        filmsSeriesDeriveJob = scope.launch(Dispatchers.Default) {
            if (allChannels !== snapshot) return@launch
            val result = deriveFilmsSeriesHalf(snapshot)
            withContext(Dispatchers.Main) {
                // Guard again before touching the UI: the shelf build is seconds of work on
                // a big catalog, and a newer load may have swapped allChannels mid-derive.
                // isActive too - a cancelled job must never land its fields on the UI.
                if (allChannels !== snapshot || !isActive) return@withContext
                filmList = result.filmList
                filmVersions = result.filmVersions
                filmShelves = result.filmShelves
                seriesList = result.seriesList
                seriesVersions = result.seriesVersions
                seriesShelves = result.seriesShelves
                cachedSeriesCategoryRows = result.seriesCategoryRows
                // Mirror of classifyAndShow()'s shelf submits for the films/series shelves.
                seriesShelfAdapter.submitList(seriesShelves)
                filmsShelfAdapter.submitList(filmShelves)
                if (showingHome) homeShelfAdapter.submitList(buildHomeShelves())
            }
        }
    }

    /** Merges one provider's freshly-fetched channels into allChannels, replacing anything
     *  previously loaded from that same provider (matched by config id). */
    private fun mergeProviderPartial(configId: String?, channels: List<Channel>) {
        allChannels = allChannels.filterNot { it.sourceProviderId == configId } + channels
    }

    /** Live-only re-render, coalesced: the first call paints the Live tab ASAP (live half +
     *  first paint); later calls while on the Live tab surgically re-filter the live data in
     *  place - no position reset, no selectTab, nothing outside the live side touched. */
    private fun renderLivePartial() {
        if (!uiPainted) {
            uiPainted = true
            classifyAndShowLiveFirst()
        } else if (activeTab == 0) {
            // Coalesce: N near-simultaneous provider completions each land here - if one
            // surgical re-render is already in flight, drop this one (the in-flight pass
            // reads allChannels fresh and sees the merged result).
            if (liveRenderJob?.isActive == true) return
            liveRenderJob = scope.launch {
                withContext(Dispatchers.Default) { deriveLiveHalf(allChannels) }
                // Mirror applyCategoryFilter()'s live branch (source filter) minus the
                // scroll-to-top - keep the user's position, just re-submit fresh data.
                val source = liveChannels
                val isFavourites = selectedRowId == FAVOURITES_CATEGORY_ID
                val favoriteIds = if (isFavourites) FavoritesStore.getFavoriteChannelIds(this@MainActivity) else emptySet()
                val brandIds = selectedBrandChannelIds
                val matchIds = selectedCategoryIds
                val filtered = withContext(Dispatchers.Default) {
                    when {
                        isFavourites -> source.filter { it.id in favoriteIds }
                        brandIds != null && matchIds != null -> source.filter { it.id in brandIds || it.filterKey() in matchIds }
                        brandIds != null -> source.filter { it.id in brandIds }
                        matchIds == null -> source
                        else -> source.filter { it.filterKey() in matchIds }
                    }
                }
                // A category-grid filter whose matchIds now match no item (a partial merge
                // that hasn't covered the selected category yet) would leave an empty pane -
                // fall back to the All view instead of showing nothing.
                val empty = if (matchIds != null) {
                    withContext(Dispatchers.Default) { source.none { it.filterKey() in matchIds } }
                } else false
                if (matchIds != null && empty) {
                    selectedCategoryIds = null
                    liveAdapter.submitList(source)
                } else {
                    liveAdapter.submitList(filtered)
                }
            }
        }
    }

    private fun computeDerivedContent(allChannels: List<Channel>, hideNonEnglish: Boolean, hideAdult: Boolean): DerivedContent {
        deriveLiveHalf(allChannels)
        val result = deriveFilmsSeriesHalf(allChannels)
        filmList = result.filmList
        filmVersions = result.filmVersions
        filmShelves = result.filmShelves
        seriesList = result.seriesList
        seriesVersions = result.seriesVersions
        seriesShelves = result.seriesShelves
        cachedSeriesCategoryRows = result.seriesCategoryRows
        return DerivedContent(liveChannels, liveVersions, filmList, filmVersions, filmShelves, seriesList, seriesVersions, seriesShelves)
    }

    /** Live half of the derive pass: filter + adult-drop + quality-version grouping into
     *  liveChannels/liveVersions. Cheap relative to the films/series half (no shelves), so
     *  it's extracted first and reused by the paint-Live-ASAP path. */
    private fun deriveLiveHalf(list: List<Channel>) {
        val hideAdult = prefs.getBoolean(PREF_HIDE_ADULT, true)
        val rawLive = list.filter { it.mediaType == MediaType.LIVE && !it.name.contains("##") }
            .filterNot { hideAdult && isAdultCategory(it.categoryName, it.group) }
        val useClassic = prefs.getBoolean(PREF_CLASSIC_CATEGORY_LAYOUT, false)
        if (useClassic) {
            // Classic: no quality version merging — show every channel as-is from the provider.
            // Version map is empty since every variant appears as its own channel entry.
            liveChannels = rawLive
            liveVersions = emptyMap()
        } else {
            val (grouped, vers) = groupLiveQualityVersions(rawLive)
            liveChannels = grouped
            liveVersions = vers
        }
    }

    /** Films/series half of the derive pass: dedup/sort, category rows, and shelf build into
     *  a [FilmsSeriesContent] result (no field side effects - the caller assigns them on its
     *  own thread). The expensive half - run off the main thread. */
    private fun deriveFilmsSeriesHalf(list: List<Channel>): FilmsSeriesContent {
        val hideNonEnglish = prefs.getBoolean(PREF_HIDE_NON_ENGLISH, true)
        val hideAdult = prefs.getBoolean(PREF_HIDE_ADULT, true)
        fun isAdult(ch: Channel) = hideAdult && isAdultCategory(ch.categoryName, ch.group)

        val rawFilms = list.filter { it.mediaType == MediaType.MOVIE }
            .filterNot { hideNonEnglish && isNonEnglishTitle(it.name) }
            .filterNot { isAdult(it) }
            .map { it.withResolvedYear() }
        val (groupedFilms, versions) = groupDuplicateMovies(rawFilms)
        val films = groupedFilms.sortedByDescending { it.year?.toIntOrNull() ?: -1 }
        // "Newest" pools the most recent releases (by date, not rating) into one shelf
        // pinned at the top, sorted by release date descending regardless of category.
        val newestFilms = newestByDate(films)
        // Poster shelves derive from the SAME category rows as the sidebar - same categories,
        // same order, guaranteed by construction. Snapshot the per-tab pinned/hidden sets
        // once here (they're per-tab prefs keys and both tabs' shelves are built in this one
        // pass). Never write categoryChildrenCache from here - that stays sidebar-owned.
        val filmPinned = getPinnedCategories(2)
        val filmHidden = getHiddenCategories(2)
        val seriesPinned = getPinnedCategories(1)
        val seriesHidden = getHiddenCategories(1)
        val animeSectionsSnapshot = animeSections
        val filmCategoryRows = buildCategoryRows(
            list = films, versionsById = versions, tab = 2,
            pinned = filmPinned, hiddenIds = filmHidden, expanded = emptySet(),
            animeSections = emptyList(), useClassicLayout = false, favoriteChannelIds = emptySet()
        )
        // The old buildShelves() count-sorting is gone - shelf order IS the sidebar's row
        // order. Films keeps its single "Newest" prepend, exactly as before.
        val filmShelvesLocal = shelvesFromCategoryRows(filmCategoryRows.rows, films)
            .let { shelves -> if (newestFilms.isEmpty()) shelves else listOf(ContentShelf("Newest", newestFilms)) + shelves }

        val rawSeries = list.filter { it.mediaType == MediaType.SERIES }
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
        val seriesCategoryRows = buildCategoryRows(
            list = series, versionsById = seriesVers, tab = 1,
            pinned = seriesPinned, hiddenIds = seriesHidden, expanded = emptySet(),
            animeSections = animeSectionsSnapshot, useClassicLayout = false, favoriteChannelIds = emptySet()
        )
        // Newest and Favourites stay pinned at the very top of the poster, above the
        // sidebar-derived category shelves. Continue Watching slots between them, matching
        // the sidebar order (Favourites > Continue Watching > Newest > categories).
        val seriesShelvesLocal = shelvesFromCategoryRows(seriesCategoryRows.rows, series)
            .let { shelves ->
                (if (newestSeries.isEmpty()) shelves else listOf(ContentShelf("Newest", newestSeries)) + shelves)
            }
            .let { shelves ->
                val cw = seriesContinueItems()
                if (cw.isEmpty()) shelves else listOf(ContentShelf("Continue Watching", cw)) + shelves
            }
            .let { shelves ->
                if (favoriteSeries.isEmpty()) shelves else listOf(ContentShelf("Favourites", favoriteSeries)) + shelves
            }

        return FilmsSeriesContent(
            filmList = films,
            filmVersions = versions,
            filmShelves = filmShelvesLocal,
            seriesList = series,
            seriesVersions = seriesVers,
            seriesShelves = seriesShelvesLocal,
            seriesCategoryRows = seriesCategoryRows.rows
        )
    }

    /** Maps sidebar category rows (the output of [buildCategoryRows], already in sidebar
     *  order) to poster shelves, in that same order - the poster renders exactly the
     *  categories the sidebar shows, guaranteed by construction. Rows that don't resolve
     *  to items (empty after pin/hide) are dropped, mirroring a sidebar click on them.
     *
     *  Item resolution mirrors applyCategoryFilter()'s two branches: an explicit
     *  channelIds set (Jellyfin, anime, genre buckets, brand rows) filters by channel id;
     *  everything else (leaves, group: parents, clustered service categories) filters by
     *  filterKey() against matchIds. */
    private fun shelvesFromCategoryRows(rows: List<CategoryFilter>, list: List<Channel>): List<ContentShelf> {
        // key: categoryId ?: title, merge same-name rows
        val merged = LinkedHashMap<String, ContentShelf>()
        for (row in rows) {
            if (row.id == null) continue          // All row - the poster IS the All view
            if (row.isChild) continue             // content already in the parent's union
            if (row.count <= 0) continue          // toggle/utility rows (classic-layout toggle, etc.)
            val items = when {
                row.channelIds.isNotEmpty() -> list.filter { it.id in row.channelIds }
                else -> list.filter { it.filterKey() in row.matchIds }
            }
            if (items.isEmpty()) continue
            val shelf = ContentShelf(title = row.name, items = items, pinned = row.pinned, categoryId = row.id)
            val key = row.id ?: row.name
            val existing = merged[key]
            merged[key] = if (existing == null) shelf else existing.copy(items = existing.items + items)
        }
        return merged.values.toList()
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

    // Newest/Favourites are synthetic shelves with no sidebar row id - pinning/hiding them
    // falls back to the legacy title-based prefs (inert, as it always was: nothing matches
    // those titles in the row pipeline).
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

    /** Pin a Series/Films poster shelf. Shelves ARE sidebar rows now, so the pin routes
     *  through the shelf's row id into the same per-tab prefs the sidebar uses. Shelves
     *  without a row id (Newest/Favourites) fall back to the legacy title-based pin. */
    private fun togglePinShelfCategory(tab: Int, shelf: ContentShelf) {
        val id = shelf.categoryId
        if (id == null) {
            togglePinnedShelf(tab, shelf.title)
            return
        }
        togglePinCategory(CategoryFilter(id = id, name = shelf.title, count = shelf.items.size), tab)
    }

    private fun toggleHiddenShelfCategory(tab: Int, shelf: ContentShelf) {
        val id = shelf.categoryId
        if (id == null) {
            toggleHiddenShelf(tab, shelf.title)
            return
        }
        toggleHiddenSidebarCategory(CategoryFilter(id = id, name = shelf.title, count = shelf.items.size), tab)
    }

    private fun togglePinCategory(category: CategoryFilter, tab: Int = activeTab) {
        val id = category.id ?: return
        val pinned = getPinnedCategories(tab)
        val pinningNow = !pinned.remove(id)
        if (pinningNow) pinned.add(id)
        prefs.edit().putStringSet(pinnedCategoriesPrefsKey(tab), pinned).apply()
        // The rebuild that follows can take a moment on a big catalog, and the row only
        // moves once it lands - without a word on screen a hold looked like it did nothing.
        Toast.makeText(
            this,
            if (pinningNow) "Pinned \"${category.name}\" to top" else "Unpinned \"${category.name}\"",
            Toast.LENGTH_SHORT
        ).show()
        scope.launch { rebuildCategoriesForActiveTab() }
    }

    /** Hides a sidebar category row - a merged "group:" parent hides every raw category
     *  folded into it (matchIds), a plain leaf just hides itself. */
    private fun toggleHiddenSidebarCategory(category: CategoryFilter, tab: Int = activeTab) {
        val ids = category.matchIds.ifEmpty { category.id?.let { setOf(it) } ?: return }
        val hidden = getHiddenCategories(tab)
        val hidingNow = ids.none { it in hidden }
        if (hidingNow) hidden.addAll(ids) else hidden.removeAll(ids)
        prefs.edit().putStringSet(hiddenCategoriesPrefsKey(tab), hidden).apply()
        Toast.makeText(this, if (hidingNow) "Hidden \"${category.name}\"" else "Unhidden \"${category.name}\"", Toast.LENGTH_SHORT).show()
        scope.launch { rebuildCategoriesForActiveTab() }
    }

    /** Films/Series long-press menu - sidebar row is a single TextView with no room for
     *  inline icon buttons like the shelf headers have, so pin/hide live behind a chooser. */
    private fun showCategoryContextMenu(category: CategoryFilter) {
        val id = category.id ?: return
        // The Jellyfin row is always first by construction, so "Pin to top" would be a
        // no-op - hiding it is the only meaningful action. Same for the synthetic Newest and
        // Continue Watching rows: they're prepended above the pinned block, so pinning them
        // moves them nowhere, and pin is inert for them anyway (guards in
        // buildCategoriesForActiveTab skip rows whose id is pinned).
        if (id == JELLYFIN_CATEGORY_ID || id == NEWEST_CATEGORY_ID || id == CONTINUE_WATCHING_CATEGORY_ID) {
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
        refreshHomeShelvesIfShowing()
        // The guide's per-row star reads the favourite store at bind time - repaint the
        // list so the toggle lands immediately (submitList diff on the same list is a no-op).
        if (activeTab == 0) liveAdapter.notifyDataSetChanged()
    }

    /** Long-press handler for any VOD poster (Home/Series/Films shelves and the category
     *  grids). Live entries go through [toggleFavoriteChannel] so a favourited channel lands
     *  in the same set the Live TV Favourites category reads; films and series share the
     *  favourite-series set, which is what both the detail screen's star and the Home
     *  Favorites shelf use. */
    private fun toggleFavoriteVodItem(item: Channel) {
        if (item.id.isBlank()) return
        if (item.mediaType == MediaType.LIVE) {
            toggleFavoriteChannel(item)
            return
        }
        val nowFavorite = FavoritesStore.toggleFavoriteSeries(this, item.id)
        Toast.makeText(
            this,
            if (nowFavorite) "Added to Favourites" else "Removed from Favourites",
            Toast.LENGTH_SHORT
        ).show()
        // Same server push the detail screen's star does - a Jellyfin item's favourite state
        // belongs to the server, not to this install.
        if (item.isJellyfin) {
            scope.launch {
                val client = jellyfinClientOrConnect() ?: return@launch
                withContext(Dispatchers.IO) { runCatching { client.setFavorite(item.id, nowFavorite) } }
            }
        }
        refreshHomeShelvesIfShowing()
        // Rebuilds the Series/Films shelves so the "Favourites" shelf at their top picks the
        // change up without a tab switch. Only worth doing on those tabs - Home is handled
        // above, and Live TV has no VOD shelf to redraw.
        if (!showingHome && activeTab != 0) scope.launch { classifyAndShow() }
    }

    /** Home is built once, in [selectHome] - anything that changes what belongs on a shelf
     *  while Home is on screen has to ask for it again or the change isn't visible until the
     *  user leaves and comes back. */
    private fun refreshHomeShelvesIfShowing() {
        if (showingHome) homeShelfAdapter.submitList(buildHomeShelves())
    }

    /** Series-shelf counterpart of [refreshHomeShelvesIfShowing]: Continue Watching (and
     *  favourites/newest) move when playback ends, so the Series poster needs the same
     *  lightweight refresh. Rebuilds the shelf list from cachedSeriesCategoryRows - never
     *  re-runs the expensive buildCategoryRows() pass. */
    private fun refreshSeriesShelvesIfShowing() {
        if (showingHome || activeTab != 1) return
        if (binding.seriesContent.visibility != View.VISIBLE) return
        val favoriteSeries = seriesList.filter { it.id in FavoritesStore.getFavoriteSeriesIds(this) }
        val newestSeries = newestByDate(seriesList)
        val shelves = shelvesFromCategoryRows(cachedSeriesCategoryRows, seriesList)
            .let { s -> (if (newestSeries.isEmpty()) s else listOf(ContentShelf("Newest", newestSeries)) + s) }
            .let { s ->
                val cw = seriesContinueItems()
                if (cw.isEmpty()) s else listOf(ContentShelf("Continue Watching", cw)) + s
            }
            .let { s -> if (favoriteSeries.isEmpty()) s else listOf(ContentShelf("Favourites", favoriteSeries)) + s }
        seriesShelves = shelves
        seriesShelfAdapter.submitList(shelves)
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
        // Home, Discover and Downloads are not categorized tabs and have no sidebar. Every
        // caller here is asynchronous, so any of them can land after the user has left the tab
        // the categories were built for - and the sidebar must not reappear over a pane that
        // never had one.
        val onCategorizedTab = !showingHome && !showingDiscover && !showingDownloads
        binding.categorySidebar.visibility =
            if (onCategorizedTab && categories.size > 1) View.VISIBLE else View.GONE
        // submitList uses AsyncListDiffer which commits the list asynchronously.
        // Set the selected highlight only after the list is committed, otherwise
        // the diff callback can reset the adapter's selected state.
        categoryAdapter.submitList(categories) {
            if (selectedRowId != null) {
                categoryAdapter.setSelected(selectedRowId)
            }
        }
    }

    private data class CategoryBuildResult(
        val rows: List<CategoryFilter>,
        val childrenByParent: Map<String, List<CategoryFilter>>
    )

    private suspend fun buildCategoriesForActiveTab(): List<CategoryFilter> {
        val list = activeFullList()
        val pinned = getPinnedCategories()
        val hiddenIds = getHiddenCategories()
        val tab = activeTab
        val expandedSnapshot = expandedGroupKeys.toSet()
        val favoriteChannelIds = if (tab == 0) FavoritesStore.getFavoriteChannelIds(this) else emptySet()
        val animeSectionsSnapshot = animeSections
        // Snapshot on the caller's thread - the pipeline below runs on Dispatchers.Default.
        val versionsById = when (tab) {
            1 -> seriesVersions
            2 -> filmVersions
            else -> emptyMap()
        }
        val useClassicLayout = tab == 0 && prefs.getBoolean(PREF_CLASSIC_CATEGORY_LAYOUT, false)
        // Synthetic Films/Series sidebar rows are computed on the same Default thread as the
        // category pipeline: newestByDate sorts the whole tab list, and seriesContinueItems()
        // reads the position store. Both prepend ABOVE Jellyfin, so the sidebar leads
        // Continue Watching > Newest > Jellyfin > the real categories.
        val (result, newestByTab, seriesContinue) = withContext(Dispatchers.Default) {
            val rows = buildCategoryRows(
                list = list,
                versionsById = versionsById,
                tab = tab,
                pinned = pinned,
                hiddenIds = hiddenIds,
                expanded = expandedSnapshot,
                animeSections = animeSectionsSnapshot,
                useClassicLayout = useClassicLayout,
                favoriteChannelIds = favoriteChannelIds
            )
            Triple(
                rows,
                if (tab != 0) newestByDate(list) else emptyList(),
                if (tab == 1) seriesContinueItems() else emptyList()
            )
        }
        categoryChildrenCache = result.childrenByParent
        // Guarded on pinned too: the legacy title-folding (buildCategoryRows) maps a pinned
        // "Newest"/"Continue Watching" shelf title onto a real row id, and a folded row would
        // collide with the synthetic one below - skip ours when the id is already pinned.
        val rows = result.rows.toMutableList()
        if (tab != 0 && NEWEST_CATEGORY_ID !in hiddenIds && NEWEST_CATEGORY_ID !in pinned) {
            rows.add(
                0,
                CategoryFilter(
                    id = NEWEST_CATEGORY_ID,
                    name = "Newest",
                    count = newestByTab.size,
                    channelIds = newestByTab.map { it.id }.toSet(),
                    isDynamic = true
                )
            )
        }
        // Continue Watching has no channelIds/matchIds - selecting it is a special case in
        // applyCategoryFilter (its episodes are not seriesList members). Only added while it
        // has items, like the Jellyfin row is only added while the tab has Jellyfin content.
        if (tab == 1 && CONTINUE_WATCHING_CATEGORY_ID !in hiddenIds && CONTINUE_WATCHING_CATEGORY_ID !in pinned) {
            if (seriesContinue.isNotEmpty()) {
                rows.add(
                    0,
                    CategoryFilter(
                        id = CONTINUE_WATCHING_CATEGORY_ID,
                        name = "Continue Watching",
                        count = seriesContinue.size,
                        isDynamic = true
                    )
                )
            }
        }
        return rows
    }

    /** Pure ordering pipeline behind the sidebar - shared with the Series/Films poster
     *  shelves (see computeDerivedContent) so both render the same categories in the same
     *  order, by construction. No prefs/state reads: every caller passes its own snapshots. */
    private fun buildCategoryRows(
        list: List<Channel>,
        versionsById: Map<String, List<Channel>>,
        tab: Int,
        pinned: Set<String>,
        hiddenIds: Set<String>,
        expanded: Set<String>,
        animeSections: List<AnimeCatalogClient.Section>,
        useClassicLayout: Boolean,
        favoriteChannelIds: Set<String>   // tab 0 only
    ): CategoryBuildResult {
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
                    expanded = expanded.contains(groupId),
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
                    val expanded = expanded.contains(bucketId)
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
                            // Categories by size, biggest first - the rows right after the
                            // Jellyfin/anime blocks are the ones people actually browse, so
                            // the fullest category leads instead of an arbitrary alphabetical one.
                            { -it.first.count },
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
                // Anime titles are deduped against the rest of the catalog by name like
                // everything else, so a show an IPTV provider also carries becomes one card -
                // and the copy that wins it is whichever had a poster, very often the provider's.
                // Matching on the representative's own "anime:" id therefore lost every title
                // the provider happened to stock, which with a large provider is most of them
                // and took the whole Anime row with it. Same failure the Jellyfin row above
                // already handles: look at every version in the group, not just the winner.
                //
                // Mapped rather than filtered, because the sections below index titles by their
                // catalog id: once a title is represented by the provider's copy, its section
                // has to point at that representative or the row would be empty.
                val animeRepById = HashMap<String, String>()
                for (ch in list) {
                    if (ch.id.startsWith(AnimeCatalogClient.ID_PREFIX)) animeRepById[ch.id] = ch.id
                    versionsById[ch.id]?.forEach { version ->
                        if (version.id.startsWith(AnimeCatalogClient.ID_PREFIX)) {
                            animeRepById[version.id] = ch.id
                        }
                    }
                }
                val animeIds = animeRepById.values.toSet()
                if (animeIds.isNotEmpty()) {
                    val children = animeSections.mapNotNull { section ->
                        val ids = section.channelIds.mapNotNullTo(mutableSetOf()) { animeRepById[it] }
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
                    val expanded = expanded.contains(ANIME_CATEGORY_ID)
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
            // Legacy shelf pin/hide prefs stored shelf titles ("KIDS & FAMILY"); rows are
            // now keyed by id. Fold any stored value that names a real row (case-
            // insensitively) into that row's id so pre-migration pins/hides keep working.
            // Only title folds are applied here - id-keyed entries already did their job
            // during construction (leaves, Jellyfin, Anime), so re-filtering them post-hoc
            // would change the sidebar's existing hide semantics for bucket/brand/group rows.
            val knownRowIds = result.mapNotNullTo(mutableSetOf()) { it.id }
            val idForName = mutableMapOf<String, String>()
            for (row in result) {
                val id = row.id ?: continue
                idForName.putIfAbsent(row.name.lowercase(), id)
            }
            val legacyPinnedIds = pinned.mapNotNullTo(linkedSetOf()) { value ->
                if (value in knownRowIds) null else idForName[value.lowercase()]
            }
            val legacyHiddenIds = hiddenIds.mapNotNullTo(linkedSetOf()) { value ->
                if (value in knownRowIds) null else idForName[value.lowercase()]
            }
            val finalRows = result
                .filterNot { it.id in legacyHiddenIds }
                .map { row -> if (row.id in legacyPinnedIds && !row.pinned) row.copy(pinned = true) else row }
            return CategoryBuildResult(finalRows, childrenByParent.toMap())
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
                // Continue Watching's items are in-progress episodes, not seriesList members -
                // a filterKey() match against seriesList would render an empty grid. Serve the
                // continue list directly.
                if (selectedRowId == CONTINUE_WATCHING_CATEGORY_ID) {
                    setGridSpan(binding.seriesContent, seriesGridAdapter, R.id.tabSeries)
                    binding.seriesContent.adapter = seriesGridAdapter
                    seriesGridAdapter.submitList(seriesContinueItems())
                    binding.seriesContent.scrollToPosition(0)
                    return
                }
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
        statusText = text
        statusWanted = visible
        applyStatus()
    }

    /**
     * Decides whether the status actually goes on screen, from the current state rather than
     * from what was true when the message was raised.
     *
     * A provider load runs for a long time - a large Stalker portal is a minute of streaming -
     * and the user is free to move around while it does. statusRow is a sibling of the content
     * panes holding the same 0dp/weight=1 slot, so any pane shown while it was up got half the
     * screen and "Connecting to <provider>..." got the other half. Raising it once and leaving
     * it also meant opening Settings afterwards couldn't take it down, because nothing
     * re-evaluated it until the next message.
     *
     * So the status only owns the slot when nothing else does, and every screen change calls
     * this. The load itself is unaffected - it just stops being narrated over whatever the user
     * went to look at instead.
     */
    private fun applyStatus() {
        binding.statusText.text = statusText
        val slotTaken = activeSettingsOverlay != null || activeSearchOverlay != null ||
            isPlayerVisible || isContentDetailVisible ||
            binding.contentRow.visibility == View.VISIBLE ||
            binding.homeContent.visibility == View.VISIBLE ||
            binding.discoverContent.visibility == View.VISIBLE
        val show = statusWanted && !slotTaken
        binding.statusRow.visibility = if (show) View.VISIBLE else View.GONE
        // In-progress messages ("Loading...", "Connecting...") get a spinner; final
        // results ("N items", errors) don't - "..." is what already distinguishes them
        // at every call site, no need for a second parameter everywhere.
        binding.statusSpinner.visibility =
            if (show && statusText.trimEnd().endsWith("...")) View.VISIBLE else View.GONE
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
            val (labelId, iconId, indicatorId) = when (tv.id) {
                R.id.tabLive -> Triple(R.id.tabLiveLabel, R.id.tabLiveIcon, R.id.tabLiveIndicator)
                R.id.tabSeries -> Triple(R.id.tabSeriesLabel, R.id.tabSeriesIcon, R.id.tabSeriesIndicator)
                R.id.tabFilms -> Triple(R.id.tabFilmsLabel, R.id.tabFilmsIcon, R.id.tabFilmsIndicator)
                R.id.tabHome -> Triple(R.id.tabHomeLabel, R.id.tabHomeIcon, R.id.tabHomeIndicator)
                R.id.tabDiscover -> Triple(R.id.tabDiscoverLabel, R.id.tabDiscoverIcon, R.id.tabDiscoverIndicator)
                R.id.tabDownloads -> Triple(R.id.tabDownloadsLabel, R.id.tabDownloadsIcon, R.id.tabDownloadsIndicator)
                else -> continue
            }
            val label = tv.findViewById<TextView>(labelId)
            val icon = tv.findViewById<ImageView>(iconId)
            val indicator = tv.findViewById<View>(indicatorId)
            label?.let {
                it.setTextColor(getColor(if (isSelected) R.color.text_primary else R.color.text_secondary))
                it.typeface = ResourcesCompat.getFont(this, if (isSelected) R.font.inter_semibold else R.font.inter_medium)
            }
            icon?.setColorFilter(
                getColor(if (isSelected) R.color.text_primary else R.color.text_tertiary),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
            indicator?.visibility = if (isSelected) View.VISIBLE else View.GONE
        }
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
        applyStatus()
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
        applyStatus()
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
        applyStatus()
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
        // User-initiated play - see playItem for why the suppression flag is cleared here.
        skipResumePrompt = false
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
                // An episode tile (Continue Watching) carries an episode number; clicking it
                // should land on the series' detail page - the season chip lands on the
                // episode's season and the Play button already points at the next-unwatched
                // episode - rather than resuming the episode directly. A top-level series
                // entry (Favorites, category grids) has no episode number and goes to the
                // detail page as normal. url is NOT a reliable discriminator - catalog
                // series items can carry one. If the episode's series can't be resolved,
                // fall back to resuming the episode directly.
                if (channel.episodeNum != null) {
                    val series = resolveHomeTileSeries(channel)
                    if (series != null) {
                        showContentDetail(series)
                    } else {
                        showPlayerFor(channel)
                        // A Continue Watching / Next Up tile is a lone episode with no queue
                        // behind it - nothing would auto-advance when it ends. Back-fill the
                        // same cross-season episode chain the detail page plays from.
                        populateHomeTileEpisodeQueue(channel)
                    }
                } else {
                    showContentDetail(channel)
                }
            }
            else -> {}
        }
    }

    /** Resolves the series a Home-tile episode belongs to: exact categoryId (the series id
     *  Xtream parseEpisode and Jellyfin toChannel both stamp on episodes) match through the
     *  catalog first, then the "{series} · {episode}" name-prefix fallback for snapshots that
     *  predate categoryId. Null if unresolvable - callers fall back to direct play. */
    private fun resolveHomeTileSeries(channel: Channel): Channel? {
        // Exact series-id match. Ids are provider-scoped (Xtream series id, Jellyfin item
        // id), so cross-matching is impossible - isJellyfin is the only guard needed, with
        // sourceProviderId compared only when the snapshot carries one (older saves don't).
        channel.categoryId?.takeIf { it.isNotBlank() }?.let { id ->
            allChannels.firstOrNull {
                it.mediaType == MediaType.SERIES && it.id == id && it.isJellyfin == channel.isJellyfin &&
                    (channel.sourceProviderId == null || it.sourceProviderId == channel.sourceProviderId)
            }?.let { return it }
        }
        // Name-prefix fallback for old snapshots: "Series Name · S01E02 · Title", longest
        // name wins. Same-provider guard only when the snapshot knows its provider.
        return allChannels
            .filter {
                it.mediaType == MediaType.SERIES && it.isJellyfin == channel.isJellyfin &&
                    (channel.sourceProviderId == null || it.sourceProviderId == channel.sourceProviderId)
            }
            .filter { it.name.isNotBlank() && channel.name.startsWith(it.name + " · ") }
            .maxByOrNull { it.name.length }
    }

    /** A Home tile can be one episode standing alone (Continue Watching, Jellyfin Next Up),
     *  played with no queue - so when it ends nothing auto-advances. Back-fill the series'
     *  full episode chain (all seasons, season-major then episode-major, the order the detail
     *  page plays) and index it from the played episode. Any failure leaves the queue empty,
     *  which is exactly what happened before this existed. */
    private fun populateHomeTileEpisodeQueue(channel: Channel) {
        val playedId = channel.id
        if (playedId.isBlank()) return
        // Jellyfin's chain comes from the server (getEpisodes/getSeasons), not Xtream
        // getSeriesFull - and its tiles now resolve to the series detail page anyway, so
        // this fallback never needs to build a Jellyfin queue.
        if (channel.isJellyfin) return
        scope.launch {
            val ordered = withContext(Dispatchers.IO) {
                val seriesId = channel.categoryId ?: return@withContext emptyList<Channel>()
                val client = XtreamClient(BaseApplication.instance.okHttpClient)
                // Seasons arrive season-major already; sort each season's episodes by
                // episode number, then flatten into the cross-season chain.
                client.getSeriesFull(xtreamProviderFor(channel) ?: provider, seriesId).seasons
                    .flatMap { (_, eps) -> eps.sortedBy { it.episodeNum ?: Int.MAX_VALUE } }
            }
            // Don't clobber a queue belonging to whatever is playing now if the user moved on
            // while the fetch was in flight.
            if (nowPlayingChannel?.id != playedId) return@launch
            val index = ordered.indexOfFirst { it.id == playedId }
            if (index >= 0) {
                currentEpisodeQueue = ordered
                currentEpisodeQueueIndex = index
            }
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

    /** X on the "Continue Watching" shelf clears the resume data itself, not just hides the
     *  shelf on the tab it was pressed on. Home, Series and Films all read the same store, so
     *  one clear empties the row everywhere. Jellyfin resume lives on the server, so those
     *  entries are dropped there too (best effort) and removed from memory immediately. Also
     *  un-hides the CW shelf so future watching isn't stuck behind a stale hide flag. */
    private fun clearContinueWatching() {
        PlaybackPositionStore.clearAll(this)
        val serverIds = jellyfinResumeItems.map { it.id }.toList()
        jellyfinResumeItems = emptyList()
        val client = jellyfinClient
        if (client != null && serverIds.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                serverIds.forEach { id -> runCatching { client.clearUserData(id) } }
            }
        }
        getHiddenHomeShelves().let { if (it.remove("Continue Watching")) prefs.edit().putStringSet("hidden_home_shelves", it).apply() }
        getHiddenCategories(1).let { if (it.remove("Continue Watching")) prefs.edit().putStringSet(hiddenCategoriesPrefsKey(1), it).apply() }
        getHiddenCategories(2).let { if (it.remove("Continue Watching")) prefs.edit().putStringSet(hiddenCategoriesPrefsKey(2), it).apply() }
        homeShelfAdapter.submitList(buildHomeShelves())
        if (!showingHome && activeTab != 0) scope.launch { classifyAndShow() }
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

        // Favourited live channels get their own Home row. Long-pressing a channel in the
        // guide has always favourited it, but the result was only ever visible as the
        // Favourites category inside Live TV - Home, the screen the app opens on, showed
        // nothing at all, so the favourites looked like they hadn't saved.
        val favChannelIds = FavoritesStore.getFavoriteChannelIds(this)
        val favChannels = liveChannels.filter { it.id in favChannelIds }.filterNot(::isAdultHomeItem)
        if (favChannels.isNotEmpty()) shelves.add(ContentShelf("Favourite Channels", favChannels))

        // One shelf for both, since favourite VOD is stored in a single set (see
        // FavoritesStore.KEY_FAVORITE_SERIES) - a favourited film used to be saved and then
        // never shown anywhere, because only seriesList was searched for the ids.
        val favIds = FavoritesStore.getFavoriteSeriesIds(this)
        val favItems = (seriesList + filmList).filter { it.id in favIds }.filterNot(::isAdultHomeItem)
        if (favItems.isNotEmpty()) shelves.add(ContentShelf("Favorites", favItems))

        return shelves.filter { it.title !in hidden }
    }

    /** Series-only Continue Watching for the Series tab - same merge as the Home shelf
     *  (server resume list first, then local in-progress entries minus anything the server
     *  already covered), filtered down to series and adult-dropped. Shared by the Series
     *  sidebar row, its content grid, and the Series poster shelf. */
    private fun seriesContinueItems(): List<Channel> {
        val local = PlaybackPositionStore.getAllInProgress(this).filter { it.mediaType == MediaType.SERIES }
        val server = jellyfinResumeItems.filter { it.mediaType == MediaType.SERIES }
        val serverIds = server.map { it.id }.toSet()
        return (server + local.filterNot { it.id in serverIds }).filterNot(::isAdultHomeItem)
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
            // A tab switch away from Live must not race the films/series derive: categories
            // for the Films/Series tabs read filmList/seriesList, so wait for any in-flight
            // derive to land before building them against possibly-stale lists.
            if (index != 0) filmsSeriesDeriveJob?.join()
            // Building categories/filtering thousands of channels can take a couple of
            // seconds on a large catalog - show the same loading indicator as app startup
            // instead of leaving the tab looking empty/frozen while it works.
            setStatus("Loading...", visible = true)
            // Pre-expand the Sports bucket so its children are visible when the user
            // scrolls down to it, regardless of what's selected at the top.
            if (index == 0) expandedGroupKeys.add("${DYNAMIC_BUCKET_ID_PREFIX}Sports")
            val categories = buildCategoriesForActiveTab()
            // buildCategoriesForActiveTab() is seconds' work on a large catalog, and the user
            // is free to leave the tab while it runs. Everything below puts the category
            // sidebar and the content row back on screen unconditionally, so landing late
            // dropped this tab's sidebar on top of whatever the user had moved to - most
            // visibly Discover, which has no sidebar of its own to overwrite it.
            if (activeTab != index || showingHome || showingDiscover || showingDownloads) {
                setStatus("", visible = false)
                return@launch
            }
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
            applyStatus()
        }
    }

    // ── Lists ──────────────────────────────────────

    private fun buildGuideHeader() {
        val density = resources.displayMetrics.density
        val slotWidthPx = (30 * LiveGuideAdapter.MINUTE_WIDTH_DP * density).toInt()
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val calendar = java.util.Calendar.getInstance()

        binding.guideHeaderRow.removeAllViews()
        repeat(24) { index ->
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
        // User-initiated play: never inherit a resume-prompt suppression left over from
        // an auto-advance that never reached STATE_READY.
        skipResumePrompt = false
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
     *  up on a large screen). item_category's root is now a container (star + label), so the
     *  label is detached and its chip chrome re-applied - the container is just a scaffold. */
    private fun inflateVersionChip(parent: ViewGroup, label: String): TextView {
        val root = layoutInflater.inflate(R.layout.item_category, parent, false)
        val chip = root.findViewById<TextView>(R.id.categoryLabel)
        (root as ViewGroup).removeView(chip)
        chip.text = label
        chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_caption))
        val padH = (12 * resources.displayMetrics.density).toInt()
        val padV = (8 * resources.displayMetrics.density).toInt()
        chip.setPadding(padH, padV, padH, padV)
        chip.background = ContextCompat.getDrawable(this, R.drawable.bg_select_item)
        chip.stateListAnimator = AnimatorInflater.loadStateListAnimator(this, R.animator.focus_scale)
        chip.isClickable = true
        chip.isFocusable = true
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
        applyStatus()

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
                // User-initiated play - see playItem for why the suppression flag is cleared here.
                skipResumePrompt = false
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
        data class SeriesTargetSelection(val target: Channel, val ordered: List<Channel>, val isResume: Boolean)

        fun findSeriesTargetEpisode(seasons: List<Pair<String, List<Channel>>>): SeriesTargetSelection? {
            // Cross-season episode chain - the same ordering the Home-tile auto-advance
            // queue uses: seasons in the order they were loaded, episodes within each
            // season sorted by number.
            val ordered = seasons.flatMap { (_, eps) ->
                eps.sortedBy { it.episodeNum ?: Int.MAX_VALUE }
            }
            // The "next episode to watch" is whatever was left part-watched most recently
            // (across every season, not just the one currently shown): the in-progress
            // episode with the newest saved position.
            val inProgress = ordered.mapNotNull { ep ->
                val key = ep.id.ifBlank { ep.url }
                if (key.isBlank()) return@mapNotNull null
                PlaybackPositionStore.get(this, key)
                    ?.takeIf { !it.isNearComplete && it.positionMs > 0 }
                    ?.let { ep to it }
            }.maxByOrNull { it.second.updatedAt }
            // Nothing part-watched: scan the chain in order, skipping finished
            // (near-complete) episodes, and land on the first episode that still needs
            // watching. Every episode finished falls back to episode 1.
            val target = inProgress?.first ?: ordered.firstOrNull { ep ->
                val key = ep.id.ifBlank { ep.url }
                key.isNotBlank() && PlaybackPositionStore.get(this, key)?.isNearComplete != true
            } ?: ordered.firstOrNull() ?: return null
            return SeriesTargetSelection(target, ordered, inProgress != null)
        }

        fun wirePlayButton(seasons: List<Pair<String, List<Channel>>>) {
            val selection = findSeriesTargetEpisode(seasons) ?: return
            val target = selection.target
            val ordered = selection.ordered
            val seasonPair = seasons.firstOrNull { (_, eps) -> eps.any { it.id == target.id } }
            val seasonNum = seasonPair?.first?.let { Regex("""\d+""").find(it)?.value }
            // "Play"/"Resume" alone didn't say *which* episode - with several seasons in
            // play this was a guessing game before committing to it.
            val tag = if (seasonNum != null && target.episodeNum != null) "S${seasonNum}E${target.episodeNum}" else null
            playButtonLabel.text = listOfNotNull(if (selection.isResume) "Resume" else "Play", tag).joinToString(" ")
            playButton.visibility = View.VISIBLE
            playButton.requestFocus()
            playButton.setOnClickListener {
                // User-initiated play - see playItem for why the suppression flag is cleared here.
                skipResumePrompt = false
                hideContentDetail()
                // Anime items route through the plugin instead of direct playback.
                if (item.id.startsWith(AnimeCatalogClient.ID_PREFIX)) {
                    val plugin = enabledStreamSearchPlugin(item)
                    if (plugin != null) {
                        showStreamSearchDialog(plugin, item, season = null, episode = target.episodeNum)
                    }
                } else {
                    currentIndex = -1
                    // Full cross-season chain behind the chosen episode, so it keeps
                    // auto-advancing through the whole show, not just the current season.
                    showPlayerFor(target)
                    detailReturnItem = item
                    detailReturnGroup = seriesGroup
                    currentEpisodeQueue = ordered
                    currentEpisodeQueueIndex = ordered.indexOf(target)
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
                        // Default the season selector to the season of the episode the user
                        // is actually on (the same "next episode to watch" the Play button
                        // targets), not season 1 - a resume from Continue Watching shouldn't
                        // land on the wrong season's episode list. Re-runs on return from
                        // playback (hidePlayer re-opens the detail), so the chip follows the
                        // episode the user just finished too.
                        val targetEp = findSeriesTargetEpisode(seasons)?.target
                        val targetSeasonIndex = seasons.indexOfFirst { (_, eps) -> eps.any { it.id == targetEp?.id } }.coerceAtLeast(0)
                        showSeason(seasons, targetSeasonIndex)
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
                        // User-initiated play - see playItem for why the suppression flag is cleared here.
                        skipResumePrompt = false
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
        applyStatus()
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
        val resultsAdapter = PosterGridAdapter(
            showTypeBadge = true,
            onItemLongClick = { item -> toggleFavoriteVodItem(item) }
        ) { item ->
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
            applyStatus()
            if (showingHome) selectHome() else if (showingDiscover) selectDiscover() else if (showingDownloads) selectDownloads() else selectTab(activeTab)
        }
        activeSearchOverlay = overlay
        applyStatus()
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

    /**
     * The toolbar's refresh button: re-connect to every enabled provider, ignoring the cache.
     *
     * Announced with a toast rather than the status row, because once there is content on
     * screen the status row is suppressed (it shares the content slot - see applyStatus), so
     * pressing refresh over a populated Home gave no sign anything had happened at all.
     */
    private fun reloadCurrentProvider() {
        if (!hasProviderEnabled()) {
            Toast.makeText(this, "No provider is enabled - turn one on in Settings", Toast.LENGTH_LONG).show()
            showProviderSettings()
            return
        }
        Toast.makeText(this, "Refreshing providers…", Toast.LENGTH_SHORT).show()
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
                val target = duration * p / 100
                showTrickplayPreview(target)
                // A touch drag commits its seek in onStopTrackingTouch, but D-pad presses
                // never fire that callback - the bar is focused, not touched, so the thumb
                // just slid with no effect. A touched bar is `pressed`; a key-driven one
                // isn't, so seek here for the key case (and clear stall state, like the
                // drag-commit does) and the video actually follows the thumb on a remote.
                if (s?.isPressed != true) {
                    playerManager.seekTo(target)
                    resetStallTracking()
                }
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

        // Safe to build here (not as field initializers): the Activity context is fully
        // attached by setupPlayerControls time, so GestureDetector's getResources() call
        // in its constructor cannot NPE.
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val width = binding.playerLayout.width
                val target = if (e.x < width / 2) {
                    (playerManager.currentPosition - GESTURE_SEEK_MS).coerceAtLeast(0L)
                } else {
                    (playerManager.currentPosition + GESTURE_SEEK_MS).coerceAtMost(maxOf(playerManager.duration, 0L))
                }
                playerManager.seekTo(target)
                // Visible feedback for the seek - the time label updates via progressRunnable.
                showControls()
                updatePlayPauseIcon()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                playerManager.togglePlayPause()
                updatePlayPauseIcon()
                toggleControls()
                return true
            }
        })
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                // Pinch around the focal point, clamped inside the surface bounds.
                val surface = binding.playerSurface
                surface.pivotX = detector.focusX.coerceIn(0f, surface.width.toFloat())
                surface.pivotY = detector.focusY.coerceIn(0f, surface.height.toFloat())
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val surface = binding.playerSurface
                val newScale = (surface.scaleX * detector.scaleFactor).coerceIn(ZOOM_MIN, ZOOM_MAX)
                surface.scaleX = newScale
                surface.scaleY = newScale
                return true
            }
        })

        binding.playerLayout.setOnTouchListener { _, event ->
            // Both detectors observe every event; the listener always returns true so touches
            // on the player are fully consumed (single/double-tap, pinch). The controls overlay
            // is a child that keeps its own clickable buttons - those consume their own events.
            gestureDetector.onTouchEvent(event)
            scaleDetector.onTouchEvent(event)
            true
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
                    // The plain save above leaves a just-finished episode near-complete
                    // (filtered off Continue Watching), but the season isn't over - keep
                    // the series on the "last watching" shelf by advancing the stored entry
                    // to the next episode. Real duration (not the 0L the old branch wrote,
                    // which the store dropped); the 1ms position is a placeholder that the
                    // next episode's own progress ticks overwrite. Exhausted queue = series
                    // finished, so drop the entry and let the series leave Home.
                    val finished = nowPlayingChannel
                    if (finished?.mediaType == MediaType.SERIES) {
                        val finishedDur = playerManager.duration
                        val finishedKey = finished.id.ifBlank { finished.url }
                        val nextIdx = currentEpisodeQueueIndex + 1
                        if (currentEpisodeQueueIndex >= 0 && nextIdx in currentEpisodeQueue.indices) {
                            val next = currentEpisodeQueue[nextIdx]
                            PlaybackPositionStore.save(
                                this@MainActivity,
                                next.id.ifBlank { next.url },
                                1L,
                                finishedDur,
                                next
                            )
                        } else if (currentEpisodeQueueIndex >= 0 && currentEpisodeQueue.isNotEmpty()) {
                            PlaybackPositionStore.clear(this@MainActivity, finishedKey)
                        }
                    }
                    // If Up Next countdown is already running, it will handle the advance.
                    if (upNextActive) return@onPlaybackStateChanged
                    // Silent fallback auto-advance when Up Next wasn't triggered
                    // (e.g. user seeks to end, skipping the 30s countdown window).
                    val queue = currentEpisodeQueue
                    val nextIdx = currentEpisodeQueueIndex + 1
                    if (nextIdx in queue.indices) {
                        skipResumePrompt = true
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
                    // Jellyfin direct-play: one fresh-URL re-resolve before giving up - a
                    // transient server timeout or an expired direct-play URL often recovers.
                    if (nowPlayingChannel?.isJellyfin == true && !jellyfinRetryAttempted) {
                        retryJellyfinPlayback()
                    } else {
                        Toast.makeText(this@MainActivity, "Playback error", Toast.LENGTH_SHORT).show()
                    }
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
     *  so the plugin branch below doesn't resolve it a second time. [audio] is the stream's
     *  audio category hint ("sub"/"dub") when the caller knows it - the player prefers the
     *  matching audio track and gates sidecar subtitles on it. */
    private fun showPlayerFor(
        channel: Channel,
        resumeFromMs: Long? = null,
        preferredVersionId: String? = null,
        externalSubtitles: List<PlayerManager.ExternalSubtitle> = emptyList(),
        pluginStreamAlreadyResolved: Boolean = false,
        audio: String? = null
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
        // Every new video starts unzoomed - a pinch-zoom from a previous session must not
        // carry over into the next title.
        binding.playerSurface.scaleX = 1f
        binding.playerSurface.scaleY = 1f
        binding.playerSurface.pivotX = 0f
        binding.playerSurface.pivotY = 0f
        applyStatus()
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
        jellyfinRetryAttempted = false
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
                    playerManager.playUrl(resolved, STREAM_USER_AGENT, audio = audio)
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
                    startPositionMs = startAt,
                    audio = audio
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
                        headers = resolved.headers.ifEmpty { null },
                        // The fresh resolve may know the audio category even when the original
                        // caller didn't (or better), so its hint wins.
                        audio = resolved.audio ?: audio
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
                    headers = startVersion.streamHeaders,
                    audio = audio
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
    /** One-shot Jellyfin direct-play recovery: a source error (server read timeout, expired
     *  direct-play URL) re-resolves the item for a fresh URL rather than erroring out - the
     *  failed URL is short-lived and per-session, so a fresh resolveStream is the right fix. */
    private fun retryJellyfinPlayback() {
        val channel = nowPlayingChannel ?: return
        if (!channel.isJellyfin || channel.id.isBlank()) return
        jellyfinRetryAttempted = true
        scope.launch {
            val startAt = playerManager.currentPosition
            val jellyfin = jellyfinClientOrConnect()
            val resolved = if (jellyfin == null) null else withContext(Dispatchers.IO) {
                runCatching { jellyfin.resolveStream(channel.id, startAt) }.getOrNull()
            }
            if (nowPlayingChannel?.id != channel.id) return@launch
            playerManager.playUrl(
                resolved?.url ?: channel.url,
                channel.streamUserAgent,
                subtitles = resolved?.let(::externalSubtitlesFor) ?: emptyList(),
                startPositionMs = startAt
            )
            jellyfinPlaySession = resolved
            jellyfinPlayingItemId = channel.id
        }
    }

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
        // Never sample while the preview player is buffering - mid-buffer frames are
        // usually black, and a slow stall could streak past the threshold and falsely
        // kill a healthy version. Re-arm and try again once it reaches READY.
        val previewState = previewPlayerManager?.playbackState
        if (previewState == null || previewState == Player.STATE_BUFFERING) {
            mainHandler.postDelayed(previewBlackFrameCheckRunnable, BLACK_FRAME_CHECK_INTERVAL_MS)
            return
        }
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
        // An auto-advanced episode should just start from its beginning - no resume
        // question. Consume the suppression either way so it never leaks into a later,
        // user-initiated play.
        if (skipResumePrompt) {
            skipResumePrompt = false
            return
        }
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
        // Jellyfin episodes carry no series id of their own (toChannel drops it), so stamp
        // the parent series id here - the detail page sets currentSeriesVersionContext for
        // its plays - letting a later Continue Watching click resolve the series page.
        // Movies and live channels are untouched.
        val saveChannel = if (channel.mediaType == MediaType.SERIES) {
            channel.copy(categoryId = channel.categoryId ?: currentSeriesVersionContext?.first?.id)
        } else channel
        PlaybackPositionStore.save(this, key, pos, dur, saveChannel)
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
        // Same for the Series poster shelf and its Continue Watching row.
        refreshSeriesShelvesIfShowing()
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
            val programs = runCatching { client.getShortEpg(chProvider, ch.id, 16) }.getOrDefault(emptyList())
            if (programs.isNotEmpty()) return programs
        }
        return null
    }

    // ── Live TV inline preview ──────────────────────

    private var lastFocusedLiveChannel: Channel? = null

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
        previewTargetChannel = null
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
        if (duration <= 0 || duration - position > UP_NEXT_COUNTDOWN_SECONDS * 1000L) return // more than the countdown window left
        upNextEpisode = currentEpisodeQueue[nextIdx]
        showUpNextOverlay()
    }

    private fun showUpNextOverlay() {
        upNextActive = true
        upNextCountdown = UP_NEXT_COUNTDOWN_SECONDS
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
        skipResumePrompt = true
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

    /** Two-press channel open: first OK opens the channel in the preview pane; a second
     *  OK on the same channel opens it fullscreen. */
    private fun onChannelOkPress(channel: Channel) {
        if (previewTargetChannel?.id == channel.id) {
            playItem(channel)
        } else {
            previewTargetChannel = channel
            requestPreviewLoad(channel)
        }
    }

    /** Debounced so fast D-pad scrolling through the list doesn't spawn a load per row. */
    private fun requestPreviewLoad(channel: Channel) {
        lastFocusedLiveChannel = channel
        previewTargetChannel = channel
        if (activeTab != 0 || isPlayerVisible) return
        if (channel.id.isNotBlank() && channel.id == previewChannelId) return
        previewLoadRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { loadPreview(channel) }
        previewLoadRunnable = runnable
        mainHandler.postDelayed(runnable, 500)
    }

    private fun loadPreview(channel: Channel) {
        if (activeTab != 0 || isPlayerVisible) return
        // A streak must never carry across a new load (or a version switch): start it
        // clean so only this load's own frames can trip the detector.
        previewBlackFrameStreak = 0
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
            // isShown, not visibility: a VISIBLE view inside a GONE parent is not focusable, and
            // requestFocus() on it returns false rather than throwing. Its return value is what
            // says whether focus actually landed - checking visibility alone reported success
            // while nothing had been focused at all.
            fun applyFocus(): Boolean {
                val target = initialFocus?.invoke() ?: return false
                return target.isShown && target.requestFocus()
            }
            // Retried on the next frame, same as showEmptyState()'s focusFirstAction: setup
            // code can hide or reveal the intended target after this post is queued
            // (openIptvForm swaps the provider list for the type picker doing exactly that),
            // and a first attempt that lands too early silently does nothing. What was left
            // behind was the root FrameLayout holding focus - which looks like a normal screen
            // but has no focused control, so the D-pad moves nowhere and nothing can be picked.
            view.post {
                if (!applyFocus()) view.post { if (!applyFocus()) view.requestFocus() }
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

    /** A Filters-pane checkbox with a dimmed caption line under its title - the other filter
     *  toggles carry a single line, but these need the caption to say what the toggle changes
     *  about playback. Wired straight to [key] in the shared "iptv_prefs" file, so PlayerManager
     *  sees the same value (subtitles_with_dub, subtitles_enabled) without any extra plumbing. Styled to match the
     *  static pane rows (hide-adult row's card surface, focus scale, and text hierarchy) so
     *  runtime-added rows don't read as cheaper than their XML siblings. */
    private fun dubCheckBoxRow(title: String, subtitle: String, key: String, onToggle: ((Boolean) -> Unit)? = null): CheckBox {
        val checkBox = CheckBox(this)
        val titleEnd = title.length
        val captionStart = titleEnd + 1 // skip the "\n"
        val text = SpannableStringBuilder(title).append("\n").append(subtitle)
        val bodySize = resources.getDimensionPixelSize(R.dimen.settings_text_body)
        val captionSize = resources.getDimensionPixelSize(R.dimen.settings_text_caption)
        val titleFont = ResourcesCompat.getFont(this, R.font.inter_medium) ?: Typeface.DEFAULT
        val captionFont = ResourcesCompat.getFont(this, R.font.inter_regular) ?: Typeface.DEFAULT
        text.setSpan(FontSpan(titleFont), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(AbsoluteSizeSpan(bodySize), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(FontSpan(captionFont), captionStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(AbsoluteSizeSpan(captionSize), captionStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ForegroundColorSpan(getColor(R.color.text_secondary)), captionStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        checkBox.text = text
        checkBox.setTextColor(getColor(R.color.text_primary))
        checkBox.setBackgroundResource(R.drawable.card_surface_background)
        val hPad = resources.getDimensionPixelSize(R.dimen.settings_gap_l)
        val vPad = resources.getDimensionPixelSize(R.dimen.settings_row_padding_vertical)
        checkBox.setPadding(hPad, vPad, hPad, vPad)
        checkBox.stateListAnimator = AnimatorInflater.loadStateListAnimator(this, R.animator.focus_scale_flat)
        checkBox.isClickable = true
        checkBox.isFocusable = true
        checkBox.isChecked = prefs.getBoolean(key, false)
        checkBox.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
            onToggle?.invoke(checked)
        }
        checkBox.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = resources.getDimensionPixelSize(R.dimen.settings_gap_m)
        }
        return checkBox
    }

    /** Applies a Typeface to a span range independent of the TextView's own typeface - lets a
     *  single two-line TextView carry a medium title over a regular caption. (TypefaceSpan's
     *  Typeface constructor is API 28+, so this hand-rolled span keeps minSdk 25 happy.) */
    private class FontSpan(private val typeface: Typeface) : MetricAffectingSpan() {
        override fun updateMeasureState(textPaint: TextPaint) { textPaint.typeface = typeface }
        override fun updateDrawState(textPaint: TextPaint) { textPaint.typeface = typeface }
    }

    @Suppress("DEPRECATION")
    private fun showProviderSettings() {
        // Already open: unticking the last provider or plugin from inside Settings reloads, and
        // that load's "nothing configured" branch calls straight back in here - which would
        // inflate a second settings tree on top of the live one, leaving the first orphaned
        // behind it and only the second reachable by Back.
        if (activeSettingsOverlay != null) return
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

        // Every place this form shows/hides a section, the view that was holding d-pad focus
        // can be the one going GONE - and a focused view disappearing leaves nothing focused,
        // so the d-pad stops responding entirely. requestFocus() on a view that hasn't been
        // laid out yet no-ops silently, hence the next-frame retry (same shape as
        // showEmptyState()'s focusFirstAction).
        fun focusWhenReady(target: View) {
            fun attempt(): Boolean = target.isShown && target.requestFocus()
            target.post { if (!attempt()) target.post { attempt() } }
        }

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
            // The tapped type card just went GONE (typePicker hidden above), taking focus
            // with it - see focusWhenReady.
            focusWhenReady(typeSummaryChange)
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
            focusWhenReady(typeM3u)
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
            // Cancel (or whatever field was focused) is inside the section just hidden.
            focusWhenReady(addIptvProviderButton)
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
            // Whatever opened this ("+ Add Provider", or a list row's Edit button) just went
            // GONE with the list, so focus has to be handed to the form explicitly. The edit
            // path is already covered by selectType() above; the add path lands on the first
            // type card. Without this, adding a second provider left nothing focused at all -
            // the type cards couldn't be reached and the d-pad did nothing. First run never
            // hit it because openIptvForm(null) runs before the overlay's show(), whose
            // initialFocus falls back to typeM3u.
            if (existing == null) focusWhenReady(typeM3u)
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
                            // The removed row's own Remove button was holding focus and is
                            // gone now - see focusWhenReady.
                            focusWhenReady(addIptvProviderButton)
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
                            focusWhenReady(addIptvProviderButton)
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

        // Dub playback preferences: prefer dub-flagged search results, and keep the
        // sideloaded subtitles on when a stream plays back with its dubbed audio track.
        // Both default off; the subtitles one is read by PlayerManager from the same prefs.
        val filtersPane = dialogView.findViewById<LinearLayout>(R.id.paneFilters)
        filtersPane.addView(dubCheckBoxRow(
            "Prefer dubbed audio",
            "Show dub results first when available",
            PREF_PREFER_DUB_AUDIO
        ))
        filtersPane.addView(dubCheckBoxRow(
            "Subtitles with dubbed audio",
            "Show subtitles on dubbed episodes too",
            PREF_SUBTITLES_WITH_DUB
        ))
        filtersPane.addView(dubCheckBoxRow(
            "Subtitles",
            "Show subtitles on all playback",
            PREF_SUBTITLES_ENABLED
        ))

        // General pane: Simple mode + Disable VOD live here, not under Filters - they shape
        // the whole app (which tabs exist, what gets fetched), not the catalogue filters.
        val generalPane = dialogView.findViewById<LinearLayout>(R.id.paneGeneral)
        lateinit var vodCheckBox: CheckBox
        generalPane.addView(dubCheckBoxRow(
            "Simple mode",
            "Show only Live TV - hides the tab bar so the EPG fills the screen",
            PREF_SIMPLE_MODE
        ) { checked ->
            // Simple mode drives the VOD toggle so the two checkboxes never disagree:
            // on -> VOD disabled (box checked), off -> VOD re-enabled (box unchecked).
            prefs.edit().putBoolean(PREF_DISABLE_VOD, checked).apply()
            vodCheckBox.isChecked = checked
            applySimpleModeUi()
            // Simple mode forces VOD off, so its effective state changed with the toggle.
            vodStateChanged()
        })
        vodCheckBox = dubCheckBoxRow(
            "Disable VOD content",
            "Fetch only live TV from providers - movies and series are hidden everywhere",
            PREF_DISABLE_VOD
        ) { vodStateChanged() }
        generalPane.addView(vodCheckBox)

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
            R.id.navGeneral to R.id.paneGeneral,
            R.id.navAbout to R.id.paneAbout
        ).map { (navId, paneId) -> dialogView.findViewById<View>(navId) to dialogView.findViewById<View>(paneId) }
        fun selectSection(index: Int) {
            navRows.forEachIndexed { i, (row, pane) ->
                row.isSelected = i == index
                pane.visibility = if (i == index) View.VISIBLE else View.GONE
            }
            // A plugin's page is not one of these panes and would otherwise stay up underneath
            // whichever section was just chosen. Selecting Plugins itself is handled by the
            // rail's own listener, which opens either the list or a specific plugin's page.
            openPluginId = null
            dialogView.findViewById<View>(R.id.panePluginDetail)?.visibility = View.GONE
            // Reachable from code, not just a rail click (e.g. onProviderAdded() jumping here
            // after a plugin candidate is added) - without this the D-pad's focus is left on
            // whatever view triggered the jump, which has often just been removed from the
            // tree by the same re-render, leaving nothing focused and the remote stuck.
            navRows[index].first.requestFocus()
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

        wirePluginsPane(dialogView) { selectSection(1) }
        // After wirePluginsPane: the child rows drive the pane through revealPluginInPane,
        // with the plugin list itself left at its previous section.
        wirePluginNavRows(dialogView) { selectSection(8) }

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
        dialogView.findViewById<View>(R.id.settingsDiscordLink).setOnClickListener {
            try {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://discord.gg/lumora")))
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
            applyStatus()
            refreshIptvProviderList = {}
            // Both close over views in the dismissed dialog - holding them past this leaks the
            // whole inflated settings tree and would touch detached views on the next call.
            revealPluginInPane = null
            refreshPluginNavRows = null
            closeOpenPluginPage = null
            openPluginId = null
            liveDiscoveryStatusView = null
            liveDiscoveryCandidateList = null
            liveDiscoveryPlugin = null
            // The tab bar and search are gated on there being something to browse, and
            // classifyAndShow() deliberately skips that check while this overlay is up (it
            // would flip the chrome underneath the dialog). Adding a provider or switching a
            // plugin on is exactly what changes the answer, so re-derive it here - on the
            // non-empty path nothing else did, and the tab bar stayed hidden until the app was
            // restarted. showEmptyState() runs it itself on the other branch.
            //
            // "Nothing to show" is the same question classifyAndShow() asks, and it counts an
            // enabled stream_search plugin as content: a torrent or anime plugin contributes no
            // catalog entries of its own but makes Discover and Find Stream usable. Testing
            // allChannels alone sent a plugin-only setup back to the "no provider" empty state
            // the moment Settings closed, however many plugins had just been switched on.
            val hasPlugin = enabledStreamSearchPlugin() != null
            // Unticking the last provider (or the last plugin) has to take the tab bar and
            // search back down, and land on the empty state - which is the only screen left
            // with a way back into Settings. Asked of the enabled providers rather than of
            // allChannels: disabling one drops its items, but a provider whose channels are
            // still in memory from a cache load would otherwise keep the chrome up with
            // nothing enabled behind it.
            if (!hasProviderEnabled() && !hasPlugin) {
                showEmptyState()
            } else if (allChannels.isEmpty() && !hasPlugin) {
                // Enabled, but it returned nothing (fetch failed, or an empty catalogue).
                showEmptyState()
            } else {
                binding.emptyState.visibility = View.GONE
                updateTopChromeVisibility()
                if (showingHome) selectHome() else if (showingDiscover) selectDiscover() else if (showingDownloads) selectDownloads() else selectTab(activeTab)
            }
        }
        activeSettingsOverlay = dialog
        // The overlay takes the slot now; a load still narrating into it must come down.
        applyStatus()
        dialog.show()

        // The Save button's listener validates and keeps the form open on error instead of
        // dismissing unconditionally. Only acts when the add/edit form is actually open -
        // the same footer button is shared by every nav pane, most of which have nothing
        // for it to save.
        dialogView.findViewById<View>(R.id.settingsSaveButton).setOnClickListener {
            // Save is a footer button shown on every pane, but only the provider add/edit form
            // has anything to commit - everything else (toggles, pickers, PIN) persists as it is
            // changed. It used to return here silently, so on the provider list, or on Playback
            // or Filters or Plugins, pressing Save did nothing whatsoever and looked broken.
            // Closing is what Save means once the work is already saved.
            if (iptvFormSection.visibility != View.VISIBLE) {
                activeSettingsOverlay?.dismiss()
                return@setOnClickListener
            }
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
                            pluginStreamAlreadyResolved = true,
                            audio = result.audio
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

        fun addResultRow(result: TorrentResult, atFront: Boolean) {
            val row = layoutInflater.inflate(R.layout.item_stream_result, resultsHost, false)
            row.findViewById<TextView>(R.id.streamTitle).text = result.title
            row.findViewById<TextView>(R.id.streamMeta).text = listOfNotNull(
                result.quality,
                result.seeders?.let { "$it seeders" },
                result.size,
                result.source
            ).joinToString("  ·  ")
            row.setOnClickListener { playResult(result) }
            if (atFront) resultsHost.addView(row, 0) else resultsHost.addView(row)
            // The first result to arrive takes focus, so the common case - the top result is
            // the one you want - is one press away instead of a hunt down the list. Results
            // stream in one at a time, so this is the first one reported, not a re-focus on
            // every addition: taking focus again mid-search would yank it back off whatever
            // the user had already moved to.
            if (resultsHost.childCount == 1) {
                row.post { row.requestFocus() }
            }
        }

        val searchJob = scope.launch {
            val query = item.name
            val year = item.year?.toIntOrNull()
            val outcome = jsPluginEngine.runSearch(
                source = source, query = query, year = year, season = season, episode = episode,
                onProgress = { if (results.isEmpty()) status.text = it },
                onResult = { result ->
                    // With "Prefer dubbed audio" on, a known-dub source jumps the queue so the
                    // most likely pick surfaces first instead of being buried under the subs.
                    val atFront = prefs.getBoolean(PREF_PREFER_DUB_AUDIO, false) && result.audio == "dub"
                    if (atFront) results.add(0, result) else results.add(result)
                    status.text = "${results.size} result(s)"
                    addResultRow(result, atFront)
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
    private fun wirePluginsPane(dialogView: View, onProviderAdded: () -> Unit = {}) {
        val listContainer = dialogView.findViewById<LinearLayout>(R.id.settingsPluginList)
        val listEmpty = dialogView.findViewById<View>(R.id.settingsPluginListEmpty)
        val manager = pluginScriptManager

        val detailPane = dialogView.findViewById<View>(R.id.panePluginDetail)
        val listPane = dialogView.findViewById<View>(R.id.panePlugins)
        val detailBack = dialogView.findViewById<View>(R.id.pluginDetailBack)
        val detailTitle = dialogView.findViewById<TextView>(R.id.pluginDetailTitle)
        val detailDescription = dialogView.findViewById<TextView>(R.id.pluginDetailDescription)
        val detailMeta = dialogView.findViewById<TextView>(R.id.pluginDetailMeta)
        val detailEnabledRow = dialogView.findViewById<View>(R.id.pluginDetailEnabledRow)
        val detailEnabledBox = dialogView.findViewById<CheckBox>(R.id.pluginDetailEnabled)
        val detailRunButton = dialogView.findViewById<View>(R.id.pluginDetailRunButton)
        val detailRunLabel = dialogView.findViewById<TextView>(R.id.pluginDetailRunLabel)
        val detailUpdateButton = dialogView.findViewById<View>(R.id.pluginDetailUpdateButton)
        val detailUpdateLabel = dialogView.findViewById<TextView>(R.id.pluginDetailUpdateLabel)
        val detailRemoveButton = dialogView.findViewById<View>(R.id.pluginDetailRemoveButton)
        val detailResults = dialogView.findViewById<View>(R.id.pluginDetailResults)
        val detailProgress = dialogView.findViewById<View>(R.id.pluginDetailProgress)
        val detailStatus = dialogView.findViewById<TextView>(R.id.pluginDetailStatus)
        val detailCandidateList = dialogView.findViewById<LinearLayout>(R.id.pluginDetailCandidateList)

        lateinit var renderPluginList: () -> Unit
        lateinit var renderPluginDetail: () -> Unit

        fun openPluginPage(id: String) {
            openPluginId = id
            // Reachable straight from the nav rail's plugin dropdown, bypassing selectSection() -
            // so whichever section pane (e.g. EPG) was showing before has to be hidden here too,
            // or it stays visible underneath this page.
            listOf(
                R.id.paneProviders, R.id.panePlayback, R.id.paneFilters, R.id.panePrivacy,
                R.id.paneBackup, R.id.paneEpg, R.id.paneDownloads, R.id.paneGeneral, R.id.paneAbout
            ).forEach { dialogView.findViewById<View>(it)?.visibility = View.GONE }
            listPane.visibility = View.GONE
            detailPane.visibility = View.VISIBLE
            // Landing on Back rather than nowhere: the page is rebuilt asynchronously, so
            // without this the D-pad has no starting point until the render lands.
            detailBack.requestFocus()
            renderPluginDetail()
        }

        fun closePluginPage() {
            openPluginId = null
            detailPane.visibility = View.GONE
            listPane.visibility = View.VISIBLE
            liveDiscoveryStatusView = null
            liveDiscoveryCandidateList = null
            renderPluginList()
        }
        // Settings always opens on the list, never on whichever plugin was last looked at.
        openPluginId = null
        detailPane.visibility = View.GONE

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
                        // Says so explicitly, because installing no longer switches it on and a
                        // plugin that is installed but does nothing is otherwise a puzzle.
                        val message = if (result.script.enabled) "Added ${result.script.label}"
                            else "Added ${result.script.label} - enable it to use it"
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
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

        fun addCandidateRow(
            candidateList: LinearLayout,
            plugin: PluginScript,
            candidate: DiscoveredProvider
        ) {
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
            // Survives the re-render that follows every discovery progress line - the button is
            // a fresh view each time, but the fact it was already used is not.
            if (candidate.url in pluginDiscoveryAdded) {
                addLabel.text = "Added"
                addButton.isEnabled = false
                addButton.isFocusable = false
            }
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
                        pluginDiscoveryAdded.add(candidate.url)
                        addLabel.text = "Added"
                        addButton.isEnabled = false
                        addButton.isFocusable = false
                        // Rebuild the provider list in the same settings screen so the newly
                        // added provider shows up immediately instead of only after reopening.
                        refreshIptvProviderList.invoke()
                        try {
                            loadAllConfiguredProviders(forceRefresh = true)
                        } catch (_: Exception) {
                            // A malformed candidate (blank URL, missing credentials) can crash
                            // the provider load. The upsert already succeeded; don't let the
                            // crash abort the UI navigation that shows the user where it landed.
                        }
                        // The user was on this plugin's page when they tapped Add; the providers
                        // list they actually want to see is in the Providers pane, so jump there
                        // rather than leaving them staring at the now-empty "Added" button.
                        onProviderAdded()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            candidateList.addView(row)
        }

        fun runDiscovery(plugin: PluginScript) {
            pluginDiscoveryJob?.cancel()
            // A run owns the results area, so anything the previous plugin left there goes -
            // two plugins' candidates in one list would be unattributable.
            pluginDiscoveryPluginId = plugin.id
            pluginDiscoveryCandidates.clear()
            pluginDiscoveryAdded.clear()
            pluginDiscoveryStatus = "Starting ${plugin.label}…"
            liveDiscoveryStatusView = null
            liveDiscoveryCandidateList = null
            liveDiscoveryPlugin = null
            // Run is only reachable from the plugin's own page, and that page is where the
            // results render - so it is already open. Redraw it to show the run starting.
            renderPluginDetail()
            pluginDiscoveryJob = scope.launch {
                val source = manager.readSource(plugin)
                val result = jsPluginEngine.runDiscovery(
                    source,
                    onProgress = { line ->
                        pluginDiscoveryStatus = line
                        liveDiscoveryStatusView?.text = line
                    },
                    onCandidate = { candidate ->
                        pluginDiscoveryCandidates.add(candidate)
                        // Appended to the live list where one exists; otherwise it's still held
                        // in the list above and the render at the end of the run puts it there.
                        liveDiscoveryCandidateList?.let { list ->
                            addCandidateRow(list, liveDiscoveryPlugin ?: plugin, candidate)
                        }
                    }
                )
                val found = pluginDiscoveryCandidates.size
                pluginDiscoveryStatus = when (result) {
                    is DiscoveryResult.Finished ->
                        result.message ?: if (found == 0) "Nothing found" else "Found $found"
                    is DiscoveryResult.Failed -> result.message
                }
                pluginDiscoveryJob = null
                liveDiscoveryStatusView = null
                liveDiscoveryCandidateList = null
                liveDiscoveryPlugin = null
                // The page shows the run; the list behind it shows its outcome in the summary
                // line, so both are redrawn.
                renderPluginDetail()
                renderPluginList()
            }
        }

        // ── The plugin list, and one plugin's own page ──

        fun openPluginDetail(id: String) {
            openPluginPage(id)
        }

        renderPluginList = {
            scope.launch {
                val plugins = manager.discoverScripts()
                listContainer.removeAllViews()
                listEmpty.visibility = if (plugins.isEmpty()) View.VISIBLE else View.GONE
                for (plugin in plugins) {
                    val row = layoutInflater.inflate(R.layout.item_plugin_row, listContainer, false)
                    row.findViewById<TextView>(R.id.pluginName).text = plugin.label
                    row.findViewById<TextView>(R.id.pluginSummary).text = listOfNotNull(
                        if (plugin.enabled) "Enabled" else "Disabled",
                        pluginDiscoveryStatus.takeIf { plugin.id == pluginDiscoveryPluginId }
                    ).joinToString("  ·  ")
                    row.setOnClickListener { openPluginDetail(plugin.id) }
                    listContainer.addView(row)

                    if (plugin.id == pluginFocusRequestId) {
                        pluginFocusRequestId = null
                        pluginFocusRequestViewId = View.NO_ID
                        row.post { row.requestFocus() }
                    }
                }
            }
            Unit
        }

        // Wires the dedicated plugin page against whichever plugin is currently open. Rebuilt
        // rather than bound once: enabling, updating and running all change what it should say,
        // and a discovery run rewrites its results as it goes.
        renderPluginDetail = {
            val id = openPluginId
            if (id != null) scope.launch {
                val plugin = manager.discoverScripts().firstOrNull { it.id == id }
                if (plugin == null) {
                    // Removed from under us - the list is the only sensible place to land.
                    closePluginPage()
                } else {
                    val running = pluginDiscoveryJob?.isActive == true
                    val isRunningPlugin = plugin.id == pluginDiscoveryPluginId

                    detailTitle.text = plugin.label
                    detailDescription.text = plugin.description.orEmpty()
                    detailDescription.visibility =
                        if (plugin.description.isNullOrBlank()) View.GONE else View.VISIBLE
                    detailMeta.text = buildList {
                        if (plugin.supportsDiscovery) add("Provider discovery")
                        if (plugin.supportsStreamSearch) add("Stream search")
                        addAll(plugin.contentTypes)
                    }.joinToString("  ·  ").uppercase(Locale.US)

                    detailEnabledBox.isChecked = plugin.enabled
                    detailEnabledRow.setOnClickListener {
                        manager.setEnabled(plugin.id, !plugin.enabled)
                        pluginFocusRequestViewId = R.id.pluginDetailEnabledRow
                        renderPluginDetail()
                        renderPluginList()
                        refreshPluginNavRows?.invoke()
                        if (plugin.supportsStreamSearch) loadAllConfiguredProviders(forceRefresh = true)
                    }

                    // Run only applies to discovery plugins; a stream_search plugin is driven
                    // from a title's "Find stream" instead.
                    if (plugin.supportsDiscovery) {
                        detailRunButton.visibility = View.VISIBLE
                        detailRunLabel.text = if (running && isRunningPlugin) "Running…" else "Run"
                        // Dimmed but still focusable when it can't be used: setEnabled(false)
                        // takes a View out of focus search entirely, and Run is exactly what the
                        // user is heading for after enabling a plugin, so it has to stay on the
                        // path. The click explains itself instead.
                        detailRunButton.alpha = if (plugin.enabled && !running) 1f else 0.4f
                        detailRunButton.setOnClickListener {
                            when {
                                running -> Toast.makeText(
                                    this@MainActivity, "A plugin is already running", Toast.LENGTH_SHORT
                                ).show()
                                !plugin.enabled -> Toast.makeText(
                                    this@MainActivity, "Enable ${plugin.label} first", Toast.LENGTH_SHORT
                                ).show()
                                else -> runDiscovery(plugin)
                            }
                        }
                    } else {
                        detailRunButton.visibility = View.GONE
                        detailRunButton.setOnClickListener(null)
                    }

                    detailUpdateLabel.text = getString(R.string.update)
                    detailUpdateButton.setOnClickListener {
                        detailUpdateLabel.text = "Updating…"
                        scope.launch {
                            val message = updatePluginFromStore(plugin)
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                            pluginFocusRequestViewId = R.id.pluginDetailUpdateButton
                            renderPluginDetail()
                            renderPluginList()
                            refreshPluginNavRows?.invoke()
                        }
                    }

                    detailRemoveButton.setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Remove ${plugin.label}?")
                            .setMessage("This deletes the installed script. You can reinstall it later from a plugin store or its URL.")
                            .setPositiveButton("Remove") { _, _ ->
                                manager.setEnabled(plugin.id, false)
                                manager.removeUserScript(plugin.fileName)
                                closePluginPage()
                                renderPluginList()
                                refreshPluginNavRows?.invoke()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }

                    // Results are this plugin's own, rebuilt from the state rather than from
                    // whatever views survived - this runs again on every interaction, and a run
                    // may still be in flight while it does.
                    if (isRunningPlugin && pluginDiscoveryStatus != null) {
                        detailResults.visibility = View.VISIBLE
                        detailProgress.visibility = if (running) View.VISIBLE else View.GONE
                        detailStatus.text = pluginDiscoveryStatus
                        detailCandidateList.removeAllViews()
                        for (candidate in pluginDiscoveryCandidates) {
                            addCandidateRow(detailCandidateList, plugin, candidate)
                        }
                        // While a run is live these are what each progress line and candidate is
                        // written into directly - re-rendering the page per line would rebuild
                        // every focusable view under the user.
                        if (running) {
                            liveDiscoveryStatusView = detailStatus
                            liveDiscoveryCandidateList = detailCandidateList
                            liveDiscoveryPlugin = plugin
                        }
                    } else {
                        detailResults.visibility = View.GONE
                    }

                    if (pluginFocusRequestViewId != View.NO_ID) {
                        val target = dialogView.findViewById<View>(pluginFocusRequestViewId)
                        pluginFocusRequestViewId = View.NO_ID
                        target?.post { target.requestFocus() }
                    }
                }
            }
            Unit
        }

        detailBack.setOnClickListener { closePluginPage() }
        closeOpenPluginPage = { closePluginPage() }

        // Lets the nav rail's plugin rows open a plugin's page - see wirePluginNavRows.
        revealPluginInPane = { id -> openPluginDetail(id) }
        renderPluginList()
    }

    /**
     * Re-installs [plugin] from whichever configured store lists its id, and reports what
     * happened as a message for the caller to show.
     *
     * Matched on the manifest id rather than the file name: a store is free to rename its file,
     * and the id is what [PluginScriptManager.installScript] overwrites on, so those two have to
     * agree or an "update" would install a second copy alongside the old one.
     */
    private suspend fun updatePluginFromStore(plugin: PluginScript): String {
        val stores = pluginStoreManager.storeUrls()
        for (store in stores) {
            val catalog = pluginStoreManager.fetchCatalog(store.url).getOrNull() ?: continue
            val entry = catalog.firstOrNull { it.id == plugin.id } ?: continue
            val text = pluginStoreManager.fetchScriptText(entry.fileUrl)
                ?: return "Couldn't download ${plugin.label}"
            // installScript() preserves the stored enabled state, so an update can't switch a
            // plugin the user had turned off back on.
            return when (val result = pluginScriptManager.installScript(text)) {
                is PluginScriptManager.InstallResult.Installed -> "Updated ${result.script.label}"
                is PluginScriptManager.InstallResult.Rejected -> "Update rejected: ${result.reason}"
            }
        }
        return "${plugin.label} isn't in any configured plugin store"
    }

    /**
     * Makes the nav rail's Plugins row a dropdown over the installed plugins. Each child opens
     * the Plugins pane with that plugin's section already expanded and focused, which is where
     * it can be updated or enabled/disabled - the rail itself is navigation, so a child row only
     * reports the enabled state rather than being another place that changes it.
     *
     * This is the reason a discovery plugin is reachable at all on a long list: the Reddit
     * scanner sits near the bottom of the installed plugins, which is several screens down a
     * pane that also holds the install-from-URL card and the store list above it.
     */
    private fun wirePluginNavRows(dialogView: View, openPluginsPane: () -> Unit) {
        val parentRow = dialogView.findViewById<View>(R.id.navPlugins)
        val caret = dialogView.findViewById<TextView>(R.id.navPluginsCaret)
        val children = dialogView.findViewById<LinearLayout>(R.id.navPluginChildren)

        fun render() {
            scope.launch {
                val plugins = pluginScriptManager.discoverScripts()
                children.removeAllViews()
                for (plugin in plugins) {
                    val row = layoutInflater.inflate(R.layout.item_plugin_nav_row, children, false)
                    row.findViewById<TextView>(R.id.pluginNavLabel).text = plugin.label
                    row.findViewById<TextView>(R.id.pluginNavState).text =
                        if (plugin.enabled) "✓" else "○"
                    row.setOnClickListener {
                        openPluginsPane()
                        revealPluginInPane?.invoke(plugin.id)
                    }
                    children.addView(row)
                }
                val hasPlugins = plugins.isNotEmpty()
                children.visibility = if (navPluginsExpanded && hasPlugins) View.VISIBLE else View.GONE
                caret.visibility = if (hasPlugins) View.VISIBLE else View.GONE
                caret.text = if (navPluginsExpanded) "▾" else "▸"
            }
            Unit
        }
        refreshPluginNavRows = { render() }

        // Selecting the parent does both jobs: it opens the pane (what every other rail row
        // does, so the row doesn't behave differently from its neighbours) and expands the list.
        parentRow.setOnClickListener {
            openPluginsPane()
            navPluginsExpanded = !navPluginsExpanded
            render()
        }
        render()
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
                // No enabled-state juggling here: installScript() leaves it alone, so an update
                // keeps whatever the user had chosen and a first install lands switched off.
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
                                    // Same rule as the add-from-URL path: installing puts the
                                    // script on the device but does not switch it on. Enabling
                                    // is a separate, visible act on the plugin's own page - a
                                    // stream_search plugin that is on starts answering Find
                                    // Stream and pulls its catalogue into the Series tab, so a
                                    // store Install tap must not silently do that.
                                    Toast.makeText(
                                        this@MainActivity,
                                        if (outcome.script.enabled) "${storeScript.label} installed"
                                        else "${storeScript.label} installed - enable it to use it",
                                        Toast.LENGTH_LONG
                                    ).show()
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
