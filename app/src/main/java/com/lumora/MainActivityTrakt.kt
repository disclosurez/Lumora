package com.lumora

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.lumora.cache.PlaybackPositionStore
import com.lumora.cache.WatchedStore
import com.lumora.data.TraktStore
import com.lumora.data.remote.trakt.TraktClient
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.pairing.QrPairingManager
import com.lumora.util.cleanVodTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Trakt: device sign-in, scrobbling, watched sync ──
//
// Extracted from MainActivity.kt; see that file's header.
//
// Three separable jobs, gated by two independent toggles (see TraktStore):
//
//   1. Sign-in     - the device flow, run from the Settings pane.
//   2. Scrobbling  - /scrobble/start|pause|stop as the player runs. Trakt decides watched
//                    state from where a stop lands, so the app never asserts it here.
//   3. Watched sync- /sync/history for marks the player never sees (the detail screen's
//                    watched toggle), plus a pull of /sync/watched into WatchedStore so
//                    what was watched elsewhere reads as watched here.
//
// Everything is best effort and silent on failure. A tracker that interrupts playback to
// complain about its own network is worse than one that quietly misses a report.

// ── Session ─────────────────────────────────────

/**
 * A usable access token, refreshing first when the stored one is close to expiry.
 *
 * Trakt tokens last three months, so this almost always returns immediately; the refresh path
 * matters over the life of an install rather than within a session. A refresh that Trakt
 * *rejects* (the grant was revoked in Trakt's own settings, or rotated out from under us) drops
 * the session, because nothing it holds can ever work again - while a refresh that merely
 * failed to reach Trakt leaves it alone to be retried on the next call.
 *
 * Null means "no usable session": not signed in, unconfigured build, or a refresh that failed.
 */
internal suspend fun MainActivity.traktAccessToken(): String? {
    if (!TraktClient.isConfigured) return null
    val tokens = TraktStore.tokens(prefs) ?: return null
    // A day's grace: the refresh is cheap and a token that expires mid-play would otherwise
    // fail the stop report, which is the one report that matters.
    if (tokens.expiresAtMs - System.currentTimeMillis() > 24 * 60 * 60 * 1000L) return tokens.accessToken

    val refreshed = traktClient.refresh(tokens.refreshToken)
    if (refreshed != null) {
        TraktStore.saveTokens(prefs, refreshed)
        return refreshed.accessToken
    }
    if (traktClient.lastRefreshWasRejected) {
        TraktStore.clear(prefs)
        return null
    }
    // Network failure - the stored token may still have days on it, so let the call try.
    return tokens.accessToken
}

internal fun MainActivity.isTraktSignedIn(): Boolean =
    TraktClient.isConfigured && TraktStore.isSignedIn(prefs)

// ── Identifying a title to Trakt ────────────────

/**
 * Turns a playing [Channel] into the movie or episode Trakt should be told about, or null when
 * it can't be identified.
 *
 * Trakt keys on its own ids plus imdb/tmdb/tvdb, and an IPTV panel gives us a name and maybe a
 * year. TMDB is the bridge - [MainActivity.tmdbClient]'s resolveId already turns a catalogue
 * title into a tmdb id for Discover's library matching, and Trakt takes `ids: { tmdb: N }`.
 *
 * An episode resolves through its *show*: the parent series entry supplies the title to look up
 * and the episode supplies season/number. That is both what Trakt prefers and the only shape
 * that works for a show whose current season isn't in Trakt's catalogue yet. An episode whose
 * parent series can't be resolved is dropped rather than guessed at - scrobbling the wrong show
 * writes to someone's public profile.
 *
 * Live TV never resolves: a channel is not a title, and the EPG programme on it is not
 * something Trakt models.
 */
internal suspend fun MainActivity.traktTargetFor(channel: Channel): TraktClient.ScrobbleTarget? {
    if (!tmdbClient.hasKey()) return null
    return when (channel.mediaType) {
        MediaType.LIVE -> null
        MediaType.MOVIE -> {
            val (_, id) = tmdbClient.resolveId(cleanVodTitle(channel.name), channel.year, isSeries = false)
                ?: return null
            TraktClient.ScrobbleTarget(tmdbId = id, isSeries = false)
        }
        MediaType.SERIES -> {
            val episodeNum = channel.episodeNum ?: return null
            val series = resolveHomeTileSeries(channel) ?: return null
            val (_, id) = tmdbClient.resolveId(cleanVodTitle(series.name), series.year, isSeries = true)
                ?: return null
            // A show with no season marker anywhere is treated as season 1 - the flat-numbered
            // strands that omit it are all single-season as far as TMDB and Trakt are concerned,
            // and the alternative is dropping them entirely.
            TraktClient.ScrobbleTarget(
                tmdbId = id,
                isSeries = true,
                season = tileSeasonNumber(channel) ?: 1,
                episode = episodeNum
            )
        }
    }
}

