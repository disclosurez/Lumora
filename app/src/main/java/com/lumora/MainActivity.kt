package com.lumora

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.os.Build
import android.util.Rational
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.media3.common.Player
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lumora.adapter.CategoryAdapter
import com.lumora.adapter.DownloadAdapter
import com.lumora.adapter.LiveGuideAdapter
import com.lumora.adapter.PosterGridAdapter
import com.lumora.adapter.SearchResultItem
import com.lumora.adapter.ShelfAdapter
import com.lumora.adapter.SideMenuCategoryAdapter
import com.lumora.cache.ReminderStore
import com.lumora.cache.FavoritesStore
import com.lumora.model.CategoryFilter
import com.lumora.databinding.ActivityMainBinding
import com.lumora.model.Channel
import com.lumora.model.ContentShelf
import com.lumora.model.MediaType
import com.lumora.model.Provider
import com.lumora.model.IptvProviderConfig
import com.lumora.pairing.QrPairingManager
import com.lumora.plugin.DiscoveredProvider
import com.lumora.plugin.js.JsPluginEngine
import com.lumora.plugin.js.PluginScript
import com.lumora.plugin.js.PluginScriptManager
import com.lumora.plugin.js.PluginStoreManager
import com.lumora.torrent.TorrentEngine
import com.lumora.torrent.TorrentForegroundService
import com.lumora.anime.AnimeCatalogClient
import com.lumora.player.PlayerManager
import com.lumora.player.PlayerTrackController
import com.lumora.util.isTvDevice
import com.lumora.util.groupDuplicateSeries
import com.lumora.data.local.LumoraDatabase
import com.lumora.data.sync.EpgSyncWorker
import com.lumora.data.backup.BackupManager
import com.lumora.data.remote.jellyfin.JellyfinProvider
import com.lumora.player.playback.PlayerDiagnostics
import com.lumora.data.update.AppUpdateChecker
import com.lumora.data.update.AppUpdateInstaller
import com.lumora.player.playback.AvOffsetManager
import kotlinx.coroutines.*

internal const val PREF_HIDE_NON_ENGLISH = "hide_non_english_vod"
internal const val PREF_HIDE_ADULT = "hide_adult_categories"
// Dub handling: prefer dub-flagged search results, and keep sideloaded subtitles on when a
// stream plays back with its dubbed audio track (both default off).
internal const val PREF_PREFER_DUB_AUDIO = "prefer_dub_audio"
internal const val PREF_SUBTITLES_WITH_DUB = "subtitles_with_dub"
// Sidecar subtitles are opt-in: off by default, and PlayerManager reads this to decide
// whether DEFAULT-flagged subtitle tracks auto-select on playback.
internal const val PREF_SUBTITLES_ENABLED = "subtitles_enabled"
/** Preferred subtitle language, as an ISO 639-1 code. Drives which track is picked when
 *  subtitles are on, and which forced track is allowed through when they're off. Read by
 *  PlayerManager straight from the same prefs file. */
internal const val PREF_SUBTITLE_LANGUAGE = "subtitle_language"
/** Preferred audio language, ISO 639-1. Applied to VOD only - a live channel's own audio is
 *  the point of it. Read by PlayerManager from the same prefs file. */
internal const val PREF_AUDIO_LANGUAGE = "audio_language"
/** Language code -> display name for the audio/subtitle pickers. Ordered by how often these
 *  turn up as tracks in IPTV catalogues rather than alphabetically. */
internal val PLAYBACK_LANGUAGES = listOf(
    "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German",
    "it" to "Italian", "pt" to "Portuguese", "nl" to "Dutch", "pl" to "Polish",
    "ru" to "Russian", "tr" to "Turkish", "ar" to "Arabic", "hi" to "Hindi",
    "zh" to "Chinese", "ja" to "Japanese", "ko" to "Korean", "sv" to "Swedish",
    "no" to "Norwegian", "da" to "Danish", "fi" to "Finnish", "el" to "Greek",
    "ro" to "Romanian", "cs" to "Czech", "hu" to "Hungarian"
)
/** UI (interface) language of the whole app. "system" follows the device language; any other
 *  value is an ISO 639-1 code with a matching res/values-<code>/ directory. Picking a language
 *  also cascades into the audio/subtitle track preferences and the TMDB API language, so a
 *  French UI picks French audio, subtitles and metadata by default too. */
internal const val PREF_UI_LANGUAGE = "ui_language"
/** The language picker's choices: native display names, so the list reads the same no matter
 *  which language the interface is currently in. Adding a language = add the matching
 *  res/values-<code>/ (translating every strings file under values/) plus one row here. */
internal val UI_LANGUAGES = listOf(
    "system" to "System default",
    "en" to "English",
    "fr" to "Français",
    "de" to "Deutsch",
    "es" to "Español",
    "it" to "Italiano",
    "sv" to "Svenska",
    "fi" to "Suomi",
    "pt" to "Português",
    "tr" to "Türkçe",
    "hr" to "Hrvatski",
    "el" to "Ελληνικά",
    "ru" to "Русский",
    "ar" to "العربية",
    "ur" to "اردو",
    "zh" to "中文",
    "ja" to "日本語",
    "hi" to "हिन्दी",
    "nl" to "Nederlands",
    "pl" to "Polski",
    "ko" to "한국어",
    "ro" to "Română",
    "no" to "Norsk",
    "da" to "Dansk",
    "cs" to "Čeština",
    "hu" to "Magyar",
    "id" to "Indonesia",
    "uk" to "Українська"
)
internal const val PREF_PARENTAL_PIN = "parental_pin"
/** Package of the video app external playback always uses; absent = ask each time. */
internal const val PREF_EXTERNAL_PLAYER_PACKAGE = "external_player_package"
/** Whether the app may offer to hand a stream over when it cannot play it properly. */
internal const val PREF_SUGGEST_EXTERNAL_PLAYER = "suggest_external_player"
internal const val PREF_ASPECT_MODE = "player_aspect_mode"
internal const val PREF_CLASSIC_CATEGORY_LAYOUT = "classic_category_layout"
internal const val PREF_SIMPLE_MODE = "simple_mode"
internal const val PREF_DISABLE_VOD = "disable_vod"
// Catalogue presentation toggles (all default ON = enabled behavior): dynamic sidebar
// categories on Live (genres/brand clusters) vs Films/Series (genres/service clusters),
// and quality/duplicate merging across all three tabs.
internal const val PREF_CATEGORIZE_LIVE = "categorize_live"
internal const val PREF_CATEGORIZE_VOD = "categorize_vod"
internal const val PREF_GROUP_CHANNELS = "group_channels"
// When the catalog was last fetched from the network; the cache serves every launch until
// this is CATALOG_TTL_MS old (a provider change force-refreshes regardless).
internal const val PREF_CATALOG_REFRESHED_AT = "catalog_refreshed_at"
internal const val CATALOG_TTL_MS = 24 * 60 * 60 * 1000L
// How long a channel's stored guide is served without re-checking the provider. Short EPG
// covers the next few hours, so a few hours of reuse is the useful window - long enough that
// relaunching the app doesn't re-fetch, short enough that same-day schedule changes land.
internal const val EPG_DISK_TTL_MS = 6 * 60 * 60 * 1000L
// Finished programmes are kept briefly so a guide that's mid-render doesn't lose the block
// the user is currently watching.
internal const val EPG_PRUNE_GRACE_SECONDS = 2 * 60 * 60L
// How far ahead a stored guide has to still reach to be worth serving. Age alone is the
// wrong test: a channel fetched at 17:00 is only hours old at 20:00, but most of what was
// fetched has already aired, so serving it fills the first slot of the timeline and leaves
// the rest of the row empty. The guide grid draws about three hours, so anything covering
// less than four is re-fetched instead.
internal const val EPG_MIN_COVERAGE_SECONDS = 4 * 60 * 60L
/** Per-provider ceiling on a catalogue fetch. Deliberately far above what a healthy provider
 *  needs: this exists to stop a *dead* entry starving the providers queued behind it, not to
 *  discipline a slow one. A real portal measured here streams 67MB of live channels in 4s and
 *  then pages VOD and series 14 items at a time - two minutes was inside that envelope, and
 *  because a timeout fails the whole provider it threw away the 51,545 live channels it had
 *  already fetched along with the rest. An unreachable host is now identified in seconds by
 *  isRetryable()/hostUnreachable, so this only has to be an outer backstop. */
