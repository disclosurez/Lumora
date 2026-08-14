package com.lumora

import java.io.File
import android.view.View
import android.widget.*
import com.lumora.cache.ChannelCache
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.model.Provider
import com.lumora.model.ProviderType
import com.lumora.model.IptvProviderConfig
import com.lumora.data.IptvProviderStore
import com.lumora.anime.AnimeCatalogClient
import com.lumora.parser.M3uParser
import com.lumora.parser.XtreamClient
import com.lumora.util.normalizeServerUrl
import com.lumora.data.remote.stalker.StalkerProvider
import com.lumora.plugin.js.JsPluginContract
import com.lumora.plugin.js.PluginScriptManager
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException

// ── Provider loading, per-provider content gates & backend fetches ──
//
// Extracted from MainActivity.kt; see that file's header.
/** Fixed, no-picker-required fallback location for devices (Fire TV, most Android TV
 *  boxes) with no Storage Access Framework document picker installed at all. */
internal fun MainActivity.localBackupFile(): File {
    val dir = File(getExternalFilesDir(null), "backups").apply { mkdirs() }
    return File(dir, "lumora_backup.json")
}

/** IPTV (Xtream/M3U/Stalker - still mutually exclusive among themselves, that's what
 *  [provider] represents) and Jellyfin are independent provider slots that can each be
 *  configured and enabled at the same time - their catalogs get merged. */
internal fun MainActivity.hasIptvConfigured(): Boolean = IptvProviderStore.load(prefs).isNotEmpty()

internal fun MainActivity.hasJellyfinConfigured(): Boolean =
    !prefs.getString("jellyfin_url", null).isNullOrBlank()

internal fun MainActivity.isJellyfinEnabled(): Boolean = prefs.getBoolean("jellyfin_provider_enabled", true)

internal fun MainActivity.hasProviderConfigured(): Boolean = hasIptvConfigured() || hasJellyfinConfigured()

/** Configured *and* switched on. A provider can exist but be disabled (its row unticked
 *  in Settings, or the demo auto-disabled), which leaves nothing to browse - distinct
 *  from hasProviderConfigured(), which only asks whether any entry exists at all. */
internal fun MainActivity.hasProviderEnabled(): Boolean =
    IptvProviderStore.load(prefs).any { it.enabled } ||
        (hasJellyfinConfigured() && isJellyfinEnabled())

/** Search and the tab bar are useful once there's either an enabled provider to browse, or
 *  an enabled stream_search plugin to find content through (Discover/anime tabs, Find
 *  Stream) - with neither, they point at nothing, so hide them and leave just the Settings
 *  button as the way in. The empty state carries its own Settings/Demo actions. Settings and
 *  refresh stay visible so the user can always get back to configuring one. */
internal fun MainActivity.updateTopChromeVisibility() {
    val enabled = hasProviderEnabled() || hasProviderlessSource()
    if (!enabled) binding.homeSearchBar.visibility = View.GONE
    // Every tab/search visibility rule now lives in applySimpleModeUi() - it recomputes the
    // same "is there anything to browse" flag, and it must have the last word anyway.
    applySimpleModeUi()
}

/** Blanket show/hide of the scroller's browsing items - the tabs plus the Search pill.
 *  Settings and Refresh sit in the same scroller now (so the chrome reads as one bar), so
 *  hiding the scroller would take them with it, and "no provider" is exactly the state
 *  where Settings must stay reachable. Showing is only lifting the blanket hide: the
 *  per-item rules (Downloads is phone-only, Catch Up needs an archive, simple mode shows
 *  Live + Catch Up alone) are re-applied by the caller straight after. */
internal fun MainActivity.setBrowseTabsVisible(visible: Boolean) {
    val vis = if (visible) View.VISIBLE else View.GONE
    for (tab in listOf(
        binding.tabHome, binding.tabLive, binding.tabSeries,
        binding.tabFilms, binding.tabDiscover, binding.tabDownloads, binding.btnSearch,
    )) {
        tab.visibility = vis
    }
}

/** Settings' LEFT target, which depends on what is actually on screen to its left:
 *  Downloads (phone), else Search, else nothing left of it at all. An explicit
 *  nextFocusLeftId pointing at a GONE view resolves to nothing and eats the press, so it
 *  can't be left to the XML default. */