/** Identity of a play, for discarding a TMDB lookup that outlived what it was looking up. */
private fun playKey(channel: Channel): String = channel.id.ifBlank { channel.url }

// ── Scrobbling ──────────────────────────────────

/**
 * Start of a play: resolves the title in the background and reports `start` once it lands.
 *
 * The resolve is a TMDB search, so it can take a second or two on a cold cache - deliberately
 * off the path that gets the picture on screen. Whatever is playing when the answer arrives is
 * checked against what was asked about, so a quick channel-surf doesn't scrobble the title the
 * user skipped past.
 */
internal fun MainActivity.traktReportStart(channel: Channel) {
    traktResolveJob?.cancel()
    traktScrobbleTarget = null
    traktScrobbleForKey = null
    traktLastReportedPaused = null
    if (!isTraktSignedIn() || !TraktStore.isScrobbleEnabled(prefs)) return
    if (channel.mediaType == MediaType.LIVE) return

    val key = playKey(channel)
    traktResolveJob = scope.launch {
        val target = traktTargetFor(channel) ?: return@launch
        // Something else started while the lookup ran - that play owns the scrobble now.
        if (playKey(nowPlayingChannel ?: return@launch) != key) return@launch
        traktScrobbleTarget = target
        traktScrobbleForKey = key
        traktSend(TraktClient.ScrobbleAction.START, target, traktProgressPercent())
        traktLastReportedPaused = false
    }
}

/**
 * Called off the same tick the Jellyfin/Plex heartbeats use, but Trakt is not a heartbeat API -
 * it wants transitions. So this only sends when the paused state has actually changed since the
 * last report: pausing sends `pause`, resuming sends `start` again with the new position.
 */
internal fun MainActivity.traktReportProgress() {
    val target = traktScrobbleTarget ?: return
    if (!isTraktSignedIn() || !TraktStore.isScrobbleEnabled(prefs)) return
    val paused = !playerManager.isPlaying
    if (paused == traktLastReportedPaused) return
    traktLastReportedPaused = paused
    traktSend(
        if (paused) TraktClient.ScrobbleAction.PAUSE else TraktClient.ScrobbleAction.START,
        target,
        traktProgressPercent()
    )
}

/**
 * End of a play. The progress sent here is what Trakt turns into either a watched mark (at or
 * above 80%) or a resume point (below it), so it has to go out before the player state is torn
 * down - same ordering constraint as reportJellyfinStopped().
 */
internal fun MainActivity.traktReportStopped() {
    val target = traktScrobbleTarget
    val progress = traktProgressPercent()
    traktResolveJob?.cancel()
    traktResolveJob = null
    traktScrobbleTarget = null
    traktScrobbleForKey = null
    traktLastReportedPaused = null
    if (target == null) return
    if (!isTraktSignedIn() || !TraktStore.isScrobbleEnabled(prefs)) return
    traktSend(TraktClient.ScrobbleAction.STOP, target, progress)
}

/** Percent through the item, 0-100. Zero when the duration isn't known yet (a live stream, or
 *  a play that failed before the first frame) - Trakt reads that as "barely started", which is
 *  the truthful answer in both cases. */
private fun MainActivity.traktProgressPercent(): Double {
    val duration = playerManager.duration
    if (duration <= 0L) return 0.0
    val position = playerManager.currentPosition.coerceAtLeast(0L)
    return (position.toDouble() / duration.toDouble() * 100.0).coerceIn(0.0, 100.0)
}

private fun MainActivity.traktSend(
    action: TraktClient.ScrobbleAction,
    target: TraktClient.ScrobbleTarget,
    progress: Double
) {
    scope.launch(Dispatchers.IO) {
        val token = traktAccessToken() ?: return@launch
        runCatching { traktClient.scrobble(token, action, target, progress) }
    }
}

// ── Continue Watching ───────────────────────────

