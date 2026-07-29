package com.lumora.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log

/**
 * Finds companion plugin APKs installed on the device and remembers which of them the user
 * has switched on.
 *
 * Discovery is a package-manager query, not code loading - nothing from a plugin runs in
 * Lumora's process at any point, and nothing runs at all until the user enables it
 * ([setEnabled]). A plugin that's merely installed is inert.
 */
class PluginManager(private val context: Context, private val prefs: SharedPreferences) {

    private var plugins: List<InstalledPlugin> = emptyList()

    /**
     * Scans installed packages for exported services answering
     * [PluginContract.ACTION_PLUGIN_SERVICE].
     *
     * Anything without a capability this host understands is dropped: an unrecognised
     * capability string is a plugin built against a newer protocol, and listing it would offer
     * the user a plugin with no action behind it.
     */
    fun discoverPlugins(): List<InstalledPlugin> {
        val enabled = enabledPackages()
        val result = mutableListOf<InstalledPlugin>()
        try {
            val intent = Intent(PluginContract.ACTION_PLUGIN_SERVICE)
            val services = context.packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)
            for (info in services) {
                val serviceInfo = info.serviceInfo ?: continue
                // An unexported service can't be bound from here; skip it rather than
                // surfacing a plugin that would fail the moment it's run.
                if (!serviceInfo.exported) continue
                val pkg = serviceInfo.packageName
                val meta = serviceInfo.metaData
                val known = setOf(
                    PluginContract.CAPABILITY_PROVIDER_DISCOVERY,
                    PluginContract.CAPABILITY_STREAM_SEARCH
                )
                val capabilities = meta?.getString(PluginContract.META_CAPABILITIES)
                    .orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it in known }
                    .toSet()
                if (capabilities.isEmpty()) continue

                val packageInfo = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.getOrNull()
                @Suppress("DEPRECATION")
                val version = packageInfo?.versionCode ?: 0
                result.add(
                    InstalledPlugin(
                        packageName = pkg,
                        component = ComponentName(pkg, serviceInfo.name),
                        pluginId = meta?.getString(PluginContract.META_PLUGIN_ID)?.takeIf { it.isNotBlank() } ?: pkg,
                        label = serviceInfo.loadLabel(context.packageManager).toString(),
                        description = meta?.getString(PluginContract.META_DESCRIPTION)?.takeIf { it.isNotBlank() },
                        versionName = packageInfo?.versionName.orEmpty(),
                        versionCode = version,
                        capabilities = capabilities,
                        enabled = pkg in enabled
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Plugin discovery failed: ${e.message}")
        }
        plugins = result.sortedBy { it.label.lowercase() }
        return plugins
    }

    fun getDiscoveredPlugins(): List<InstalledPlugin> = plugins

    fun isEnabled(packageName: String): Boolean = packageName in enabledPackages()

    /** Enabling is the user's explicit consent for this package to be bound and run. */
    fun setEnabled(packageName: String, enabled: Boolean) {
        val current = enabledPackages().toMutableSet()
        if (enabled) current.add(packageName) else current.remove(packageName)
        prefs.edit().putStringSet(PREF_ENABLED_PACKAGES, current).apply()
        plugins = plugins.map { if (it.packageName == packageName) it.copy(enabled = enabled) else it }
    }

    private fun enabledPackages(): Set<String> =
        prefs.getStringSet(PREF_ENABLED_PACKAGES, emptySet()) ?: emptySet()

    companion object {
        private const val TAG = "PluginManager"
        private const val PREF_ENABLED_PACKAGES = "plugin_enabled_packages"
    }
}