internal const val PROVIDER_FETCH_TIMEOUT_MS = 360_000L

// Free-TV/IPTV: a community-maintained list of publicly available free-to-air streams.
// Used by the empty state's "Try the Demo" so the app can be exercised before any
// credentials exist. Nothing else references it - it is an ordinary M3U url handed to the
// ordinary M3U provider path, not a special-cased content source.
// Generic User-Agent for stream HTTP requests.
internal const val STREAM_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
internal const val FAVOURITES_CATEGORY_ID = "__favourites__"
/** Films/Series sidebar row pooling the tab's most recent releases by date - mirrors the
 *  "Newest" content shelf that already led the Films/Series poster. */
internal const val NEWEST_CATEGORY_ID = "__newest__"
/** Series sidebar row listing in-progress series - mirrors the Home "Continue Watching"
 *  shelf, filtered to series entries. Renders its own grid because the episodes it carries
 *  are not seriesList members (a grid-filter on seriesList would come up empty). */
internal const val CONTINUE_WATCHING_CATEGORY_ID = "__continue_watching__"
internal const val CLASSIC_LAYOUT_TOGGLE_ID = "__classic_layout_toggle__"
/** Sidebar utility row that collapses the category rail; persisted so the rail stays
 *  collapsed across launches. */
internal const val COLLAPSE_CATEGORIES_TOGGLE_ID = "__collapse_categories__"
internal const val PREF_CATEGORY_SIDEBAR_COLLAPSED = "category_sidebar_collapsed"
/** Persisted collapse state for the Settings nav rail (landscape/TV). Portrait phones use
 *  the transient [MainActivity.portraitSettingsRailExpanded] instead - see
 *  isSettingsRailCollapsed() in MainActivitySettings.kt. */
internal const val PREF_SETTINGS_RAIL_COLLAPSED = "settings_nav_rail_collapsed"
/** Rows that act on the rail itself rather than filtering it. They must never be hideable:
 *  hiding one is unrecoverable, since the only way to unhide a row is the context menu on
 *  that same row. The hidden-id filter in buildCategoryRows skips these too, so anyone who
 *  already hid one gets it back. */
internal val UTILITY_ROW_IDS = setOf(CLASSIC_LAYOUT_TOGGLE_ID, COLLAPSE_CATEGORIES_TOGGLE_ID)
/** Films/Series sidebar row that filters the tab down to Jellyfin-sourced items only.
 *  Only built when the tab actually contains Jellyfin content. */
internal const val JELLYFIN_CATEGORY_ID = "__jellyfin__"
/** Series sidebar row for the plugin-gated anime catalog. Expandable: its children are the
 *  catalog's sections (Trending Now, Currently Airing, one per genre, ...). Built explicitly
 *  rather than derived from the channels' own category name, because anime titles carry a
 *  single "Anime" category and the sections they belong to overlap. */
internal const val ANIME_CATEGORY_ID = "__anime__"
/** Films/Series sidebar row collecting every category too thin to be worth its own row, so
 *  the long tail of near-empty categories costs one line instead of a dozen. Expandable -
 *  the categories themselves are its children. */
