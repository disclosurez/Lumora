package com.lumora.data

import android.content.SharedPreferences
import com.lumora.model.MediaServerConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persists the configured Jellyfin/Plex accounts as a single JSON array pref, exactly as
 *  [IptvProviderStore] does for IPTV providers - simplest storage that supports an arbitrary
 *  number of entries without a database. Both media-server types share one list: they are the
 *  same kind of thing to everything above (an own-library source with a session), and keeping
 *  them together means the Settings list, the load loop and the gates iterate once. */
object MediaServerStore {
    private const val KEY = "media_servers_json"

    fun load(prefs: SharedPreferences): List<MediaServerConfig> {
        val raw = prefs.getString(KEY, null) ?: return migrateLegacy(prefs)
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(prefs: SharedPreferences, list: List<MediaServerConfig>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun get(prefs: SharedPreferences, id: String?): MediaServerConfig? =
        if (id.isNullOrBlank()) null else load(prefs).firstOrNull { it.id == id }

    fun upsert(prefs: SharedPreferences, config: MediaServerConfig): List<MediaServerConfig> {
        val current = load(prefs).toMutableList()
        val idx = current.indexOfFirst { it.id == config.id }
        if (idx >= 0) current[idx] = config else current.add(config)
        save(prefs, current)
        return current
    }

    fun remove(prefs: SharedPreferences, id: String): List<MediaServerConfig> {
        val current = load(prefs).filterNot { it.id == id }
        save(prefs, current)
        return current
    }

    fun setEnabled(prefs: SharedPreferences, id: String, enabled: Boolean): List<MediaServerConfig> {
        val current = load(prefs).map { if (it.id == id) it.copy(enabled = enabled) else it }
        save(prefs, current)
        return current
    }

    /** Flips one per-server content-type gate, mirroring [IptvProviderStore.setContentFlags].
     *  Pass null to leave a flag untouched. */
    fun setContentFlags(
        prefs: SharedPreferences,
        id: String,
        live: Boolean? = null,
        movies: Boolean? = null,
        series: Boolean? = null
    ): List<MediaServerConfig> {
        val current = load(prefs).map {
            if (it.id != id) it
            else it.copy(
                liveEnabled = live ?: it.liveEnabled,
                moviesEnabled = movies ?: it.moviesEnabled,
                seriesEnabled = series ?: it.seriesEnabled
            )
        }
        save(prefs, current)
        return current
    }

    fun newId(): String = UUID.randomUUID().toString()

    /**
     * One-time upgrade from the old fixed-slot pref scheme (`jellyfin_*` / `plex_*`) into a
     * list - runs once, then the list pref takes over so this never runs again (an empty JSON
     * array from then on is a real "no media servers configured" state, not "not migrated").
     *
     * `plex_client_id` is deliberately left behind: it identifies this *install* to plex.tv
     * and is shared by every Plex entry, so it stays a loose pref (see
     * MainActivity.plexClientIdentifier).
     */
    private fun migrateLegacy(prefs: SharedPreferences): List<MediaServerConfig> {
        val migrated = mutableListOf<MediaServerConfig>()
        val jellyfinUrl = prefs.getString("jellyfin_url", null)
        if (!jellyfinUrl.isNullOrBlank()) {
            migrated += MediaServerConfig(
                id = newId(),
                type = "jellyfin",
                name = "Jellyfin",
                enabled = prefs.getBoolean("jellyfin_provider_enabled", true),
                url = jellyfinUrl,
                username = prefs.getString("jellyfin_user", null)?.takeIf { it.isNotBlank() },
                password = prefs.getString("jellyfin_pass", null)?.takeIf { it.isNotBlank() },
                token = prefs.getString("jellyfin_token", null)?.takeIf { it.isNotBlank() },
                userId = prefs.getString("jellyfin_userid", null)?.takeIf { it.isNotBlank() },
                liveEnabled = prefs.getBoolean("jellyfin_live_enabled", true),
                // The pre-split scheme had one movies+series switch (jellyfin_disable_vod);
                // the individual keys win where they were written.
                moviesEnabled = legacyFlag(prefs, "jellyfin_movies_enabled", "jellyfin_disable_vod"),
                seriesEnabled = legacyFlag(prefs, "jellyfin_series_enabled", "jellyfin_disable_vod")
            )
        }
        val plexUrl = prefs.getString("plex_url", null)
        val plexToken = prefs.getString("plex_token", null)
        if (!plexUrl.isNullOrBlank() && !plexToken.isNullOrBlank()) {
            migrated += MediaServerConfig(
                id = newId(),
                type = "plex",
                name = prefs.getString("plex_server_name", null)?.takeIf { it.isNotBlank() } ?: "Plex",
                enabled = prefs.getBoolean("plex_provider_enabled", true),
                url = plexUrl,
                token = plexToken,
                accountToken = prefs.getString("plex_account_token", null)?.takeIf { it.isNotBlank() },
                moviesEnabled = prefs.getBoolean("plex_movies_enabled", true),
                seriesEnabled = prefs.getBoolean("plex_series_enabled", true)
            )
        }
        // Saved even when empty: the list pref existing is what stops this running again, and
        // load() is called often enough that re-migrating (and re-clearing the legacy keys) on
        // every call would be a pref write per call.
        save(prefs, migrated)
        prefs.edit()
            .remove("jellyfin_url").remove("jellyfin_user").remove("jellyfin_pass")
            .remove("jellyfin_token").remove("jellyfin_userid")
            .remove("jellyfin_provider_enabled").remove("jellyfin_disable_vod")
            .remove("jellyfin_live_enabled").remove("jellyfin_movies_enabled").remove("jellyfin_series_enabled")
            .remove("plex_url").remove("plex_token").remove("plex_account_token")
            .remove("plex_server_name").remove("plex_provider_enabled")
            .remove("plex_movies_enabled").remove("plex_series_enabled")
            .apply()
        return migrated
    }

    private fun legacyFlag(prefs: SharedPreferences, key: String, legacyOffKey: String): Boolean =
        if (prefs.contains(key)) prefs.getBoolean(key, true) else !prefs.getBoolean(legacyOffKey, false)

    private fun toJson(c: MediaServerConfig): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("type", c.type)
        put("name", c.name)
        put("enabled", c.enabled)
        put("liveEnabled", c.liveEnabled)
        put("moviesEnabled", c.moviesEnabled)
        put("seriesEnabled", c.seriesEnabled)
        c.url?.let { put("url", it) }
        if (c.altUrls.isNotEmpty()) put("altUrls", JSONArray().apply { c.altUrls.forEach { put(it) } })
        c.username?.let { put("username", it) }
        c.password?.let { put("password", it) }
        c.token?.let { put("token", it) }
        c.userId?.let { put("userId", it) }
        c.accountToken?.let { put("accountToken", it) }
    }

    private fun fromJson(o: JSONObject): MediaServerConfig = MediaServerConfig(
        id = o.optString("id").ifBlank { newId() },
        type = o.optString("type", "jellyfin"),
        name = o.optString("name", "Media server"),
        enabled = o.optBoolean("enabled", true),
        url = o.optString("url").takeIf { it.isNotBlank() },
        altUrls = o.optJSONArray("altUrls")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { u -> u.isNotBlank() } }
        }.orEmpty(),
        username = o.optString("username").takeIf { it.isNotBlank() },
        password = o.optString("password").takeIf { it.isNotBlank() },
        token = o.optString("token").takeIf { it.isNotBlank() },
        userId = o.optString("userId").takeIf { it.isNotBlank() },
        accountToken = o.optString("accountToken").takeIf { it.isNotBlank() },
        liveEnabled = o.optBoolean("liveEnabled", true),
        moviesEnabled = o.optBoolean("moviesEnabled", true),
        seriesEnabled = o.optBoolean("seriesEnabled", true)
    )
}
