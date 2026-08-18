package com.lumora.data.remote.plex

import android.util.Log
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.model.Provider
import com.lumora.util.qualifiedMediaItemId
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** plex.tv account API - PIN login and the account's server list live here, not on any
 *  particular server. */
private const val TAG = "LumoraPlex"
private const val PLEX_TV_BASE = "https://plex.tv/api/v2"
/** Same API behind Plex's own CDN edge. Used for /resources because plex.tv itself is the
 *  endpoint most likely to be slow or geo-blocked, and the two are interchangeable. */
private const val PLEX_CLIENTS_BASE = "https://clients.plex.tv/api/v2"

private const val PRODUCT = "Lumora"
private const val VERSION = "1.0"
private const val PLATFORM = "Android"
/** Fixed literal rather than Build.MODEL, for the same reason Jellyfin's is: a device that
 *  reports an odd model string turns into an odd entry in the user's Plex device list, and
 *  these values also ride as query params on the transcode endpoints where anything exotic
 *  is one more thing to escape. */
private const val DEVICE = "Lumora"

/** One page of a library crawl. Plex's own clients page at 100-ish; 500 keeps the round
 *  trips down on a big library without producing a payload a TV stick struggles to parse. */
private const val PAGE_SIZE = 500
/** Backstop for the paging loop - a server that keeps returning full pages can't spin us
 *  forever. Well past any realistic personal library. */
private const val MAX_ITEMS = 50_000

/** Whole-call bound for every Plex request. Same reasoning as JellyfinProvider's: the shared
 *  client sets only per-phase timeouts, so a server dribbling one byte per read timeout keeps
 *  a call alive indefinitely, and these are blocking `execute()` calls that a cancelled
 *  coroutine cannot unblock. Only OkHttp's watchdog closing the socket makes them return. */
private const val CALL_TIMEOUT_SECONDS = 30L

/** Per-candidate bound while probing a server's connection list. Short on purpose: a server
 *  publishes up to a dozen endpoints (LAN, WAN, relay, IPv6) and most of them are dead from
 *  wherever the TV is sitting, so each dead one has to fail fast. */
private const val PROBE_TIMEOUT_SECONDS = 6L

/**
 * Plex Media Server provider integration.
 *
 * Structured to mirror [com.lumora.data.remote.jellyfin.JellyfinProvider] - the two are
 * independent slots that can both be connected at once, and the rest of the app treats them
 * the same way (own-library content, server-side watch state, negotiated playback).
 *
 * Two differences are inherent to Plex rather than choices made here:
 *  - **Sign-in is an account flow, not a server flow.** The user authenticates against
 *    plex.tv (PIN + QR), and the account then hands back the list of servers it can reach.
 *    There is no username/password login against a Plex server itself.
 *  - **Live TV is not supported.** Plex Live TV is a tuner session flow (`/livetv/dvrs` →
 *    lineup → `tune`, each tune producing a session that has to be torn down), which does
 *    not fit Lumora's URL-per-channel live model. Movies and Series only.
 */
class PlexProvider(baseClient: OkHttpClient, private val clientIdentifier: String) {

    /** Shares the base client's connection pool, dispatcher and cache - newBuilder() only
     *  layers the call timeout on top, so this costs nothing beyond the wrapper. */
    private val client: OkHttpClient = baseClient.newBuilder()
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** Separate client for connection probing - see [PROBE_TIMEOUT_SECONDS]. */
    private val probeClient: OkHttpClient = baseClient.newBuilder()
        .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val mutex = Mutex()
    // @Volatile for the same reason JellyfinProvider's are: written on whichever thread
    // connect/restoreSession ran on (usually Main), read unsynchronized from IO threads for
    // every subsequent fetch. Without it a token set on main can read stale-null on a worker
    // and produce a spuriously empty catalog right after connect.
    @Volatile
    private var accessToken: String? = null
    @Volatile
    private var serverBase: String? = null

    /** Set by [startPinLogin]/[fetchServers] on failure so callers can show *why* instead of
     *  a generic message - "couldn't reach plex.tv" and "this account has no servers" look
     *  identical from a null return alone. */
    var lastAuthError: String? = null
        private set

    // ── Types ──────────────────────────────────────

    /** A PIN login in flight. [code] is shown on the TV, [authUrl] is what the QR encodes,
     *  and [id] is what gets polled until the user finishes signing in on their phone. */
    data class PinLogin(val id: Long, val code: String, val authUrl: String)

    /** One server the account can reach. [accessToken] is *per server* - a shared server
     *  hands back a different token from an owned one, and it is that token, not the account
     *  token, that every call to the server must carry. */
    data class PlexServerInfo(
        val name: String,
        val machineIdentifier: String,
        val accessToken: String,
        val owned: Boolean,
        val connections: List<PlexConnection>
    )

    data class PlexConnection(
        val uri: String,
        /** Host as plex.tv published it - a bare IP for LAN/WAN entries, the hashed
         *  `*.plex.direct` name for HTTPS ones. Kept so an HTTP fallback URL can be built. */
        val address: String,
        val port: Int,
        val local: Boolean,
        val relay: Boolean,
        val https: Boolean
    )

    data class PlexItem(
        val id: String,
        val name: String,
        /** Direct-play path on the server (`/library/parts/…`), token-free. Empty for shows
         *  and anything the listing carried no media for. See [directPlayUrl]. */
        val partPath: String,
        val mediaType: String, // Movie, Series, Episode
        val imageUrl: String? = null,
        val backdropUrl: String? = null,
        val overview: String? = null,
        val year: Int? = null,
        val genres: List<String> = emptyList(),
        val rating: Double? = null,
        val releaseDate: String? = null, // ISO "YYYY-MM-DD"
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val runtimeMs: Long? = null,
        // Server-side per-user state. Same point as Jellyfin's UserData: watch state made in
        // any other Plex client belongs here too.
        val resumePositionMs: Long = 0L,
        val played: Boolean = false,
        // Episodes only - a Next Up row is meaningless without saying which show it's from.
        val seriesId: String? = null,
        val seriesName: String? = null
    )

    /** One season of a series as the server names it - "Specials" (index 0) included, which
     *  grouping episodes by their own parentIndex alone can't produce. */
    data class PlexSeason(
        val id: String,
        val name: String,
        val indexNumber: Int?
    )

    /** A chapter marker, for chapter skip in the player. */
    data class Chapter(
        val name: String,
        val positionMs: Long,
        val imageUrl: String?
    )

    /** One subtitle track the resolved part carries. [url] is set for tracks the server can
     *  hand over as a sidecar file (external ones, and embedded text it will convert), which
     *  are the ones Media3 can render itself. */
    data class SubtitleStream(
        val id: Long,
        val url: String?,
        val language: String?,
        val title: String?,
        val codec: String?,
        val isExternal: Boolean,
        val isForced: Boolean,
        val isDefault: Boolean
    )

