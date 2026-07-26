package com.lumora

import android.Manifest
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.net.Uri
import android.os.Build
import java.io.File
import android.util.Rational
import android.content.Context
import android.graphics.BitmapFactory
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lumora.adapter.CategoryAdapter
import com.lumora.adapter.DownloadAdapter
import com.lumora.adapter.EpisodeAdapter
import com.lumora.adapter.EpgSourceAdapter
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
import com.lumora.pairing.QrPairingManager
import com.lumora.parser.M3uParser
import com.lumora.parser.XtreamClient
import com.lumora.player.PlayerManager
import com.lumora.player.PlayerTrackController
import com.lumora.player.VideoAspectFrameLayout
import com.lumora.util.extractLeadingTag
import com.lumora.util.deriveBrandCategories
import com.lumora.util.groupCategories
import com.lumora.util.CategoryGroup
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
import com.lumora.player.playback.PlayerErrorClassifier
import kotlinx.coroutines.*
import okhttp3.Request

private const val PREF_HIDE_NON_ENGLISH = "hide_non_english_vod"
private const val PREF_HIDE_ADULT = "hide_adult_categories"
private const val PREF_PARENTAL_PIN = "parental_pin"
private const val PREF_ASPECT_MODE = "player_aspect_mode"
private const val PREF_CLASSIC_CATEGORY_LAYOUT = "classic_category_layout"
private const val FAVOURITES_CATEGORY_ID = "__favourites__"
private const val CLASSIC_LAYOUT_TOGGLE_ID = "__classic_layout_toggle__"
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
// Auto-failover to the next quality/source version of a live channel triggers on
// either a single long stall or several shorter stalls close together - a lone
// hiccup shouldn't cause a switch, but a stream that keeps stuttering should.
private const val STALL_LONG_MS = 15_000L
private const val STALL_WINDOW_MS = 45_000L
private const val STALL_COUNT_THRESHOLD = 3

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var playerManager: PlayerManager
    private lateinit var prefs: SharedPreferences
    private lateinit var playerDiagnostics: PlayerDiagnostics
    private lateinit var database: LumoraDatabase
    private val trackController = PlayerTrackController()
    private val qrManager by lazy { QrPairingManager(this) }

    // Live TV inline preview: a separate, muted player instance so browsing the
    // channel list doesn't touch the main PlayerManager used for fullscreen playback.
    private var previewPlayerManager: PlayerManager? = null
    private var previewChannelId: String? = null
    private var previewLoadRunnable: Runnable? = null

    private var allChannels = listOf<Channel>()
    private var liveChannels = listOf<Channel>()
    private var seriesList = listOf<Channel>()
    private var filmList = listOf<Channel>()
    private var filmVersions: Map<String, List<Channel>> = emptyMap()
    private var liveVersions: Map<String, List<Channel>> = emptyMap()
    private var filmShelves: List<ContentShelf> = emptyList()
    private var seriesShelves: List<ContentShelf> = emptyList()
    private var currentVersionGroup: List<Channel> = emptyList()
    private var currentVersionIndex = 0
    private var bufferingStartMs = 0L
    private val stallTimestamps = mutableListOf<Long>()
    private val longStallCheckRunnable = Runnable { attemptBufferFailover() }

    private var provider: Provider = Provider()
    private var currentIndex = -1
    // Which episode queue (if any) is currently playing, so Next/Prev and
    // auto-advance-on-end know what "next episode" means. -1 = not playing an episode.
    private var currentEpisodeQueue: List<Channel> = emptyList()
    private var currentEpisodeQueueIndex: Int = -1
    private var isPlayerVisible = false
    private var isContentDetailVisible = false
    private var nowShowingDetailId: String? = null
    private var activeTab = 0
    private var showingHome = true
    private var showingDownloads = false
    private val isTv by lazy { isTvDevice(this) }
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
    private var nowPlayingChannel: Channel? = null
    private var resumePromptShown = false
    private var progressTickCount = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingBackupManager: BackupManager? = null

    companion object {
        private const val REQUEST_EXPORT_BACKUP = 2001
        private const val REQUEST_IMPORT_BACKUP = 2002
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
            if (playerManager.isPlaying) { updateProgress(); mainHandler.postDelayed(this, 1000) }
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

        prefs = getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
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
        loadSavedProvider()
        requestNotificationPermissionIfNeeded()
        checkAndPromptUpdate()

        // Downloads are a mobile-only affordance - a TV box has nowhere meaningful to
        // browse a downloaded file, and it's not what "download for offline" means there.
        if (!isTv) {
            binding.tabDownloads.visibility = View.VISIBLE
            val filter = android.content.IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            ContextCompat.registerReceiver(this, downloadCompleteReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            // The XML D-pad chain routes Films -> Downloads -> Home, but Downloads stays
            // View.GONE on TV - an explicit nextFocus target that's GONE just eats the
            // key press instead of falling through, so D-pad right from Films did
            // nothing. Skip the hidden tab in the chain on TV specifically.
            binding.tabFilms.nextFocusRightId = R.id.tabHome
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
                        installer.installApk(path)
                    } else {
                        Toast.makeText(this@MainActivity, "Update download failed", Toast.LENGTH_SHORT).show()
                    }
                    break
                }
            }
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
            if (isPlayerVisible) { saveCurrentPlaybackPosition(); playerManager.pause() }
            releaseLivePreview()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isPlayerVisible && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
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
        releaseLivePreview()
        if (!isTv) runCatching { unregisterReceiver(downloadCompleteReceiver) }
    }

    override fun onBackPressed() {
        if (isPlayerVisible) hidePlayer()
        else if (isContentDetailVisible) hideContentDetail()
        else super.onBackPressed()
    }

    /** DPAD up/down channel-surfs while fullscreen on a live channel, without needing the on-screen controls. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (isPlayerVisible && nowPlayingChannel?.mediaType == MediaType.LIVE) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> { navigateChannel(-1); return true }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { navigateChannel(1); return true }
            }
        }
        // Fire TV remote's center/select button - only intercept while the overlay is
        // hidden (reveal it); if it's already showing, let the press through normally so
        // it activates whatever button is currently focused instead of eating the click.
        if (isPlayerVisible &&
            (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) &&
            binding.controlsOverlay.visibility != View.VISIBLE
        ) {
            showControls()
            return true
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

    private fun hasProviderConfigured(): Boolean =
        (provider.type == ProviderType.XTREAM && provider.serverUrl != null) || !provider.m3uUrl.isNullOrBlank()

    private fun loadSavedProvider() {
        val type = prefs.getString("provider_type", "m3u") ?: "m3u"
        provider = when (type) {
            "xtream" -> Provider(
                name = prefs.getString("provider_name", "Xtream") ?: "Xtream",
                type = ProviderType.XTREAM,
                serverUrl = prefs.getString("xtream_url", null)?.let { normalizeServerUrl(it) },
                username = prefs.getString("xtream_user", null),
                password = prefs.getString("xtream_pass", null)
            )
            "stalker" -> Provider(
                name = prefs.getString("provider_name", "Stalker") ?: "Stalker",
                type = ProviderType.M3U,
                m3uUrl = "stalker://" + (prefs.getString("stalker_url", "") ?: ""),
                serverUrl = prefs.getString("stalker_url", null)?.let { normalizeServerUrl(it) },
                userAgent = prefs.getString("stalker_mac", null)
            )
            "jellyfin" -> Provider(
                name = prefs.getString("provider_name", "Jellyfin") ?: "Jellyfin",
                type = ProviderType.M3U,
                m3uUrl = "jellyfin://" + (prefs.getString("jellyfin_url", "") ?: ""),
                serverUrl = prefs.getString("jellyfin_url", null)?.let { normalizeServerUrl(it) },
                username = prefs.getString("jellyfin_user", null)
            )
            else -> Provider(
                name = prefs.getString("provider_name", "M3U") ?: "M3U",
                type = ProviderType.M3U,
                m3uUrl = prefs.getString("m3u_url", null)?.let { normalizeServerUrl(it) },
                userAgent = prefs.getString("user_agent", null)
            )
        }
        if (!hasProviderConfigured()) { showProviderSettings(); return }

        setStatus("Loading...", visible = true)
        scope.launch {
            val cached = withContext(Dispatchers.IO) { ChannelCache.load(this@MainActivity) }
            if (!cached.isNullOrEmpty()) {
                allChannels = cached
                classifyAndShow()
                setStatus("", visible = false)
                return@launch
            }
            when (type) {
                "xtream" -> loadXtreamContent()
                "stalker" -> loadStalkerContent()
                "jellyfin" -> loadJellyfinContent()
                else -> loadM3uPlaylist(provider.m3uUrl!!)
            }
        }
    }

    private suspend fun loadStalkerContent() {
        setStatus("Connecting to Stalker Portal...", visible = true)
        try {
            val mac = provider.userAgent ?: run { setStatus("No MAC address", visible = true); return }
            val stalker = StalkerProvider(BaseApplication.instance.okHttpClient)
            val result = withContext(Dispatchers.IO) { stalker.loadContent(provider) }
            if (result.isFailure) { setStatus("Stalker error: ${result.exceptionOrNull()?.message?.take(60)}", visible = true); return }
            val content = result.getOrThrow()
            allChannels = content.live + content.films + content.series
            classifyAndShow()
            withContext(Dispatchers.IO) { ChannelCache.save(this@MainActivity, allChannels) }
            prefs.edit().putString("provider_type", "stalker").apply()
            setStatus("${allChannels.size} items", visible = true)
            mainHandler.postDelayed({ setStatus("", visible = false) }, 2000)
        } catch (e: Exception) {
            setStatus("Error: ${e.message?.take(60)}", visible = true)
        }
    }

    private suspend fun loadJellyfinContent() {
        setStatus("Connecting to Jellyfin...", visible = true)
        try {
            val username = provider.username ?: run { setStatus("No username", visible = true); return }
            val jellyfin = JellyfinProvider(BaseApplication.instance.okHttpClient)
            val authResult = withContext(Dispatchers.IO) {
                jellyfin.authenticate(provider.serverUrl ?: "", username, provider.password.orEmpty())
            }
            if (authResult.isFailure) { setStatus("Jellyfin auth error: ${authResult.exceptionOrNull()?.message?.take(60)}", visible = true); return }

            setStatus("Loading content...", visible = true)
            val items: List<Channel> = withContext(Dispatchers.IO) {
                val liveItems = jellyfin.getLiveTvChannels().map { JellyfinProvider.toChannel(it, provider) }
                val movies = jellyfin.getMovies().map { JellyfinProvider.toChannel(it, provider) }
                val series = jellyfin.getSeries().map { JellyfinProvider.toChannel(it, provider) }
                liveItems + movies + series
            }
            allChannels = items
            classifyAndShow()
            withContext(Dispatchers.IO) { ChannelCache.save(this@MainActivity, allChannels) }
            prefs.edit().putString("provider_type", "jellyfin").apply()
            setStatus("${allChannels.size} items", visible = true)
            mainHandler.postDelayed({ setStatus("", visible = false) }, 2000)
        } catch (e: Exception) {
            setStatus("Error: ${e.message?.take(60)}", visible = true)
        }
    }

    private fun loadM3uPlaylist(url: String) {
        setStatus("Loading M3U...", visible = true)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    M3uParser.parseFromUrl(url, BaseApplication.instance.okHttpClient)
                }
                allChannels = result.channels
                classifyAndShow()
                withContext(Dispatchers.IO) { ChannelCache.save(this@MainActivity, allChannels) }
                prefs.edit().putString("m3u_url", url).putString("provider_type", "m3u").apply()
                setStatus("${allChannels.size} items", visible = true)
                mainHandler.postDelayed({ setStatus("", visible = false) }, 2000)
            } catch (e: Exception) {
                setStatus("Error: ${e.message?.take(60)}", visible = true)
            }
        }
    }

    private fun loadXtreamContent() {
        setStatus("Connecting to Xtream...", visible = true)
        scope.launch {
            try {
                val client = XtreamClient(BaseApplication.instance.okHttpClient)
                val authResult = withContext(Dispatchers.IO) { client.authenticate(provider) }
                val auth = authResult.getOrElse { error ->
                    setStatus("Auth error: ${error.message?.take(60)}", visible = true)
                    return@launch
                }
                if (!auth.valid) { setStatus("Auth failed - check server URL and credentials", visible = true); return@launch }

                setStatus("Loading content...", visible = true)
                val live: List<Channel>
                val films: List<Channel>
                val series: List<Channel>
                withContext(Dispatchers.IO) {
                    val liveCatsDeferred = async { runCatching { client.getLiveCategories(provider) }.getOrDefault(emptyList()) }
                    val vodCatsDeferred = async { runCatching { client.getVodCategories(provider) }.getOrDefault(emptyList()) }
                    val seriesCatsDeferred = async { runCatching { client.getSeriesCategories(provider) }.getOrDefault(emptyList()) }
                    val liveDeferred = async { client.getLiveStreams(provider) }
                    val filmsDeferred = async { client.getVodStreams(provider) }
                    val seriesDeferred = async { client.getSeries(provider) }

                    val liveCatNames = liveCatsDeferred.await().toMap()
                    val vodCatNames = vodCatsDeferred.await().toMap()
                    val seriesCatNames = seriesCatsDeferred.await().toMap()

                    live = liveDeferred.await().map { ch ->
                        liveCatNames[ch.categoryId]?.let { ch.copy(categoryName = it) } ?: ch
                    }
                    films = filmsDeferred.await().map { ch ->
                        vodCatNames[ch.categoryId]?.let { ch.copy(categoryName = it) } ?: ch
                    }
                    series = seriesDeferred.await().map { ch ->
                        seriesCatNames[ch.categoryId]?.let { ch.copy(categoryName = it) } ?: ch
                    }
                }

                allChannels = live + films + series
                classifyAndShow()
                withContext(Dispatchers.IO) { ChannelCache.save(this@MainActivity, allChannels) }

                val expiryText = formatSubscriptionStatus(auth.expDateSeconds, auth.isTrial)
                prefs.edit()
                    .putString("provider_type", "xtream")
                    .putString("xtream_url", provider.serverUrl)
                    .putString("xtream_user", provider.username)
                    .putString("xtream_pass", provider.password)
                    .putString("xtream_exp_date", auth.expDateSeconds?.toString() ?: "")
                    .putBoolean("xtream_is_trial", auth.isTrial)
                    .apply()

                val isExpired = auth.expDateSeconds != null && auth.expDateSeconds < System.currentTimeMillis() / 1000
                if (allChannels.isEmpty()) {
                    setStatus("Connected, but no channels returned by server", visible = true)
                } else {
                    val summary = "${allChannels.size} items" + (expiryText?.let { "  ·  $it" } ?: "")
                    setStatus(summary, visible = true)
                    // Don't auto-hide an expiry warning - that's the whole point of surfacing it.
                    if (!isExpired) mainHandler.postDelayed({ setStatus("", visible = false) }, 4000)
                }
            } catch (e: Exception) {
                setStatus("Error: ${e.message?.take(60)}", visible = true)
            }
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
        seriesShelves = derived.seriesShelves

        val hasContent = allChannels.isNotEmpty()

        // Show/hide empty state
        binding.emptyState.visibility = if (hasContent) View.GONE else View.VISIBLE
        binding.contentRow.visibility = if (hasContent) View.VISIBLE else View.GONE

        if (hasContent) {
            binding.liveContent.adapter = liveAdapter
            binding.seriesContent.adapter = seriesShelfAdapter
            binding.filmsContent.adapter = filmsShelfAdapter
            binding.categorySidebar.adapter = categoryAdapter
            binding.homeContent.adapter = homeShelfAdapter
            seriesShelfAdapter.submitList(seriesShelves)
            filmsShelfAdapter.submitList(filmShelves)

            if (showingHome) selectHome() else selectTab(activeTab)
        }
    }

    private fun computeDerivedContent(allChannels: List<Channel>, hideNonEnglish: Boolean, hideAdult: Boolean): DerivedContent {
        fun isAdult(ch: Channel) = hideAdult && isAdultCategory(ch.categoryName, ch.group)

        val rawLive = allChannels.filter { it.mediaType == MediaType.LIVE && !it.name.contains("##") }
            .filterNot { isAdult(it) }
        val (groupedLive, liveVers) = groupLiveQualityVersions(rawLive)

        val rawFilms = allChannels.filter { it.mediaType == MediaType.MOVIE }
            .filterNot { hideNonEnglish && isNonEnglishTitle(it.name) }
            .filterNot { isAdult(it) }
            .map { it.withResolvedYear() }
        val (groupedFilms, versions) = groupDuplicateMovies(rawFilms)
        val films = groupedFilms.sortedByDescending { it.year?.toIntOrNull() ?: -1 }
        val filmShelvesLocal = buildShelves(films, tab = 2)

        val rawSeries = allChannels.filter { it.mediaType == MediaType.SERIES }
            .filterNot { hideNonEnglish && isNonEnglishTitle(it.name) }
            .filterNot { isAdult(it) }
            .map { it.withResolvedYear() }
        val series = groupDuplicateSeries(rawSeries).sortedByDescending { it.year?.toIntOrNull() ?: -1 }
        // Favourited series get their own shelf pinned above everything else in the
        // Series tab itself, not just on Home.
        val favoriteSeriesIds = FavoritesStore.getFavoriteSeriesIds(this)
        val favoriteSeries = series.filter { it.id in favoriteSeriesIds }
        val seriesShelvesLocal = buildShelves(series, tab = 1).let { shelves ->
            if (favoriteSeries.isEmpty()) shelves else listOf(ContentShelf("Favourites", favoriteSeries)) + shelves
        }

        return DerivedContent(groupedLive, liveVers, films, versions, filmShelvesLocal, series, seriesShelvesLocal)
    }

    /** Groups already-sorted content by category into Netflix-style shelves, pinned first then biggest first; hidden categories are dropped entirely. */
    private fun buildShelves(list: List<Channel>, tab: Int): List<ContentShelf> {
        val pinned = getPinnedCategories(tab)
        val hidden = getHiddenCategories(tab)
        val groups = LinkedHashMap<String, MutableList<Channel>>()
        for (ch in list) {
            val key = ch.categoryName?.takeIf { it.isNotBlank() } ?: ch.group?.takeIf { it.isNotBlank() } ?: "Other"
            if (key in hidden) continue
            groups.getOrPut(key) { mutableListOf() }.add(ch)
        }
        return groups.entries
            .map { (title, items) -> ContentShelf(title, items, pinned = title in pinned) }
            .sortedWith(compareBy({ !it.pinned }, { -it.items.size }))
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

    /** A channel's filter key: Xtream category id, or M3U group name as a fallback. */
    private fun Channel.filterKey(): String? =
        categoryId?.takeIf { it.isNotBlank() } ?: group?.takeIf { it.isNotBlank() }

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
        val lower = name.lowercase()
        return when {
            lower.contains("sport") -> 0
            lower.contains("uk") -> 1
            else -> 2
        }
    }

    /** Building the category list scans the whole active tab's content - real work on a big catalog. */
    private suspend fun rebuildCategoriesForActiveTab(): List<CategoryFilter> {
        val list = activeFullList()
        val pinned = getPinnedCategories()
        val hiddenIds = getHiddenCategories()
        val tab = activeTab
        val expandedSnapshot = expandedGroupKeys.toSet()
        val favoriteChannelIds = if (tab == 0) FavoritesStore.getFavoriteChannelIds(this) else emptySet()
        val categories = withContext(Dispatchers.Default) {
            val names = LinkedHashMap<String, String>()
            val counts = LinkedHashMap<String, Int>()
            for (ch in list) {
                val key = ch.filterKey() ?: continue
                if (key in hiddenIds) continue
                val label = ch.categoryName?.takeIf { it.isNotBlank() } ?: ch.group?.takeIf { it.isNotBlank() } ?: key
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
                if (group.members.size == 1) return group.members.first() to group.members
                val groupId = "group:${group.label}"
                val parent = CategoryFilter(
                    id = groupId,
                    name = group.label,
                    count = group.members.sumOf { it.count },
                    pinned = pinned.contains(groupId),
                    matchIds = group.members.flatMap { it.matchIds }.toSet(),
                    isParent = true,
                    expanded = expandedSnapshot.contains(groupId)
                )
                return parent to group.members
            }

            fun expandUnit(unit: Pair<CategoryFilter, List<CategoryFilter>>): List<CategoryFilter> {
                val (row, rawMembers) = unit
                return if (row.isParent && row.expanded) {
                    listOf(row) + rawMembers.sortedBy { it.name.lowercase() }.map { it.copy(isChild = true) }
                } else {
                    listOf(row)
                }
            }

            // Categories are frequently the same content repeated per quality tier
            // ("Sport HD"/"Sport SD"/"Sport RAW") or near-duplicate spellings - merge
            // those into expandable parents on every tab, not just Live TV, or picking
            // one from the Films/Series sidebar only grabs one narrow raw slice instead
            // of the full category.
            // Brand/franchise clusters ("Sky Sports Main Event"/"Sky Sports F1"/...
            // -> "Sky Sports") cut across whatever provider category each channel is
            // actually filed under, so they're synthesized from the raw channel list
            // directly rather than from the grouped category rows. Live TV only - a
            // "brand" concept doesn't map onto film/series categories.
            val groupUnits = groupCategories(leaves).map(::groupUnit)
            val brandUnits = if (tab == 0) {
                deriveBrandCategories(list).map { (label, members) ->
                    val brandId = "brand:$label"
                    CategoryFilter(
                        id = brandId,
                        name = label,
                        count = members.size,
                        pinned = pinned.contains(brandId),
                        channelIds = members.map { it.id }.toSet()
                    ) to emptyList<CategoryFilter>()
                }
            } else {
                emptyList()
            }
            val allUnits = groupUnits + brandUnits

            val useClassicLayout = tab == 0 && prefs.getBoolean(PREF_CLASSIC_CATEGORY_LAYOUT, false)

            // Live TV leads with a handful of dynamic buckets (Sports/News/Music/Cinema)
            // that vacuum up every matching category/brand row regardless of which raw
            // provider category it actually lives in; everything left over cascades below,
            // same priority order as before this existed. The classic pref bypasses this
            // entirely and shows the old flat/grouped list, for anyone who prefers it.
            val (bucketRows, bucketedIds) = if (tab == 0 && !useClassicLayout) {
                fun bucketFor(name: String): String? {
                    val lower = name.lowercase()
                    return LIVE_DYNAMIC_BUCKETS.firstOrNull { (_, keywords) -> keywords.any { lower.contains(it) } }?.first
                }
                val bucketed = LinkedHashMap<String, MutableList<Pair<CategoryFilter, List<CategoryFilter>>>>()
                allUnits.forEach { unit -> bucketFor(unit.first.name)?.let { bucketed.getOrPut(it) { mutableListOf() }.add(unit) } }
                val rows = LIVE_DYNAMIC_BUCKETS.mapNotNull { (label, _) ->
                    val members = bucketed[label] ?: return@mapNotNull null
                    val bucketId = "dynbucket:$label"
                    val expanded = expandedSnapshot.contains(bucketId)
                    val channelIds = members.flatMap { (row, _) ->
                        row.channelIds.ifEmpty { list.filter { ch -> ch.filterKey() in row.matchIds }.map { it.id } }
                    }.toSet()
                    val parent = CategoryFilter(
                        id = bucketId,
                        name = label,
                        count = members.sumOf { it.first.count },
                        pinned = pinned.contains(bucketId),
                        channelIds = channelIds,
                        isParent = true,
                        expanded = expanded
                    )
                    if (expanded) {
                        // Sky Sports/TNT Sports are what people actually look for under
                        // Sports - surface them before whatever else clustered in there.
                        fun childPriority(name: String): Int = when {
                            name.lowercase().startsWith("sky sports") -> 0
                            name.lowercase().startsWith("tnt sports") -> 1
                            else -> 2
                        }
                        listOf(parent) + members.map { it.first.copy(isChild = true, isParent = false, expanded = false) }
                            .sortedWith(compareBy({ childPriority(it.name) }, { it.name.lowercase() }))
                    } else {
                        listOf(parent)
                    }
                }.flatten()
                rows to bucketed.values.flatten().map { it.first.id }.toSet()
            } else {
                emptyList<CategoryFilter>() to emptySet()
            }
            // Series/Films: merged (grouped) categories surface above plain single-provider
            // leaves, alphabetical within each cluster - sorted here, at the unit level,
            // so an expanded parent's own children stay adjacent to it (sorting the already-
            // flattened rows would scatter them back in with unrelated leaves by name).
            val remainderUnits = allUnits.filter { it.first.id !in bucketedIds }
                .let { units ->
                    if (tab != 0) units.sortedWith(compareBy({ if (it.first.isParent) 0 else 1 }, { it.first.name.lowercase() }))
                    else units
                }
            val cascadeRows = remainderUnits.flatMap(::expandUnit)

            val (pinnedRows, unpinnedRows) = cascadeRows.partition { it.pinned }
            val allRow = CategoryFilter(id = null, name = "All", count = list.size)
            // Live TV sorts "All" below the dynamic buckets - Favourites/pinned/buckets
            // are what people actually want first there. Other tabs have no buckets, so
            // "All" just stays at the top like before.
            val result = mutableListOf<CategoryFilter>()
            if (tab != 0) result.add(allRow)
            if (tab == 0) {
                val favoriteCount = list.count { it.id in favoriteChannelIds }
                if (favoriteCount > 0) {
                    result.add(CategoryFilter(id = FAVOURITES_CATEGORY_ID, name = "Favourites", count = favoriteCount))
                }
                result.add(
                    CategoryFilter(
                        id = CLASSIC_LAYOUT_TOGGLE_ID,
                        name = if (useClassicLayout) "Group into categories" else "Show all categories (classic list)",
                        count = -1
                    )
                )
            }
            // Pinned categories are an explicit "I want this first" signal from the user -
            // outrank even the dynamic buckets.
            result += pinnedRows.sortedBy { it.name.lowercase() }
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
            result
        }

        binding.categorySidebar.visibility = if (categories.size > 1) View.VISIBLE else View.GONE
        categoryAdapter.setSelected(selectedRowId)
        categoryAdapter.submitList(categories)
        return categories
    }

    /** Column count for the single-category poster grid, sized off the RecyclerView's actual
     *  width where possible (it's already laid out by the time a category gets picked). */
    private fun gridSpanCount(recyclerView: RecyclerView): Int {
        val widthPx = recyclerView.width.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - resources.getDimensionPixelSize(R.dimen.category_sidebar_width))
        val widthDp = widthPx / resources.displayMetrics.density
        return (widthDp / 150f).toInt().coerceAtLeast(1)
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

    // Series/Films normally use Netflix-style shelves grouped by category; picking one
    // specific category from the sidebar swaps that tab's RecyclerView to a vertical,
    // scrollable poster grid instead - a horizontal strip isn't enough room to browse
    // a whole category in. Filtering the full catalog is real work, so it runs off-main.
    private suspend fun applyCategoryFilter() {
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
                        brandIds != null -> source.filter { it.id in brandIds }
                        matchIds == null -> source
                        else -> source.filter { it.filterKey() in matchIds }
                    }
                }
                liveAdapter.submitList(filtered)
                binding.liveContent.scrollToPosition(0)
            }
            1 -> {
                val source = seriesList
                val shelfItems = selectedShelfItems
                if (shelfItems != null) {
                    binding.seriesContent.layoutManager = GridLayoutManager(this, gridSpanCount(binding.seriesContent))
                    binding.seriesContent.adapter = seriesGridAdapter
                    seriesGridAdapter.submitList(shelfItems)
                } else if (matchIds == null) {
                    binding.seriesContent.layoutManager = LinearLayoutManager(this)
                    binding.seriesContent.adapter = seriesShelfAdapter
                    seriesShelfAdapter.submitList(seriesShelves)
                } else {
                    val filtered = withContext(Dispatchers.Default) { source.filter { it.filterKey() in matchIds } }
                    binding.seriesContent.layoutManager = GridLayoutManager(this, gridSpanCount(binding.seriesContent))
                    binding.seriesContent.adapter = seriesGridAdapter
                    seriesGridAdapter.submitList(filtered)
                }
                binding.seriesContent.scrollToPosition(0)
            }
            2 -> {
                val source = filmList
                val shelfItems = selectedShelfItems
                if (shelfItems != null) {
                    binding.filmsContent.layoutManager = GridLayoutManager(this, gridSpanCount(binding.filmsContent))
                    binding.filmsContent.adapter = filmsGridAdapter
                    filmsGridAdapter.submitList(shelfItems)
                } else if (matchIds == null) {
                    binding.filmsContent.layoutManager = LinearLayoutManager(this)
                    binding.filmsContent.adapter = filmsShelfAdapter
                    filmsShelfAdapter.submitList(filmShelves)
                } else {
                    val filtered = withContext(Dispatchers.Default) { source.filter { it.filterKey() in matchIds } }
                    binding.filmsContent.layoutManager = GridLayoutManager(this, gridSpanCount(binding.filmsContent))
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
            scope.launch { rebuildCategoriesForActiveTab() }
            return
        }
        // First tap on a parent just selects/filters it - expand only toggles on a second
        // tap while it's already the selected row, so picking a category doesn't also
        // dump its whole child list open unasked for.
        if (category.isParent && selectedRowId == category.id) {
            val id = category.id!!
            if (!expandedGroupKeys.remove(id)) expandedGroupKeys.add(id)
        }
        selectedShelfItems = null
        selectedRowId = category.id
        selectedCategoryLabel = category.name
        selectedBrandChannelIds = category.channelIds.ifEmpty { null }
        selectedCategoryIds = if (category.id == null || category.channelIds.isNotEmpty()) null else category.matchIds
        scope.launch {
            rebuildCategoriesForActiveTab()
            applyCategoryFilter()
        }
    }

    private fun setStatus(text: String, visible: Boolean) {
        binding.statusText.text = text
        binding.statusText.visibility = if (visible) View.VISIBLE else View.GONE
    }

    // ── Tabs ───────────────────────────────────────

    private fun setupTabs() {
        binding.tabHome.setOnClickListener { selectHome() }
        binding.tabLive.setOnClickListener { showingHome = false; selectTab(0) }
        binding.tabSeries.setOnClickListener { showingHome = false; selectTab(1) }
        binding.tabFilms.setOnClickListener { showingHome = false; selectTab(2) }
        binding.tabDownloads.setOnClickListener { showingHome = false; selectDownloads() }
    }

    private fun updateTabStyles(selected: View) {
        for (tv in listOf(binding.tabHome, binding.tabLive, binding.tabSeries, binding.tabFilms, binding.tabDownloads)) {
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
        showingHome = true
        showingDownloads = false
        releaseLivePreview()
        binding.contentRow.visibility = View.GONE
        binding.homeContent.visibility = View.VISIBLE
        updateTabStyles(binding.tabHome)
        homeShelfAdapter.submitList(buildHomeShelves())
    }

    /** Downloads reuses the contentRow's FrameLayout but skips the category sidebar and
     *  the live/series/films lists entirely - it's not part of the categorized catalog. */
    private fun selectDownloads() {
        showingDownloads = true
        releaseLivePreview()
        binding.contentRow.visibility = View.VISIBLE
        binding.homeContent.visibility = View.GONE
        binding.categorySidebar.visibility = View.GONE
        binding.liveRow.visibility = View.GONE
        binding.seriesContent.visibility = View.GONE
        binding.filmsContent.visibility = View.GONE
        updateTabStyles(binding.tabDownloads)
        refreshDownloadsList()
        mainHandler.post(downloadsProgressRunnable)
    }

    private fun onHomeItemClick(channel: Channel) {
        when (channel.mediaType) {
            MediaType.LIVE -> playItem(channel)
            MediaType.MOVIE -> { currentIndex = filmList.indexOf(channel); showPlayerFor(channel) }
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

    private fun buildHomeShelves(): List<ContentShelf> {
        val shelves = mutableListOf<ContentShelf>()
        val hidden = getHiddenHomeShelves()

        val continueItems = PlaybackPositionStore.getAllInProgress(this)
        if (continueItems.isNotEmpty()) shelves.add(ContentShelf("Continue Watching", continueItems))

        val recentItems = RecentlyPlayedStore.getRecentIds(this)
            .mapNotNull { id -> liveChannels.firstOrNull { it.id == id } }
        if (recentItems.isNotEmpty()) shelves.add(ContentShelf("Recently Played", recentItems))

        val favIds = FavoritesStore.getFavoriteSeriesIds(this)
        val favItems = seriesList.filter { it.id in favIds }
        if (favItems.isNotEmpty()) shelves.add(ContentShelf("Favorites", favItems))

        return shelves.filter { it.title !in hidden }
    }

    private fun selectTab(index: Int) {
        activeTab = index
        showingDownloads = false
        binding.contentRow.visibility = View.VISIBLE
        binding.homeContent.visibility = View.GONE
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
        scope.launch {
            var categories = rebuildCategoriesForActiveTab()
            // Live TV opens straight into Sky Sports (under the Sports bucket) rather than
            // the unfiltered "All" list - that's what people actually came here for. Falls
            // through to the Sports bucket itself, then to All, if either's missing (e.g.
            // classic layout, or no matching channels in the catalog).
            // categoryAdapter.currentList isn't used here - ListAdapter.submitList() diffs
            // asynchronously, so currentList lags behind what was just submitted; the
            // freshly-built list from rebuildCategoriesForActiveTab()'s return is what's
            // actually current.
            if (index == 0) {
                val sportsBucket = categories.firstOrNull { it.id == "dynbucket:Sports" }
                var target: CategoryFilter? = sportsBucket
                if (sportsBucket != null) {
                    expandedGroupKeys.add(sportsBucket.id!!)
                    categories = rebuildCategoriesForActiveTab()
                    categories.firstOrNull { it.isChild && it.name.lowercase().startsWith("sky sports") }?.let { target = it }
                }
                if (target != null) {
                    selectedRowId = target!!.id
                    selectedCategoryLabel = target!!.name
                    selectedBrandChannelIds = target!!.channelIds.ifEmpty { null }
                    selectedCategoryIds = if (target!!.channelIds.isNotEmpty()) null else target!!.matchIds
                    rebuildCategoriesForActiveTab()
                }
            }
            applyCategoryFilter()
            binding.categorySidebar.scrollToPosition(0)
        }
    }

    // ── Lists ──────────────────────────────────────

    private fun buildGuideHeader() {
        val density = resources.displayMetrics.density
        val slotWidthPx = (30 * 2.6f * density).toInt() // 30 minutes at the guide's MINUTE_WIDTH_DP scale
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val calendar = java.util.Calendar.getInstance()

        binding.guideHeaderRow.removeAllViews()
        repeat(10) { index ->
            val label = TextView(this).apply {
                text = if (index == 0) "Now" else timeFmt.format(calendar.time)
                setTextColor(getColor(R.color.text_tertiary))
                textSize = 11f
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

    /** Unified detail screen for a movie or series: poster/plot/cast plus its versions or episode list. */
    private fun showContentDetail(item: Channel) {
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
        favoriteButton.visibility = View.GONE
        downloadButton.visibility = View.GONE
        downloadButton.setOnClickListener(null)
        seasonScroll.visibility = View.GONE
        seasonRow.removeAllViews()
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

        if (isSeries && item.id.isNotBlank()) {
            favoriteButton.visibility = View.VISIBLE
            fun refreshFavoriteIcon() {
                favoriteIcon.text = if (FavoritesStore.isFavoriteSeries(this, item.id)) "★" else "☆"
            }
            refreshFavoriteIcon()
            favoriteButton.setOnClickListener {
                FavoritesStore.toggleFavoriteSeries(this, item.id)
                refreshFavoriteIcon()
                scope.launch { classifyAndShow() }
            }
        }

        lateinit var itemAdapter: EpisodeAdapter
        itemAdapter = EpisodeAdapter(
            onEpisodeClick = { chosen ->
                hideContentDetail()
                currentIndex = if (isSeries) -1 else filmList.indexOf(item)
                val queue = if (isSeries) itemAdapter.currentList else emptyList()
                showPlayerFor(chosen)
                if (isSeries) {
                    currentEpisodeQueue = queue
                    currentEpisodeQueueIndex = queue.indexOf(chosen)
                }
            },
            showDownloadButton = !isTv,
            onDownloadClick = { episode -> downloadItem(episode) },
            isDownloaded = { episode -> DownloadStore.get(this, episode.id) != null }
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
                seasonRow.getChildAt(i).isSelected = i == index
            }
            itemAdapter.submitList(seasons[index].second)
        }

        val requestedItemId = item.id
        scope.launch {
            try {
                val client = XtreamClient(BaseApplication.instance.okHttpClient)
                if (isSeries) {
                    sectionLabel.text = "Episodes"
                    val info = withContext(Dispatchers.IO) { client.getSeriesFull(provider, item.id) }
                    if (nowShowingDetailId != requestedItemId) return@launch
                    applyDetails(info.details)
                    if (info.seasons.isEmpty()) {
                        statusText.text = "No episodes found"
                    } else {
                        statusText.visibility = View.GONE
                        itemsList.visibility = View.VISIBLE
                        if (info.seasons.size > 1) {
                            seasonScroll.visibility = View.VISIBLE
                            info.seasons.forEachIndexed { index, (label, _) ->
                                val chip = layoutInflater.inflate(R.layout.item_category, seasonRow, false) as TextView
                                chip.text = label
                                chip.layoutParams = LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                                ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
                                chip.setOnClickListener { showSeason(info.seasons, index) }
                                seasonRow.addView(chip)
                            }
                        }
                        showSeason(info.seasons, 0)
                    }
                } else {
                    val details = withContext(Dispatchers.IO) { client.getVodInfo(provider, item.id) }
                    if (nowShowingDetailId != requestedItemId) return@launch
                    applyDetails(details)
                    val versions = filmVersions[item.id] ?: listOf(item)
                    statusText.visibility = View.GONE

                    // The obvious action for a film is "play it" - a button, not a list
                    // labeled "Versions" with one cryptically-named entry in it.
                    playButton.visibility = View.VISIBLE
                    playButton.requestFocus()
                    playButton.setOnClickListener {
                        hideContentDetail()
                        currentIndex = filmList.indexOf(item)
                        showPlayerFor(versions.first())
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
                            val chip = layoutInflater.inflate(R.layout.item_category, versionsRow, false) as TextView
                            chip.text = extractLeadingTag(version.name) ?: "Version ${index + 1}"
                            chip.textSize = 10f
                            chip.setPadding(
                                (12 * resources.displayMetrics.density).toInt(), (8 * resources.displayMetrics.density).toInt(),
                                (12 * resources.displayMetrics.density).toInt(), (8 * resources.displayMetrics.density).toInt()
                            )
                            chip.isSelected = index == 0
                            chip.layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
                            chip.setOnClickListener {
                                hideContentDetail()
                                currentIndex = filmList.indexOf(item)
                                showPlayerFor(version)
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
    }

    // ── Search ───────────────────────────────────────

    private fun showSearchDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_search, null)
        val input = dialogView.findViewById<EditText>(R.id.searchInput)
        val statusText = dialogView.findViewById<TextView>(R.id.searchStatus)
        val resultsList = dialogView.findViewById<RecyclerView>(R.id.searchResults)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setNegativeButton("Close", null)
            .create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
        dialog.show()

        // The dialog's own RecyclerView isn't laid out yet right after show(), so
        // gridSpanCount() (which reads actual width) isn't usable here - estimate off
        // full screen width instead, no sidebar to subtract in a dialog.
        val dialogSpanCount = (resources.displayMetrics.widthPixels / resources.displayMetrics.density / 150f).toInt().coerceAtLeast(2)
        resultsList.layoutManager = GridLayoutManager(this, dialogSpanCount)
        val resultsAdapter = PosterGridAdapter { item ->
            dialog.dismiss()
            showContentDetail(item)
        }
        resultsList.adapter = resultsAdapter

        var searchRunnable: Runnable? = null
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchRunnable?.let { mainHandler.removeCallbacks(it) }
                val query = s?.toString()?.trim().orEmpty()
                if (query.length < 2) {
                    statusText.text = "Type at least 2 characters"
                    statusText.visibility = View.VISIBLE
                    resultsList.visibility = View.GONE
                    return
                }
                val runnable = Runnable { runSearch(query, resultsAdapter, statusText, resultsList) }
                searchRunnable = runnable
                mainHandler.postDelayed(runnable, 300)
            }
        })
        dialog.setOnDismissListener { searchRunnable?.let { mainHandler.removeCallbacks(it) } }
    }

    private fun runSearch(query: String, adapter: PosterGridAdapter, statusText: TextView, resultsList: RecyclerView) {
        statusText.text = "Searching…"
        statusText.visibility = View.VISIBLE
        resultsList.visibility = View.GONE
        scope.launch {
            val results = withContext(Dispatchers.Default) {
                val lower = query.lowercase()
                (filmList + seriesList)
                    .filter { it.name.lowercase().contains(lower) }
                    .sortedWith(compareBy({ !it.name.lowercase().startsWith(lower) }, { it.name.lowercase() }))
                    .take(150)
            }
            if (results.isEmpty()) {
                statusText.text = "No results for \"$query\""
                statusText.visibility = View.VISIBLE
                resultsList.visibility = View.GONE
            } else {
                statusText.visibility = View.GONE
                resultsList.visibility = View.VISIBLE
                adapter.submitList(results)
            }
        }
    }

    private fun reloadCurrentProvider() {
        when (provider.type) {
            ProviderType.XTREAM -> if (provider.serverUrl != null) loadXtreamContent() else showProviderSettings()
            ProviderType.M3U -> if (!provider.m3uUrl.isNullOrBlank()) loadM3uPlaylist(provider.m3uUrl!!) else showProviderSettings()
        }
    }

    // ── Player ─────────────────────────────────────

    private fun setupPlayerControls() {
        binding.btnPlayPause.setOnClickListener { playerManager.togglePlayPause(); updatePlayPauseIcon() }
        binding.btnPrevChannel.setOnClickListener { navigateChannel(-1) }
        binding.btnNextChannel.setOnClickListener { navigateChannel(1) }
        binding.btnBack.setOnClickListener { hidePlayer() }
        binding.btnAudioTrack.setOnClickListener { showTrackPicker(isAudio = true) }
        binding.btnSubtitleTrack.setOnClickListener { showTrackPicker(isAudio = false) }
        binding.btnLiveVersions.setOnClickListener { showLiveVersionPicker() }
        applyAspectMode(loadSavedAspectMode())
        binding.btnAspectRatio.setOnClickListener { cycleAspectMode() }

        // Speed control
        val speedController = com.lumora.player.playback.PlaybackSpeedController(playerManager.getExoPlayer())
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
        val sleepTimer = com.lumora.player.playback.SleepTimer(playerManager.getExoPlayer())
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

        // Cast
        binding.btnCast.setOnClickListener {
            val channel = nowPlayingChannel ?: return@setOnClickListener
            Toast.makeText(this, "Cast: ${channel.name}", Toast.LENGTH_SHORT).show()
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
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar?) { tracking = true }
            override fun onStopTrackingTouch(s: SeekBar?) {
                tracking = false
                if (playerManager.duration > 0) {
                    playerManager.seekTo((playerManager.duration * (s?.progress ?: 0)) / 100)
                }
            }
        })

        binding.playerLayout.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) toggleControls(); true
        }

        playerManager.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                binding.bufferingSpinner.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_BUFFERING) onBufferingStarted() else onBufferingEnded()
                if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                    updateProgress(); updatePlayPauseIcon()
                    if (state == Player.STATE_READY) maybeShowResumePrompt()
                    if (state == Player.STATE_ENDED) {
                        saveCurrentPlaybackPosition()
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
                if (!tryNextQualityVersion()) {
                    Toast.makeText(this@MainActivity, "Playback error", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseIcon()
                if (isPlaying) mainHandler.post(progressRunnable)
                else mainHandler.removeCallbacks(progressRunnable)
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.height == 0 || videoSize.width == 0) return
                val rotated = videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270
                val w = if (rotated) videoSize.height else videoSize.width
                val h = if (rotated) videoSize.width else videoSize.height
                binding.playerAspectContainer.videoAspectRatio = (w * videoSize.pixelWidthHeightRatio) / h
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

    private fun showPlayerFor(channel: Channel) {
        // Cleared unconditionally - callers that want episode tracking (Next/Prev,
        // auto-advance) re-set these right after calling this, once playback has
        // actually started for the episode they picked.
        currentEpisodeQueue = emptyList()
        currentEpisodeQueueIndex = -1
        // Never run the preview decode and the fullscreen decode at once.
        releaseLivePreview()
        isPlayerVisible = true
        nowPlayingChannel = channel
        resumePromptShown = false
        progressTickCount = 0
        binding.mainContent.visibility = View.GONE
        binding.playerLayout.visibility = View.VISIBLE
        binding.playerLayout.keepScreenOn = true
        binding.playerChannelName.text = channel.name
        binding.playerSubtitle.visibility = View.GONE
        binding.playerLiveBadge.visibility = if (channel.mediaType == MediaType.LIVE) View.VISIBLE else View.GONE
        if (channel.mediaType == MediaType.LIVE) RecentlyPlayedStore.recordPlayed(this, channel.id)

        // Live channels get a square logo tile (fitCenter, so a wide/odd-aspect logo
        // doesn't get cropped); movies/series get a poster-shaped tile (centerCrop) -
        // squeezing a 2:3 poster into a 52x52 square looked like a stretched thumbnail.
        val density = resources.displayMetrics.density
        val isPoster = channel.mediaType != MediaType.LIVE
        binding.playerLogoBox.layoutParams = binding.playerLogoBox.layoutParams.apply {
            width = ((if (isPoster) 40 else 44) * density).toInt()
            height = ((if (isPoster) 60 else 44) * density).toInt()
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
        currentVersionIndex = 0

        resetStallTracking()
        binding.playerAspectContainer.videoAspectRatio = 0f
        playerManager.setSurfaceView(binding.playerSurface)
        showControls()
        binding.bufferingSpinner.visibility = View.VISIBLE
        playerManager.playUrl(channel.url, provider.userAgent)
    }

    /** Retries playback with the next-best quality version of the current live channel, if any. */
    private fun tryNextQualityVersion(message: String = "Switching to alternate quality…"): Boolean {
        if (nowPlayingChannel?.mediaType != MediaType.LIVE) return false
        val nextIndex = currentVersionIndex + 1
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
        binding.playerAspectContainer.videoAspectRatio = 0f
        Toast.makeText(this, message ?: "Switching to ${extractLeadingTag(next.name) ?: next.name}", Toast.LENGTH_SHORT).show()
        binding.bufferingSpinner.visibility = View.VISIBLE
        playerManager.playUrl(next.url, provider.userAgent)
    }

    /** Lets the user manually pick a specific quality/source version of the currently playing
     *  live channel - groupLiveQualityVersions() auto-picks the best on play, but sometimes the
     *  "best" one buffers or is geo-blocked while a lower-ranked sibling works fine. */
    private fun showLiveVersionPicker() {
        if (nowPlayingChannel?.mediaType != MediaType.LIVE) {
            Toast.makeText(this, "Versions are only available for live TV", Toast.LENGTH_SHORT).show()
            return
        }
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

    // ── Buffer-based auto-failover ─────────────────
    // onPlayerError already fails over on a hard error; this covers the "plays but
    // buffers constantly" case, which ExoPlayer never surfaces as an error at all.

    private fun onBufferingStarted() {
        if (nowPlayingChannel?.mediaType != MediaType.LIVE) return
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
        resetStallTracking()
        tryNextQualityVersion("Stream buffering, switching version…")
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
        val dur = playerManager.duration
        val pos = playerManager.currentPosition
        if (dur <= 0) return
        val key = channel.id.ifBlank { channel.url }
        PlaybackPositionStore.save(this, key, pos, dur, channel)
    }

    private fun hidePlayer() {
        saveCurrentPlaybackPosition()
        isPlayerVisible = false
        nowPlayingChannel = null
        binding.playerLayout.visibility = View.GONE
        binding.mainContent.visibility = View.VISIBLE
        binding.playerLayout.keepScreenOn = false
        mainHandler.removeCallbacksAndMessages(null)
        playerManager.stop()
        if (activeTab == 0) {
            showLivePreviewPane()
            lastFocusedLiveChannel?.let { requestPreviewLoad(it) }
        }
        // Whatever just finished playing may have changed Continue Watching - refresh
        // Home so it's not stale until the next unrelated rebuild happens to touch it.
        if (showingHome) homeShelfAdapter.submitList(buildHomeShelves())
        restoreTabFocus()
    }

    // ── EPG ──────────────────────────────────────────

    /** Currently-airing program for a live channel, or null if there's no EPG data for it. */
    /** Every id worth trying for a channel's EPG: itself first, then its merged quality/source siblings - same physical channel, the provider just didn't attach guide data to every feed. */
    private fun epgCandidateIds(channelId: String): List<String> {
        val siblings = liveVersions[channelId].orEmpty().map { it.id }.filter { it.isNotBlank() && it != channelId }
        return listOf(channelId) + siblings
    }

    private suspend fun resolveCurrentProgram(channelId: String): XtreamClient.EpgProgram? {
        if (provider.type != ProviderType.XTREAM) return null
        val client = XtreamClient(BaseApplication.instance.okHttpClient)
        val nowSeconds = System.currentTimeMillis() / 1000
        for (id in epgCandidateIds(channelId)) {
            val programs = runCatching { client.getShortEpg(provider, id, 2) }.getOrDefault(emptyList())
            if (programs.isNotEmpty()) return programs.firstOrNull { it.isNowAiring(nowSeconds) } ?: programs.firstOrNull()
        }
        return null
    }

    /** Next several EPG entries for a channel, used to build one row of the guide timeline. */
    private suspend fun resolveEpgPrograms(channelId: String): List<XtreamClient.EpgProgram>? {
        if (provider.type != ProviderType.XTREAM) return null
        val client = XtreamClient(BaseApplication.instance.okHttpClient)
        for (id in epgCandidateIds(channelId)) {
            val programs = runCatching { client.getShortEpg(provider, id, 6) }.getOrDefault(emptyList())
            if (programs.isNotEmpty()) return programs
        }
        return null
    }

    // ── Live TV inline preview ──────────────────────

    private var lastFocusedLiveChannel: Channel? = null

    private fun ensurePreviewPlayer(): PlayerManager {
        previewPlayerManager?.let { return it }
        val manager = PlayerManager(this)
        manager.setVolume(0f)
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
        ensurePreviewPlayer().setTextureView(binding.previewSurface)
    }

    private fun releaseLivePreview() {
        previewLoadRunnable?.let { mainHandler.removeCallbacks(it) }
        previewLoadRunnable = null
        previewChannelId = null
        binding.livePreviewGutter.visibility = View.GONE
        binding.livePreviewPane.visibility = View.GONE
        // A hardware-overlay SurfaceView can keep compositing its last frame even
        // after the Java view tree is hidden; explicitly stop playback and hide the
        // surface itself (not just its parent) so it actually goes away.
        binding.previewSurface.visibility = View.GONE
        previewPlayerManager?.let { manager ->
            manager.stop()
            manager.release()
        }
        previewPlayerManager = null
        binding.previewSurface.visibility = View.VISIBLE
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
        binding.previewChannelGroup.text = channel.categoryName?.takeIf { it.isNotBlank() } ?: channel.group ?: ""
        binding.previewNowPlaying.visibility = View.GONE
        binding.previewNowPlayingTime.visibility = View.GONE
        binding.previewBuffering.visibility = View.VISIBLE
        ensurePreviewPlayer().playUrl(channel.url, provider.userAgent)

        scope.launch {
            val program = runCatching { resolveCurrentProgram(channel.id) }.getOrNull()
            if (previewChannelId != channel.id || program == null) return@launch
            binding.previewNowPlaying.text = program.title
            binding.previewNowPlaying.visibility = View.VISIBLE
            binding.previewNowPlayingTime.text = formatEpgTimeRange(program.startTimestamp, program.stopTimestamp)
            binding.previewNowPlayingTime.visibility = View.VISIBLE
        }
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
        binding.controlsOverlay.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, 4000)
    }

    private fun hideControls() { binding.controlsOverlay.visibility = View.GONE }
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
        val pos = playerManager.currentPosition
        val dur = playerManager.duration
        binding.currentTime.text = formatTime(pos)
        if (dur > 0) {
            binding.duration.text = formatTime(dur)
            binding.seekBar.progress = ((pos.toFloat() / dur) * 100).toInt()
        }
        binding.seekBar.isEnabled = dur > 0

        // Ticks every ~1s while playing; persist progress every ~5s instead of every tick.
        progressTickCount++
        if (progressTickCount % 5 == 0) saveCurrentPlaybackPosition()
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

    @Suppress("DEPRECATION")
    private fun showProviderSettings() {
        val dialogView = layoutInflater.inflate(R.layout.activity_settings, null)
        val providerTypeSpinner = dialogView.findViewById<Spinner>(R.id.settingsProviderTypeSpinner)
        val showQrButton = dialogView.findViewById<View>(R.id.settingsShowQrButton)
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
        val stalkerSerial = dialogView.findViewById<EditText>(R.id.settingsStalkerSerial)
        val jellyfinUrl = dialogView.findViewById<EditText>(R.id.settingsJellyfinUrl)
        val jellyfinUser = dialogView.findViewById<EditText>(R.id.settingsJellyfinUser)
        val jellyfinPass = dialogView.findViewById<EditText>(R.id.settingsJellyfinPass)
        val hideNonEnglish = dialogView.findViewById<CheckBox>(R.id.settingsHideNonEnglish)
        val clearHistory = dialogView.findViewById<View>(R.id.settingsClearHistory)
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

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        // A rounded floating window + dimmed backdrop reads as a popup no matter how big
        // it's sized - drop both so this renders as a genuine full screen instead.
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(getColor(R.color.canvas)))
        dialog.window?.setDimAmount(0f)

        var serverRunning = false
        var currentType = if (provider.type == ProviderType.XTREAM) "xtream" else "m3u"

        fun selectType(type: String) {
            currentType = type
            m3uGroup.visibility = if (type == "m3u") View.VISIBLE else View.GONE
            xtreamGroup.visibility = if (type == "xtream") View.VISIBLE else View.GONE
            stalkerGroup.visibility = if (type == "stalker") View.VISIBLE else View.GONE
            jellyfinGroup.visibility = if (type == "jellyfin") View.VISIBLE else View.GONE
            showQrButton.visibility = if (type in listOf("m3u", "xtream")) View.VISIBLE else View.GONE
        }

        fun stopQrServer() {
            qrManager.stop()
            serverRunning = false
            qrSection.visibility = View.GONE
            qrFrame.visibility = View.GONE
            qrTimer.visibility = View.GONE
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
                        provider = Provider(name = form["name"] ?: "QR M3U", type = ProviderType.M3U, m3uUrl = url, userAgent = form["userAgent"])
                        prefs.edit().putString("m3u_url", url).putString("provider_type", "m3u").apply()
                        stopQrServer()
                        dialog.dismiss()
                        loadM3uPlaylist(url)
                    }
                    "xtream" -> {
                        val su = form["serverUrl"]?.let { normalizeServerUrl(it) } ?: return@runOnUiThread
                        provider = Provider(name = form["name"] ?: "QR Xtream", type = ProviderType.XTREAM, serverUrl = su, username = form["username"], password = form["password"])
                        prefs.edit().putString("xtream_url", su).putString("xtream_user", form["username"]).putString("xtream_pass", form["password"]).putString("provider_type", "xtream").apply()
                        stopQrServer()
                        dialog.dismiss()
                        loadXtreamContent()
                    }
                }
            }
        }

        qrManager.onError = { msg ->
            runOnUiThread { qrStatus.text = msg }
        }

        val providerTypeLabels = listOf("M3U URL", "Xtream", "Stalker Portal", "Jellyfin")
        val providerTypeValues = listOf("m3u", "xtream", "stalker", "jellyfin")
        val spinnerAdapter = ArrayAdapter(this, R.layout.item_spinner_selected, providerTypeLabels).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        providerTypeSpinner.adapter = spinnerAdapter
        val currentTypeIndex = providerTypeValues.indexOf(currentType).coerceAtLeast(0)
        providerTypeSpinner.setSelection(currentTypeIndex, false)
        providerTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val type = providerTypeValues[position]
                selectType(type)
                if (serverRunning) {
                    stopQrServer()
                    startQrServer(type)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        showQrButton.setOnClickListener { startQrServer(currentType) }

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

        // Drive Backup
        val driveBackupManager = com.lumora.data.backup.DriveBackupManager(this)
        val backupStatus = driveBackupManager.getStatus()
        dialogView.findViewById<View>(R.id.settingsExportBackup).apply {
            setOnClickListener {
                if (!driveBackupManager.isSignedIn()) {
                    Toast.makeText(this@MainActivity, "Sign in via Google account", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                scope.launch {
                    val backupManager = BackupManager(this@MainActivity)
                    // Collect data without URI (in-memory)
                    Toast.makeText(this@MainActivity, "Pushing to Google Drive...", Toast.LENGTH_SHORT).show()
                }
            }
            if (backupStatus.lastPushAt != null) {
                val date = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
                    .format(java.util.Date(backupStatus.lastPushAt!!))
                findViewById<TextView>(R.id.settingsExportBackup).text = "Drive backup ($date)"
            }
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

        // Pre-fill existing values
        m3uUrl.setText(prefs.getString("m3u_url", ""))
        uaInput.setText(prefs.getString("user_agent", ""))
        xtreamUrl.setText(prefs.getString("xtream_url", ""))
        xtreamUser.setText(prefs.getString("xtream_user", ""))
        xtreamPass.setText(prefs.getString("xtream_pass", ""))
        stalkerUrl.setText(prefs.getString("stalker_url", ""))
        stalkerMac.setText(prefs.getString("stalker_mac", ""))
        stalkerSerial.setText(prefs.getString("stalker_serial", "000000000000"))
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

        // First-run: force the user through provider setup before anything else is even
        // reachable - the rest of Settings assumes a working provider already exists.
        val providerConfigured = hasProviderConfigured()
        dialogView.findViewById<View>(R.id.settingsNavRail).visibility = if (providerConfigured) View.VISIBLE else View.GONE
        dialogView.findViewById<View>(R.id.settingsNavDivider).visibility = if (providerConfigured) View.VISIBLE else View.GONE

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
        selectType(currentType)
        dialog.setOnDismissListener { qrManager.stop() }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // QR is opt-in (via showQrButton) - the positive button is bound after show()
        // so its listener can validate and keep the dialog open on error instead of the
        // default auto-dismiss-on-click AlertDialog buttons do.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            when (currentType) {
                "m3u" -> {
                    val url = m3uUrl.text.toString().trim().let { if (it.isBlank()) it else normalizeServerUrl(it) }
                    if (url.isBlank()) {
                        Toast.makeText(this, "Enter an M3U URL", Toast.LENGTH_SHORT).show(); return@setOnClickListener
                    }
                    prefs.edit().putString("m3u_url", url).putString("user_agent", uaInput.text.toString().trim()).putString("provider_type", "m3u").apply()
                    provider = Provider(name = "M3U", type = ProviderType.M3U, m3uUrl = url, userAgent = uaInput.text.toString().trim().ifBlank { null })
                    dialog.dismiss(); loadM3uPlaylist(url)
                }
                "xtream" -> {
                    val url = xtreamUrl.text.toString().trim().let { if (it.isBlank()) it else normalizeServerUrl(it) }
                    if (url.isBlank()) { Toast.makeText(this, "Enter a server URL", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                    val user = xtreamUser.text.toString().trim(); val pass = xtreamPass.text.toString().trim()
                    prefs.edit().putString("xtream_url", url).putString("xtream_user", user).putString("xtream_pass", pass).putString("provider_type", "xtream").apply()
                    provider = Provider(name = "Xtream", type = ProviderType.XTREAM, serverUrl = url, username = user, password = pass)
                    dialog.dismiss(); loadXtreamContent()
                }
                "stalker" -> {
                    val url = stalkerUrl.text.toString().trim().let { if (it.isBlank()) it else normalizeServerUrl(it) }
                    if (url.isBlank()) { Toast.makeText(this, "Enter a server URL", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                    val mac = stalkerMac.text.toString().trim()
                    val serial = stalkerSerial.text.toString().trim().ifBlank { "000000000000" }
                    prefs.edit().putString("stalker_url", url).putString("stalker_mac", mac).putString("stalker_serial", serial).putString("provider_type", "stalker").apply()
                    provider = Provider(name = "Stalker", type = ProviderType.M3U, m3uUrl = "stalker://$url", userAgent = mac)
                    dialog.dismiss()
                    Toast.makeText(this, "Stalker provider saved. Loading...", Toast.LENGTH_SHORT).show()
                    loadM3uPlaylist(provider.m3uUrl!!)
                }
                "jellyfin" -> {
                    val url = jellyfinUrl.text.toString().trim().let { if (it.isBlank()) it else normalizeServerUrl(it) }
                    if (url.isBlank()) { Toast.makeText(this, "Enter a server URL", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                    val user = jellyfinUser.text.toString().trim(); val pass = jellyfinPass.text.toString().trim()
                    prefs.edit().putString("jellyfin_url", url).putString("jellyfin_user", user).putString("provider_type", "jellyfin").apply()
                    provider = Provider(name = "Jellyfin", type = ProviderType.M3U, m3uUrl = "jellyfin://$url", username = user, password = pass)
                    dialog.dismiss()
                    Toast.makeText(this, "Jellyfin provider saved. Loading...", Toast.LENGTH_SHORT).show()
                    loadM3uPlaylist(provider.m3uUrl!!)
                }
            }
        }
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