internal fun MainActivity.updateChromeFocusChain() {
    binding.btnSettings.nextFocusLeftId = when {
        binding.tabDownloads.visibility == View.VISIBLE -> R.id.tabDownloads
        binding.btnSearch.visibility == View.VISIBLE -> R.id.btnSearch
        else -> R.id.btnSettings
    }
}

/** Simple mode is "TV only": Live TV (with Catch Up reachable through its own small chip,
 *  not a tab) and nothing else. The bar would only ever hold the single Live tab, which is
 *  worth no space, so it is hidden entirely and the EPG/live content shifts up into the
 *  freed space, which is what simple mode always did. The toolbar above it stays, so
 *  Settings stays reachable. */
internal fun MainActivity.isSimpleMode(): Boolean = prefs.getBoolean(PREF_SIMPLE_MODE, false)

/** VOD is dropped at fetch time; the manual toggle and simple mode both turn it off,
 *  and simple mode never writes the manual pref so turning it off re-enables VOD. */
internal fun MainActivity.isVodDisabled(): Boolean = isSimpleMode() || prefs.getBoolean(PREF_DISABLE_VOD, false)

// ── Per-provider content-type gates ──────────────
/** Effective per-content-type gate for one IPTV provider: the provider's own flag,
 *  with Movies/Series additionally subject to the global VOD gate. */
internal fun MainActivity.providerAllowsLive(cfg: IptvProviderConfig): Boolean = cfg.liveEnabled

internal fun MainActivity.providerAllowsMovies(cfg: IptvProviderConfig): Boolean = !isVodDisabled() && cfg.moviesEnabled

internal fun MainActivity.providerAllowsSeries(cfg: IptvProviderConfig): Boolean = !isVodDisabled() && cfg.seriesEnabled

/** Jellyfin's content-type flags live in loose prefs (one slot, no IptvProviderConfig).
 *  Legacy jellyfin_disable_vod (movies+series off) maps into the individual flags until
 *  the new keys are written. */
internal fun MainActivity.jellyfinAllowsLive(): Boolean = prefs.getBoolean("jellyfin_live_enabled", true)

internal fun MainActivity.jellyfinAllowsMovies(): Boolean =
    !isVodDisabled() && jellyfinFlag("jellyfin_movies_enabled")

internal fun MainActivity.jellyfinAllowsSeries(): Boolean =
    !isVodDisabled() && jellyfinFlag("jellyfin_series_enabled")

internal fun MainActivity.jellyfinFlag(key: String): Boolean =
    if (prefs.contains(key)) prefs.getBoolean(key, true)
    else !prefs.getBoolean("jellyfin_disable_vod", false)

/** True when a channel survives the per-provider content-type gates for its owner.
 *  Used by the cold-start cache filter and cache resurrection - the cache is saved
 *  unfiltered, so a channel whose type was switched off since it was written would
 *  otherwise come back here. A channel with no configured owner passes. */
internal fun MainActivity.isTypeAllowed(ch: Channel, configs: List<IptvProviderConfig>): Boolean {
    val owner = ch.sourceProviderId?.let { id -> configs.firstOrNull { it.id == id } }
    return when (ch.mediaType) {
        MediaType.LIVE -> if (ch.isJellyfin) jellyfinAllowsLive() else owner?.let { providerAllowsLive(it) } ?: true
        MediaType.MOVIE -> if (ch.isJellyfin) jellyfinAllowsMovies() else owner?.let { providerAllowsMovies(it) } ?: true
        MediaType.SERIES -> if (ch.isJellyfin) jellyfinAllowsSeries() else owner?.let { providerAllowsSeries(it) } ?: true
    }
}

