package com.lumora.data.remote.trakt

import com.lumora.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Trakt API v2 client: OAuth device flow, real-time scrobbling, and watched-history sync.
 *
 * ## Why the device flow
 *
 * Lumora runs on TV sticks with no browser worth typing a password into and no reliable way to
 * receive an OAuth redirect. Trakt's Device Authorization Grant (RFC 8628) is built for exactly
 * that: the app asks for a short code, the user types it at trakt.tv/activate on a phone or
 * laptop, and the app polls until the authorisation lands. Same shape as the Plex PIN flow this
 * app already runs (see PlexProvider.startPinLogin), and it reuses the same QR trick - the
 * verification URL with the code already in it, so scanning skips the typing.
 *
 * ## Credentials
 *
 * [CLIENT_ID]/[CLIENT_SECRET] come from BuildConfig, populated out of the gitignored
 * keystore.properties (see app/build.gradle.kts). A build without them is *configured off*
 * rather than broken: [isConfigured] is false and every call here no-ops, which is what the
 * Settings pane reports.
 *
 * ## Identifying a title to Trakt
 *
 * Trakt keys everything on its own ids plus imdb/tmdb/tvdb. A Channel from an IPTV panel has
 * none of those - it has a name and maybe a year - so the bridge is TMDB: TmdbClient.resolveId()
 * already turns a catalogue title into a tmdb id for the Discover matching, and Trakt accepts
 * `ids: { tmdb: N }` directly. Jellyfin and Plex items go through the same path rather than a
 * provider-specific one, because their own external-id fields aren't carried on [Channel].
 *
 * An episode is always sent as *show ids + season + number*, never as episode ids. Trakt's own
 * documentation prefers this shape, and it keeps working when the episode isn't in Trakt's
 * catalogue yet - which for a show mid-season it frequently isn't.
 */
class TraktClient(private val http: OkHttpClient) {

    // ── Device authorization (RFC 8628) ──────────