/**
 * Drops [items] from Trakt's own Continue Watching, to follow a local clear.
 *
 * Trakt keeps its resume points in a list of its own (`/sync/playback`) that scrobbling writes
 * and nothing else touches - so clearing an item here and not there leaves it offered on every
 * other Trakt client, and pulled straight back the next time anything reads that list.
 *
 * Entries are matched by [TraktClient.ScrobbleTarget], the same tmdb-id/season/episode identity
 * a scrobble is sent under, because a playback entry's own id is per-entry and means nothing
 * locally. Anything that doesn't resolve to a target is left alone: a delete is destructive and
 * a guessed match would remove someone else's progress on an unrelated title.
 *
 * Gated on scrobbling rather than on watched sync - the playback list is what scrobbling
 * writes, so an install that never scrobbled has nothing of its own to remove. Best effort and
 * silent, like the rest of the Trakt path.
 */
internal fun MainActivity.traktRemovePlayback(items: List<Channel>) {
    if (items.isEmpty()) return
    if (!isTraktSignedIn() || !TraktStore.isScrobbleEnabled(prefs)) return
    val subjects = items.filter { it.mediaType != MediaType.LIVE }
    if (subjects.isEmpty()) return
    scope.launch {
        val token = traktAccessToken() ?: return@launch
        // Read the list first: no resolution work is worth doing for an account with nothing
        // paused, which is the common case right after a clear-all.
        val entries = traktClient.playback(token)
        if (entries.isEmpty()) return@launch
        val wanted = entries.map { it.target }.toSet()
        val targets = mutableSetOf<TraktClient.ScrobbleTarget>()
        for (item in subjects) {
            val target = traktTargetFor(item) ?: continue
            // Each resolve is a TMDB lookup; stop as soon as every paused entry is accounted
            // for rather than walking a long clear-all list to no purpose.
            if (target in wanted) targets.add(target)
            if (targets.size == wanted.size) break
        }
        if (targets.isEmpty()) return@launch
        for (entry in entries) {
            if (entry.target in targets) traktClient.removePlayback(token, entry.id)
        }
    }
}

// ── Watched sync ────────────────────────────────

/**
 * Mirrors a watched mark onto Trakt, for the marks the player never sees: the detail screen's
 * per-episode toggle and its whole-season tick.
 *
 * Runs only under the watched-sync toggle, not the scrobble one - see [TraktStore]. A play that
 * ran through the player has already been scrobbled, and Trakt de-duplicates a history add
 * against it, so the overlap is harmless.
 */
internal fun MainActivity.pushWatchedToTrakt(item: Channel, watched: Boolean) {
    if (!isTraktSignedIn() || !TraktStore.isWatchedSyncEnabled(prefs)) return
    if (item.mediaType == MediaType.LIVE) return
    scope.launch {
        val target = traktTargetFor(item) ?: return@launch
        val token = traktAccessToken() ?: return@launch
        withContext(Dispatchers.IO) {
            runCatching {
                if (watched) traktClient.addToHistory(token, target)
                else traktClient.removeFromHistory(token, target)
            }
        }
    }
}

/**
 * Pulls the account's watched history into [WatchedStore] so anything seen on another client
 * reads as watched here.
 *
 * Writes through the same title-derived keys the rest of the app uses
 * ([movieWatchedKey]/[episodeWatchedKey]), which is what makes this work at all: Trakt knows
 * titles and season/episode numbers, not this install's provider-scoped ids, and those keys are
 * built from exactly that. A show watched on Trakt therefore reads as watched on every copy in
 * the catalogue, including providers added later.
 *
 * Additive only. A title *absent* from Trakt is not evidence it wasn't watched - it may simply
 * predate the connection, or have been watched on a provider Trakt never heard about - so
 * nothing is ever un-marked from here. Removal stays a deliberate act in the detail screen.
 *
 * Rate-limited to [PULL_INTERVAL_MS] because it is two whole-library responses; [force] is the
 * Settings pane's "Sync now", which means now.
 */
