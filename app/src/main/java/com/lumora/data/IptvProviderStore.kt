package com.lumora.data

import android.content.SharedPreferences
import android.util.Log
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
    private const val TAG = "IptvProviderStore"

    /** Set when the stored JSON fails to parse. save() then refuses to write, so a later
     *  upsert/remove/setEnabled can't overwrite the corrupt blob with `[]` and destroy the
     *  user's configs for good. Cleared only by a process restart. */
    @Volatile private var corrupt = false

    fun load(prefs: SharedPreferences): List<IptvProviderConfig> {
        val raw = prefs.getString(KEY, null)
        if (raw == null) return migrateLegacy(prefs)
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> fromJson(arr.getJSONObject(i)) }
        } catch (e: Exception) {
            if (!corrupt) {
                corrupt = true
                Log.e(TAG, "Corrupt provider JSON; refusing to overwrite it. ${e.message}")
            }
            emptyList()
        }
    }

    fun save(prefs: SharedPreferences, list: List<IptvProviderConfig>) {
        if (corrupt) {
            Log.e(TAG, "Refusing to overwrite corrupt provider JSON")
            return
        }
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

    /** Flips one per-provider content-type gate (live/movies/series) for one provider,
     *  mirroring setEnabled - loads, updates the matching entry, saves, returns the new
     *  list. Pass null to leave a flag untouched. */
    fun setContentFlags(
        prefs: SharedPreferences,
        id: String,
        live: Boolean? = null,
        movies: Boolean? = null,
        series: Boolean? = null
    ): List<IptvProviderConfig> {
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
        put("liveEnabled", c.liveEnabled)
        put("moviesEnabled", c.moviesEnabled)
        put("seriesEnabled", c.seriesEnabled)
        c.url?.let { put("url", it) }
        c.username?.let { put("username", it) }
        c.password?.let { put("password", it) }
        c.userAgent?.let { put("userAgent", it) }
    }

    private fun fromJson(o: JSONObject): IptvProviderConfig? {
        // An entry with no id can't be looked up again (favourites/positions key off it), and
        // minting a fresh id here would hand out a different one on every load without ever
        // persisting it - orphaning anything keyed by a previous minted id. Drop the entry.
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        // Legacy migration: configs saved before the per-type split carry disableVod
        // (movies+series off together). Newer entries have the individual flags.
        val legacyVodOff = o.optBoolean("disableVod", false)
        fun flag(key: String) = if (o.has(key)) o.optBoolean(key, true) else !legacyVodOff
        return IptvProviderConfig(
            id = id,
            type = o.optString("type", "m3u"),
            name = o.optString("name", "Provider"),
            enabled = o.optBoolean("enabled", true),
            liveEnabled = o.optBoolean("liveEnabled", true),
            moviesEnabled = flag("moviesEnabled"),
            seriesEnabled = flag("seriesEnabled"),
            url = o.optString("url").takeIf { it.isNotBlank() },
            username = o.optString("username").takeIf { it.isNotBlank() },
            password = o.optString("password").takeIf { it.isNotBlank() },
            userAgent = o.optString("userAgent").takeIf { it.isNotBlank() }
        )
    }
}
