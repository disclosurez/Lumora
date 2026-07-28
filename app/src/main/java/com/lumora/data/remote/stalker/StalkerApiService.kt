package com.lumora.data.remote.stalker

import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.model.Provider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Stalker Portal / Ministra API client.
 * Handles the custom auth flow (MAC-based with token handshake),
 * channel/EPG/VOD fetching for Stalker middleware providers.
 */
class StalkerApiService(private val client: OkHttpClient) {

    data class StalkerProfile(
        val serialNumber: String,
        val deviceId: String,
        val deviceId2: String,
        val signature: String,
        val mac: String
    )

    data class AuthResult(
        val token: String?,
        val jsToken: String? = null,
        val cookie: String? = null,
        val success: Boolean = false
    )

    data class ChannelEntry(
        val id: String,
        val name: String,
        val url: String,
        val logo: String?,
        val categoryId: String?,
        val categoryName: String?,
        val tvgId: String?,
        val tvgChno: String?,
        val isAdult: Boolean = false,
        // VOD/series metadata. poster is screenshot_uri (a TMDB image), releaseDate/year come
        // from the API's "year" field (which is actually a full date), and rawCmd is the
        // unresolved play command - base64 for VOD, needing create_link at play time, so it's
        // kept rather than baked into url.
        val poster: String? = null,
        val description: String? = null,
        val releaseDate: String? = null,
        val rating: String? = null,
        val rawCmd: String? = null
    )

    private var authToken: String? = null
    private var jsToken: String? = null
    private var authCookie: String? = null
    private var serverBase: String? = null
    // The MAC (colon form) and timezone go in a Cookie on every request - portals key the
    // session to the MAC that way, and reject calls that omit it. Kept as fields so the
    // low-level fetchJson() can attach them without every caller threading them through.
    private var deviceMac: String? = null
    private var deviceTimezone: String = "Europe/London"
    // Path to load.php, discovered at handshake. Portals disagree on where it lives:
    // Ministra serves /portal.php, classic Stalker /stalker_portal/server/load.php, and
    // some put it under /c/. The old client hard-coded the middle one and silently 404'd on
    // every portal using either of the others - which is most of them. Cached once resolved
    // so the rest of the session doesn't re-probe.
    private var endpoint: String? = null

    private val endpointCandidates = listOf(
        "/portal.php",
        "/stalker_portal/server/load.php",
        "/c/portal.php",
        "/server/load.php"
    )

    /**
     * Generate a Stalker device profile from a MAC address.
     */
    fun generateProfile(mac: String, serialNumber: String = "000000000000"): StalkerProfile {
        val cleanMac = mac.replace(":", "").replace("-", "").uppercase()
        val deviceId = cleanMac + serialNumber.take(9)
        val deviceId2 = "0123456789ABCDEF0123456789ABCDEF"
        val signature = md5(cleanMac + serialNumber + deviceId2)
        return StalkerProfile(
            serialNumber = serialNumber,
            deviceId = deviceId,
            deviceId2 = deviceId2,
            signature = signature,
            mac = cleanMac
        )
    }