internal fun MainActivity.pullTraktWatched(force: Boolean = false, onDone: ((Int) -> Unit)? = null) {
    if (!isTraktSignedIn() || !TraktStore.isWatchedSyncEnabled(prefs)) {
        onDone?.invoke(0)
        return
    }
    if (!force && System.currentTimeMillis() - TraktStore.lastWatchedPullMs(prefs) < PULL_INTERVAL_MS) {
        onDone?.invoke(0)
        return
    }
    scope.launch {
        val token = traktAccessToken() ?: run { onDone?.invoke(0); return@launch }
        val entries = withContext(Dispatchers.IO) {
            runCatching { traktClient.watched(token) }.getOrDefault(emptyList())
        }
        if (entries.isEmpty()) { onDone?.invoke(0); return@launch }
        val added = withContext(Dispatchers.Default) {
            var count = 0
            for (entry in entries) {
                if (entry.isSeries) {
                    for ((season, numbers) in entry.episodes) {
                        for (number in numbers) {
                            val key = episodeWatchedKey(entry.title, season, number) ?: continue
                            if (WatchedStore.setWatched(this@pullTraktWatched, key, true)) count++
                        }
                    }
                } else {
                    val key = movieWatchedKey(entry.title) ?: continue
                    if (WatchedStore.setWatched(this@pullTraktWatched, key, true)) count++
                }
            }
            count
        }
        TraktStore.markWatchedPulled(prefs)
        if (added > 0) {
            // Watched state decides what "next episode" means, so the memo behind Up Next is
            // stale the moment any mark lands.
            clearUpNextMemo()
            // The catalog this install is matching Trakt titles against may not be loaded yet
            // (this whole pull runs off app launch, racing loadSavedProvider's own async
            // fetch) - reconcileTraktUpNextTrails is also called from classifyAndShow, so a
            // catalog that lands after this point still picks these marks up.
            reconcileTraktUpNextTrails()
            refreshHomeShelvesIfShowing()
            refreshSeriesShelvesIfShowing()
        }
        onDone?.invoke(added)
    }
}

/**
 * Gives every series [WatchedStore] knows has at least one watched episode a completed-trail
 * stub in [PlaybackPositionStore], so [PlaybackPositionStore.getCompletedSeriesTrails] - and so
 * Up Next - can find it.
 *
 * Reads from [WatchedStore] rather than a Trakt response directly, which is what makes this
 * safe to call from anywhere the catalog might have just changed (see [classifyAndShow]) as
 * well as from [pullTraktWatched] itself: a mark that arrived from Trakt before the catalog had
 * loaded this show is still on disk in [WatchedStore], and gets matched up the next time the
 * catalog does load - not silently dropped because the two raced on app launch.
 *
 * Idempotent and cheap once a series has a stub: an existing `trakt|<id>` entry is left alone
 * rather than rewritten, so this can run on every catalog rebuild without spamming disk writes
 * or reshuffling Up Next's most-recent ordering.
 */
internal fun MainActivity.reconcileTraktUpNextTrails() {
    if (allChannels.isEmpty()) return
    val seriesIndex = allChannels.asSequence()
        .filter { it.mediaType == MediaType.SERIES && it.episodeNum == null }
        .associateBy { com.lumora.util.normalizeTitleForGrouping(cleanVodTitle(it.name)) }
    if (seriesIndex.isEmpty()) return
    val seenTitles = HashSet<String>()
    for (key in WatchedStore.allKeys(this)) {
        val parts = key.split('|')
        if (parts.size != 4 || parts[0] != "e") continue
        val normalized = parts[1]
        if (!seenTitles.add(normalized)) continue
        val series = seriesIndex[normalized] ?: continue
        if (series.id.isBlank()) continue
        val copyKey = "trakt|${series.id}"
        if (PlaybackPositionStore.get(this, copyKey) != null) continue
        PlaybackPositionStore.save(this, copyKey, 1L, 1L, series.copy(id = copyKey, categoryId = series.id))
    }
}

/** Six hours. Long enough that launching the app repeatedly costs nothing, short enough that a
 *  day's watching elsewhere shows up without the user asking. */
private const val PULL_INTERVAL_MS = 6L * 60 * 60 * 1000

/**
 * How many *distinct titles* one backfill run may look up on TMDB.
 *
 * Each unresolved title is one or two TMDB searches, so an untouched library of a few hundred
 * shows would otherwise be a long unbroken burst of requests the first time somebody presses
 * Sync. The budget is per run and the answers are memoised, so a second press picks up where the
 * first stopped and a settled library costs nothing.
 */
private const val BACKFILL_RESOLVE_BUDGET = 150