    /** One issued device code. The user types [userCode] at [verificationUrl]; the app polls
     *  with [deviceCode] every [intervalSeconds] until [expiresInSeconds] runs out. */
    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val expiresInSeconds: Int,
        val intervalSeconds: Int
    ) {
        /** The verification URL with the code already filled in - what the QR encodes, so a
         *  phone that scans it lands on a page with nothing left to type. */
        val activateUrlWithCode: String get() = "${verificationUrl.trimEnd('/')}/$userCode"
    }

    /** One poll of the token endpoint. Trakt signals the whole flow through status codes
     *  rather than an error body, so each one maps to a case here. */
    sealed interface DevicePoll {
        /** 400 - the user hasn't finished at trakt.tv/activate yet. Keep waiting. */
        data object Pending : DevicePoll
        /** 429 - polling faster than `interval`. Back off by 5s, as RFC 8628 prescribes. */
        data object SlowDown : DevicePoll
        /** 404 (unknown code) or 410 (expired). The code is dead; mint a new one. */
        data object Expired : DevicePoll
        /** 418 (user said no) or 409 (this code was already used). */
        data object Denied : DevicePoll
        data class Success(val tokens: Tokens) : DevicePoll
        /** Network trouble, or a status Trakt doesn't document. Treated as [Pending] by the
         *  caller - a blip mid-flow shouldn't tear down a code the user is still typing. */
        data object Unknown : DevicePoll
    }

    /** An OAuth token pair plus the absolute expiry computed from `created_at`/`expires_in`.
     *  Trakt tokens last 3 months, so the refresh matters over the life of an install rather
     *  than within a session. */
    data class Tokens(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtMs: Long
    )

    /** Starts a device authorisation. Null when Trakt refuses or the build has no client id. */
    suspend fun createDeviceCode(): DeviceCode? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null
        val body = JSONObject().put("client_id", CLIENT_ID)
        val json = postJson("$API_BASE/oauth/device/code", body)?.second ?: return@withContext null
        val verificationUrl = json.optString("verification_url").ifBlank { ACTIVATE_URL }
        val userCode = json.optString("user_code").takeIf { it.isNotBlank() } ?: return@withContext null
        DeviceCode(
            deviceCode = json.optString("device_code").takeIf { it.isNotBlank() } ?: return@withContext null,
            userCode = userCode,
            verificationUrl = verificationUrl,
            // Defaults matching Trakt's documented values, for a response that omits them.
            expiresInSeconds = json.optInt("expires_in", 600),
            intervalSeconds = json.optInt("interval", 5)
        )
    }

    /** One poll of `/oauth/device/token`. Call at the code's own interval, not faster. */
    suspend fun pollDeviceToken(deviceCode: String): DevicePoll = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext DevicePoll.Expired
        val body = JSONObject()
            .put("code", deviceCode)
            .put("client_id", CLIENT_ID)
            .put("client_secret", CLIENT_SECRET)
        val (code, json) = postJson("$API_BASE/oauth/device/token", body)
            ?: return@withContext DevicePoll.Unknown
        when (code) {
            200 -> json?.let { parseTokens(it) }?.let { DevicePoll.Success(it) } ?: DevicePoll.Unknown
            400 -> DevicePoll.Pending
            404, 410 -> DevicePoll.Expired
            409, 418 -> DevicePoll.Denied
            429 -> DevicePoll.SlowDown
            else -> DevicePoll.Unknown
        }
    }

    /**
     * Trades a refresh token for a fresh pair.
     *
     * No `redirect_uri` is sent. Trakt documents one on the refresh grant, but the value only
     * means anything to the authorization-code flow - a device-flow app has no redirect - and
     * sending a value the registration doesn't match is a 400 for no gain.
     *
     * Null means the refresh failed. The caller decides whether that is fatal: a 4xx is a dead
     * grant and the session should be dropped, while a network failure should be retried later.
     * [lastRefreshWasRejected] separates the two.
     */
    suspend fun refresh(refreshToken: String): Tokens? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null
        val body = JSONObject()
            .put("refresh_token", refreshToken)
            .put("client_id", CLIENT_ID)
            .put("client_secret", CLIENT_SECRET)
            .put("grant_type", "refresh_token")
        val (code, json) = postJson("$API_BASE/oauth/token", body) ?: run {
            lastRefreshWasRejected = false
            return@withContext null
        }
        if (code == 200 && json != null) return@withContext parseTokens(json)
        // 4xx means the grant itself is gone (revoked in Trakt's own settings, or rotated out
        // from under us). Anything else - 5xx, a proxy page - is worth another try later.
        lastRefreshWasRejected = code in 400..499
        null
    }

    /** Whether the last [refresh] failure was Trakt rejecting the grant rather than the
     *  network failing. Only meaningful immediately after a null return. */
    @Volatile
    var lastRefreshWasRejected: Boolean = false
        private set

    /** Best-effort revoke, so signing out here also drops the token on Trakt's side. */
    suspend fun revoke(accessToken: String) {
        if (!isConfigured) return
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("token", accessToken)
                .put("client_id", CLIENT_ID)
                .put("client_secret", CLIENT_SECRET)
            runCatching { postJson("$API_BASE/oauth/revoke", body) }
        }
    }

    // ── Account ─────────────────────────────────

    /** The signed-in user's display name, for the Settings pane. Null if the call fails. */
    suspend fun username(accessToken: String): String? = withContext(Dispatchers.IO) {
        val json = getJson("$API_BASE/users/settings", accessToken) ?: return@withContext null
        val user = json.optJSONObject("user") ?: return@withContext null
        user.optString("username").takeIf { it.isNotBlank() }
            ?: user.optString("name").takeIf { it.isNotBlank() }
    }

    // ── Scrobbling ──────────────────────────────

    /** Which scrobble endpoint a report goes to. */
    enum class ScrobbleAction(val path: String) {
        START("start"),
        PAUSE("pause"),
        /** Trakt turns a stop at >=80% progress into a play and anything below into a
         *  resumable position - the app doesn't decide that, it just reports where it got to. */
        STOP("stop")
    }

    /**
     * What is being scrobbled, in the shape Trakt's body wants.
     *
     * [tmdbId] is the *show's* id for an episode, not the episode's - see the class doc.
     */
    data class ScrobbleTarget(
        val tmdbId: Int,
        val isSeries: Boolean,
        val season: Int? = null,
        val episode: Int? = null
    )

    /**
     * Reports playback. [progressPercent] is 0-100.
     *
     * A 409 is success as far as the caller is concerned: it means Trakt already has a scrobble
     * for this item inside its 15-minute window, which is the expected answer when a stream
     * restarts (a failover, a version switch) rather than an error worth surfacing.
     */
    suspend fun scrobble(
        accessToken: String,
        action: ScrobbleAction,
        target: ScrobbleTarget,
        progressPercent: Double
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext false
        val body = scrobbleBody(target).put("progress", progressPercent.coerceIn(0.0, 100.0))
        val code = postJson("$API_BASE/scrobble/${action.path}", body, accessToken)?.first
        code == 200 || code == 201 || code == 409
    }

    // ── Watched history ─────────────────────────

    /**
     * Adds one item to the watched history, for the marks that never pass through the player:
     * the detail screen's watched toggle, a whole-season tick, an external-player play.
     *
     * [watchedAtIso] is ISO-8601 UTC. Omitted, Trakt records "now".
     */
    suspend fun addToHistory(
        accessToken: String,
        target: ScrobbleTarget,
        watchedAtIso: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext false
        val code = postJson("$API_BASE/sync/history", historyBody(target, watchedAtIso), accessToken)?.first
        code == 200 || code == 201
    }

    /**
     * Adds many items to the watched history in one request.
     *
     * The per-item [addToHistory] is right for a toggle; it is the wrong shape for the backfill,
     * where a library can be thousands of episodes and one request each would be both slow and a
     * good way to get rate-limited. `/sync/history` takes arrays keyed by type, with episodes
     * nested under seasons under shows, so a whole catalogue goes in one body.
     *
     * [movieTmdbIds] are film tmdb ids. [showEpisodes] maps a show's tmdb id to season number to
     * the episode numbers watched in it. No `watched_at` anywhere: Trakt dates these to now,
     * which is honest - a local mark records *that* something was seen, never when.
     *
     * Returns true when Trakt accepted the batch.
     */
    suspend fun addWatchedBatch(
        accessToken: String,
        movieTmdbIds: Collection<Int>,
        showEpisodes: Map<Int, Map<Int, Set<Int>>>
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext false
        if (movieTmdbIds.isEmpty() && showEpisodes.isEmpty()) return@withContext true
        val body = JSONObject()
        if (movieTmdbIds.isNotEmpty()) {
            val movies = JSONArray()
            for (id in movieTmdbIds) {
                movies.put(JSONObject().put("ids", JSONObject().put("tmdb", id)))
            }
            body.put("movies", movies)
        }
        if (showEpisodes.isNotEmpty()) {
            val shows = JSONArray()
            for ((showId, seasons) in showEpisodes) {
                val seasonsArr = JSONArray()
                for ((seasonNumber, episodeNumbers) in seasons) {
                    if (episodeNumbers.isEmpty()) continue
                    val episodesArr = JSONArray()
                    for (number in episodeNumbers.sorted()) {
                        episodesArr.put(JSONObject().put("number", number))
                    }
                    seasonsArr.put(JSONObject().put("number", seasonNumber).put("episodes", episodesArr))
                }
                if (seasonsArr.length() == 0) continue
                shows.put(
                    JSONObject()
                        .put("ids", JSONObject().put("tmdb", showId))
                        .put("seasons", seasonsArr)
                )
            }
            if (shows.length() > 0) body.put("shows", shows)
        }
        val code = postJson("$API_BASE/sync/history", body, accessToken)?.first
        code == 200 || code == 201
    }

    /** Removes one item from the watched history - the un-tick side of the same toggle. */
    suspend fun removeFromHistory(
        accessToken: String,
        target: ScrobbleTarget
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext false
        val code = postJson("$API_BASE/sync/history/remove", historyBody(target, null), accessToken)?.first
        code == 200 || code == 201
    }

    /** One watched title pulled from Trakt: a film, or a show with the episodes seen in it. */
    data class WatchedEntry(
        val title: String,
        val year: Int?,
        val tmdbId: Int?,
        val isSeries: Boolean,
        /** season number -> episode numbers watched. Empty for a film. */
        val episodes: Map<Int, Set<Int>>
    )

    /**
     * The account's full watched history, films and shows.
     *
     * Two calls rather than one - Trakt splits the endpoint by type - and both are the *summary*
     * form, which returns every show with its seen seasons/episodes nested rather than one row
     * per play. That is a single response for a whole library instead of thousands.
     */
    suspend fun watched(accessToken: String): List<WatchedEntry> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext emptyList()
        val out = mutableListOf<WatchedEntry>()
        getArray("$API_BASE/sync/watched/movies", accessToken)?.let { arr ->
            for (i in 0 until arr.length()) {
                val movie = arr.optJSONObject(i)?.optJSONObject("movie") ?: continue
                out += WatchedEntry(
                    title = movie.optString("title"),
                    year = movie.optInt("year", 0).takeIf { it > 0 },
                    tmdbId = movie.optJSONObject("ids")?.optInt("tmdb", 0)?.takeIf { it > 0 },
                    isSeries = false,
                    episodes = emptyMap()
                )
            }
        }
        getArray("$API_BASE/sync/watched/shows", accessToken)?.let { arr ->
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val show = row.optJSONObject("show") ?: continue
                val seasons = HashMap<Int, MutableSet<Int>>()
                val seasonsArr = row.optJSONArray("seasons")
                if (seasonsArr != null) {
                    for (s in 0 until seasonsArr.length()) {
                        val season = seasonsArr.optJSONObject(s) ?: continue
                        val number = season.optInt("number", -1).takeIf { it >= 0 } ?: continue
                        val eps = season.optJSONArray("episodes") ?: continue
                        val set = seasons.getOrPut(number) { mutableSetOf() }
                        for (e in 0 until eps.length()) {
                            eps.optJSONObject(e)?.optInt("number", -1)?.takeIf { it >= 0 }?.let(set::add)
                        }
                    }
                }
                out += WatchedEntry(
                    title = show.optString("title"),
                    year = show.optInt("year", 0).takeIf { it > 0 },
                    tmdbId = show.optJSONObject("ids")?.optInt("tmdb", 0)?.takeIf { it > 0 },
                    isSeries = true,
                    episodes = seasons
                )
            }
        }
        out
    }

    // ── Body builders ───────────────────────────

    /** `{ movie: { ids } }` or `{ show: { ids }, episode: { season, number } }`. */
    private fun scrobbleBody(target: ScrobbleTarget): JSONObject {
        val ids = JSONObject().put("tmdb", target.tmdbId)
        if (!target.isSeries || target.season == null || target.episode == null) {
            return JSONObject().put("movie", JSONObject().put("ids", ids))
        }
        return JSONObject()
            .put("show", JSONObject().put("ids", ids))
            .put(
                "episode",
                JSONObject().put("season", target.season).put("number", target.episode)
            )
    }

    /** `/sync/history` takes arrays keyed by type, with episodes nested under seasons. */
    private fun historyBody(target: ScrobbleTarget, watchedAtIso: String?): JSONObject {
        val ids = JSONObject().put("tmdb", target.tmdbId)
        if (!target.isSeries || target.season == null || target.episode == null) {
            val movie = JSONObject().put("ids", ids)
            if (watchedAtIso != null) movie.put("watched_at", watchedAtIso)
            return JSONObject().put("movies", JSONArray().put(movie))
        }
        val episode = JSONObject().put("number", target.episode)
        if (watchedAtIso != null) episode.put("watched_at", watchedAtIso)
        val season = JSONObject()
            .put("number", target.season)
            .put("episodes", JSONArray().put(episode))
        val show = JSONObject()
            .put("ids", ids)
            .put("seasons", JSONArray().put(season))
        return JSONObject().put("shows", JSONArray().put(show))
    }

    // ── HTTP ────────────────────────────────────

    /** Status code plus parsed body, or null when the request never completed. The status is
     *  returned rather than swallowed because the device-flow poll *is* the status. */
    private fun postJson(url: String, body: JSONObject, accessToken: String? = null): Pair<Int, JSONObject?>? {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .applyTraktHeaders(accessToken)
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string()
                val json = text?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (!response.isSuccessful) android.util.Log.w("TraktClient", "POST $url -> ${response.code}: $text")
                response.code to json
            }
        }.onFailure { android.util.Log.w("TraktClient", "POST $url failed", it) }.getOrNull()
    }

    private fun getJson(url: String, accessToken: String): JSONObject? = runCatching {
        http.newCall(Request.Builder().url(url).applyTraktHeaders(accessToken).build()).execute().use { response ->
            if (!response.isSuccessful) {
                android.util.Log.w("TraktClient", "GET $url -> ${response.code}")
                return@use null
            }
            response.body?.string()?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
        }
    }.onFailure { android.util.Log.w("TraktClient", "GET $url failed", it) }.getOrNull()

    private fun getArray(url: String, accessToken: String): JSONArray? = runCatching {
        http.newCall(Request.Builder().url(url).applyTraktHeaders(accessToken).build()).execute().use { response ->
            if (!response.isSuccessful) {
                android.util.Log.w("TraktClient", "GET $url -> ${response.code}")
                return@use null
            }
            response.body?.string()?.takeIf { it.isNotBlank() }?.let { JSONArray(it) }
        }
    }.onFailure { android.util.Log.w("TraktClient", "GET $url failed", it) }.getOrNull()

    /** Every Trakt call needs the version and the client id; authenticated ones add the
     *  bearer token. Missing `trakt-api-key` is a 403 regardless of the bearer. */
    private fun Request.Builder.applyTraktHeaders(accessToken: String?): Request.Builder {
        header("Content-Type", "application/json")
        header("Accept", "application/json")
        header("trakt-api-version", API_VERSION)
        header("trakt-api-key", CLIENT_ID)
        if (accessToken != null) header("Authorization", "Bearer $accessToken")
        return this
    }

    private fun parseTokens(json: JSONObject): Tokens? {
        val access = json.optString("access_token").takeIf { it.isNotBlank() } ?: return null
        val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: return null
        // created_at + expires_in are both seconds. A response missing created_at is dated from
        // now, which is at worst a slightly early refresh.
        val createdAtSec = json.optLong("created_at", 0L).takeIf { it > 0L }
            ?: (System.currentTimeMillis() / 1000)
        val expiresInSec = json.optLong("expires_in", 0L).takeIf { it > 0L } ?: (90L * 24 * 60 * 60)
        return Tokens(access, refresh, (createdAtSec + expiresInSec) * 1000)
    }

    companion object {
        const val API_BASE = "https://api.trakt.tv"
        const val API_VERSION = "2"
        /** Where the user types the code. Shown verbatim in the Settings pane. */
        const val ACTIVATE_URL = "https://trakt.tv/activate"

        val CLIENT_ID: String = BuildConfig.TRAKT_CLIENT_ID
        val CLIENT_SECRET: String = BuildConfig.TRAKT_CLIENT_SECRET

        /** False in a build with no credentials in keystore.properties. Every call no-ops and
         *  the Settings pane says the build isn't configured, rather than offering a sign-in
         *  that can only fail. */
        val isConfigured: Boolean get() = CLIENT_ID.isNotBlank() && CLIENT_SECRET.isNotBlank()

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