    /**
     * Authenticate with a Stalker portal.
     * Flow: server → handshake → get profile → get token → get channels
     */
    suspend fun authenticate(serverUrl: String, mac: String, timezone: String = "Europe/London"): Result<AuthResult> {
        return try {
            val base = serverUrl.trimEnd('/')
            val profile = generateProfile(mac)
            serverBase = base
            // Fresh session - a reused instance must not send a stale Bearer during the
            // unauthenticated handshake or carry a previous portal's cookie.
            authToken = null
            authCookie = null
            endpoint = null
            // The cookie/param MAC keeps its colons - portals match the session on the exact
            // string, and the colon-stripped form generateProfile() derives is only for the
            // signature/device-id maths. Uppercased for consistency with the fingerprint.
            deviceMac = mac.uppercase()
            deviceTimezone = timezone

            // Step 1: Server handshake - also resolves which endpoint path this portal uses,
            // trying each candidate until one returns a token.
            var jsTokenFromServer: String? = null
            for (candidate in endpointCandidates) {
                val handshakeJson = fetchJson(
                    "$base$candidate?type=stb&action=handshake&JsHttpRequest=1-xml", null
                ) ?: continue
                val t = handshakeJson.optJSONObject("js")?.optString("token")?.ifBlank { null }
                    ?: handshakeJson.optString("token").ifBlank { null }
                if (t != null) {
                    endpoint = candidate
                    jsTokenFromServer = t
                    break
                }
            }
            if (jsTokenFromServer == null || endpoint == null) {
                return Result.failure(Exception("Handshake failed - no Stalker endpoint responded"))
            }
            jsToken = jsTokenFromServer
            // The handshake token is what authorises the profile call, so adopt it now -
            // fetchJson() sends it as the Bearer token from here on.
            authToken = jsTokenFromServer

            // Step 2: Get profile and authenticate
            val profileUrl = "$base${endpoint}?type=stb&action=get_profile" +
                    "&hd=1&ver=&num_banks=1&stb_type=MAG250&image_version=218" +
                    "&JsHttpRequest=1-xml" +
                    "&sn=${profile.serialNumber}" +
                    "&device_id=${profile.deviceId}" +
                    "&device_id2=${profile.deviceId2}" +
                    "&signature=${profile.signature}" +
                    "&mac=${URLEncoder.encode(deviceMac, "UTF-8")}"

            val profileJson = fetchJson(profileUrl, jsTokenFromServer)
            if (profileJson == null) return Result.failure(Exception("Profile fetch failed"))

            // get_profile may mint a fresh token; if it doesn't, the handshake token stays
            // valid, so fall back to it rather than dropping to null and losing the session.
            val token = profileJson.optJSONObject("js")?.optString("token")?.ifBlank { null }
                ?: profileJson.optString("token").ifBlank { null }
                ?: jsTokenFromServer

            authToken = token

            val authResult = AuthResult(
                token = token,
                jsToken = jsTokenFromServer,
                success = token != null
            )

            // Step 3: Get channels (test auth)
            if (token != null) {
                // Fetch initial data
                val channelsUrl = buildApiUrl("get_all_channels", mac, token)
                val channelsJson = fetchJson(channelsUrl, jsTokenFromServer)
                if (channelsJson != null) {
                    return Result.success(authResult)
                }
            }

            Result.success(authResult)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch all live channels from the Stalker portal.
     */
    suspend fun getLiveChannels(serverUrl: String, mac: String): List<ChannelEntry> {
        val token = authToken ?: return emptyList()
        // get_all_channels is one cheap call, but plenty of portals leave it empty and only
        // populate the paged get_ordered_list (portal-dependent, not a fault we can detect
        // ahead of time) - so fall through to that when the bulk call comes back empty.
        val cats = fetchCategories("itv", mac, token)
        val bulk = fetchChannelList(serverUrl, mac, token, "itv", "get_all_channels", cats)
        if (bulk.isNotEmpty()) return bulk
        // Only portals that leave get_all_channels empty reach the paged path. Live TV gets
        // the largest budget of the three - it's what people open the app for.
        return fetchOrderedList(mac, token, "itv", maxPages = LIVE_MAX_PAGES, categories = cats)
    }

    /**
     * Fetch VOD list from the Stalker portal.
     */
    suspend fun getVodList(serverUrl: String, mac: String): List<ChannelEntry> {
        val token = authToken ?: return emptyList()
        return fetchOrderedList(mac, token, "vod", maxPages = VOD_MAX_PAGES, categories = fetchCategories("vod", mac, token))
    }

    /**
     * Fetch series list from the Stalker portal.
     */
    suspend fun getSeriesList(serverUrl: String, mac: String): List<ChannelEntry> {
        val token = authToken ?: return emptyList()
        return fetchOrderedList(mac, token, "series", maxPages = VOD_MAX_PAGES, categories = fetchCategories("series", mac, token))
    }

    /** id -> title map for a content [type], so list items (which carry only the id) can show
     *  a real category name. Live TV names its buckets through get_genres, not get_categories
     *  (VOD/series) - using the wrong one left live categories showing raw numeric genre ids.
     *  Empty map on failure - callers fall back to no name. */
    private suspend fun fetchCategories(type: String, mac: String, token: String): Map<String, String> {
        val action = if (type == "itv") "get_genres" else "get_categories"
        val url = buildApiUrl(action, mac, token, type)
        val json = fetchJson(url, jsToken) ?: return emptyMap()
        val arr = json.optJSONArray("js") ?: return emptyMap()
        val map = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val title = o.optString("title")
            if (id.isNotBlank() && id != "*" && title.isNotBlank()) map[id] = title
        }
        return map
    }

    /** Pages through get_ordered_list for a content [type] ("itv"/"vod"/"series"), following
     *  the js.total_items count the first page reports. [maxPages] hard-caps the walk: this
     *  API returns ~14 items a page, so a 17k-item VOD catalogue is over 1200 pages - fetching
     *  every one sequentially on connect took minutes and looked like the portal never
     *  responded. The cap trades a complete upfront catalogue for a portal that connects
     *  promptly; live TV gets a bigger budget than VOD/series (see the call sites). */
    private suspend fun fetchOrderedList(
        mac: String, token: String, type: String, maxPages: Int, categories: Map<String, String> = emptyMap()
    ): List<ChannelEntry> {
        val base = serverBase?.trimEnd('/') ?: return emptyList()
        val out = ArrayList<ChannelEntry>()
        var page = 1
        var total = Int.MAX_VALUE
        while (page <= maxPages && out.size < total) {
            val url = buildApiUrl("get_ordered_list", mac, token, type) + "&genre=*&sortby=added&p=$page"
            val json = fetchJson(url, jsToken) ?: break
            val js = json.optJSONObject("js") ?: break
            if (page == 1) total = js.optInt("total_items", 0).takeIf { it > 0 } ?: Int.MAX_VALUE
            val items = js.optJSONArray("data") ?: break
            if (items.length() == 0) break
            out.addAll(parseEntries(base, items, categories))
            page++
        }
        return out
    }

    /** Resolves a VOD/series play command (the base64 cmd from a list item) into a real,
     *  time-limited stream URL via create_link. Must be called on an authenticated instance.
     *  [type] is "vod" for a film, "series" for an episode. */
    suspend fun createLink(cmd: String, episode: Int? = null): String? {
        val base = serverBase?.trimEnd('/') ?: return null
        authToken ?: return null
        val path = endpoint ?: return null
        // type=vod resolves both films and series episodes; a series episode adds &series=N
        // (the number within its season) - the season's own cmd is shared across its episodes.
        val url = "$base$path?type=vod&action=create_link" +
                "&cmd=${URLEncoder.encode(cmd, "UTF-8")}" +
                (episode?.let { "&series=$it" } ?: "") +
                "&mac=${URLEncoder.encode(deviceMac ?: "", "UTF-8")}" +
                "&forced_storage=undefined&disable_ad=0&JsHttpRequest=1-xml"
        val json = fetchJson(url, jsToken) ?: return null
        val resolved = json.optJSONObject("js")?.optString("cmd")?.ifBlank { null } ?: return null
        return buildStreamUrl(base, resolved)
    }

    /** One season of a Stalker series: its display [label], the shared play [cmd] (create_link
     *  with &series=N picks the episode), and the episode [numbers] it contains. */
    data class StalkerSeason(val label: String, val cmd: String, val numbers: List<Int>)

    /** Authenticates and returns true if the portal accepted the MAC - lets a caller resolve
     *  a play URL (createLink) without the full loadContent() catalogue fetch. */
    suspend fun connect(serverUrl: String, mac: String): Boolean =
        authenticate(serverUrl, mac).getOrNull()?.success == true

    /** Episode list for a Stalker series. Stalker returns a series' seasons/episodes through
     *  the same get_ordered_list, filtered to the series' [movieId]; each returned row's
     *  `series` array holds its episode numbers. Returns (episodeNumber, playCmd) pairs. */
    suspend fun getSeriesEpisodes(movieId: String, categoryId: String?, mac: String): List<StalkerSeason> {
        authToken ?: return emptyList()
        val base = serverBase?.trimEnd('/') ?: return emptyList()
        val path = endpoint ?: return emptyList()
        val out = ArrayList<StalkerSeason>()
        var page = 1
        var total = Int.MAX_VALUE
        while (page <= 20 && out.size < total) {
            val url = "$base$path?type=series&action=get_ordered_list" +
                    "&movie_id=${URLEncoder.encode(movieId, "UTF-8")}" +
                    (categoryId?.let { "&category=${URLEncoder.encode(it, "UTF-8")}" } ?: "") +
                    "&genre=*&p=$page&mac=${URLEncoder.encode(mac, "UTF-8")}&JsHttpRequest=1-xml"
            val json = fetchJson(url, jsToken) ?: break
            val js = json.optJSONObject("js") ?: break
            if (page == 1) total = js.optInt("total_items", 0).takeIf { it > 0 } ?: Int.MAX_VALUE
            val items = js.optJSONArray("data") ?: break
            if (items.length() == 0) break
            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                val cmd = obj.optString("cmd", "")
                if (cmd.isBlank()) continue
                val label = obj.optString("name", "").ifBlank { "Season ${out.size + 1}" }
                val epsArr = obj.optJSONArray("series")
                val nums = if (epsArr != null && epsArr.length() > 0) {
                    (0 until epsArr.length()).map { epsArr.optInt(it) }
                } else {
                    // No explicit episode array - treat the row itself as one playable item.
                    listOf(obj.optString("number").toIntOrNull() ?: 1)
                }
                out.add(StalkerSeason(label, cmd, nums))
            }
            page++
        }
        return out
    }