/**
 * Pushes watched marks this install already had up to Trakt.
 *
 * The per-mark push in [pushWatchedToTrakt] only ever fires on a *new* mark, so a library that
 * was watched before the account was connected stays invisible to Trakt no matter how long it
 * runs. This is the other half: the whole of [WatchedStore], turned back into titles.
 *
 * ## Turning a mark back into a title
 *
 * A mark is keyed by what was watched rather than by which copy played, which is what makes it
 * work across providers - but the key is lossy on purpose: `e|breaking bad|s5|e14`, with the
 * title already normalised down for matching. Trakt needs a real title to look up.
 *
 * So the catalogue supplies it. Every series and film in [MainActivity.allChannels] is indexed by
 * the *same* normalisation the key was built with, which turns the key's title fragment back into
 * the catalogue entry it came from - and that entry has the real name and year that TMDB wants.
 * [PlaybackPositionStore] snapshots are folded in as a second source, because they carry a whole
 * [Channel] each and so cover titles that have since left the catalogue.
 *
 * A mark whose title matches neither is skipped. There is genuinely nothing to send: no title, no
 * TMDB id, no Trakt item.
 *
 * ## Not re-sending
 *
 * `/sync/watched` is pulled first and everything Trakt already holds is subtracted, so a run
 * after the first sends only what is genuinely missing and a settled library sends nothing at
 * all. That, plus the id memo, is what makes this safe to wire into the ordinary Sync now.
 */
internal fun MainActivity.pushExistingWatchedToTrakt(onDone: ((pushed: Int, unresolved: Int) -> Unit)? = null) {
    if (!isTraktSignedIn() || !TraktStore.isWatchedSyncEnabled(prefs)) {
        onDone?.invoke(0, 0)
        return
    }
    scope.launch {
        val token = traktAccessToken() ?: run { onDone?.invoke(0, 0); return@launch }

        // What Trakt already has, so nothing is sent twice.
        val remote = withContext(Dispatchers.IO) {
            runCatching { traktClient.watched(token) }.getOrDefault(emptyList())
        }
        val remoteMovies = remote.filterNot { it.isSeries }.mapNotNull { it.tmdbId }.toHashSet()
        val remoteEpisodes = HashMap<Int, MutableMap<Int, MutableSet<Int>>>()
        for (entry in remote) {
            val id = entry.tmdbId ?: continue
            if (!entry.isSeries) continue
            val seasons = remoteEpisodes.getOrPut(id) { HashMap() }
            for ((season, numbers) in entry.episodes) seasons.getOrPut(season) { mutableSetOf() }.addAll(numbers)
        }

        // Normalised title -> a real title (and year) to search TMDB with. The catalogue first,
        // then position-store snapshots for anything that has since left it.
        val titleIndex = withContext(Dispatchers.Default) { buildWatchedTitleIndex() }
        val keys = withContext(Dispatchers.IO) { WatchedStore.allKeys(this@pushExistingWatchedToTrakt) }

        val memo = HashMap(TraktStore.idMemo(prefs))
        var budget = BACKFILL_RESOLVE_BUDGET
        var unresolved = 0
        val movies = HashSet<Int>()
        val shows = HashMap<Int, MutableMap<Int, MutableSet<Int>>>()

        /** Resolved tmdb id for a normalised title, memoised - including the misses. */
        suspend fun tmdbIdFor(normalized: String, isSeries: Boolean): Int? {
            val memoKey = (if (isSeries) "e|" else "m|") + normalized
            memo[memoKey]?.let { return it.takeIf { id -> id > 0 } }
            val known = titleIndex[memoKey] ?: return null
            if (budget <= 0) return null
            budget--
            val resolved = tmdbClient.resolveId(known.title, known.year, isSeries)?.second
            memo[memoKey] = resolved ?: -1
            return resolved
        }

        for (key in keys) {
            val parts = key.split('|')
            when {
                parts.size == 2 && parts[0] == "m" -> {
                    val id = tmdbIdFor(parts[1], isSeries = false) ?: run { unresolved++; null } ?: continue
                    if (id !in remoteMovies) movies.add(id)
                }
                parts.size == 4 && parts[0] == "e" -> {
                    // `s0` is the store's "no season stated" - the flat-numbered strands. Trakt
                    // has no season zero for those, so they go to season 1, matching what
                    // traktTargetFor does for the same case.
                    val season = parts[2].removePrefix("s").toIntOrNull()?.takeIf { it > 0 } ?: 1
                    val episode = parts[3].removePrefix("e").toIntOrNull() ?: continue
                    val id = tmdbIdFor(parts[1], isSeries = true) ?: run { unresolved++; null } ?: continue
                    if (remoteEpisodes[id]?.get(season)?.contains(episode) == true) continue
                    shows.getOrPut(id) { HashMap() }.getOrPut(season) { mutableSetOf() }.add(episode)
                }
            }
        }
        TraktStore.saveIdMemo(prefs, memo)

        if (movies.isEmpty() && shows.isEmpty()) {
            onDone?.invoke(0, unresolved)
            return@launch
        }
        val ok = withContext(Dispatchers.IO) {
            runCatching { traktClient.addWatchedBatch(token, movies, shows) }.getOrDefault(false)
        }
        val pushed = if (ok) movies.size + shows.values.sumOf { seasons -> seasons.values.sumOf { it.size } } else 0
        onDone?.invoke(pushed, unresolved)
    }
}

