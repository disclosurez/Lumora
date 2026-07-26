package com.lumora.plugin

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log

/**
 * Manages discovery and lifecycle of companion APK plugins.
 * Scans installed packages for Lumora plugin implementations.
 */
class PluginManager(private val context: Context) {

    private val TAG = "PluginManager"
    private var plugins: List<InstalledPlugin> = emptyList()

    /**
     * Scan installed packages for Lumora plugins.
     * Plugins declare themselves with an intent filter for the custom action.
     */
    fun discoverPlugins(): List<InstalledPlugin> {
        val result = mutableListOf<InstalledPlugin>()

        try {
            val intent = android.content.Intent("com.lumora.action.PLUGIN")
            val resolveInfos: List<ResolveInfo> = context.packageManager
                .queryIntentServices(intent, PackageManager.GET_META_DATA)

            for (info in resolveInfos) {
                val serviceInfo = info.serviceInfo ?: continue
                val pkg = serviceInfo.packageName
                val label = serviceInfo.loadLabel(context.packageManager).toString()

                val versionCode = try {
                    context.packageManager.getPackageInfo(pkg, 0).versionCode
                } catch (e: Exception) { 0 }

                val versionName = try {
                    context.packageManager.getPackageInfo(pkg, 0).versionName ?: ""
                } catch (e: Exception) { "" }

                // Get provider type from meta-data
                val providerType = serviceInfo.metaData?.getString("lumora.provider_type")

                result.add(InstalledPlugin(
                    packageName = pkg,
                    label = label,
                    versionName = versionName,
                    versionCode = versionCode,
                    providerType = providerType
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Plugin discovery failed: ${e.message}")
        }

        plugins = result
        return result
    }

    fun getDiscoveredPlugins(): List<InstalledPlugin> = plugins
}
