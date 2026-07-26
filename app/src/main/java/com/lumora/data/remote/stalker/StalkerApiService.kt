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
        val isAdult: Boolean = false
    )

    private var authToken: String? = null
    private var jsToken: String? = null
    private var authCookie: String? = null
    private var serverBase: String? = null

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
    suspend fun authenticate(serverUrl: String, mac: String): Result<AuthResult> {
        return try {
            val base = serverUrl.trimEnd('/')
            val profile = generateProfile(mac)

            // Step 1: Server handshake
            val handshakeUrl = "$base/stalker_portal/server/load.php?type=stb&action=handshake&JsHttpRequest=1-xml"
            val handshakeJson = fetchJson(handshakeUrl, null)
            if (handshakeJson == null) return Result.failure(Exception("Handshake failed"))

            val jsTokenFromServer = handshakeJson.optJSONObject("js")?.optString("token")
                ?: handshakeJson.optString("token")
            jsToken = jsTokenFromServer

            // Step 2: Get profile and authenticate
            val profileUrl = "$base/stalker_portal/server/load.php?type=stb&action=get_profile" +
                    "&JsHttpRequest=1-xml" +
                    "&sn=${profile.serialNumber}" +
                    "&device_id=${profile.deviceId}" +
                    "&device_id2=${profile.deviceId2}" +
                    "&signature=${profile.signature}" +
                    "&mac=${profile.mac}"

            val profileJson = fetchJson(profileUrl, jsTokenFromServer)
            if (profileJson == null) return Result.failure(Exception("Profile fetch failed"))

            val token = profileJson.optJSONObject("js")?.optString("token")
                ?: profileJson.optString("token")

            authToken = token
            serverBase = base

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
        return fetchChannelList(serverUrl, mac, token, "get_all_channels")
    }

    /**
     * Fetch VOD list from the Stalker portal.
     */
    suspend fun getVodList(serverUrl: String, mac: String): List<ChannelEntry> {
        val token = authToken ?: return emptyList()
        return fetchChannelList(serverUrl, mac, token, "get_vod_list")
    }

    /**
     * Fetch series list from the Stalker portal.
     */
    suspend fun getSeriesList(serverUrl: String, mac: String): List<ChannelEntry> {
        val token = authToken ?: return emptyList()
        return fetchChannelList(serverUrl, mac, token, "get_series_list")
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
        serverUrl: String, mac: String, token: String, action: String
    ): List<ChannelEntry> {
        return try {
            val base = serverUrl.trimEnd('/')
            val url = buildApiUrl(action, mac, token)
            val json = fetchJson(url, jsToken)
            val js = json?.optJSONObject("js")
            val items = js?.optJSONArray("data") ?: js?.optJSONArray("") ?: return emptyList()

            (0 until items.length()).mapNotNull { i ->
                val obj = items.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id")
                if (id.isBlank()) return@mapNotNull null

                val name = obj.optString("name")
                val streamUrl = buildStreamUrl(base, obj.optString("cmd", id))

                ChannelEntry(
                    id = id,
                    name = name,
                    url = streamUrl,
                    logo = obj.optString("logo", "").ifBlank { null },
                    categoryId = obj.optString("category_id", "").ifBlank { null },
                    categoryName = obj.optString("category_name", "").ifBlank { null },
                    tvgId = obj.optString("tv_genre_id", null),
                    tvgChno = obj.optString("number", null),
                    isAdult = obj.optString("is_adult", "0") == "1"
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun buildStreamUrl(base: String, cmd: String): String {
        if (cmd.startsWith("http")) return cmd
        val clean = cmd.trimStart('/')
        return "$base/stalker_portal/$clean"
    }

    private fun buildApiUrl(action: String, mac: String, token: String): String {
        val base = serverBase?.trimEnd('/') ?: return ""
        val macParam = URLEncoder.encode(mac, "UTF-8")
        val tokenParam = URLEncoder.encode(token, "UTF-8")
        return "$base/stalker_portal/server/load.php?type=stb&action=$action" +
                "&mac=$macParam&token=$tokenParam" +
                "&JsHttpRequest=1-xml"
    }

    private suspend fun fetchJson(url: String, jsToken: String?): JSONObject? {
        return try {
            val builder = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; Android TV)")

            if (authCookie != null) {
                builder.header("Cookie", authCookie!!)
            }
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