/**
 * Both directions, in the order that avoids work: pull what Trakt has into [WatchedStore] first,
 * then push whatever is still only local.
 *
 * Pulling first matters - the push subtracts what Trakt already holds, so a title that arrived in
 * the pull is one the push then correctly skips instead of echoing straight back.
 */
internal fun MainActivity.traktSyncBothWays(onStatus: ((String) -> Unit)? = null) {
    if (!isTraktSignedIn() || !TraktStore.isWatchedSyncEnabled(prefs)) return
    pullTraktWatched(force = true) { pulled ->
        onStatus?.invoke(getString(R.string.trakt_pushing))
        pushExistingWatchedToTrakt { pushed, unresolved ->
            onStatus?.invoke(
                if (unresolved > 0) getString(R.string.trakt_synced_both_partial, pulled, pushed, unresolved)
                else getString(R.string.trakt_synced_both, pulled, pushed)
            )
        }
    }
}

/** A real title to hand TMDB, recovered from a normalised watched key. */
private data class WatchedTitle(val title: String, val year: String?)

/**
 * Indexes every title this install knows about by the key prefix a watched mark would carry:
 * `m|<normalised>` for a film, `e|<normalised>` for a show.
 *
 * Two sources, in order of trust. The catalogue is authoritative and complete for anything still
 * installed. [PlaybackPositionStore] snapshots fill the gap behind it - each carries a whole
 * [Channel], so a title watched on a provider that has since been removed still has a real name
 * to search with. Existing entries win: a live catalogue name is better than a months-old
 * snapshot of one.
 */
private fun MainActivity.buildWatchedTitleIndex(): Map<String, WatchedTitle> {
    val index = HashMap<String, WatchedTitle>()
    fun put(prefix: String, rawTitle: String, year: String?) {
        val cleaned = cleanVodTitle(rawTitle)
        if (cleaned.isBlank()) return
        val normalized = com.lumora.util.normalizeTitleForGrouping(cleaned)
        if (normalized.isBlank()) return
        index.putIfAbsent("$prefix|$normalized", WatchedTitle(cleaned, year))
    }
    for (channel in allChannels) {
        when (channel.mediaType) {
            MediaType.MOVIE -> put("m", channel.name, channel.year)
            // Top-level series entries only. An episode's own name is the episode's, and its
            // show is already in this list under its own entry.
            MediaType.SERIES -> if (channel.episodeNum == null) put("e", channel.name, channel.year)
            MediaType.LIVE -> Unit
        }
    }
    for (item in com.lumora.cache.PlaybackPositionStore.getAllInProgress(this)) {
        when (item.mediaType) {
            MediaType.MOVIE -> put("m", item.name, item.year)
            MediaType.SERIES -> resolveHomeTileSeries(item)?.let { put("e", it.name, it.year) }
            MediaType.LIVE -> Unit
        }
    }
    return index
}

// ── Sign-in (OAuth device flow) ─────────────────

/**
 * Runs a full device-code sign-in, reporting progress through the callbacks so the caller owns
 * the presentation.
 *
 * The shape is the same as [performPlexSignIn]: mint a code, show it (as text to type at
 * trakt.tv/activate and as a QR of the same URL with the code already in it), then poll until
 * the user finishes on their phone. Trakt signals the whole flow through HTTP status codes
 * rather than a body, which [TraktClient.pollDeviceToken] maps to cases.
 *
 * Polls at the interval Trakt asked for and backs off by 5s on a 429, as RFC 8628 prescribes -
 * polling faster than told is how an app gets its key rate-limited.
 */