internal fun MainActivity.applySimpleModeUi() {
    val simple = isSimpleMode()
    // Chrome up = something to browse, so the tab bar would be showing in normal mode.
    // Simple mode hides it regardless; the flag still gates the forced tab switch below
    // (with no providers the empty state owns the screen and selectTab would fight it).
    val chromeUp = hasProviderEnabled() || hasProviderlessSource()
    if (simple) {
        // Every browsing tab goes, Live TV included - it would be the bar's only item,
        // worth no space - leaving just Settings/Refresh, the same screen the old "hide
        // the tab bar" produced now that those two live inside the scroller. Catch Up
        // stays reachable through its own chip, gated below same as normal mode.
        setBrowseTabsVisible(false)
        if (chromeUp) updateCatchupTabVisibility()
        // Live TV is the only pane reachable in simple mode - Home/Discover/Downloads
        // (and any Series/Movies drill) land back on it instead of leaving a hidden pane
        // on screen.
        if (chromeUp && (showingHome || showingDiscover || showingDownloads || activeTab != 0)) {
            if (!showingCatchup) selectTab(0)
        }
    } else {
        setBrowseTabsVisible(chromeUp)
        if (chromeUp) {
            // Downloads is phone-only and Catch Up needs an archive - the blanket show
            // above put both back, so re-apply their own rules.
            if (isTv) binding.tabDownloads.visibility = View.GONE
            updateCatchupTabVisibility()
            updateDiscoverTabVisibility()
            updateProviderOnlyTabsVisibility()
        }
        // Back to the full-row chain: Live -> Series -> Films -> Home -> Discover.
        binding.tabLive.nextFocusLeftId = R.id.tabDownloads
        binding.tabLive.nextFocusRightId = R.id.tabSeries
    }
    updateChromeFocusChain()
}

/** Discover (TMDB browse) is only useful once there's nothing else to browse: with an
 *  IPTV or Jellyfin provider enabled and no plugin, its own catalog covers Movies/Series
 *  and Discover would just be a second, disconnected way in - but a plugin (stream_search
 *  or scraper_sites) is Discover's own way of finding something to play, so it stays even
 *  once a provider exists (the startup chooser's "Both" pairs an IPTV/Jellyfin provider
 *  with public streaming plugins on purpose - a provider must not switch the plugins'
 *  entry point off). Also re-points its neighbors' D-pad targets, which have to skip the
 *  tab while it is GONE - a nextFocus onto a GONE view resolves to nothing and the press
 *  is silently eaten. */
internal fun MainActivity.updateDiscoverTabVisibility() {
    val available = !hasProviderEnabled() || hasProviderlessSource()
    binding.tabDiscover.visibility = if (available) View.VISIBLE else View.GONE
    binding.btnSearch.nextFocusLeftId = if (available) R.id.tabDiscover else R.id.tabFilms
    // The tab vanishing under the user (a provider enabled while they are in Discover)
    // must not leave the pane on screen with no way back to it.
    if (!available && showingDiscover) selectTab(0)
}

/** Home/Live are provider catalog views with nothing to show once there's no IPTV/Jellyfin
 *  provider - Live has no channels and Home's shelves are built from the same catalog.
 *  Series/Films are different: with a plugin enabled they still have something to browse,
 *  Discover's own TMDB catalog (see showDiscoverBackedCatalogTab), so they only disappear
 *  alongside Home/Live when there is truly nothing at all. Runs after
 *  updateDiscoverTabVisibility(), whose focus-chain fix it overrides where it would
 *  otherwise point at a tab this function just hid. */
internal fun MainActivity.updateProviderOnlyTabsVisibility() {
    val providerAvailable = hasProviderEnabled()
    val pluginAvailable = hasProviderlessSource()
    val catalogVis = if (providerAvailable) View.VISIBLE else View.GONE
    binding.tabHome.visibility = catalogVis
    binding.tabLive.visibility = catalogVis
    val seriesFilmsVis = if (providerAvailable || pluginAvailable) View.VISIBLE else View.GONE
    binding.tabSeries.visibility = seriesFilmsVis
    binding.tabFilms.visibility = seriesFilmsVis
    if (providerAvailable) {
        // Back to the XML chain, in case a previous no-provider pass below rewired these.
        binding.tabSeries.nextFocusLeftId = R.id.tabLive
        binding.tabFilms.nextFocusRightId = R.id.tabHome
    } else {
        binding.tabDiscover.nextFocusLeftId = if (pluginAvailable) R.id.tabFilms else R.id.tabDiscover
        if (pluginAvailable) {
            // Home/Live/Catchup are gone, so the row is just Series -> Films -> Discover.
            binding.tabSeries.nextFocusLeftId = R.id.tabSeries
            binding.tabFilms.nextFocusRightId = R.id.tabDiscover
        }
        // The tabs vanishing under the user (every provider disabled while on one of
        // them) must not leave the pane on screen with no way back to it. Series/Films
        // stay reachable with a plugin enabled, so only Home/Live losing their tab (or
        // every tab, with no plugin either) needs the bounce.
        val strandedOnHiddenTab = !showingDiscover && !showingDownloads && !showingCatchup &&
            (activeTab == 0 || !pluginAvailable)
        if (showingHome || strandedOnHiddenTab) selectDiscover()
    }
}