internal const val OTHER_CATEGORY_ID = "__other__"
// Live TV sidebar leads with these dynamic buckets (Sports/News/Music/Cinema),
// each vacuuming up every matching provider category *and* brand cluster
// regardless of where it lives in the raw catalog; everything left over cascades
// below in the usual priority/alpha order, same as before this existed.
internal val LIVE_DYNAMIC_BUCKETS = listOf(
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
internal val VOD_DYNAMIC_BUCKETS = listOf(
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
internal const val STALL_LONG_MS = 15_000L
internal const val STALL_WINDOW_MS = 45_000L
internal const val STALL_COUNT_THRESHOLD = 3

// A dead IPTV feed sometimes never stalls or errors at all - the server just serves a
// technically-valid, steadily-decoding encode of a blank black frame instead. Neither
// onPlayerError nor the buffer-stall watchdog above ever fires for that case, so the
// actual rendered surface gets sampled periodically and sustained near-black output is
// treated as a dead feed too.
internal const val BLACK_FRAME_INITIAL_DELAY_MS = 3_000L
internal const val BLACK_FRAME_CHECK_INTERVAL_MS = 2_000L
internal const val BLACK_FRAME_LUMA_THRESHOLD = 10 // 0-255 average brightness
internal const val BLACK_FRAME_STREAK_THRESHOLD = 2
internal const val DEAD_STREAM_COOLDOWN_MS = 60 * 60 * 1000L
// Dead marks, persisted so a cooldown survives the app being closed and reopened.
internal const val PREF_DEAD_STREAMS = "dead_streams_until"
// How long a freshly-tuned stream is exempt from stall/black-frame failover. Startup and a
// channel change both have a slow first buffer fill; without this the app walks the whole
// version group in the first few seconds and marks each one dead for DEAD_STREAM_COOLDOWN_MS,
// so the best version stays skipped for hours afterwards.
internal const val FAILOVER_GRACE_MS = 12_000L

// Phone touch gestures on the player: double-tap seek step and pinch-zoom range.
internal const val GESTURE_SEEK_MS = 10_000L
internal const val ZOOM_MIN = 1.0f
internal const val ZOOM_MAX = 3.0f

class MainActivity : AppCompatActivity() {

    internal lateinit var binding: ActivityMainBinding
    internal lateinit var playerManager: PlayerManager
    internal lateinit var castManager: com.lumora.player.CastManager
    /** `::castManager.isInitialized` reads the backing field, which only the declaring class
     *  can do - sibling files (MainActivityPlayer) go through this instead. */
    internal val isCastManagerReady: Boolean get() = ::castManager.isInitialized
    internal lateinit var prefs: SharedPreferences

    /** "system" or an ISO 639-1 code — the single source of truth for the whole app's
     *  language. Read wherever a context without an Activity to ask is needed (TMDB tag). */
    internal fun uiLanguageCode(): String = prefs.getString(PREF_UI_LANGUAGE, "system") ?: "system"

    /** TMDB wants an xx-XX tag, not a bare xx code. "system" maps to the current locale so
     *  Discover metadata follows the device language when no explicit override is set. */
    internal fun tmdbLanguageTagFor(code: String): String = when (code) {
        "system" -> java.util.Locale.getDefault().toLanguageTag().takeIf { it.length in 2..5 } ?: "en-US"
        else -> if (code.contains("-")) code else "${code.lowercase()}-${code.uppercase()}"
    }

    /** Applies [code] to the running process (recreating the activity), and cascades it into
     *  the audio/subtitle track prefs PlayerManager reads and the TMDB API language. The pref
     *  write happens first so a recreation that lands mid-cascade still sees the new value. */
    internal fun onUiLanguageSelected(code: String) {
        prefs.edit().putString(PREF_UI_LANGUAGE, code).apply()
        // Audio/subtitle defaults follow the UI language; the General-pane pickers remain for
        // per-track overrides. "system" leaves them untouched - there's no single code to set.
        if (code != "system") {
            prefs.edit()
                .putString(PREF_SUBTITLE_LANGUAGE, code)
                .putString(PREF_AUDIO_LANGUAGE, code)
                .apply()
        }
        applyUiLanguageOverride(code)
    }

    /** Applies the persisted UI language via AppCompatDelegate (per-app languages, works all
     *  the way down to the minSdk). An empty list clears any override and follows the device. */
    internal fun applyUiLanguageOverride(code: String) {
        val locales = if (code == "system" || code.isBlank()) LocaleListCompat.getEmptyLocaleList()
        else LocaleListCompat.forLanguageTags(code)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    internal lateinit var playerDiagnostics: PlayerDiagnostics
    internal lateinit var database: LumoraDatabase
    internal lateinit var speedController: com.lumora.player.playback.PlaybackSpeedController
    internal lateinit var sleepTimer: com.lumora.player.playback.SleepTimer
    internal val trackController = PlayerTrackController()
    internal val qrManager by lazy { QrPairingManager(this) }
    internal var activeSettingsOverlay: FullScreenOverlay? = null
    /** Set by showProviderSettings to its local renderIptvProviderList, so the plugin-discovery
     *  pane (a sibling scope in the same settings screen) can refresh the provider list after it
     *  adds a discovered provider - otherwise the new provider is saved but the list stays stale. */
    internal var refreshIptvProviderList: () -> Unit = {}
    internal var activeSearchOverlay: FullScreenOverlay? = null
    /** The Live TV tab's dropdown (Live TV / Catch Up) while it is open, so a pane switch
     *  or a second press on the tab can close it instead of leaving it hanging over the
     *  screen it no longer belongs to. */
    internal var liveTabMenu: android.widget.PopupWindow? = null

    // Live TV inline preview: a separate, muted player instance so browsing the
    // channel list doesn't touch the main PlayerManager used for fullscreen playback.
    internal var previewPlayerManager: PlayerManager? = null
    internal var previewChannelId: String? = null
    // The channel the user last committed to the preview pane (first OK press, or any
    // auto-load). A second OK on the same channel opens it fullscreen.
    internal var previewTargetChannel: Channel? = null
    internal var previewLoadRunnable: Runnable? = null
    internal var previewVersionGroup: List<Channel> = emptyList()
    internal var previewVersionIndex = 0
    internal var previewBlackFrameStreak = 0
    internal val previewBlackFrameCheckRunnable = Runnable { checkForPreviewBlackFrame() }

    internal var allChannels = listOf<Channel>()
    internal var liveChannels = listOf<Channel>()
    internal var seriesList = listOf<Channel>()
    internal var filmList = listOf<Channel>()
    internal var filmVersions: Map<String, List<Channel>> = emptyMap()
    // Duplicate series copies keyed by the representative's id - unlike films these aren't
    // alternate streams of one item, they're each provider's own separate episode list
    // (see groupDuplicateSeries), so the detail screen switches between them rather than
    // playing one directly.
    internal var seriesVersions: Map<String, List<Channel>> = emptyMap()
    internal var liveVersions: Map<String, List<Channel>> = emptyMap()
    internal var filmShelves: List<ContentShelf> = emptyList()
    internal var seriesShelves: List<ContentShelf> = emptyList()
    /** The Series sidebar's category rows, cached at derive time so refreshSeriesShelvesIfShowing()
     *  can rebuild the Series shelf list (favourites/newest/continue move after playback) without
     *  re-running the expensive buildCategoryRows() pass. */
    internal var cachedSeriesCategoryRows: List<CategoryFilter> = emptyList()
    internal var currentVersionGroup: List<Channel> = emptyList()
    internal var currentVersionIndex = 0
    /** The series a currently-playing episode came from, paired with every provider's copy of
     *  that series (see seriesVersions). An episode Channel carries no link back to its show,
     *  so the in-player version picker can't find the alternatives without this. */
    internal var currentSeriesVersionContext: Pair<Channel, List<Channel>>? = null
    internal var bufferingStartMs = 0L
    // When the current stream was handed to the player, and whether it ever reached READY -
    // the two things every automatic failover has to know before it condemns a stream.
    internal var currentStreamStartMs = 0L
    internal var currentStreamPlayed = false
    internal val stallTimestamps = mutableListOf<Long>()
    internal val longStallCheckRunnable = Runnable { attemptBufferFailover() }
    internal var blackFrameStreak = 0
    internal val blackFrameCheckRunnable = Runnable { checkForBlackFrame() }
    // Keyed by stream key (id, or url when id is blank) - a version that just failed over
    // out of is skipped by both fullscreen and preview auto-pick/failover for a cooldown
    // window instead of being retried again a few seconds later.
    internal val deadStreamUntil = mutableMapOf<String, Long>()

    internal var provider: Provider = Provider()
    // Every configured Xtream provider, keyed by IptvProviderConfig.id - detail/EPG calls
    // resolve the right one per-Channel via Channel.sourceProviderId instead of assuming
    // whichever Xtream provider loaded last (the old single `provider` field above).
    internal var xtreamProviderConfigs: Map<String, IptvProviderConfig> = emptyMap()
    /** IptvProviderConfig id -> display name, for showing which provider an item came from. */
    internal var providerNamesById: Map<String, String> = emptyMap()
    /** The in-flight plugin discovery run, if any - one at a time, cancelled when Settings closes. */
    internal var pluginDiscoveryJob: Job? = null
    /** The plugin whose page is open in Settings > Plugins, or null on the list. Held here
     *  rather than on the views because the page is rebuilt from scratch on any change (enable,
     *  update, remove, a discovery run's progress), and it has to know what it is showing. */
    internal var openPluginId: String? = null
    /** Returns from an open plugin page to the plugin list. Held so Back can go up one level
     *  inside Settings instead of closing the whole overlay from two screens deep. */
    internal var closeOpenPluginPage: (() -> Unit)? = null
    /** The plugin whose run output the rows below belong to, and that output. Same reason as
     *  above: a re-render must be able to put the results back where they were, so they live
     *  outside the views. Cleared when a different plugin is run. */
    internal var pluginDiscoveryPluginId: String? = null
    internal var pluginDiscoveryStatus: String? = null
    internal val pluginDiscoveryCandidates = mutableListOf<DiscoveredProvider>()
    /** Candidate URLs already added as providers, so a re-render keeps showing "Added" rather
     *  than offering to add the same one twice. */
    internal val pluginDiscoveryAdded = mutableSetOf<String>()
    /** The views of the currently-running plugin's results block, so a progress line or a new
     *  candidate can be written straight into them. Re-rendering the whole pane per line would
     *  rebuild every row - and every row is focusable, so it would also move the user's focus
     *  mid-run. Null while nothing is running, or before the row exists. */
    internal var liveDiscoveryStatusView: TextView? = null
    internal var liveDiscoveryCandidateList: LinearLayout? = null
    internal var liveDiscoveryPlugin: PluginScript? = null
    /** Which plugin's row, and which view inside it, should take focus once the plugin list is
     *  next rebuilt. Every interaction in that pane re-renders the whole list, which destroys
     *  the view the user was on - without this, ticking Enabled dropped focus out of the
     *  section entirely and there was no way to reach Run below it. */
    internal var pluginFocusRequestId: String? = null
    internal var pluginFocusRequestViewId: Int = View.NO_ID
    /** Opens a plugin's section in the Plugins pane and puts focus on it. Set by
     *  [wirePluginsPane] while the settings overlay is up, so the nav rail's plugin rows can
     *  drive the pane. Null when settings isn't open. */
    internal var revealPluginInPane: ((String) -> Unit)? = null
    /** What the last setStatus() asked for, kept because whether it can actually be shown
     *  depends on screen state that changes after the fact - see applyStatus(). */
    internal var statusText = ""
    internal var statusWanted = false
    /** Whether the nav rail's Plugins row is showing its installed-plugin children. */
    internal var navPluginsExpanded = false
    /** Rebuilds those child rows - the pane calls it after anything that changes a plugin's
     *  enabled state or removes one, so the rail doesn't go stale behind it. */
    internal var refreshPluginNavRows: (() -> Unit)? = null
    /** Installed JS plugin scripts - see PluginScriptManager. Discovered once at startup and
     *  refreshed whenever Settings > Plugins is opened. */
    internal val pluginScriptManager by lazy { PluginScriptManager(this, prefs) }
    internal val pluginStoreManager by lazy { PluginStoreManager(prefs) }
    internal val jsPluginEngine by lazy { JsPluginEngine() }
    /** Backs whatever's currently playing via a resolvesNatively plugin - the
     *  local HTTP server it owns must stay alive for the life of playback. See showStreamSearchDialog. */
    internal var activeTorrentSession: TorrentEngine? = null
    /** The film/series whose detail page a VOD playback was started from, so backing out of the
     *  player returns to that poster rather than dumping the user in the grid they had to walk
     *  to reach it. Set right before showPlayerFor by every detail-originated play path, and
     *  consumed (and cleared) by hidePlayer. Null for live TV and for anything played straight
     *  from a shelf, which have no detail page behind them. */
    internal var detailReturnItem: Channel? = null
    /** The version group [detailReturnItem] was opened with, so re-opening its detail page shows
     *  the same set of alternate versions/episodes rather than re-deriving a narrower one. */
    internal var detailReturnGroup: List<Channel>? = null
    /** Embed hosts from the last scraper source lookup (see MainActivityScraper). Held because a
     *  source that fails to resolve puts the same list back rather than closing the picker -
     *  picking a different host is the normal recovery, and re-fetching the list to do it would
     *  mean a second round trip to the site for something already known. */
    internal var lastScraperServers: List<com.lumora.scraper.models.Video.Server> = emptyList()
    /** Section membership from the last anime catalog fetch (Trending Now, Action, ...), used to
     *  build the Series sidebar's Anime parent and its child rows. A title belongs to several
     *  sections at once, so these are ids into the tab's channel list, not separate channels. */
    internal var animeSections: List<AnimeCatalogClient.Section> = emptyList()
    // Kept around after a successful Jellyfin content load so a series' detail page can
    // fetch its episodes without re-authenticating - Jellyfin's episode API has no
    // Xtream equivalent, so this is the only path a Jellyfin series' episodes ever load through.
    internal var jellyfinClient: JellyfinProvider? = null
    internal var currentIndex = -1
    // Which episode queue (if any) is currently playing, so Next/Prev and
    // auto-advance-on-end know what "next episode" means. -1 = not playing an episode.
    internal var currentEpisodeQueue: List<Channel> = emptyList()
    internal var currentEpisodeQueueIndex: Int = -1
    internal var isPlayerVisible = false
    internal var isContentDetailVisible = false
    /** Channel id of the item whose detail screen is open, so closing it can return focus to
     *  the poster it was opened from rather than to the tab bar. */
    internal var detailReturnItemId: String? = null
    internal var nowShowingDetailId: String? = null
    /** Category drill-down inside the player side menu (Live/Series/Films section rows). */
    internal lateinit var sideMenuCategoryAdapter: SideMenuCategoryAdapter
    internal var sideMenuCategoriesExpanded = false
    /** Which content section (0 Live / 1 Series / 2 Films) the flown-out column belongs to -
     *  not necessarily the tab on screen: every section row opens its own categories. */
    internal var sideMenuExpandedTab = 0
    /** Set while the column is a step deeper, listing this live category's channels. */
    internal var sideMenuChannelCategory: CategoryFilter? = null
    internal var sideMenuChannelRows: List<Channel> = emptyList()
    /** True while the column's list is mid-swap between levels - see onSideMenuCategoryClicked. */
    internal var sideMenuColumnBusy = false
    /** Per-tab category rows, built once per player session (the non-active tabs aren't in
     *  the browsing sidebar, so they'd otherwise be rebuilt on every expand). */
    internal val sideMenuCategoryCache = mutableMapOf<Int, List<CategoryFilter>>()
    /** In-flight fly-out/fly-in of the category column's width - cancelled before a new
     *  one starts so a fast expand/collapse can't leave the column stuck mid-width. */
    internal var sideMenuCategoryWidthAnimator: android.animation.ValueAnimator? = null
    /** The season chip matching the episode list currently on screen - where UP from the
     *  list's first row lands. Kept pointed at the *selected* chip (updated on every season
     *  change) because default focus search would otherwise pick whichever chip is
     *  geometrically nearest, which can be a different season entirely. */
    internal var selectedSeasonChip: View? = null
    internal var activeTab = 0
    // Live TV is the landing screen: this is a TV app first, and Home's shelves are only
    // meaningful once there's watch history to fill them. The first render after a catalog
    // load routes on this flag (see the tail of classifyAndShow).
    internal var showingHome = false
    internal var showingDownloads = false
    internal var showingDiscover = false
    /** Catch Up is a pane of its own rather than a fourth catalogue tab: it browses the
     *  same live channels through a different axis (time), and every tab-indexed path
     *  (activeTab 0/1/2, its prefs, its category rail) would otherwise need a fourth case
     *  that means nothing. Mirrors showingHome/showingDiscover/showingDownloads. */
    internal var showingCatchup = false
    internal var catchupStage = CatchupStage.CHANNELS
    internal var catchupChannel: Channel? = null
    /** Local midnight of the day being listed, ms. 0 while no day is chosen. */
    internal var catchupDayStart = 0L
    internal var catchupEpgJob: Job? = null
    /** One adapter per column. Separate instances rather than one re-submitted list: each
     *  column keeps its own selection highlight and its own sideways focus targets. */
    internal var catchupCategoryName: String? = null
    internal val catchupCategoryAdapter = com.lumora.adapter.CatchupAdapter { row -> onCatchupCategoryClick(row) }
    internal val catchupChannelAdapter = com.lumora.adapter.CatchupAdapter { row -> onCatchupChannelClick(row) }
    internal val catchupDayAdapter = com.lumora.adapter.CatchupAdapter { row -> onCatchupDayClick(row) }
    internal val catchupProgrammeAdapter = com.lumora.adapter.CatchupAdapter { row -> playCatchup(row) }
    internal val isTv by lazy { isTvDevice(this) }
    /** Phone portrait auto-hides the category rail (see isSidebarCollapsed): the screen is
     *  too narrow to carry the rail plus a usable content column. Manually re-expanding it
     *  flips this for the current portrait session only - it is deliberately not persisted,
     *  and resets on every rotation back into portrait, so portrait always opens hidden. */
    internal var portraitSidebarExpanded = false
    /** Same transient for the Settings nav rail: a portrait phone auto-hides it (see
     *  isSettingsRailCollapsed()), and manually re-expanding flips this for the current
     *  portrait session only - deliberately not persisted, and reset on every rotation
     *  back into portrait, mirroring [portraitSidebarExpanded]. */
    internal var portraitSettingsRailExpanded = false
    /** Last tabWantsSidebar passed to applySidebarVisibility, so a rotation can re-apply the
     *  rail's visibility without re-deriving the tab's category state. */
    internal var lastTabWantsSidebar = false
    // Edge-swipe-to-back tracking (phone only - see dispatchTouchEvent). Only armed when
    // the gesture *starts* within EDGE_SWIPE_ZONE_DP of the left edge, so it can't be
    // confused with the horizontal shelf/episode-row scrolling used throughout the UI.
    private var edgeSwipeTracking = false
    private var edgeSwipeStartX = 0f
    private var edgeSwipeStartY = 0f
    internal var selectedCategoryIds: Set<String>? = null
    internal var selectedBrandChannelIds: Set<String>? = null
    internal var selectedRowId: String? = null
    internal var selectedCategoryLabel: String? = null
    // "See All" on a Films/Series shelf header - shows that exact shelf's items in the
    // grid, bypassing the sidebar's category-id matching entirely (a shelf's grouping by
    // exact category name doesn't necessarily line up with the sidebar's merged rows).
    // Takes priority over selectedCategoryIds in applyCategoryFilter when set.
    internal var selectedShelfItems: List<Channel>? = null
    /** Bumped by every applyCategoryFilter() run. Each run filters the whole catalog on a
     *  background dispatcher, and nothing cancels the run a fast category switch just
     *  superseded - two in flight can resume in either order, so without this the *older*
     *  filter can be the one that submits last and leaves the previous category's items
     *  (or nothing) on screen. A run whose generation is stale on resume drops its result. */
    internal var categoryFilterGeneration = 0
    internal val expandedGroupKeys = mutableSetOf<String>()
    /** Set while the search overlay is open. Receives a typed character, or null for
     *  backspace, from a real keyboard - the query field itself isn't focusable. */
    internal var searchKeyHandler: ((String?) -> Unit)? = null
    /** Child rows for every expandable sidebar parent, from the last category build. Lets
     *  expanding a row splice its children in rather than rerunning the whole build, which
     *  rescans every channel in the tab. Refreshed on every build, so it can't outlive the
     *  catalog/filters it was derived from. */
    internal var categoryChildrenCache: Map<String, List<CategoryFilter>> = emptyMap()
    internal var nowPlayingChannel: Channel? = null
    /** Id of the completed HLS download currently being played back from its offline cache.
     *  Matched against the channel about to play so the offline data source can only ever be
     *  applied to that one item - a stale value cannot silently starve a live stream of its
     *  network source. Cleared once playback is set up. */
    internal var offlineHlsPlaybackId: String? = null
    /** One external-player offer per stream: the undecodable-audio check and the error path
     *  can both fire for the same tune, and two dialogs for one problem is worse than none.
     *  Reset by beginStreamAttempt(). */
    internal var externalPlayerSuggestedForStream = false
    internal var resumePromptShown = false
    /** Set right before an auto-advanced episode starts so its STATE_READY does not throw a
     *  "Resume playback?" dialog at the top of a brand-new episode; consumed and cleared in
     *  maybeShowResumePrompt, and cleared again by every user-initiated play entry point so a
     *  stale value (playback errored before STATE_READY) never suppresses a real prompt. */
    internal var skipResumePrompt = false
    internal var progressTickCount = 0

    // ── Jellyfin server-side state ──────────────
    /** The server's own Continue Watching / Next Up, refreshed with the catalog. Kept apart
     *  from [allChannels] because these are ordered *views* of items already in the catalog,
     *  not extra content - merging them in would duplicate every partly-watched title. */
    internal var jellyfinResumeItems: List<Channel> = emptyList()
    internal var jellyfinNextUpItems: List<Channel> = emptyList()

    // ── Up-next series (Continue Watching extension) ──
    /** Bounded count of series whose episodes we'll fetch per Home build to surface
     *  "next episode" tiles - each is one network call, and the catalog is cache-first
     *  by design. */
    internal val MAX_UP_NEXT_SERIES = 6
    /** seriesId -> resolved next-episode tile. Null value = resolved but no next episode
     *  (fully watched / no seasons) - memoized so Home rebuilds don't refetch it. */
    internal val upNextTiles = LinkedHashMap<String, Channel?>()
    /** seriesId currently being fetched, so Home rebuilds don't stack duplicate fetches. */
    internal val upNextFetching = mutableSetOf<String>()
    /** Bumped on every clearUpNextMemo; in-flight fetches snapshot it and discard their
     *  results if it moved - a fetch launched before a watched-state change must not write
     *  pre-change tiles after the memo was reset. */
    internal var upNextEpoch = 0
    /** next-episode id -> full cross-season episode chain, so clicking an up-next tile
     *  plays with auto-advance instead of as a lone episode. */
    internal val upNextQueues = HashMap<String, List<Channel>>()

    /** The memo is only valid while watched state is unchanged - any toggle or playback end
     *  shifts which episode is "next", so drop everything and re-resolve lazily on the next
     *  Home build. */
    internal fun clearUpNextMemo() {
        upNextEpoch++
        upNextTiles.clear()
        upNextFetching.clear()
        upNextQueues.clear()
    }
    /** The negotiated stream for whatever Jellyfin item is playing (see
     *  JellyfinProvider.resolveStream). Its PlaySessionId is what ties every progress report
     *  to this play, and what lets the server tear a transcode down when it ends. */
    internal var jellyfinPlaySession: JellyfinProvider.ResolvedStream? = null
    // One-shot fresh-URL retry guard for Jellyfin direct-play: a transient server timeout or
    // expired direct-play URL gets one re-resolve before the generic "Playback error".
    internal var jellyfinRetryAttempted = false
    // Backoff retry count for a live channel with no other version to fail over to (see
    // tryNextQualityVersion) - a transient server-side throttle (HTTP 509 etc.) is worth
    // retrying the exact same URL for before giving up.
    internal var liveRetryAttempt = 0
    // Auto-switches used up within the current channel-watch session (see
    // tryNextQualityVersion) - capped so a provider-wide throttle that fails every version
    // in the group one after another doesn't read as the channel rapidly flipping; past the
    // cap, onPlayerError falls through to the quiet same-URL backoff retry instead.
    internal var liveVersionSwitchAttempt = 0
    internal var jellyfinPlayingItemId: String? = null
    internal var jellyfinChapters: List<JellyfinProvider.Chapter> = emptyList()
    internal var jellyfinTrickplay: JellyfinProvider.TrickplayInfo? = null
    /** Last decoded trickplay sprite sheet, kept so scrubbing within one sheet (~100
     *  thumbnails) doesn't re-download it on every seek step. */
    internal var trickplayTileCache: Pair<Int, android.graphics.Bitmap>? = null
    internal var trickplayLoadJob: kotlinx.coroutines.Job? = null

    // ── A/V Sync Offset ─────────────────────────
    internal val avOffsetManager by lazy { AvOffsetManager(this) }

    // ── Picture-in-Picture video size cache ──────
    internal var lastVideoWidth = 16
    internal var lastVideoHeight = 9

    // ── Numeric Remote Input ────────────────────
    internal val digitInputBuffer = StringBuilder(6)
    internal var isDigitEntryActive = false
    internal val digitInputTimeoutRunnable = Runnable { resolveDigitInput() }

    // ── Up Next / Auto-Advance ──────────────────
    internal var upNextEpisode: Channel? = null
    internal var upNextCountdown = UP_NEXT_COUNTDOWN_SECONDS
    internal var upNextActive = false
    internal val upNextTickRunnable = object : Runnable {
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
    internal var searchAllResults: List<SearchResultItem> = emptyList()
    internal var searchDisplayedCount = 0
    /** Media-type filter for search results (All/Live/Films/Series chips). */
    internal enum class SearchFilter { ALL, LIVE, MOVIE, SERIES }
    internal var searchFilter = SearchFilter.ALL
    /** EPG search budget: program results capped (a bonus surface, not a second catalog)
     *  and on-demand guide fetches capped (each is a network call). */
    internal val MAX_EPG_SEARCH_RESULTS = 30
    internal val MAX_EPG_SEARCH_FETCHES = 10
    /** Monotonic id for the latest scheduled search; async search work checks it before
     *  publishing results, so a stale run (older query, or one whose overlay was dismissed)
     *  can't clobber a newer one - the EPG fetches make runs long enough that the race
     *  is real. */
    internal var searchRunId = 0
    /** Query to restore once the player/detail opened from a search result closes, so picking
     *  a result doesn't destroy the session (Back returns to the results, query intact). */
    internal var pendingSearchRestore: String? = null

    internal val mainHandler = Handler(Looper.getMainLooper())
    /** Toolbar clock tick - see startToolbarClock(). Re-arms itself so the next tick stays
     *  on the minute boundary even after a drifted or delayed post. */
    internal val clockTickRunnable = Runnable { updateToolbarClock(); scheduleNextClockTick() }
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    internal var pendingBackupManager: BackupManager? = null
    /** First-paint flag for the progressive render path (paint Live ASAP once, then surgical
     *  partial re-renders) - see renderLivePartial(). */
    internal var uiPainted: Boolean = false
    /** The in-flight films/series derive launched by deriveFilmsSeries(), if any - cancelled
     *  on a new provider load and joined before tab switches that need it. */
    internal var filmsSeriesDeriveJob: Job? = null
    /** The in-flight surgical live re-render launched by renderLivePartial(), if any -
     *  coalesces the near-simultaneous provider-completion re-renders into one pass. */
    internal var liveRenderJob: Job? = null

    companion object {
        internal const val REQUEST_EXPORT_BACKUP = 2001
        internal const val REQUEST_IMPORT_BACKUP = 2002
        private const val EDGE_SWIPE_ZONE_DP = 24f
        private const val EDGE_SWIPE_THRESHOLD_DP = 64f
        internal const val UP_NEXT_COUNTDOWN_SECONDS = 30
    }

    internal val liveAdapter = LiveGuideAdapter(
        onChannelClick = { channel -> onChannelOkPress(channel) },
        onChannelFocused = { channel -> lastFocusedLiveChannel = channel },
        onChannelLongPress = { channel -> toggleFavoriteChannel(channel) },
        onChannelFavClick = { channel -> toggleFavoriteChannel(channel) },
        isChannelFavourite = { id -> FavoritesStore.getFavoriteChannelIds(this).contains(id) },
        onProgramLongPress = { channel, program -> toggleProgramReminder(channel, program) },
        isReminderSet = { key -> ReminderStore.get(this, key) != null },
        fetchPrograms = { channelId -> resolveEpgPrograms(channelId) }
    )
    internal val seriesShelfAdapter = ShelfAdapter(
        // onHomeItemClick, not playItem: the Continue Watching shelf row holds EPISODES,
        // and playItem's SERIES branch would open the episode itself as a dead detail page.
        // onHomeItemClick resolves an episode to its series (with direct-play fallback).
        onItemClick = { item -> onHomeItemClick(item) },
        onItemLongClick = { item -> toggleFavoriteVodItem(item) },
        onPinClick = { shelf -> togglePinShelfCategory(1, shelf) },
        onHideClick = { shelf -> if (shelf.title == getString(R.string.category_continue_watching)) clearContinueWatching() else toggleHiddenShelfCategory(1, shelf) },
        onSeeAllClick = { shelf -> showSeeAll(shelf) }
    )
    internal val filmsShelfAdapter = ShelfAdapter(
        onItemClick = { item -> playItem(item) },
        onItemLongClick = { item -> toggleFavoriteVodItem(item) },
        onPinClick = { shelf -> togglePinShelfCategory(2, shelf) },
        onHideClick = { shelf -> if (shelf.title == getString(R.string.category_continue_watching)) clearContinueWatching() else toggleHiddenShelfCategory(2, shelf) },
        onSeeAllClick = { shelf -> showSeeAll(shelf) }
    )
    internal val homeShelfAdapter = ShelfAdapter(
        onItemClick = { item -> onHomeItemClick(item) },
        onItemLongClick = { item -> toggleFavoriteVodItem(item) },
        onHideClick = { shelf -> if (shelf.title == getString(R.string.category_continue_watching)) clearContinueWatching() else toggleHiddenHomeShelf(shelf.title) },
        showPinButton = false
    )
    // Single-category selection swaps to these - a vertical, scrollable grid instead of
    // the shelves' horizontal strip, since one category's whole catalog doesn't fit a
    // single row.
    internal val seriesGridAdapter = com.lumora.adapter.PosterGridAdapter(
        onItemLongClick = { item -> toggleFavoriteVodItem(item) }
    ) { item -> onHomeItemClick(item) }
    internal val filmsGridAdapter = com.lumora.adapter.PosterGridAdapter(
        onItemLongClick = { item -> toggleFavoriteVodItem(item) }
    ) { item -> playItem(item) }
    internal val tmdbClient = com.lumora.data.remote.tmdb.TmdbClient()
    /** Discover tile id -> short badge naming the sources that already carry the title.
     *  Filled by loadDiscover() off the main thread; empty means "not in your library". */
    internal var discoverLibrarySources: Map<String, String> = emptyMap()
    internal val discoverGridAdapter = com.lumora.adapter.PosterGridAdapter(
        badgeFor = { item -> discoverLibrarySources[item.id]?.let { it to R.color.primary } }
    ) { item -> onDiscoverItemClick(item) }
    internal var discoverSearchJob: Job? = null
    /** Badge pass for the Discover grid - cancelled when a new search replaces the tiles. */
    internal var discoverBadgeJob: Job? = null
    /** Which of Discover's filter chips is active - null is the default "Trending" chip
     *  (also what a submitted search runs under). Movies/Series switch the grid from
     *  trending/week's thin mixed list to the same paginated Popular ranking the
     *  no-provider Series/Films tabs use (see MainActivityDiscover.loadDiscoverByType). */
    internal var discoverTypeFilter: com.lumora.model.MediaType? = null
    internal var providerLoadJob: Job? = null
    internal val categoryAdapter = CategoryAdapter(
        onCategoryClick = { category -> onCategorySelected(category) },
        onCategoryStarClick = { category -> togglePinCategory(category) },
        onCategoryLongClick = { category ->
            // Live TV's sidebar has other long-press-worthy stuff going on (brand/bucket
            // rows) - keep it a plain pin toggle there. Films/Series get a small menu so
            // hide is reachable too.
            if (activeTab == 0) togglePinCategory(category) else showCategoryContextMenu(category)
        }
    )
    internal val downloadAdapter = DownloadAdapter(
        onClick = { record -> playDownload(record) },
        onDelete = { record -> deleteDownload(record) }
    )
    internal val hideControlsRunnable = Runnable { hideControls() }
    internal val progressRunnable = object : Runnable {
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
    internal lateinit var gestureDetector: GestureDetector
    internal lateinit var scaleDetector: ScaleGestureDetector
    internal val downloadsProgressRunnable = object : Runnable {
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
        // Re-apply the persisted UI language before any views inflate (AppCompatDelegate has
        // its own storage, but the pref is the source of truth for the audio/subtitle/TMDB
        // cascade, and the override here also covers fresh installs and restored backups).
        applyUiLanguageOverride(uiLanguageCode())
        tmdbClient.languageTag = tmdbLanguageTagFor(uiLanguageCode())
        // With no IPTV/Jellyfin provider, Live/Series/Films have nothing to show - land on
        // Discover (TMDB browse) instead of the usual Live tab. classifyAndShow's first-paint
        // dispatch reads this flag, so setting it here is enough; it's untouched afterwards,
        // so navigating away and back behaves exactly as it always has.
        if (!hasProviderEnabled()) showingDiscover = true
        // Cheap (no network) - just parses each script's PLUGIN manifest header - but async
        // since it runs the JS engine, so kick it off early rather than on first use.
        // loadSavedProvider()'s gate (see loadAllConfiguredProviders) checks enabledStreamSearchPlugin(),
        // which reads this discovery's cached result - awaited here so a plugin-only setup
        // (no traditional provider) isn't wrongly bounced to "Add a Provider" on cold start
        // because that check ran against the still-empty pre-discovery cache.
        val pluginDiscoveryOnStart = scope.launch {
            pluginScriptManager.discoverScripts()
            // Needs discovery to have run - it looks for an installed scraper_sites script, and
            // supersedes the bundled site list BaseApplication already applied.
            loadScraperSiteManifest()
        }
        playerManager = PlayerManager(this)
        playerDiagnostics = PlayerDiagnostics(playerManager.getExoPlayer())
        playerManager.getExoPlayer().addAnalyticsListener(playerDiagnostics.getAnalyticsListener())
        database = LumoraDatabase.getInstance(this)

        // Periodic XMLTV EPG re-download. Nothing scheduled this before — the call here went to
        // a BackgroundWorkEnabler.initialize() that was an empty stub whose comment claimed
        // BaseApplication did the scheduling, which it didn't — so EpgSyncWorker never ran and
        // XMLTV sources were fetched exactly never. Unique periodic work with an UPDATE policy,
        // so re-asserting it on every start is free; WorkManager keeps the timer across process
        // death.
        EpgSyncWorker.schedulePeriodic(this)

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
        setStatus(getString(R.string.loading), visible = true)
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
        pruneStoredEpg()
        checkAndPromptUpdate()

        // Downloads are a mobile-only affordance - a TV box has nowhere meaningful to
        // browse a downloaded file, and it's not what "download for offline" means there.
        if (!isTv) {
            binding.tabDownloads.visibility = View.VISIBLE
            // The player side menu mirrors the tab bar, so its Downloads row is phone-only
            // too (the row ships GONE - see activity_main.xml).
            binding.navDownloads.visibility = View.VISIBLE
            val filter = android.content.IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            ContextCompat.registerReceiver(this, downloadCompleteReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            // Downloads stays View.GONE on TV. In the merged chrome row the XML chain already
            // exits the tabs into the button cluster (Discover -> Search pill), so only the
            // left end needs fixing: Live's LEFT would target the GONE Downloads tab and eat
            // the press - stop it there instead of wrapping into a hidden tab.
            binding.tabLive.nextFocusLeftId = View.NO_ID
            // Same in the side menu: Discover's DOWN would land on the GONE Downloads row
            // and stop the walk short of Settings.
            binding.navDiscover.nextFocusDownId = R.id.navSettings
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
                .setTitle(getString(R.string.plug_update_available))
                .setMessage(getString(R.string.plug_update_available_message, info.latestVersion, info.currentVersion, info.releaseNotes.take(200)))
                .setPositiveButton(getString(R.string.update)) { _, _ -> downloadAndInstallUpdate(info.downloadUrl, info.latestVersion) }
                .setNegativeButton(getString(R.string.plug_later), null)
                .show()
        }
    }

    /** Downloads the release APK via DownloadManager, then hands it to the system package
     *  installer as soon as the download finishes - no separate "tap to install" step. */
    internal fun downloadAndInstallUpdate(downloadUrl: String, versionName: String) {
        val installer = AppUpdateInstaller(this)
        val downloadId = installer.downloadApk(downloadUrl, versionName)
        Toast.makeText(this, getString(R.string.plug_downloading_update), Toast.LENGTH_SHORT).show()
        scope.launch {
            while (true) {
                delay(1000)
                if (installer.isDownloadFailed(downloadId)) {
                    Toast.makeText(this@MainActivity, getString(R.string.plug_update_download_failed), Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(this@MainActivity, getString(R.string.plug_update_download_failed), Toast.LENGTH_SHORT).show()
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
        // The site scrapers' Cloudflare bypass runs headless first and only needs an Activity if
        // a challenge has to be promoted to a visible dialog - which can happen several seconds
        // into a fetch that no Activity started. Held weakly, so this is a handle, not a leak.
        com.lumora.scraper.ScraperApp.setCurrentActivity(this)
        // Resync on return: the clock may have been stopped across a long background stint,
        // and the time (or the 12/24h setting) can have changed while it was.
        startToolbarClock()
        if (isPlayerVisible && playerManager.playbackState == Player.STATE_READY) playerManager.play()
        else if (activeTab == 0) showLivePreviewPane()
    }

    override fun onPause() {
        super.onPause()
        // Cleared so a background scraper fetch cannot try to raise a challenge dialog over an
        // Activity that is no longer in front of the user - it falls back to headless-only.
        com.lumora.scraper.ScraperApp.setCurrentActivity(null)
        stopToolbarClock()
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

    /** The Activity handles orientation changes itself (configChanges in the manifest), so
     *  nothing rebuilds on rotate - the rail's visibility has to be re-applied by hand.
     *  Rotating into portrait also drops any manual re-expand, so portrait re-hides. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isTv) return
        if (newConfig.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            portraitSidebarExpanded = false
            // Same auto-rehide as the category rail: portrait always opens collapsed.
            portraitSettingsRailExpanded = false
        }
        applySidebarVisibility(lastTabWantsSidebar)
        applySettingsRailVisibility()
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
        else if (isPlayerVisible && isPlayerSideMenuOpen()) { closeSideMenu() }
        else if (isPlayerVisible) { hidePlayer(); restoreSearchIfPending() }
        else if (isContentDetailVisible) { hideContentDetail(); restoreSearchIfPending() }
        // Back walks back up the way the user came in rather than dropping straight out of
        // the app. Inside a section (Live/Series/Films/Discover/Downloads) the first press
        // goes to the top of that section - a Films/Series category grid up to that tab's
        // shelves, otherwise the first category with both lists scrolled back to the top -
        // and only once already at the top does the next press go Home. Back on Home itself
        // exits. Leaving the app was previously one press from anywhere, which on a remote
        // is very easy to do by accident.
        else if (showingHome) return false
        else if (!isAtSectionTop()) goToSectionTop()
        else goHomeFromBack()
        return true
    }

    /** Re-opens search with the query that led to the just-closed player/detail, so picking
     *  a search result doesn't destroy the session (P1-1). Only when the overlays that would
     *  fight it are actually gone - a hidePlayer that lands back on the detail screen (the
     *  normal episode flow) or a still-open overlay means there's nothing to restore yet. */
    internal fun restoreSearchIfPending() {
        val query = pendingSearchRestore ?: return
        if (isPlayerVisible || isContentDetailVisible || activeSearchOverlay != null) return
        pendingSearchRestore = null
        showSearchDialog(query)
    }

    /** The list filling the content area of whatever section is on screen. */
    private fun activeContentList(): RecyclerView = when {
        showingCatchup -> binding.catchupCategoryList
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
        // Catch Up's own steps are levels above the section's top: while a day or a
        // programme list is showing, Back walks the crumb back up rather than leaving.
        if (showingCatchup && catchupStage != CatchupStage.CATEGORIES) return false
        if (!isListAtTop(activeContentList())) return false
        if (showingCatchup || showingDiscover || showingDownloads) return true
        // Collapsed rail (or a tab whose sidebar is otherwise hidden) has nothing to scroll
        // to - the content list alone decides "top" then. Without this guard, a GONE
        // RecyclerView keeps stale child geometry and can report a mid-list scroll position.
        if (binding.categorySidebar.visibility == View.VISIBLE && !isListAtTop(binding.categorySidebar)) return false
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
        // Inside Catch Up, "up a level" is the crumb, not a scroll position.
        if (showingCatchup && catchupBack()) return
        val content = activeContentList()
        content.scrollToPosition(0)
        // No rail to focus when the sidebar is hidden (collapsed / Downloads-style) - focus
        // the content instead, same as the non-categorized panes below.
        if (showingCatchup || showingDiscover || showingDownloads || binding.categorySidebar.visibility != View.VISIBLE) {
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
        !showingHome && !showingDiscover && !showingDownloads && !showingCatchup && activeTab != 0 &&
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
    internal fun focusFirstItemWhenReady(list: RecyclerView) {
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
        // Two-stage OK while fullscreen: first press only reveals the controls, a second one
        // (on the play/pause button they land focus on) is what actually pauses. Glancing at
        // the clock or the now-playing programme is then free - on LIVE especially, where an
        // unwanted pause costs a rebuffer on resume.
        //
        // This has to sit in dispatchKeyEvent, not onKeyDown: showControls() focuses
        // btnPlayPause, and that focus outlives the overlay going GONE on auto-hide, so the
        // next OK reaches the button's click listener and the Activity-level reveal branch
        // never runs. Claiming the key before it is dispatched to any view is the only place
        // that holds regardless of what happens to be focused behind the hidden overlay.
        // Every action (down, up, repeats) is swallowed so the reveal press can't also click
        // whatever it just focused. Up Next is excluded - its card owns focus while the
        // controls are hidden and OK there means "play the next episode now".
        if (isPlayerVisible && !isPlayerSideMenuOpen() && !upNextActive &&
            binding.controlsOverlay.visibility != View.VISIBLE &&
            (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == android.view.KeyEvent.KEYCODE_ENTER)
        ) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) showControls()
            return true
        }
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.keyCode == android.view.KeyEvent.KEYCODE_SEARCH) {
            // Many TV/Fire remotes carry a magnifier key; map it straight to search. Not
            // while the player is up (a stray press mid-playback shouldn't drop the video)
            // or with a detail open (search hides contentRow, and the stale isContentDetailVisible
            // would corrupt the Back stack).
            if (!isPlayerVisible && !isContentDetailVisible) showSearchDialog()
            return true
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
                // Same seek steps the on-screen buttons use. Remotes split across two
                // codes for this pair - the transport keys on a media remote send
                // FAST_FORWARD/REWIND, the +10/-10 style keys on newer TV remotes send
                // SKIP_FORWARD/SKIP_BACKWARD - and neither was claimed, so a press went
                // to the media session (which has no seek command wired) and did nothing.
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                    playerManager.seekBy(30_000); showControls(); return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                    playerManager.seekBy(-15_000); showControls(); return true
                }
            }
        }

        // Side menu: DPAD_LEFT opens it (TV remotes have no touch; the phone gets the
        // btnPlayerMenu hamburger instead). While it's open, LEFT stays consumed so focus
        // never tries to leave the panel, RIGHT crosses into the category column when one
        // is flown out (and dismisses the whole menu otherwise), and UP/DOWN/CENTER fall
        // through to the framework to navigate/activate rows. LEFT back out of the column
        // is the adapter's job - the focused row sees the key before this runs.
        if (isPlayerVisible) {
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && !isPlayerSideMenuOpen()) {
                // Only from the bare video. With the controls bar up and focus inside it,
                // LEFT belongs to the button row, and at its left end there is nowhere to
                // go - flying the side menu out from under a user who was just walking
                // along the buttons is wrong, so the key stays inside the overlay either
                // way (the menu is still one BACK, hiding the controls, away).
                //
                // The move itself has to be performed here rather than left to the
                // framework: Activity.onKeyDown runs BEFORE ViewRootImpl's focus
                // navigation, so returning true - as this did unconditionally - killed the
                // press before any nextFocusLeft chain was ever consulted, and LEFT off
                // btnPlayPause could never reach btnRewind. RIGHT has no such branch, which
                // is why only the leftward half of the transport row was stuck.
                if (binding.controlsOverlay.visibility == View.VISIBLE && binding.controlsOverlay.hasFocus()) {
                    focusOverlayNeighbour(View.FOCUS_LEFT)
                    showControls()
                    return true
                }
                openSideMenu()
                return true
            }
            if (isPlayerSideMenuOpen()) {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> return true
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        // Resolved once, before the branches - the old code re-called the
                        // helper and force-unwrapped it, an NPE footgun if focus moved
                        // between the two calls.
                        val sectionTab = focusedSideMenuSectionTab()
                        when {
                            // Already inside the column - nothing further right, so swallow
                            // it rather than close the menu out from under the user.
                            binding.sideMenuCategoryList.hasFocus() -> {}
                            // The right-pointing chevron on a section row is an "opens
                            // rightwards" promise - RIGHT there flies that column out (or
                            // steps into it when it's already open).
                            sectionTab != null -> {
                                if (sideMenuCategoriesExpanded && sideMenuExpandedTab == sectionTab) {
                                    focusSideMenuCategoryList()
                                } else {
                                    expandSideMenuCategories(sectionTab)
                                }
                            }
                            else -> closeSideMenu()
                        }
                        return true
                    }
                }
            }
        }

        // Live channel-surf is a blind shortcut only while the controls are hidden - once
        // they're showing, UP/DOWN needs to navigate between buttons (transport row ->
        // seek bar -> Speed/Sleep/Cast/...) instead of surfing channels out from under
        // whatever the user's trying to select. Skipped entirely while the side menu is
        // open so UP from the first menu row doesn't surf channels under the drawer.
        if (isPlayerVisible && !isPlayerSideMenuOpen() && nowPlayingChannel?.mediaType == MediaType.LIVE && binding.controlsOverlay.visibility != View.VISIBLE) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> { navigateChannel(-1); return true }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { navigateChannel(1); return true }
            }
        }
        // Any other D-pad press reveals the controls when they're hidden - was
        // center-only, so a movie/series (no channel-surf shortcut to fall back on) had
        // literally no key that showed them at all. First press just reveals; doesn't
        // also perform whatever that direction would otherwise do, same as it not also
        // clicking the button it lands focus on. Skipped while the side menu is open -
        // the drawer is the only chrome on screen and it must not pop the bottom bar
        // over itself.
        val isDirectionalKey = keyCode in intArrayOf(
            android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER
        )
        if (isPlayerVisible && !isPlayerSideMenuOpen() && isDirectionalKey) {
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

    internal data class DerivedContent(
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
    internal data class FilmsSeriesContent(
        val filmList: List<Channel>,
        val filmVersions: Map<String, List<Channel>>,
        val filmShelves: List<ContentShelf>,
        val seriesList: List<Channel>,
        val seriesVersions: Map<String, List<Channel>>,
        val seriesShelves: List<ContentShelf>,
        val seriesCategoryRows: List<CategoryFilter>
    )

    /** A channel's filter key: Xtream category id, or M3U group name as a fallback.
     *  Falls back to categoryName as a last resort so channels always have a category
     *  to group under, even when the provider doesn't assign a numeric category id. */

    internal fun Channel.filterKey(): String? =
        categoryId?.takeIf { it.isNotBlank() }
            ?: group?.takeIf { it.isNotBlank() }
            ?: categoryName?.takeIf { it.isNotBlank() }

    internal data class CategoryBuildResult(
        val rows: List<CategoryFilter>,
        val childrenByParent: Map<String, List<CategoryFilter>>
    )

    internal var lastFocusedLiveChannel: Channel? = null

    internal val previewGlobalRect = android.graphics.Rect()
    internal val guideRowGlobalRect = android.graphics.Rect()

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            REQUEST_EXPORT_BACKUP -> {
                scope.launch {
                    val success = pendingBackupManager?.exportTo(uri) == true
                    Toast.makeText(this@MainActivity, if (success) getString(R.string.plug_backup_exported) else getString(R.string.plug_export_failed), Toast.LENGTH_SHORT).show()
                    pendingBackupManager = null
                }
            }
            REQUEST_IMPORT_BACKUP -> {
                scope.launch {
                    var result = pendingBackupManager?.importFrom(uri)
                    // The user explicitly chose Import, so "conflicts" are not a decision
                    // point - importFrom(confirmed=false) returned without applying anything
                    // over existing data, so re-run with confirmed=true to actually restore.
                    // Without this the toast reported success while 0 rows were imported.
                    if (result != null && result.conflicts > 0 &&
                        result.providersImported == 0 && result.epgSourcesImported == 0
                    ) {
                        result = pendingBackupManager?.importFrom(uri, confirmed = true)
                    }
                    val msg = result?.let { getString(R.string.plug_imported_summary, it.providersImported, it.epgSourcesImported, it.customGroupsImported) } ?: getString(R.string.plug_import_failed)
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
    internal class FullScreenOverlay(
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

    internal class FontSpan(private val typeface: Typeface) : MetricAffectingSpan() {
        override fun updateMeasureState(textPaint: TextPaint) { textPaint.typeface = typeface }
        override fun updateDrawState(textPaint: TextPaint) { textPaint.typeface = typeface }
    }
}

/** One provider fetch's outcome. Top-level rather than nested in MainActivity because the
 *  per-backend fetches now live in sibling files (MainActivityProviders/Jellyfin) and every
 *  one of them returns it. */
internal sealed class FetchResult {
    data class Success(val channels: List<Channel>) : FetchResult()
    data class Failure(val message: String) : FetchResult()
}