internal suspend fun MainActivity.performTraktSignIn(
    onQr: (android.graphics.Bitmap?) -> Unit = {},
    onCode: (String?) -> Unit = {},
    onStatus: (String) -> Unit
): Boolean {
    if (!TraktClient.isConfigured) {
        onStatus(getString(R.string.trakt_not_configured))
        return false
    }
    onStatus(getString(R.string.trakt_starting))
    val code = traktClient.createDeviceCode() ?: run {
        onStatus(getString(R.string.trakt_couldnt_start))
        return false
    }
    onQr(runCatching { QrPairingManager.createQrBitmap(code.activateUrlWithCode) }.getOrNull())
    onCode(code.userCode)
    onStatus(getString(R.string.trakt_enter_code_at, code.verificationUrl))

    var intervalMs = code.intervalSeconds.coerceAtLeast(1) * 1000L
    val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
    var tokens: TraktClient.Tokens? = null
    var failure: String? = null

    while (System.currentTimeMillis() < deadline && tokens == null) {
        delay(intervalMs)
        if (!kotlin.coroutines.coroutineContext.isActive) return false
        when (val poll = traktClient.pollDeviceToken(code.deviceCode)) {
            is TraktClient.DevicePoll.Success -> tokens = poll.tokens
            TraktClient.DevicePoll.Pending -> Unit
            // Unknown covers a network blip and any status Trakt doesn't document. Neither is
            // a reason to tear down a code the user may be halfway through typing.
            TraktClient.DevicePoll.Unknown -> Unit
            TraktClient.DevicePoll.SlowDown -> intervalMs += 5000L
            TraktClient.DevicePoll.Denied -> { failure = getString(R.string.trakt_denied); break }
            TraktClient.DevicePoll.Expired -> { failure = getString(R.string.trakt_code_expired); break }
        }
    }

    onQr(null)
    onCode(null)
    val session = tokens ?: run {
        onStatus(failure ?: getString(R.string.trakt_timed_out))
        return false
    }

    TraktStore.saveTokens(prefs, session)
    val username = withContext(Dispatchers.IO) {
        runCatching { traktClient.username(session.accessToken) }.getOrNull()
    }
    TraktStore.saveUsername(prefs, username)
    onStatus(
        if (username != null) getString(R.string.trakt_signed_in_as, username)
        else getString(R.string.trakt_signed_in)
    )
    // A fresh connection is exactly when the two histories are worth reconciling - and that
    // means both directions: an install with years of local watched marks has to send them, or
    // Trakt stays empty however long the app runs (the per-mark push only fires on new marks).
    traktSyncBothWays { text -> onStatus(text) }
    return true
}

internal fun MainActivity.traktSignOut() {
    val token = TraktStore.tokens(prefs)?.accessToken
    TraktStore.clear(prefs)
    traktScrobbleTarget = null
    traktScrobbleForKey = null
    traktLastReportedPaused = null
    if (token != null) {
        // Best effort, and deliberately after the local clear: a revoke that fails must not
        // leave the app still holding a session the user asked it to forget.
        scope.launch(Dispatchers.IO) { runCatching { traktClient.revoke(token) } }
    }
}

// ── Settings pane ───────────────────────────────

/**
 * Wires the Trakt section of the Settings dialog.
 *
 * Three states, one pane: unconfigured build (no credentials compiled in), signed out (a sign-in
 * button and the code/QR area it fills), and connected (the username, the two toggles, a manual
 * sync, and sign-out).
 */