/** Re-runs the provider load so the VOD gate takes effect - VOD is skipped at fetch
 *  time (and filtered out of a cached cold start), so either toggle needs a reload. */
internal fun MainActivity.vodStateChanged() {
    if (hasProviderConfigured()) scope.launch { loadAllConfiguredProviders(forceRefresh = true) }
}

/** Shows the "no provider" empty state and, crucially, moves focus onto one of its
 *  buttons. With the tab bar and search hidden and the content panes gone, nothing else
 *  on screen is focusable - so without this the D-pad had nothing to land on and stopped
 *  responding entirely (the "can't navigate when nothing's returned" trap). */
internal fun MainActivity.showEmptyState() {
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
        val target = binding.emptyChooseProvider.takeIf { it.isShown }
            ?: return false
        return target.requestFocus()
    }
    binding.emptyState.post { if (!focusFirstAction()) binding.emptyState.post { focusFirstAction() } }
}

internal fun MainActivity.loadSavedProvider() {
    loadAllConfiguredProviders()
}

/** Whether a finished catalog load should refresh in place rather than run the full
 *  first-paint render.
 *
 *  [MainActivity.uiPainted] alone is not the question. A load kicked off from Settings sets it
 *  on the way past (the live-partial paint marks it even though the settings overlay
 *  suppressed the render), so adding the very first provider then closing Settings before the
 *  fetch landed left the app on the "no provider" empty state: the surgical refresh has no
 *  path that takes that screen down. If the empty state is what the user is looking at, the
 *  catalog that just arrived is first content, whatever the flag says. */
internal fun MainActivity.shouldPreserveUiOnLoad(): Boolean =
    uiPainted && binding.emptyState.visibility != View.VISIBLE

/** Reacts to a provider being switched on or off in Settings.
 *
 *  Switching one *off* needs no network at all: its items are already in memory and
 *  carry their own provenance, so they're just dropped. Re-fetching every other provider
 *  to achieve that meant a full "Connecting to ..." reload - visible behind the settings
 *  dialog - and left the catalog at the mercy of a provider that happened to be down.
 *  Switching one *on* genuinely needs its catalog, so that still refreshes. */
internal fun MainActivity.applyProviderToggle(enabled: Boolean, belongsToProvider: (Channel) -> Boolean) {
    if (enabled) { loadAllConfiguredProviders(forceRefresh = true); return }
    scope.launch {
        allChannels = allChannels.filterNot(belongsToProvider)
        classifyAndShow()
        persistCatalog(allChannels)
    }
}

/** Saves [channels] to the disk catalog cache, first merging back the previously-cached
 *  movies/series of any provider whose VOD gate is currently active. Fetch-time gates skip
 *  the slow VOD/series crawl, which would otherwise strip that provider's VOD from the
 *  cache and leave it unrecoverable until a network fetch runs with the gate lifted. The
 *  cold-start filter (loadAllConfiguredProviders) is what hides gated VOD at display time,
 *  so the cache itself stays display-unfiltered. */
internal suspend fun MainActivity.persistCatalog(channels: List<Channel>) = withContext(Dispatchers.IO) {
    val configs = IptvProviderStore.load(prefs)
    val configuredIds = configs.map { it.id }.toSet()
    // Fast path: no content-type gate is active (per-provider flags fold the global VOD
    // gate in via isVodDisabled), so the cache can be saved unfiltered.
    val anyGateOff = configs.any { !providerAllowsLive(it) || !providerAllowsMovies(it) || !providerAllowsSeries(it) } ||
        !jellyfinAllowsLive() || !jellyfinAllowsMovies() || !jellyfinAllowsSeries()
    if (!anyGateOff) {
        ChannelCache.save(this@persistCatalog, channels)
        return@withContext
    }
    val old = ChannelCache.load(this@persistCatalog)
    if (old.isNullOrEmpty()) {
        ChannelCache.save(this@persistCatalog, channels)
        return@withContext
    }
    val known = channels.map { it.id }.toSet()
    // Keep cached items whose type is currently gated OFF for their still-configured
    // owner, so re-enabling the type restores them without a network re-fetch. Items of
    // dropped/removed providers and of types that are ON do not resurrect.
    val resurrect = old.filter { ch ->
        if (ch.id in known) return@filter false
        val ownerConfigured = if (ch.isJellyfin) hasJellyfinConfigured()
        else ch.sourceProviderId != null && ch.sourceProviderId in configuredIds
        if (!ownerConfigured) return@filter false
        !isTypeAllowed(ch, configs)
    }
    ChannelCache.save(this@persistCatalog, channels + resurrect)
}

