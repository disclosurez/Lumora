package com.lumora.data.domain

import android.content.Context
import com.lumora.data.local.LumoraDatabase
import com.lumora.data.local.entity.ChannelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Combined M3U profile: merges channels from multiple M3U providers
 * into a single unified Live TV source for browsing.
 */
data class CombinedM3uProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val providerIds: List<String>,
    val activeProviderId: String? = null
) {
    companion object {
        private const val PREFS_KEY = "combined_m3u_profiles"

        fun loadAll(context: Context): List<CombinedM3uProfile> {
            val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            val json = prefs.getString(PREFS_KEY, "[]") ?: "[]"
            return try {
                val arr = org.json.JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    CombinedM3uProfile(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        providerIds = obj.getJSONArray("providerIds").let { arr2 ->
                            (0 until arr2.length()).map { arr2.getString(it) }
                        },
                        activeProviderId = obj.optString("activeProviderId", null)
                    )
                }
            } catch (e: Exception) { emptyList() }
        }

        fun saveAll(context: Context, profiles: List<CombinedM3uProfile>) {
            val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            val arr = org.json.JSONArray()
            for (p in profiles) {
                arr.put(org.json.JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("providerIds", org.json.JSONArray(p.providerIds))
                    put("activeProviderId", p.activeProviderId ?: "")
                })
            }
            prefs.edit().putString(PREFS_KEY, arr.toString()).apply()
        }
    }
}

/**
 * Repository for Combined M3U profiles.
 */
class CombinedM3uRepository(private val context: Context) {

    private val db = LumoraDatabase.getInstance(context)

    fun getAll(): List<CombinedM3uProfile> = CombinedM3uProfile.loadAll(context)

    fun saveProfile(profile: CombinedM3uProfile) {
        val profiles = CombinedM3uProfile.loadAll(context)
            .filter { it.id != profile.id } + profile
        CombinedM3uProfile.saveAll(context, profiles)
    }

    fun deleteProfile(id: String) {
        val profiles = CombinedM3uProfile.loadAll(context).filter { it.id != id }
        CombinedM3uProfile.saveAll(context, profiles)
    }

    suspend fun getMergedChannels(profile: CombinedM3uProfile): List<ChannelEntity> {
        return withContext(Dispatchers.IO) {
            profile.providerIds.flatMap { providerId ->
                db.channelDao().getByProvider(providerId)
                    .filter { it.mediaType == "LIVE" }
            }.distinctBy { it.tvgId ?: it.name }
        }
    }
}