internal fun MainActivity.wireTraktPane(dialogView: View) {
    val status = dialogView.findViewById<TextView>(R.id.traktStatus) ?: return
    val codeText = dialogView.findViewById<TextView>(R.id.traktCode)
    val qr = dialogView.findViewById<ImageView>(R.id.traktQr)
    val signInRow = dialogView.findViewById<View>(R.id.traktSignInRow)
    val signOutRow = dialogView.findViewById<View>(R.id.traktSignOutRow)
    val syncNowRow = dialogView.findViewById<View>(R.id.traktSyncNowRow)
    val scrobbleCheck = dialogView.findViewById<android.widget.CheckBox>(R.id.traktScrobbleCheck)
    val watchedCheck = dialogView.findViewById<android.widget.CheckBox>(R.id.traktWatchedSyncCheck)

    // Assigned without firing the listener. render() runs again after every state change, and
    // CheckBox.setChecked() calls the OnCheckedChangeListener even when the value is unchanged
    // - so a re-render was writing both prefs back and, for the watched-sync box, kicking a
    // forced /sync/watched pull that nobody asked for.
    fun setCheckedSilently(box: android.widget.CheckBox, checked: Boolean, onChange: (Boolean) -> Unit) {
        box.setOnCheckedChangeListener(null)
        box.isChecked = checked
        box.setOnCheckedChangeListener { _, value -> onChange(value) }
    }

    /**
     * Wires the pane's D-pad chain by hand, over only the rows that are visible right now.
     *
     * The settings panes are thirteen overlapping children of one FrameLayout inside a
     * ScrollView, so FocusFinder is scoped to that ScrollView and picks the "best" candidate
     * geometrically across every pane's leftovers and the nav rail beside them. That is fine
     * for a pane whose rows are all present at inflation, and not fine for this one, where
     * four of the six rows appear only once an account is connected: the DOWN search out of
     * the scrobble checkbox could resolve to the rail rather than to the row directly beneath
     * it, which is what made Sync watched history unreachable.
     *
     * Same reasoning as the shelf/grid adapters naming their own focus targets rather than
     * trusting focusSearch - see ShelfAdapter.nextFocusUpTargetId. Ids are reassigned on every
     * render, including back to NO_ID, so a row hidden by a later state change never leaves a
     * stale target pointing at it.
     */
    fun linkFocusChain(rows: List<View>) {
        for ((i, row) in rows.withIndex()) {
            row.nextFocusUpId = rows.getOrNull(i - 1)?.id ?: View.NO_ID
            row.nextFocusDownId = rows.getOrNull(i + 1)?.id ?: View.NO_ID
        }
    }

    fun render() {
        val configured = TraktClient.isConfigured
        val signedIn = isTraktSignedIn()
        codeText.visibility = View.GONE
        qr.visibility = View.GONE
        signInRow.visibility = if (configured && !signedIn) View.VISIBLE else View.GONE
        signOutRow.visibility = if (signedIn) View.VISIBLE else View.GONE
        // Gated on the toggle, not just on being signed in: traktSyncBothWays does nothing
        // while watched sync is off, so a visible row here was a dead end - it set the status
        // to "Syncing…" and then nothing ever completed to replace it.
        val canSync = signedIn && TraktStore.isWatchedSyncEnabled(prefs)
        syncNowRow.visibility = if (canSync) View.VISIBLE else View.GONE
        scrobbleCheck.visibility = if (signedIn) View.VISIBLE else View.GONE
        watchedCheck.visibility = if (signedIn) View.VISIBLE else View.GONE
        status.text = when {
            !configured -> getString(R.string.trakt_not_configured)
            !signedIn -> getString(R.string.trakt_not_connected)
            else -> TraktStore.username(prefs)
                ?.let { getString(R.string.trakt_signed_in_as, it) }
                ?: getString(R.string.trakt_signed_in)
        }
        setCheckedSilently(scrobbleCheck, TraktStore.isScrobbleEnabled(prefs)) { checked ->
            TraktStore.setScrobbleEnabled(prefs, checked)
        }
        setCheckedSilently(watchedCheck, TraktStore.isWatchedSyncEnabled(prefs)) { checked ->
            TraktStore.setWatchedSyncEnabled(prefs, checked)
            // The toggle decides whether the Sync now row exists at all, so the pane has to be
            // rebuilt around the new state before anything else happens.
            render()
            // Turning it on is a request for the two sides to agree - which means both
            // directions, not just the pull, and now rather than after the six-hour interval.
            if (checked) traktSyncBothWays { text -> status.text = text }
        }
        linkFocusChain(
            when {
                !signedIn -> listOf(signInRow)
                canSync -> listOf(scrobbleCheck, watchedCheck, syncNowRow, signOutRow)
                else -> listOf(scrobbleCheck, watchedCheck, signOutRow)
            }
        )
        // Signing in hides the row the user just pressed. Android drops focus when the focused
        // view goes GONE and hands it to whatever it finds first - in practice the nav rail -
        // so the pane the user is looking at ends up with nothing focused. Put the cursor on
        // the first row that now exists instead.
        if (signedIn && signInRow.hasFocus()) scrobbleCheck.requestFocus()
    }
    render()

    signInRow.setOnClickListener {
        // One sign-in at a time: a second would mint a second code and invalidate the one on
        // screen, which reads as the code randomly changing while it's being typed.
        if (traktSignInJob?.isActive == true) return@setOnClickListener
        traktSignInJob = scope.launch {
            performTraktSignIn(
                onQr = { bitmap ->
                    if (bitmap == null) qr.visibility = View.GONE
                    else { qr.setImageBitmap(bitmap); qr.visibility = View.VISIBLE }
                },
                onCode = { code ->
                    if (code == null) codeText.visibility = View.GONE
                    else { codeText.text = code; codeText.visibility = View.VISIBLE }
                },
                onStatus = { text -> status.text = text }
            )
            render()
        }
    }

    signOutRow.setOnClickListener {
        traktSignInJob?.cancel()
        traktSignOut()
        render()
        Toast.makeText(this, getString(R.string.trakt_signed_out), Toast.LENGTH_SHORT).show()
    }

    syncNowRow.setOnClickListener {
        status.text = getString(R.string.trakt_syncing)
        traktSyncBothWays { text -> status.text = text }
    }
}