/** Drops catalog items that only exist because a plugin supplied them, once that plugin
 *  is gone or switched off.
 *
 *  The anime catalog is fetched by the stream_search plugin, and its items are written to
 *  the flat cache like everything else - but unlike a provider's channels they carry no
 *  sourceProviderId, so the enabled-provider filter passes them straight through. Disabling
 *  the plugin therefore left its titles (and the Anime sidebar row built from them) on
 *  screen until the cache happened to be rewritten by a network refresh. A fresh fetch
 *  already skips them; this is the cached cold-start path saying the same thing. */
internal fun MainActivity.dropDisabledPluginContent(channels: List<Channel>): List<Channel> {
    if (enabledAnimePlugin() != null) return channels
    return channels.filterNot { it.id.startsWith(AnimeCatalogClient.ID_PREFIX) }
}

/** True when the cached catalog is old enough to be worth re-fetching. A missing stamp
 *  counts as stale so a cache written by an older build refreshes once, then follows
 *  the TTL like everything else. */
internal fun MainActivity.isCatalogStale(): Boolean {
    val last = prefs.getLong(PREF_CATALOG_REFRESHED_AT, 0L)
    return last <= 0L || System.currentTimeMillis() - last >= CATALOG_TTL_MS
}

/** The one place that loads whatever's configured+enabled across every IPTV provider
 *  (any number of them now, not just one) plus Jellyfin, and merges the result - every
 *  settings Save/Quick Connect/reload call site routes through here instead of assuming
 *  a single active provider. [forceRefresh] skips the on-disk cache (used after a
 *  settings change, where showing stale content would be actively wrong) and re-fetches
 *  from the network(s) directly. */