    /**
     * Get EPG data for a channel.
     */
    suspend fun getEpg(channelId: String): List<EpgProgram> {
        return emptyList() // Basic EPG — Stalker usually needs XMLTV
    }

    data class EpgProgram(
        val title: String,
        val startTimestamp: Long,
        val stopTimestamp: Long
    )

    // ── Internal helpers ───────────────────────

    private suspend fun fetchChannelList(
        serverUrl: String, mac: String, token: String, type: String, action: String,
        categories: Map<String, String> = emptyMap()
    ): List<ChannelEntry> {
        return try {
            val base = serverUrl.trimEnd('/')
            val url = buildApiUrl(action, mac, token, type)
            val json = fetchJson(url, jsToken)
            val js = json?.optJSONObject("js")
            val items = js?.optJSONArray("data") ?: js?.optJSONArray("") ?: return emptyList()
            parseEntries(base, items, categories)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseEntries(base: String, items: JSONArray, categories: Map<String, String>): List<ChannelEntry> =
        (0 until items.length()).mapNotNull { i ->
            val obj = items.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id")
            if (id.isBlank()) return@mapNotNull null
            val catId = obj.optString("category_id", "").ifBlank { null }
                ?: obj.optString("tv_genre_id", "").ifBlank { null }
            val rawCmd = obj.optString("cmd", "").ifBlank { null }
            // "year" is actually a full release date ("2026-07-07") on this API.
            val date = obj.optString("year", "").ifBlank { null }
            ChannelEntry(
                id = id,
                name = obj.optString("name"),
                // Live commands ("ffmpeg http://…") resolve to a URL inline; VOD commands are
                // base64 and only become playable via create_link, so leave url blank for
                // those and carry the raw cmd for the play step to resolve.
                url = if (rawCmd != null && rawCmd.startsWith("ffmpeg")) buildStreamUrl(base, rawCmd)
                      else if (rawCmd != null && rawCmd.startsWith("http")) rawCmd
                      else "",
                logo = obj.optString("logo", "").ifBlank { null },
                categoryId = catId,
                categoryName = catId?.let { categories[it] } ?: obj.optString("category_name", "").ifBlank { null },
                tvgId = obj.optString("tv_genre_id", null),
                tvgChno = obj.optString("number", null),
                isAdult = obj.optString("is_adult", "0") == "1",
                poster = obj.optString("screenshot_uri", "").ifBlank { null }
                    ?: obj.optString("pic", "").ifBlank { null },
                description = obj.optString("description", "").ifBlank { null },
                releaseDate = date,
                rating = obj.optString("rating_imdb", "").ifBlank { null },
                rawCmd = rawCmd
            )
        }

    private fun buildStreamUrl(base: String, cmd: String): String {
        // Stalker prefixes the playable URL with the player it wants used - "ffmpeg http://…"
        // or "ffrt3 http://…". The old code checked startsWith("http"), which an "ffmpeg "
        // prefix fails, so it fell through and built "$base/stalker_portal/ffmpeg http://…" -
        // an unplayable path. Strip a single leading player token, then take the URL as-is.
        val stripped = cmd.trim().let { c ->
            val sp = c.indexOf(' ')
            if (sp in 1 until 12 && !c.startsWith("http")) c.substring(sp + 1).trim() else c
        }
        if (stripped.startsWith("http")) return stripped
        return "$base/${stripped.trimStart('/')}"
    }

    private fun buildApiUrl(action: String, mac: String, token: String, type: String = "stb"): String {
        val base = serverBase?.trimEnd('/') ?: return ""
        val path = endpoint ?: "/stalker_portal/server/load.php"
        val macParam = URLEncoder.encode(deviceMac ?: mac, "UTF-8")
        val tokenParam = URLEncoder.encode(token, "UTF-8")
        // type is the Stalker API family, and it is not always "stb": channel/VOD/series
        // listing lives under itv/vod/series. The old client sent type=stb for get_all_channels
        // too, which returns nothing - so live TV came back empty even when auth succeeded.
        return "$base$path?type=$type&action=$action" +
                "&mac=$macParam&token=$tokenParam" +
                "&JsHttpRequest=1-xml"
    }

    private suspend fun fetchJson(url: String, jsToken: String?): JSONObject? {
        return try {
            val builder = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; Linux; MAG250; Model: MAG250; Link: WiFi) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250")

            // The MAG session cookie: the MAC (colon form) plus locale/timezone. Portals key
            // the whole session to this and 401/404 without it - this was the second thing
            // (after the endpoint path) the old client never sent, so even a reachable portal
            // rejected it. A Set-Cookie from the server, if any, is appended after.
            val mac = deviceMac
            if (mac != null) {
                val base = "mac=${URLEncoder.encode(mac, "UTF-8")}; stb_lang=en; timezone=${URLEncoder.encode(deviceTimezone, "UTF-8")}"
                builder.header("Cookie", if (authCookie != null) "$base; $authCookie" else base)
            } else if (authCookie != null) {
                builder.header("Cookie", authCookie!!)
            }
            // Stalker authorises API calls with a Bearer token, not a query param alone.
            authToken?.let { builder.header("Authorization", "Bearer $it") }
            if (jsToken != null) {
                builder.header("X-Js-Request-Token", jsToken)
            }

            val response = client.newCall(builder.build()).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val cookies = response.header("Set-Cookie")
            if (cookies != null) authCookie = cookies.split(";").firstOrNull()

            JSONObject(body)
        } catch (e: Exception) {
            null
        }
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        // ~14 items per page on this API. Live gets ~2800 channels before the cap, VOD/series
        // ~700 each - enough to browse immediately, without the multi-minute upfront crawl a
        // full 17k-item catalogue would need over a one-page-at-a-time API.
        private const val LIVE_MAX_PAGES = 200
        private const val VOD_MAX_PAGES = 50

        fun toChannel(entry: ChannelEntry, provider: Provider): Channel = Channel(
            id = entry.id,
            name = entry.name,
            url = entry.url,
            logoUrl = entry.logo,
            categoryId = entry.categoryId,
            categoryName = entry.categoryName,
            tvgId = entry.tvgId,
            tvgChno = entry.tvgChno,
            mediaType = MediaType.LIVE
        )
    }
}
