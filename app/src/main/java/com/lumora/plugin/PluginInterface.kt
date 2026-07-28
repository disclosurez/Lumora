package com.lumora.plugin

import android.content.Context
import com.lumora.model.Channel

/**
 * Plugin interface for Lumora extensibility.
 * Companion APKs can implement this interface to add provider types,
 * playback enhancements, or UI panels.
 */
interface LumoraPlugin {

    /** Unique plugin identifier. */
    val pluginId: String

    /** Human-readable plugin name. */
    val pluginName: String

    /** Plugin version code. */
    val pluginVersion: Int

    /** Plugin version name. */
    val pluginVersionName: String

    /** Initialize the plugin. Called once at application start. */
    fun initialize(context: Context)

    /** Provider type constant (e.g., "custom_xyz"). Return null if this is not a provider plugin. */
    fun getProviderType(): String? = null

    /**
     * Fetch channels from this plugin's provider.
     * Only called if getProviderType() returns a non-null value.
     */
    suspend fun fetchChannels(
        context: Context,
        serverUrl: String?,
        username: String?,
        password: String?,
        additionalConfig: Map<String, String>
    ): Result<List<Channel>>
}

/**
 * Represents a discovered plugin.
 */
data class InstalledPlugin(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Int,
    val providerType: String? = null
)