internal fun MainActivity.loadAllConfiguredProviders(forceRefresh: Boolean = false) {
    // A stream_search plugin (anime catalog, Discover/Find Stream) still has work to do here
    // even with zero traditional providers configured - bailing out before reaching the
    // anime-catalog fetch below meant a plugin-only setup never populated anything.
    //
    // With neither, classifyAndShow's own hasContent check lands on showEmptyState() - the
    // first-run chooser - rather than auto-opening Settings unasked on a fresh install.
    if (!hasProviderConfigured() && !hasProviderlessSource()) { scope.launch { classifyAndShow() }; return }
    // Raised for the cached path too: reading and re-deriving a big catalog still takes
    // a few seconds, and with no status up the app just looks frozen.
    setStatus(getString(R.string.loading), visible = true)
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
        var cached: List<Channel>? = null
        if (!forceRefresh) {
            cached = withContext(Dispatchers.IO) { ChannelCache.load(this@loadAllConfiguredProviders) }
            if (!cached.isNullOrEmpty()) {
                // The content-type gates apply to a cached cold start too - the cache
                // is saved unfiltered, so types switched off since it was written would
                // otherwise resurrect here (a cache saved with VOD on, for example).
                val typeGates = IptvProviderStore.load(prefs)
                cached = if (isVodDisabled()) {
                    cached.filter { it.mediaType == MediaType.LIVE }
                } else {
                    cached.filter { isTypeAllowed(it, typeGates) }
                }
                cached = dropDisabledPluginContent(cached)
                // Paint the cached catalog immediately (Live first, films/series in background),
                // then only hit the network when the cache is stale - a non-stale cache returns
                // here; a stale one falls through and refreshes silently under the content.
                val hadContentOnScreen = uiPainted // content already rendered before this cached paint?
                allChannels = cached
                classifyAndShowLiveFirst()
                uiPainted = true
                deriveFilmsSeries()
                // On a cold start the initial tab render runs through selectTab (reached from
                // classifyAndShowLiveFirst above), which clears the status itself once content
                // lands. Clearing here first - before that render - blanked the screen into a
                // "Loading..." -> blank -> "Loading..." sandwich, because selectTab re-raises
                // the same message on entry. Only drop the status when content was already on
                // screen before this cached paint (a warm re-load).
                if (hadContentOnScreen) setStatus("", visible = false)
                // A cache written before a Channel field existed reads back with that
                // field at its default for every item - which is why Catch Up stayed
                // hidden on an upgraded install: tvArchive was false catalogue-wide until
                // something refetched. Treat that file as stale so the refresh below runs
                // once, silently, under the content already on screen.
                if (!isCatalogStale() && !ChannelCache.lastLoadWasLegacyFormat) return@launch
            }
        }

        val combined = mutableListOf<Channel>()
        val errors = mutableListOf<String>()
        var expiryText: String? = null

        val enabledConfigs = IptvProviderStore.load(prefs).filter { it.enabled }
        if (!uiPainted && enabledConfigs.isNotEmpty()) {
            setStatus(
                if (enabledConfigs.size == 1) getString(R.string.plug_connecting_to, enabledConfigs.first().name)
                else getString(R.string.plug_connecting_to_n, enabledConfigs.size),
                visible = true
            )
        }
        val animeDeferred = if (enabledAnimePlugin() != null) {
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
        // Jellyfin fetches alongside the IPTV providers rather than after all of them. It used
        // to run sequentially once awaitAll() returned, so its connect didn't even begin until
        // the slowest IPTV provider finished - up to PROVIDER_FETCH_TIMEOUT_MS of waiting
        // before the first Jellyfin byte moved, which is most of "Jellyfin takes a while".
        // Started here so it overlaps; awaited below with the rest.
        val jellyfinDeferred = if (hasJellyfinConfigured() && isJellyfinEnabled()) {
            // Bounded like every other provider. Unbounded, one unresponsive server held the
            // whole load open indefinitely, and because the loader cancelAndJoins the previous
            // run before starting a new one, every retry queued behind that stuck fetch
            // instead of replacing it.
            async { withTimeoutOrNull(PROVIDER_FETCH_TIMEOUT_MS) { fetchJellyfinChannels() } ?: FetchResult.Failure("Jellyfin: timed out") }
        } else null
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
        if (jellyfinDeferred != null) {
            if (!uiPainted) setStatus(getString(R.string.plug_connecting_to_jellyfin), visible = true)
            when (val result = jellyfinDeferred.await()) {
                is FetchResult.Success -> combined += result.channels
                is FetchResult.Failure -> errors += result.message
            }
        }
        val animeChannels = animeDeferred?.await()
        if (!animeChannels.isNullOrEmpty()) {
            combined += animeChannels
        }

        // A refresh that produced nothing must not wipe the cached catalog off disk or off the
        // screen: keep the previously-cached allChannels, surface the errors, and don't stamp
        // the TTL (a stamp here would leave the app on stale data for the whole TTL window).
        // Covers both the stale-cache refresh and a forceRefresh (e.g. a VOD-toggle reload)
        // whose fetches all failed - forceRefresh never loads `cached` up front, so fall back
        // to reading the disk cache here.
        if (combined.isEmpty()) {
            val fallback = (cached ?: withContext(Dispatchers.IO) { ChannelCache.load(this@loadAllConfiguredProviders) })
                ?.let { dropDisabledPluginContent(it) }
            if (!fallback.isNullOrEmpty()) {
                allChannels = fallback
                filmsSeriesDeriveJob?.cancel()
            }
            // classifyAndShow() has to run even when nothing above changed allChannels -
            // its own hasContent check also asks hasProviderlessSource(), and skipping this
            // call left a plugin-only setup (no IPTV/Jellyfin channels, empty/no disk cache)
            // with nothing ever painted past the toolbar: uiPainted stays false and no later
            // event re-triggers a render.
            classifyAndShow(preserveUi = shouldPreserveUiOnLoad())
            setStatus("", visible = false)
            if (errors.isNotEmpty()) {
                Toast.makeText(this@loadAllConfiguredProviders, errors.joinToString(" · "), Toast.LENGTH_LONG).show()
            }
            return@launch
        }

        allChannels = combined
        filmsSeriesDeriveJob?.cancel()
        classifyAndShow(preserveUi = shouldPreserveUiOnLoad())
        persistCatalog(allChannels)
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
                Toast.makeText(this@loadAllConfiguredProviders, errors.joinToString(" · "), Toast.LENGTH_LONG).show()
            }
        } else {
            val summary = getString(R.string.plug_item_count, combined.size) +
                (expiryText?.let { "  ·  $it" } ?: "") +
                (errors.takeIf { it.isNotEmpty() }?.let { "  ·  ⚠ " + it.joinToString(", ") } ?: "")
            setStatus(summary, visible = true)
            // A refresh over existing content can't use the status row (suppressed while a
            // pane owns the slot), so the outcome would otherwise be silent - and a failed
            // provider is exactly what the user needs told.
            if (binding.statusRow.visibility != View.VISIBLE) {
                Toast.makeText(this@loadAllConfiguredProviders, summary, Toast.LENGTH_LONG).show()
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
internal suspend fun MainActivity.fetchStalkerChannels(config: IptvProviderConfig, onLive: suspend (List<Channel>) -> Unit): FetchResult {
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
        val live = tag(liveResult.getOrThrow()).filter { providerAllowsLive(config) }
        onLive(live)

        // Content-type gates: a live-only setup never touches the (slow) VOD/series fetch.
        if (!providerAllowsMovies(config) && !providerAllowsSeries(config)) return FetchResult.Success(live)

        val vodSeriesResult = withContext(Dispatchers.IO) { stalker.loadVodAndSeries(stalkerProvider) }
        if (vodSeriesResult.isFailure) return FetchResult.Failure(vodSeriesResult.exceptionOrNull()?.message?.take(60) ?: "error")
        val (films, series) = vodSeriesResult.getOrThrow()
        FetchResult.Success(
            live +
                tag(films).filter { providerAllowsMovies(config) } +
                tag(series).filter { providerAllowsSeries(config) }
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FetchResult.Failure(e.message?.take(60) ?: "error")
    }
}

/**
 * Fetches trending anime from the public anime database. Returns Channel objects
 * (mediaType=SERIES) that populate the Series section. Playback is handled by the
 * stream_search plugin — these channels have no direct URL, just metadata for browsing.
 */
internal fun MainActivity.fetchAnimeChannels(): List<Channel> {
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

// ── M3U / Xtream channel fetch ─────────────────

internal suspend fun MainActivity.fetchM3uChannels(config: IptvProviderConfig): FetchResult {
    val url = config.url ?: return FetchResult.Failure("no URL")
    return try {
        val result = withContext(Dispatchers.IO) { M3uParser.parseFromUrl(url, BaseApplication.instance.okHttpClient) }
        // Content-type gates: an M3U file lists live and VOD in one parse - drop the
        // types this provider has switched off, per mediaType.
        val channels = result.channels.filter { ch ->
            when (ch.mediaType) {
                MediaType.LIVE -> providerAllowsLive(config)
                MediaType.MOVIE -> providerAllowsMovies(config)
                MediaType.SERIES -> providerAllowsSeries(config)
            }
        }
        // sourceProviderId isn't needed for playback here (an M3U item's url is already
        // final), but it's what names the provider a duplicate came from on the detail
        // screen's version chips - without it every M3U copy is an anonymous "Version N".
        FetchResult.Success(channels.map { it.copy(streamUserAgent = config.userAgent, sourceProviderId = config.id) })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FetchResult.Failure(e.message?.take(60) ?: "error")
    }
}

internal suspend fun MainActivity.fetchXtreamChannels(config: IptvProviderConfig, onExpiry: (String?) -> Unit): FetchResult {
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
            // Content-type gates: the (slow) VOD/series category+stream fetches are
            // skipped entirely for types this provider has switched off.
            val liveOff = !providerAllowsLive(config)
            val moviesOff = !providerAllowsMovies(config)
            val seriesOff = !providerAllowsSeries(config)
            val liveCatsDeferred: Deferred<List<Pair<String, String>>>? = if (liveOff) null else async { runCatching { client.getLiveCategories(xtreamProvider) }.getOrDefault(emptyList()) }
            val vodCatsDeferred: Deferred<List<Pair<String, String>>>? = if (moviesOff) null else async { runCatching { client.getVodCategories(xtreamProvider) }.getOrDefault(emptyList()) }
            val seriesCatsDeferred: Deferred<List<Pair<String, String>>>? = if (seriesOff) null else async { runCatching { client.getSeriesCategories(xtreamProvider) }.getOrDefault(emptyList()) }
            val liveDeferred: Deferred<List<Channel>>? = if (liveOff) null else async { client.getLiveStreams(xtreamProvider) }
            val filmsDeferred: Deferred<List<Channel>>? = if (moviesOff) null else async { client.getVodStreams(xtreamProvider) }
            val seriesDeferred: Deferred<List<Channel>>? = if (seriesOff) null else async { client.getSeries(xtreamProvider) }

            val liveCatNames = liveCatsDeferred?.await()?.toMap() ?: emptyMap()
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
            live = liveDeferred?.await()?.map { withCategory(it, liveCatNames, "uncat_live") } ?: emptyList()
            films = filmsDeferred?.await()?.map { withCategory(it, vodCatNames, "uncat_vod") } ?: emptyList()
            series = seriesDeferred?.await()?.map { withCategory(it, seriesCatNames, "uncat_series") } ?: emptyList()
        }

        // Persist the subscription expiry so Settings' "Active until / Expired" line can
        // display - xtream_exp_date / xtream_is_trial were never written anywhere before,
        // so the Settings read always came back empty. putString(key, null) removes the
        // key, which drops a stale expiry when the account stops reporting one.
        prefs.edit()
            .putString("xtream_exp_date", auth.expDateSeconds?.toString())
            .putBoolean("xtream_is_trial", auth.isTrial)
            .apply()
        onExpiry(formatSubscriptionStatus(auth.expDateSeconds, auth.isTrial))
        FetchResult.Success(live + films + series)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FetchResult.Failure(e.message?.take(60) ?: "error")
    }
}

// ── First-run startup chooser (empty state) ──

/** Wires the empty state's three first-run entry points. Only reachable while there is
 *  neither a provider nor a plugin enabled - showEmptyState() is the sole caller of the
 *  screen that hosts them. */
internal fun MainActivity.wireStartupChooser() {
    binding.emptyChooseProvider.setOnClickListener { showProviderSettings() }
    binding.emptyChoosePublic.setOnClickListener {
        installPublicStreamingPlugins { selectDiscover() }
    }
    binding.emptyChooseBoth.setOnClickListener {
        installPublicStreamingPlugins { showProviderSettings() }
    }
}

/** Downloads and installs every stream_search/scraper_sites script the default plugin
 *  store lists - "public streaming content" is torrent/site-scraper plugins, not a
 *  traditional provider. installScript() switches a first install on by itself, so
 *  nothing here has to enable them separately. Runs [onDone] whether or not anything
 *  actually installed - a store outage must not strand the user on a dead button. */
internal fun MainActivity.installPublicStreamingPlugins(onDone: () -> Unit) {
    ensurePublicContentDisclaimerAccepted { doInstallPublicStreamingPlugins(onDone) }
}

private fun MainActivity.doInstallPublicStreamingPlugins(onDone: () -> Unit) {
    val status = binding.emptyStartupStatus
    status.text = getString(R.string.plug_downloading_streaming_plugins)
    status.visibility = View.VISIBLE
    binding.emptyChooseProvider.isEnabled = false
    binding.emptyChoosePublic.isEnabled = false
    binding.emptyChooseBoth.isEnabled = false
    scope.launch {
        val wanted = setOf(JsPluginContract.CAPABILITY_STREAM_SEARCH, JsPluginContract.CAPABILITY_SCRAPER_SITES)
        var installed = 0
        for (store in pluginStoreManager.storeUrls()) {
            val catalog = pluginStoreManager.fetchCatalog(store.url).getOrNull() ?: continue
            for (storeScript in catalog) {
                if (wanted.none { it in storeScript.capabilities }) continue
                val text = pluginStoreManager.fetchScriptText(storeScript.fileUrl) ?: continue
                if (pluginScriptManager.installScript(text) is PluginScriptManager.InstallResult.Installed) installed++
            }
        }
        binding.emptyChooseProvider.isEnabled = true
        binding.emptyChoosePublic.isEnabled = true
        binding.emptyChooseBoth.isEnabled = true
        status.visibility = View.GONE
        if (installed == 0) {
            Toast.makeText(this@doInstallPublicStreamingPlugins, getString(R.string.plug_couldnt_reach_plugin_store), Toast.LENGTH_SHORT).show()
            return@launch
        }
        pluginScriptManager.discoverScripts()
        // The empty state was showing because there was nothing to browse - there is now,
        // so take the chrome (tab bar/search) out of its "nothing configured" hide before
        // handing off to the caller's destination.
        binding.emptyState.visibility = View.GONE
        updateTopChromeVisibility()
        onDone()
    }
}