    /** One audio track the *file* carries. Kept for the same reason Jellyfin's is: a
     *  transcode only ever delivers the one track the server picked, so switching to another
     *  means asking the server to rebuild the stream around it. */
    data class AudioStream(
        val id: Long,
        val language: String?,
        val title: String?,
        val codec: String?,
        val channels: Int?,
        val isDefault: Boolean
    )

    /**
     * The outcome of a transcode-decision negotiation: the URL to actually play, plus the
     * session identity that timeline reporting has to quote back so the server can tie
     * progress to this play (and reap the transcode when it ends).
     */
    data class ResolvedStream(
        val url: String,
        /** `X-Plex-Session-Identifier` - what the timeline reports quote. */
        val playSessionId: String?,
        /** The `session` param the transcode was started with, for the keepalive ping. */
        val transcodeSessionId: String?,
        /** "DirectPlay" or "Transcode". */
        val playMethod: String,
        val runtimeMs: Long?,
        val subtitles: List<SubtitleStream> = emptyList(),
        val audioStreams: List<AudioStream> = emptyList(),
        /** Which of [audioStreams] this stream was built around. */
        val audioStreamId: Long? = null,
        /** Re-applied to every otherwise-unqueried request the HLS source makes: Plex writes
         *  segment paths into the playlist without a token, so a transcode plays for one
         *  segment and then 401s without this. */
        val tokenQuery: String? = null
    )

    // ── Headers ────────────────────────────────────

    private fun Request.Builder.plexHeaders(token: String? = null): Request.Builder = apply {
        header("Accept", "application/json")
        header("X-Plex-Product", PRODUCT)
        header("X-Plex-Version", VERSION)
        header("X-Plex-Client-Identifier", clientIdentifier)
        header("X-Plex-Platform", PLATFORM)
        header("X-Plex-Device", DEVICE)
        header("X-Plex-Device-Name", DEVICE)
        header("X-Plex-Model", "standalone")
        token?.takeIf { it.isNotBlank() }?.let { header("X-Plex-Token", it) }
    }

    // ── plex.tv sign-in (PIN + QR) ─────────────────

