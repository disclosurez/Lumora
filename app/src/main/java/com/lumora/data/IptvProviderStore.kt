package com.lumora.data

import android.content.SharedPreferences
import com.lumora.model.IptvProviderConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persists the list of configured IPTV providers as a single JSON array pref - simplest
 *  storage that supports an arbitrary number of entries without a database, and this app
 *  already depends on org.json (JellyfinProvider) so no new dependency either. */
object IptvProviderStore {
    private const val KEY = "iptv_providers_json"
    private const val LEGACY_ENABLED_KEY = "iptv_provider_enabled"

    fun load(prefs: SharedPreferences): List<IptvProviderConfig> {
        val raw = prefs.getString(KEY, null)
        if (raw == null) return migrateLegacy(prefs)
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(prefs: SharedPreferences, list: List<IptvProviderConfig>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun upsert(prefs: SharedPreferences, config: IptvProviderConfig): List<IptvProviderConfig> {
        val current = load(prefs).toMutableList()
        val idx = current.indexOfFirst { it.id == config.id }
        if (idx >= 0) current[idx] = config else current.add(config)
        save(prefs, current)
        return current
    }

    fun remove(prefs: SharedPreferences, id: String): List<IptvProviderConfig> {
        val current = load(prefs).filterNot { it.id == id }
        save(prefs, current)
        return current
    }

    fun setEnabled(prefs: SharedPreferences, id: String, enabled: Boolean): List<IptvProviderConfig> {
        val current = load(prefs).map { if (it.id == id) it.copy(enabled = enabled) else it }
        save(prefs, current)
        return current
    }

    /** Flips the per-provider VOD gate (movies/series off) for one provider, mirroring
     *  setEnabled - loads, updates the matching entry, saves, returns the new list. */
    fun setVodDisabled(prefs: SharedPreferences, id: String, disabled: Boolean): List<IptvProviderConfig> {
        val current = load(prefs).map { if (it.id == id) it.copy(disableVod = disabled) else it }
        save(prefs, current)
        return current
    }

    fun newId(): String = UUID.randomUUID().toString()

    /** One-time upgrade from the old single-provider pref scheme (provider_type, plus
     *  xtream_/stalker_/m3u_ prefixed keys) into a one-entry list - runs once, then the
     *  list pref takes over so this never runs again (an empty JSON array `[]` from then
     *  on is a real "no providers configured" state, not "not migrated yet"). */
    private fun migrateLegacy(prefs: SharedPreferences): List<IptvProviderConfig> {
        val type = prefs.getString("provider_type", null) ?: return emptyList() // no legacy data to migrate
        val enabled = prefs.getBoolean(LEGACY_ENABLED_KEY, true)
        val config = when (type) {
            "xtream" -> {
                val url = prefs.getString("xtream_url", null)
                if (url.isNullOrBlank()) null else IptvProviderConfig(
                    id = newId(), type = "xtream", name = prefs.getString("provider_name", "Xtream") ?: "Xtream",
                    enabled = enabled, url = url, username = prefs.getString("xtream_user", null), password = prefs.getString("xtream_pass", null)
                )
            }
            "stalker" -> {
                val url = prefs.getString("stalker_url", null)
                if (url.isNullOrBlank()) null else IptvProviderConfig(
                    id = newId(), type = "stalker", name = prefs.getString("provider_name", "Stalker") ?: "Stalker",
                    enabled = enabled, url = url, userAgent = prefs.getString("stalker_mac", null)
                )
            }
            "m3u" -> {
                val url = prefs.getString("m3u_url", null)
                if (url.isNullOrBlank()) null else IptvProviderConfig(
                    id = newId(), type = "m3u", name = prefs.getString("provider_name", "M3U") ?: "M3U",
                    enabled = enabled, url = url, userAgent = prefs.getString("user_agent", null)
                )
            }
            else -> null
        }
        val migrated = listOfNotNull(config)
        if (migrated.isNotEmpty()) {
            save(prefs, migrated)
        }
        // Clear legacy keys so this doesn't re-run
        prefs.edit().remove("provider_type").remove(LEGACY_ENABLED_KEY)
            .remove("xtream_url").remove("xtream_user").remove("xtream_pass")
            .remove("stalker_url").remove("stalker_mac")
            .remove("m3u_url").remove("provider_name").remove("user_agent")
            .apply()
        return migrated
    }

    private fun toJson(c: IptvProviderConfig): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("type", c.type)
        put("name", c.name)
        put("enabled", c.enabled)
        put("disableVod", c.disableVod)
        c.url?.let { put("url", it) }
        c.username?.let { put("username", it) }
        c.password?.let { put("password", it) }
        c.userAgent?.let { put("userAgent", it) }
    }

    private fun fromJson(o: JSONObject): IptvProviderConfig = IptvProviderConfig(
        id = o.optString("id").ifBlank { newId() },
        type = o.optString("type", "m3u"),
        name = o.optString("name", "Provider"),
        enabled = o.optBoolean("enabled", true),
        disableVod = o.optBoolean("disableVod", false),
        url = o.optString("url").takeIf { it.isNotBlank() },
        username = o.optString("username").takeIf { it.isNotBlank() },
        password = o.optString("password").takeIf { it.isNotBlank() },
        userAgent = o.optString("userAgent").takeIf { it.isNotBlank() }
    )
}