    /**
     * Starts a PIN login: the user goes to plex.tv/link and enters [PinLogin.code] (or scans
     * the QR of [PinLogin.authUrl], which is that same page with the code pre-filled), signs
     * in, and [pollPin] then returns an account token.
     *
     * This is the *only* way to sign a Plex account in: unlike Jellyfin there is no
     * username/password endpoint on the server itself, so the link flow isn't a convenience
     * here, it's the login.
     *
     * Deliberately **not** `?strong=true`. A strong PIN is a long random string, meant for
     * the app.plex.tv deep link where nobody ever reads it; plex.tv/link takes the short
     * 4-character code, and that page is the one a person can actually use from a phone while
     * looking at a TV. Asking for a strong PIN and then telling the user to type it at
     * plex.tv/link gives them a code that page will not accept.
     */
    suspend fun startPinLogin(): PinLogin? {
        lastAuthError = null
        return try {
            val request = Request.Builder()
                .url("$PLEX_TV_BASE/pins")
                .plexHeaders()
                .post("".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    lastAuthError = "plex.tv returned HTTP ${response.code}"
                    return null
                }
                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    lastAuthError = "plex.tv returned an empty response"
                    return null
                }
                val json = JSONObject(body)
                val id = json.optLong("id", -1L).takeIf { it > 0 }
                val code = json.optStringOrNull("code")
                if (id == null || code == null) {
                    lastAuthError = "plex.tv didn't return a sign-in code"
                    return null
                }
                // The client identifier is logged because it is what scopes the PIN: polling
                // with a different one 404s exactly like an expired code, and that is not
                // distinguishable from the response alone.
                Log.i(TAG, "pin minted: id=$id code=$code (client id $clientIdentifier)")
                PinLogin(id, code, authUrl(code))
            }
        } catch (e: Exception) {
            // Bare OkHttp messages ("Unable to resolve host", a TLS failure) are exactly what
            // distinguishes "no internet on this TV" from "Plex rejected us", and there is
            // nowhere else a user could see them.
            lastAuthError = e.message ?: "Couldn't reach plex.tv"
            null
        }
    }

    /**
     * The URL a PIN's QR encodes: plex.tv/link with the code pre-filled, so scanning it is the
     * same flow as typing the code, minus the typing.
     *
     * The same page the on-screen instructions name, on purpose. Pointing the QR at
     * app.plex.tv/auth instead would work, but it lands somewhere that looks nothing like the
     * page the TV just told the user to open - and when the QR fails to scan (which on a TV
     * across a room it often does), "type this code at the address on screen" has to be a
     * route that actually finishes.
     */
    private fun authUrl(code: String): String =
        "https://plex.tv/link?pin=${java.net.URLEncoder.encode(code, "UTF-8")}"

    /** What one [pollPin] pass found. */
    sealed interface PinPoll {
        /** The user finished signing in. */
        data class Claimed(val accountToken: String) : PinPoll
        /** Nobody has entered the code yet - keep polling. */
        data object Pending : PinPoll
        /** plex.tv says this PIN doesn't exist. [message] is user-facing. */
        data class Gone(val message: String) : PinPoll
        /** Anything else - a transient error worth another pass. */
        data class Failed(val message: String) : PinPoll
    }

    /**
     * Polls a PIN once.
     *
     * A 404 here is *not* proof the code expired, which is why it isn't reported as such: a
     * PIN is scoped to the `X-Plex-Client-Identifier` that minted it, and plex.tv answers a
     * mismatched identifier with the same "Code not found or expired" 404 as a genuinely aged
     * one. A brand-new PIN 404ing two seconds after being issued means the identifiers
     * disagree, not that the user was too slow - so the distinction is left to the caller,
     * which knows how long the code has actually been up.
     */
    suspend fun pollPin(id: Long): PinPoll {
        return try {
            val request = Request.Builder()
                .url("$PLEX_TV_BASE/pins/$id")
                .plexHeaders()
                .build()
            // use{}: this runs in a poll loop, and a pending PIN answers 200-with-no-token
            // every pass - an unclosed body per pass leaks a connection out of the pool.
            client.newCall(request).execute().use { response ->
                if (response.code == 404 || response.code == 410) {
                    Log.w(TAG, "pin poll: HTTP ${response.code} for pin $id (client id $clientIdentifier)")
                    return PinPoll.Gone("This sign-in code is no longer valid")
                }
                if (!response.isSuccessful) {
                    Log.w(TAG, "pin poll: HTTP ${response.code} for pin $id")
                    return PinPoll.Failed("plex.tv returned HTTP ${response.code}")
                }
                val body = response.body?.string() ?: return PinPoll.Failed("Empty response from plex.tv")
                val json = JSONObject(body)
                // isNull first, and deliberately so. A pending PIN answers with
                // `"authToken": null`, and Android's optString(name, fallback) returns the
                // *literal string* "null" for a JSON null - it stringifies JSONObject.NULL and
                // only falls back when the key is missing entirely. So the first poll returned
                // "null" as the account token, which is not blank: the sign-in declared itself
                // finished about two seconds after the code appeared, tore down the QR before
                // anyone could reach plex.tv/link, and then failed the server list with a 401
                // because it was authenticating with the four characters n-u-l-l.
                if (json.isNull("authToken")) return PinPoll.Pending
                json.optString("authToken", "").takeIf { it.isNotBlank() }
                    ?.let { PinPoll.Claimed(it) } ?: PinPoll.Pending
            }
        } catch (e: Exception) {
            Log.w(TAG, "pin poll failed: ${e.message}")
            PinPoll.Failed(e.message ?: "Couldn't reach plex.tv")
        }
    }

    /** Every Plex server this account can reach, owned and shared alike. */
    suspend fun fetchServers(accountToken: String): List<PlexServerInfo> {
        lastAuthError = null
        return try {
            val request = Request.Builder()
                .url("$PLEX_CLIENTS_BASE/resources?includeHttps=1&includeRelay=1&includeIPv6=1")
                .plexHeaders(accountToken)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    lastAuthError = "Couldn't list your servers (HTTP ${response.code})"
                    return emptyList()
                }
                val body = response.body?.string() ?: return emptyList()
                val resources = JSONArray(body)
                val servers = (0 until resources.length()).mapNotNull { i ->
                    parseServer(resources.optJSONObject(i) ?: return@mapNotNull null)
                }
                if (servers.isEmpty()) lastAuthError = "This Plex account has no media servers"
                servers
            }
        } catch (e: Exception) {
            lastAuthError = e.message ?: "Couldn't reach plex.tv"
            emptyList()
        }
    }

    /**
     * [JSONObject.optString] that treats a JSON null as absent.
     *
     * The stock one returns the literal string "null" for `"key": null`, because it
     * stringifies JSONObject.NULL and only falls back when the key is missing altogether. The
     * plex.tv account API emits explicit nulls freely - that is exactly how a pending PIN's
     * authToken briefly became the four characters n-u-l-l - so every field read off it goes
     * through here. The Plex *server* library API omits keys instead of nulling them, which is
     * why its parsers below are not affected.
     */
    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name, "").takeIf { it.isNotBlank() }

    private fun parseServer(json: JSONObject): PlexServerInfo? {
        // "provides" is a comma-separated capability list; a server may also provide
        // "player"/"controller", so a plain equality check drops perfectly good servers.
        val provides = json.optStringOrNull("provides").orEmpty()
        if (!provides.split(',').any { it.trim() == "server" }) return null
        val token = json.optStringOrNull("accessToken") ?: return null
        val id = json.optStringOrNull("clientIdentifier") ?: return null
        val connectionsJson = json.optJSONArray("connections") ?: return null
        val connections = (0 until connectionsJson.length()).mapNotNull { i ->
            val c = connectionsJson.optJSONObject(i) ?: return@mapNotNull null
            val uri = c.optStringOrNull("uri") ?: return@mapNotNull null
            val address = c.optStringOrNull("address").orEmpty()
            // Addresses no client can ever reach: IPv6 link-local and the all-zeros bind
            // address. A server behind Docker publishes several of them and each one costs a
            // full probe timeout for nothing.
            if (isUnreachableAddress(address)) return@mapNotNull null
            PlexConnection(
                uri = uri.trimEnd('/'),
                address = address,
                port = c.optInt("port", 0),
                local = c.optBoolean("local", false),
                relay = c.optBoolean("relay", false),
                https = c.optStringOrNull("protocol") == "https" || uri.startsWith("https://")
            )
        }
        if (connections.isEmpty()) return null
        return PlexServerInfo(
            name = json.optStringOrNull("name") ?: "Plex Server",
            machineIdentifier = id,
            accessToken = token,
            owned = json.optBoolean("owned", false),
            connections = connections
        )
    }

    /**
     * The first of [server]'s published endpoints that actually answers from where this
     * device is sitting, or null if none do.
     *
     * A server publishes every address it knows about - LAN, WAN, IPv6, and Plex's relay -
     * and most are dead from any given network. They're tried in the order that matters for
     * playback: LAN before WAN before relay (the relay is bandwidth-capped and is a last
     * resort), HTTPS before HTTP within each. One tier at a time, all of a tier's candidates
     * at once - see [candidateTiers] for why the tiers stay sequential and the candidates
     * inside them do not.
     */
    suspend fun pickConnection(server: PlexServerInfo): String? {
        val tiers = candidateTiers(server)
        if (tiers.all { it.isEmpty() }) {
            lastAuthError = "${server.name} published no reachable addresses"
            return null
        }
        for (tier in tiers) {
            if (tier.isEmpty()) continue
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val winner = probeTier(tier, server.accessToken)
            if (winner != null) return winner
        }
        lastAuthError = "None of ${server.name}'s ${tiers.sumOf { it.size }} addresses answered"
        return null
    }

    /**
     * [server]'s candidate base URLs, grouped into the tiers that must be tried in order:
     * LAN, then WAN, then Plex's relay (bandwidth-capped, so a last resort). HTTPS comes
     * before HTTP inside a tier.
     *
     * Each tier is probed *concurrently*. Serially it was one [PROBE_TIMEOUT_SECONDS] per
     * candidate, and a server reached from off its LAN publishes up to a dozen addresses that
     * blackhole rather than refuse - every LAN address, every Docker bridge, every IPv6 - so
     * the relay at the end of the list was reached a minute later, long after the sign-in had
     * been given up on. Tiers stay sequential so a working LAN address is still preferred
     * over a WAN one that would answer faster.
     *
     * An HTTP fallback is added for HTTPS candidates whose host is a `*.plex.direct` name or a
     * bare IP: those resolve through plex.tv's DNS and present a Plex-issued certificate, and
     * where either is broken on the network (filtered DNS, a middlebox) the same host:port
     * still serves plain HTTP.
     */
    private fun candidateTiers(server: PlexServerInfo): List<List<String>> {
        val seen = HashSet<String>()
        fun tier(matching: (PlexConnection) -> Boolean): List<String> =
            server.connections.filter(matching)
                .sortedBy { if (it.https) 0 else 1 }
                .flatMap { connection ->
                    val urls = mutableListOf(connection.uri)
                    if (connection.https && connection.port > 0 &&
                        (isPlexDirectUri(connection.uri) || isIpLiteral(connection.address))
                    ) {
                        urls += "http://${hostForUrl(connection.address)}:${connection.port}"
                    }
                    urls
                }
                .filter { it.isNotBlank() && seen.add(it) }

        return listOf(
            tier { it.local && !it.relay },
            tier { !it.local && !it.relay },
            tier { it.relay }
        )
    }

    /** Probes every URL in one tier at once and returns the earliest one in tier order that
     *  answered, or null when none did. */
    private suspend fun probeTier(urls: List<String>, token: String): String? =
        kotlinx.coroutines.coroutineScope {
            val probes = urls.map { url -> url to async { probe(url, token) } }
            val winner = probes.firstNotNullOfOrNull { (url, deferred) -> url.takeIf { deferred.await() } }
            // Cancelling the losers cancels their OkHttp calls (see [probe]); without it this
            // scope would not return until the slowest dead candidate hit its timeout, which
            // is the serial cost this whole race exists to avoid.
            probes.forEach { (_, deferred) -> deferred.cancel() }
            winner
        }

    private fun isPlexDirectUri(uri: String): Boolean =
        uri.toHttpUrlOrNull()?.host?.lowercase()?.endsWith(".plex.direct") == true

    /** True for a bare IPv4/IPv6 literal - no hostname means no reverse proxy, so an HTTP
     *  attempt on the HTTPS port can't land on something else's vhost. */
    private fun isIpLiteral(address: String): Boolean {
        val bare = address.removePrefix("[").removeSuffix("]")
        if (bare.isBlank()) return false
        return (bare.all { it.isDigit() || it == '.' } && bare.count { it == '.' } == 3) ||
            bare.contains(':')
    }

    /** IPv6 literals must be bracketed inside a URL; everything else is used as-is. */
    private fun hostForUrl(address: String): String =
        if (address.contains(':') && !address.startsWith("[")) "[$address]" else address

    /** True when [address] is one no client can reach: IPv6 link-local or all-zeros. */
    private fun isUnreachableAddress(address: String): Boolean {
        val normalized = address.removePrefix("[").removeSuffix("]").lowercase()
        if (normalized.isBlank()) return false
        if (normalized.startsWith("fe80:")) return true
        if (normalized == "::" || normalized == "0.0.0.0") return true
        return normalized.matches(Regex("^(0+:){7}0+$"))
    }

    /** True when [base] answers Plex's identity endpoint with this token. Enqueued rather than
     *  executed so cancelling the coroutine cancels the in-flight call - a blocking
     *  `execute()` would keep a losing probe's socket open for its full timeout. */
    private suspend fun probe(base: String, token: String): Boolean {
        val url = base.toHttpUrlOrNull()?.newBuilder()?.addPathSegment("identity")?.build()
            ?: return false
        val call = probeClient.newCall(Request.Builder().url(url).plexHeaders(token).build())
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (cont.isActive) cont.resume(false)
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val ok = response.use { it.isSuccessful }
                    if (cont.isActive) cont.resume(ok)
                }
            })
        }
    }

    /** Binds this client to a server. No network call - just state, so every fetch below
     *  works. [serverName] verification is left to [friendlyName]. */
    fun restoreSession(serverUrl: String, token: String) {
        val base = serverUrl.trimEnd('/')
        // @Synchronized rather than the coroutine Mutex: this is called from the connect path
        // on Main, and parking a UI thread on a suspending primitive to write three fields is
        // not worth the suspend colour it would force on every caller.
        synchronized(this) {
            this.accessToken = token
            this.serverBase = base
        }
        PlexSession.update(base, token)
    }

    /** Confirms the session works and returns the server's own name, or null if it doesn't. */
    suspend fun friendlyName(): String? {
        val base = serverBase ?: return null
        val json = getJson("$base/") ?: return null
        return container(json)?.optString("friendlyName", "")?.takeIf { it.isNotBlank() }
    }

    suspend fun connect(serverUrl: String, token: String): Result<String> {
        return try {
            mutex.withLock { restoreSession(serverUrl, token) }
            val name = friendlyName()
                ?: return Result.failure(Exception("Server didn't respond"))
            Result.success(name)
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: e.toString(), e))
        }
    }

    // ── Catalog ────────────────────────────────────

    /** The server's library sections, as (key, type) pairs - "movie", "show", "artist", … */
    private suspend fun sections(): List<Pair<String, String>> {
        val base = serverBase ?: return emptyList()
        val json = getJson("$base/library/sections") ?: return emptyList()
        val directories = container(json)?.optJSONArray("Directory") ?: return emptyList()
        return (0 until directories.length()).mapNotNull { i ->
            val dir = directories.optJSONObject(i) ?: return@mapNotNull null
            val key = dir.optString("key", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = dir.optString("type", "")
            key to type
        }
    }

    suspend fun getMovies(): List<PlexItem> = fetchSectionItems("movie", type = 1)

    suspend fun getSeries(): List<PlexItem> = fetchSectionItems("show", type = 2)

    private suspend fun fetchSectionItems(sectionType: String, type: Int): List<PlexItem> {
        val base = serverBase ?: return emptyList()
        return try {
            sections().filter { it.second == sectionType }.flatMap { (key, _) ->
                fetchAllMetadata("$base/library/sections/$key/all?type=$type")
            }.mapNotNull { parseItem(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancellation is not a fetch failure. Folded into the empty-list fallback it
            // would report a cancelled or timed-out load as a library that simply has nothing
            // in it, and would hide the between-pages cancellation check that stops an
            // abandoned crawl from running to completion.
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Episodes for a series, in order. `allLeaves` is every episode under the show in one
     *  call - the alternative is a children call per season. */
    suspend fun getEpisodes(seriesId: String): List<PlexItem> {
        val base = serverBase ?: return emptyList()
        return try {
            fetchAllMetadata("$base/library/metadata/$seriesId/allLeaves").mapNotNull { parseItem(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The series' seasons as the server names them. Grouping episodes by parentIndex alone
     *  can only ever produce "Season 0" for specials, and loses any season the server has a
     *  real name for. */
    suspend fun getSeasons(seriesId: String): List<PlexSeason> {
        val base = serverBase ?: return emptyList()
        return try {
            fetchAllMetadata("$base/library/metadata/$seriesId/children").mapNotNull { json ->
                // `children` on a show returns its seasons, but a show with loose episodes
                // can mix leaves into the same response - those are not seasons.
                if (json.optString("type", "") != "season") return@mapNotNull null
                val id = json.optString("ratingKey", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                PlexSeason(
                    id = id,
                    name = json.optString("title", "").takeIf { it.isNotBlank() } ?: "Season",
                    indexNumber = json.optInt("index", -1).takeIf { it >= 0 }
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Partly-watched films and episodes, newest first - Plex's On Deck, which knows about
     * playback that happened in any other client.
     *
     * On Deck is one list holding two different things: titles with a real resume position,
     * and the *next* unwatched episode of a series that was finished cleanly. Jellyfin
     * exposes those as separate endpoints (Resume and Next Up) and the Home rows are built
     * around that split, so the same split is made here on `viewOffset`.
     */
    suspend fun getResumeItems(limit: Int = 24): List<PlexItem> =
        onDeck(limit).filter { it.resumePositionMs > 0 }

    /** The next unwatched episode of each series the user is partway through. */
    suspend fun getNextUp(limit: Int = 24): List<PlexItem> =
        onDeck(limit).filter { it.resumePositionMs <= 0 && it.mediaType == "Episode" }

    private suspend fun onDeck(limit: Int): List<PlexItem> {
        val base = serverBase ?: return emptyList()
        return try {
            // A single page: On Deck is a short curated list, and both callers cap it anyway.
            val json = getJson("$base/library/onDeck?X-Plex-Container-Start=0&X-Plex-Container-Size=$limit")
                ?: return emptyList()
            metadataArray(json).mapNotNull { parseItem(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Chapter markers for one item, for chapter skip. Fetched per item rather than as a
     *  catalog field - chapters on a few thousand items is a large payload for something only
     *  ever needed for the one title being played. */
    suspend fun getChapters(itemId: String): List<Chapter> {
        val base = serverBase ?: return emptyList()
        val json = getJson("$base/library/metadata/$itemId?includeChapters=1") ?: return emptyList()
        val metadata = metadataArray(json).firstOrNull() ?: return emptyList()
        val chapters = metadata.optJSONArray("Chapter") ?: return emptyList()
        return (0 until chapters.length()).mapNotNull { i ->
            val chapter = chapters.optJSONObject(i) ?: return@mapNotNull null
            val start = chapter.optLong("startTimeOffset", -1L).takeIf { it >= 0 } ?: return@mapNotNull null
            Chapter(
                name = chapter.optString("tag", "").takeIf { it.isNotBlank() } ?: "Chapter ${i + 1}",
                positionMs = start,
                imageUrl = chapter.optString("thumb", "").takeIf { it.isNotBlank() }?.let { "$base$it" }
            )
        }
    }

    /**
     * Every item [url] matches, a page at a time. Plex caps a response at its own container
     * size regardless of what was asked for, so a library past that cap silently truncates
     * without the paging loop - the items simply aren't there, with nothing to indicate why.
     */
    private suspend fun fetchAllMetadata(url: String): List<JSONObject> {
        val all = mutableListOf<JSONObject>()
        var start = 0
        while (start < MAX_ITEMS) {
            // The only cancellation point in the crawl: getJson blocks in execute(), so a
            // cancelled loader (provider toggled, a retry starting) can't take effect inside a
            // page - without this the loop carries on fetching every remaining page of a
            // catalogue nobody is waiting for any more.
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val separator = if (url.contains('?')) "&" else "?"
            val paged = "$url${separator}X-Plex-Container-Start=$start&X-Plex-Container-Size=$PAGE_SIZE"
            val json = getJson(paged) ?: break
            val items = metadataArray(json)
            all += items
            if (items.size < PAGE_SIZE) break
            start += items.size
            val total = container(json)?.optInt("totalSize", -1)?.takeIf { it >= 0 }
            if (total != null && all.size >= total) break
        }
        return all
    }

    // ── Parsing ────────────────────────────────────

    private fun container(json: JSONObject): JSONObject? = json.optJSONObject("MediaContainer")

    private fun metadataArray(json: JSONObject): List<JSONObject> {
        val array = container(json)?.optJSONArray("Metadata") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun parseItem(json: JSONObject): PlexItem? {
        val base = serverBase ?: return null
        val id = json.optString("ratingKey", "").takeIf { it.isNotBlank() } ?: return null
        val type = json.optString("type", "")
        val mediaType = when (type) {
            "movie" -> "Movie"
            "show" -> "Series"
            "episode" -> "Episode"
            else -> return null
        }

        val season = json.optInt("parentIndex", -1).takeIf { it >= 0 }
        val episode = json.optInt("index", -1).takeIf { it >= 0 }
        val title = json.optString("title", "").takeIf { it.isNotBlank() } ?: "Unknown"
        // Same "S01E02 · Title" shape Xtream and Jellyfin bake in, so EpisodeAdapter's
        // stripper and every list that renders an episode name behave identically whichever
        // backend the episode came from.
        val name = if (type == "episode" && season != null && episode != null) {
            "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')} · $title"
        } else {
            title
        }

        val media = json.optJSONArray("Media")?.optJSONObject(0)
        val part = media?.optJSONArray("Part")?.optJSONObject(0)
        val rating = json.optDouble("rating", Double.NaN).takeIf { !it.isNaN() }
            ?: json.optDouble("audienceRating", Double.NaN).takeIf { !it.isNaN() }

        return PlexItem(
            id = id,
            name = name,
            partPath = part?.optString("key", "").orEmpty(),
            mediaType = mediaType,
            imageUrl = json.optString("thumb", "").takeIf { it.isNotBlank() }?.let { "$base$it" },
            backdropUrl = json.optString("art", "").takeIf { it.isNotBlank() }?.let { "$base$it" },
            overview = json.optString("summary", "").takeIf { it.isNotBlank() },
            year = json.optInt("year", 0).takeIf { it > 0 },
            genres = json.optJSONArray("Genre")?.let { array ->
                (0 until array.length()).mapNotNull {
                    array.optJSONObject(it)?.optString("tag", "")?.takeIf { tag -> tag.isNotBlank() }
                }
            } ?: emptyList(),
            rating = rating,
            releaseDate = json.optString("originallyAvailableAt", "").takeIf { it.isNotBlank() }?.take(10),
            seasonNumber = season,
            episodeNumber = episode,
            runtimeMs = json.optLong("duration", 0L).takeIf { it > 0 },
            resumePositionMs = json.optLong("viewOffset", 0L).coerceAtLeast(0L),
            played = json.optInt("viewCount", 0) > 0,
            seriesId = json.optString("grandparentRatingKey", "").takeIf { it.isNotBlank() },
            seriesName = json.optString("grandparentTitle", "").takeIf { it.isNotBlank() }
        )
    }

    // ── HTTP helpers ───────────────────────────────

    /** GET one JSON document. */
    private fun getJson(url: String): JSONObject? {
        val token = accessToken ?: return null
        return runCatching {
            val request = Request.Builder().url(url).plexHeaders(token).build()
            // use{}: an early return on a non-2xx used to leave the body unread and unclosed -
            // a leaked connection never returned to the pool. A run of those starves every
            // other caller into fresh TCP+TLS handshakes.
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
            }
        }.getOrNull()
    }

    /** Fire-and-forget call, ignoring the response beyond success/failure - what every
     *  reporting endpoint needs. Plex's timeline/scrobble endpoints are GETs. */
    private fun fireAndForget(url: HttpUrl, sessionId: String? = null): Boolean {
        val token = accessToken ?: return false
        return runCatching {
            val request = Request.Builder().url(url).plexHeaders(token)
                .apply { sessionId?.let { header("X-Plex-Session-Identifier", it) } }
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    // ── Playback negotiation ───────────────────────

    /**
     * Asks the server how this item should actually be played.
     *
     * Plex's answer comes from the transcode *decision* endpoint: the same parameters the
     * stream would be started with, sent to `/decision` first, which replies with what the
     * server would do - direct play the file as-is, or build an HLS transcode. Direct play
     * wins whenever it's offered (no re-encode on the server, no quality loss, instant
     * seeking); only when the server says it can't do we take the transcode.
     *
     * Returns null on any failure; callers fall back to the item's direct-play URL, which is
     * what a file the device can decode would have got anyway.
     */
    suspend fun resolveStream(
        itemId: String,
        startPositionMs: Long = 0L,
        maxBitrateKbps: Int = DEFAULT_MAX_BITRATE_KBPS,
        preferredAudioLanguage: String? = null,
        forceAudioStreamId: Long? = null,
        /**
         * Skips the decision entirely and takes the HLS transcode.
         *
         * For containers the server will happily hand over and Media3 cannot read. The
         * decision endpoint answers "can *I* serve this as-is", not "can this client parse
         * it", and the profile sent with it advertises transcode targets without ever
         * declaring which containers this client can actually demux - so an AVI, WMV or VOB
         * comes back as direct play and dies in the extractors with
         * ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED. The player sets this on the retry after
         * exactly that failure.
         */
        forceTranscode: Boolean = false
    ): ResolvedStream? {
        val base = serverBase ?: return null
        val token = accessToken ?: return null

        val json = getJson(
            "$base/library/metadata/$itemId?includeChapters=1&checkFiles=1&includeStreams=1"
        ) ?: return null
        val metadata = metadataArray(json).firstOrNull() ?: return null
        val media = metadata.optJSONArray("Media")?.optJSONObject(0) ?: return null
        val part = media.optJSONArray("Part")?.optJSONObject(0) ?: return null

        val audioStreams = parseAudioStreams(part)
        val wantedAudioId = forceAudioStreamId
            ?: preferredAudioId(audioStreams, preferredAudioLanguage)
        val runtimeMs = media.optLong("duration", 0L).takeIf { it > 0 }
            ?: metadata.optLong("duration", 0L).takeIf { it > 0 }

        val sessionId = UUID.randomUUID().toString()
        val transcodeSessionId = UUID.randomUUID().toString()
        val params = transcodeParams(
            itemId = itemId,
            startPositionMs = startPositionMs,
            maxBitrateKbps = maxBitrateKbps,
            sessionId = sessionId,
            transcodeSessionId = transcodeSessionId,
            audioStreamId = wantedAudioId,
            token = token
        )

        val directPlay = !forceTranscode && decisionAllowsDirectPlay(base, params)
        val partKey = part.optString("key", "").takeIf { it.isNotBlank() }
        // Embedded text subtitles are only sideloaded on the transcode path. Direct play
        // hands the whole file over, so those tracks arrive inside it and Media3 lists them
        // itself - sideloading a second copy would show every one of them twice. A transcode
        // goes out with `subtitles=none`, so there the sideload is the only way they exist at
        // all. External sidecar files are sideloaded either way: they are not in the file.
        val subtitles = parseSubtitleStreams(part, base, token, includeEmbedded = !directPlay)

        // A decision that says "direct play" still needs a part to point at; without one
        // there is nothing to direct-play and the transcode is the only answer.
        if (directPlay && partKey != null) {
            return ResolvedStream(
                url = directPlayUrl(partKey),
                playSessionId = sessionId,
                transcodeSessionId = null,
                playMethod = "DirectPlay",
                runtimeMs = runtimeMs,
                subtitles = subtitles,
                audioStreams = audioStreams,
                audioStreamId = wantedAudioId ?: audioStreams.firstOrNull { it.isDefault }?.id
            )
        }

        val startUrl = buildUrl("$base/video/:/transcode/universal/start.m3u8", params) ?: return null
        return ResolvedStream(
            url = startUrl,
            playSessionId = sessionId,
            transcodeSessionId = transcodeSessionId,
            playMethod = "Transcode",
            runtimeMs = runtimeMs,
            subtitles = subtitles,
            audioStreams = audioStreams,
            audioStreamId = wantedAudioId ?: audioStreams.firstOrNull { it.isDefault }?.id,
            // Plex writes segment paths into the playlist with no token on them, so without
            // this the first segment 401s and playback dies a second or two in.
            tokenQuery = "X-Plex-Token=$token"
        )
    }

    /** The token-carrying direct-play URL for a part path. Playback and subtitles go through
     *  Media3's own HTTP stack, which never sees [PlexAuthInterceptor], so the token has to
     *  ride in the query string. */
    fun directPlayUrl(partPath: String): String {
        val base = serverBase ?: return partPath
        val token = accessToken.orEmpty()
        val absolute = if (partPath.startsWith("http")) partPath else "$base$partPath"
        val separator = if (absolute.contains('?')) "&" else "?"
        return "$absolute${separator}X-Plex-Token=$token"
    }

    /**
     * The parameter set shared by the decision and start endpoints - they must match exactly,
     * or the decision describes a stream that isn't the one being started.
     *
     * `directPlay=1` is what lets the server answer "just play the file". The client profile
     * pins the transcode target to HLS/MPEG-TS with H.264 + AAC: this path only ever runs
     * *because* the device couldn't handle the original, so the fallback has to be the format
     * with the broadest hardware support rather than a second exotic one. AAC in particular
     * is the point on hardware with no Dolby licence, where the original's AC3/E-AC3 track is
     * exactly what made direct play impossible.
     */
    private fun transcodeParams(
        itemId: String,
        startPositionMs: Long,
        maxBitrateKbps: Int,
        sessionId: String,
        transcodeSessionId: String,
        audioStreamId: Long?,
        token: String
    ): Map<String, String> {
        val profileExtra = buildString {
            append("add-transcode-target(type=videoProfile&context=streaming")
            append("&protocol=hls&container=mpegts&videoCodec=h264&audioCodec=aac)")
            append("+add-transcode-target(type=subtitleProfile&context=streaming")
            append("&protocol=hls&container=webvtt&subtitleCodec=webvtt)")
            append("+add-limitation(scope=videoCodec&scopeName=*&type=upperBound")
            append("&name=video.bitrate&value=$maxBitrateKbps&replace=true)")
            append("+add-settings(DirectPlayStreamSelection=true)")
        }
        return buildMap {
            put("hasMDE", "1")
            put("path", "/library/metadata/$itemId")
            put("mediaIndex", "0")
            put("partIndex", "0")
            put("protocol", "hls")
            put("fastSeek", "1")
            put("directPlay", "1")
            put("directStream", "1")
            put("directStreamAudio", "1")
            put("subtitleSize", "100")
            put("audioBoost", "100")
            put("location", "lan")
            put("addDebugOverlay", "0")
            put("autoAdjustQuality", "0")
            put("mediaBufferSize", "102400")
            put("subtitles", "none")
            put("offset", (startPositionMs / 1000).toString())
            put("maxVideoBitrate", maxBitrateKbps.toString())
            put("session", transcodeSessionId)
            audioStreamId?.let { put("audioStreamID", it.toString()) }
            put("X-Plex-Session-Identifier", sessionId)
            put("X-Plex-Client-Profile-Extra", profileExtra)
            put("X-Plex-Client-Profile-Name", "Generic")
            put("X-Plex-Incomplete-Segments", "1")
            put("X-Plex-Product", PRODUCT)
            put("X-Plex-Version", VERSION)
            put("X-Plex-Client-Identifier", clientIdentifier)
            put("X-Plex-Platform", PLATFORM)
            put("X-Plex-Device", DEVICE)
            put("X-Plex-Device-Name", DEVICE)
            put("X-Plex-Model", "standalone")
            put("X-Plex-Token", token)
        }
    }

    /**
     * True when the server says it would direct-play this item.
     *
     * A decision that fails to come back at all also answers true: the fallback is then the
     * plain file, which is what the app would have played regardless, and refusing to open a
     * title because a negotiation endpoint was unreachable is the worse outcome of the two.
     */
    private fun decisionAllowsDirectPlay(base: String, params: Map<String, String>): Boolean {
        val url = buildUrl("$base/video/:/transcode/universal/decision", params) ?: return true
        val json = getJson(url) ?: return true
        val container = container(json) ?: return true
        // Anything from 2000 up is an error code ("not enough bandwidth", "unsupported
        // profile"); the transcode is what those mean in practice.
        val general = container.optInt("generalDecisionCode", 0)
        if (general >= 2000) return false
        val part = metadataArray(json).firstOrNull()
            ?.optJSONArray("Media")?.optJSONObject(0)
            ?.optJSONArray("Part")?.optJSONObject(0)
        val decision = part?.optString("decision", "")?.lowercase()
        // "copy" is a remux, not a re-encode - the file's own streams, repackaged. Media3 can
        // take the original in that case, so it counts as direct play here.
        return decision == null || decision.isBlank() || decision == "directplay" || decision == "copy"
    }

    private fun buildUrl(base: String, params: Map<String, String>): String? {
        val builder = base.toHttpUrlOrNull()?.newBuilder() ?: return null
        // addQueryParameter, not a hand-built string: the client profile is a clause list full
        // of literal '&' and '=' that has to survive as one parameter value.
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    /** Every audio track of the resolved part, in the file's own order. */
    private fun parseAudioStreams(part: JSONObject): List<AudioStream> =
        streams(part, streamType = 2).mapNotNull { stream ->
            val id = stream.optLong("id", -1L).takeIf { it >= 0 } ?: return@mapNotNull null
            AudioStream(
                id = id,
                language = stream.optString("languageCode", "").takeIf { it.isNotBlank() }
                    ?: stream.optString("language", "").takeIf { it.isNotBlank() },
                title = stream.optString("displayTitle", "").takeIf { it.isNotBlank() },
                codec = stream.optString("codec", "").takeIf { it.isNotBlank() },
                channels = stream.optInt("channels", 0).takeIf { it > 0 },
                // Plex marks the track the server would use as `selected`, which is the same
                // role Jellyfin's IsDefault plays for the picker.
                isDefault = stream.optBoolean("selected", false) || stream.optBoolean("default", false)
            )
        }

    /** Subtitle tracks of the resolved part. Anything the server can hand over as a file gets
     *  a URL here so it can be sideloaded into the MediaItem and rendered by Media3;
     *  image-based tracks (PGS/VOBSUB) have no Media3 renderer and are left alone. */
    private fun parseSubtitleStreams(
        part: JSONObject,
        base: String,
        token: String,
        includeEmbedded: Boolean
    ): List<SubtitleStream> =
        streams(part, streamType = 3).mapNotNull { stream ->
            val id = stream.optLong("id", -1L).takeIf { it >= 0 } ?: return@mapNotNull null
            val codec = stream.optString("codec", "").takeIf { it.isNotBlank() }?.lowercase()
            val key = stream.optString("key", "").takeIf { it.isNotBlank() }
            val url = when {
                // An external sidecar has its own key and is served as-is.
                key != null -> "$base$key${if (key.contains('?')) "&" else "?"}X-Plex-Token=$token"
                // An embedded text track has no key, but the server will serve it on
                // request. Image-based ones (PGS/VOBSUB) have no Media3 renderer and get no
                // URL - a sideloaded track that renders nothing is worse than no track.
                includeEmbedded && isTextSubtitle(codec) ->
                    "$base/library/streams/$id?X-Plex-Token=$token"
                else -> null
            }
            SubtitleStream(
                id = id,
                url = url,
                language = stream.optString("languageCode", "").takeIf { it.isNotBlank() }
                    ?: stream.optString("language", "").takeIf { it.isNotBlank() },
                title = stream.optString("displayTitle", "").takeIf { it.isNotBlank() },
                codec = codec,
                isExternal = key != null,
                isForced = stream.optBoolean("forced", false),
                isDefault = stream.optBoolean("selected", false) || stream.optBoolean("default", false)
            )
        }

    private fun streams(part: JSONObject, streamType: Int): List<JSONObject> {
        val array = part.optJSONArray("Stream") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            .filter { it.optInt("streamType", -1) == streamType }
    }

    private fun isTextSubtitle(codec: String?): Boolean =
        codec in setOf("subrip", "srt", "webvtt", "vtt", "ass", "ssa", "ttml", "mov_text", "text")

    /**
     * The item's audio stream in [language], or null when there is nothing to choose (one
     * track, no language given, or no track tagged with it).
     *
     * Plex reports both an ISO 639-1 `languageCode` on some servers and a 639-2 one on
     * others, while the app's setting is 639-1, so both forms are matched. Among several
     * tracks in the same language the server's own selection wins - that is the one the file
     * was authored around, with commentary and descriptive tracks as the extras.
     */
    private fun preferredAudioId(streams: List<AudioStream>, language: String?): Long? {
        val wanted = language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (streams.size < 2) return null
        val iso3 = runCatching { java.util.Locale(wanted).isO3Language }.getOrNull().orEmpty().lowercase()
        val matches = streams.filter {
            val tag = it.language?.lowercase().orEmpty()
            tag.isNotEmpty() && (tag == wanted || (iso3.isNotEmpty() && tag == iso3) || tag.startsWith("$wanted-"))
        }
        return (matches.firstOrNull { it.isDefault } ?: matches.firstOrNull())?.id
    }

    // ── Playback reporting ─────────────────────────

    /**
     * Tells the server a play has started. Without this trio of calls nothing watched in
     * Lumora ever reaches the server: watch state, resume points and On Deck all stay as they
     * were, and a transcode is left running with no session to attribute it to.
     */
    suspend fun reportPlaybackStart(
        itemId: String,
        sessionId: String?,
        positionMs: Long,
        durationMs: Long?
    ): Boolean = timeline(itemId, sessionId, positionMs, durationMs, "playing")

    /** Progress heartbeat, sent every ~10s while playing. It's also what keeps a live
     *  transcode from being reaped as abandoned. */
    suspend fun reportPlaybackProgress(
        itemId: String,
        sessionId: String?,
        positionMs: Long,
        isPaused: Boolean,
        durationMs: Long?
    ): Boolean = timeline(itemId, sessionId, positionMs, durationMs, if (isPaused) "paused" else "playing")

    /**
     * End of play.
     *
     * Unlike Jellyfin, a stopped timeline on its own does not mark a title watched: PMS only
     * acts on a threshold crossing it observes *within* one session, and a play resumed near
     * the end never produces one. So a finished title is scrobbled explicitly - without it, a
     * film watched to the end in Lumora still shows as unwatched in every other Plex client.
     */
    suspend fun reportPlaybackStopped(
        itemId: String,
        sessionId: String?,
        positionMs: Long,
        durationMs: Long?
    ): Boolean {
        val stopped = timeline(itemId, sessionId, positionMs, durationMs, "stopped")
        if (durationMs != null && durationMs > 0 && positionMs >= durationMs * 0.9) {
            markWatched(itemId)
        }
        return stopped
    }

    private fun timeline(
        itemId: String,
        sessionId: String?,
        positionMs: Long,
        durationMs: Long?,
        state: String
    ): Boolean {
        val base = serverBase ?: return false
        val params = buildMap {
            put("ratingKey", itemId)
            put("key", "/library/metadata/$itemId")
            put("state", state)
            put("time", positionMs.coerceAtLeast(0L).toString())
            durationMs?.takeIf { it > 0 }?.let { put("duration", it.toString()) }
            put("identifier", "com.plexapp.plugins.library")
        }
        val url = buildUrl("$base/:/timeline", params)?.toHttpUrlOrNull() ?: return false
        return fireAndForget(url, sessionId)
    }

    /** Keeps a paused transcode alive. Timeline updates alone have historically not stopped
     *  PMS reaping an idle transcoder, so Plex's own clients send this alongside a paused
     *  timeline. Best effort - a failed ping must never disturb playback. */
    suspend fun pingTranscodeSession(transcodeSessionId: String): Boolean {
        val base = serverBase ?: return false
        val url = buildUrl(
            "$base/video/:/transcode/universal/ping",
            mapOf("session" to transcodeSessionId)
        )?.toHttpUrlOrNull() ?: return false
        return fireAndForget(url)
    }

    suspend fun markWatched(itemId: String): Boolean = scrobble(itemId, watched = true)

    /** Clears the server's watch state for [itemId] and drops it out of Continue Watching -
     *  the Plex equivalent of Jellyfin's UserData delete. */
    suspend fun clearUserData(itemId: String): Boolean {
        val base = serverBase ?: return false
        val unscrobbled = scrobble(itemId, watched = false)
        val url = buildUrl(
            "$base/actions/removeFromContinueWatching",
            mapOf("ratingKey" to itemId)
        )?.toHttpUrlOrNull()
        // The Continue Watching removal is best effort: older servers have no such endpoint,
        // and the unscrobble alone is already the meaningful half.
        url?.let { fireAndForget(it) }
        return unscrobbled
    }

    private fun scrobble(itemId: String, watched: Boolean): Boolean {
        val base = serverBase ?: return false
        val path = if (watched) "/:/scrobble" else "/:/unscrobble"
        val url = buildUrl(
            "$base$path",
            mapOf("key" to itemId, "identifier" to "com.plexapp.plugins.library")
        )?.toHttpUrlOrNull() ?: return false
        return fireAndForget(url)
    }

    companion object {
        /** Ceiling handed to the transcoder, in kbps. Deliberately generous - the transcode
         *  path only runs for files the device couldn't take as-is, and starving it produces
         *  a visibly worse picture than the original for no benefit on a LAN. */
        const val DEFAULT_MAX_BITRATE_KBPS = 20_000

        /** [prefixSeriesName] labels an episode with the show it belongs to - needed for the
         *  Next Up / Continue Watching rows, where a bare "S02E04 · Title" says nothing about
         *  which series is being offered. Off inside a series' own episode list, which is
         *  already scoped to one show. */
        fun toChannel(
            item: PlexItem,
            provider: Provider,
            prefixSeriesName: Boolean = false,
            sourceId: String? = null
        ): Channel {
            val mediaType = when (item.mediaType) {
                "Movie" -> MediaType.MOVIE
                "Series" -> MediaType.SERIES
                "Episode" -> MediaType.SERIES
                else -> MediaType.MOVIE
            }
            val serverBase = provider.serverUrl?.trimEnd('/') ?: ""
            // Token-free on purpose: this URL is written to the on-disk channel cache, and a
            // session token has no business sitting in a catalogue file. The token is appended
            // at play time (see PlexProvider.directPlayUrl / MainActivityPlex).
            //
            // A show carries no media of its own, so it falls back to its metadata path rather
            // than an empty string: a blank url means "nothing can play this" elsewhere in the
            // app (see isUnreleasedEpisode), which is the wrong thing to say about a series
            // whose episodes all play fine.
            val streamUrl = if (item.partPath.isNotBlank()) "$serverBase${item.partPath}"
                else "$serverBase/library/metadata/${item.id}"

            val displayName = if (prefixSeriesName && !item.seriesName.isNullOrBlank()) {
                "${item.seriesName} · ${item.name}"
            } else {
                item.name
            }

            return Channel(
                // Qualified with the server this came from - a Plex rating key is a small
                // per-server integer, so two configured servers would otherwise hand the
                // catalog two different films under the same id. See qualifiedMediaItemId.
                id = qualifiedMediaItemId(sourceId, item.id),
                name = displayName,
                url = streamUrl,
                logoUrl = item.imageUrl,
                posterUrl = item.imageUrl,
                backdropUrl = item.backdropUrl,
                description = item.overview,
                year = item.year?.toString(),
                categoryName = item.genres.firstOrNull(),
                // Stamp the parent series id on an episode so Continue Watching / Next Up
                // tiles resolve back to the show rather than direct-playing (see
                // resolveHomeTileSeries, which matches categoryId against the catalog).
                categoryId = if (item.mediaType == "Episode") {
                    item.seriesId?.let { qualifiedMediaItemId(sourceId, it) }
                } else null,
                episodeNum = item.episodeNumber,
                mediaType = mediaType,
                rating = item.rating?.toString(),
                releaseDate = item.releaseDate,
                isPlex = true,
                // Which configured Plex server this came from - detail fetches, playback
                // negotiation and reporting all need *that* server's client and token.
                sourceProviderId = sourceId
            )
        }
    }
}
