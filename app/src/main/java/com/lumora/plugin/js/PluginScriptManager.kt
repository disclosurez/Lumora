package com.lumora.plugin.js

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Finds installed JS plugin scripts and remembers which are enabled. Replaces
 * [com.lumora.plugin.PluginManager]'s "scan installed APKs" with "scan `filesDir/plugin_scripts`".
 *
 * Nothing ships bundled with the app - see [PluginScript]'s kdoc. A script only exists here
 * because the user installed it (via [installScript], reached from Settings > Plugins' "add from
 * URL" or a plugin store browse dialog), and every script needs an explicit enable, same as
 * every other user-added script did before this class dropped the old "bundled = always on" tier.
 */
class PluginScriptManager(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val engine: JsPluginEngine = JsPluginEngine(),
) {
    private var scripts: List<PluginScript> = emptyList()

    suspend fun discoverScripts(): List<PluginScript> {
        val enabledIds = enabledScriptIds()
        val result = mutableListOf<PluginScript>()

        userScriptsDir().listFiles { f -> f.isFile && f.name.endsWith(".js") }?.forEach { file ->
            val text = runCatching { file.readText() }.getOrNull()
            if (text != null) {
                val fallbackId = file.name.removeSuffix(".js")
                toPluginScript(file.name, fallbackId, text, enabled = fallbackId in enabledIds)?.let { result.add(it) }
            }
        }

        scripts = result.sortedBy { it.label.lowercase() }
        return scripts
    }

    fun getDiscoveredScripts(): List<PluginScript> = scripts

    fun readSource(script: PluginScript): String = File(userScriptsDir(), script.fileName).readText()

    fun isEnabled(scriptId: String): Boolean = scriptId in enabledScriptIds()

    fun setEnabled(scriptId: String, enabled: Boolean) {
        val current = enabledScriptIds().toMutableSet()
        if (enabled) current.add(scriptId) else current.remove(scriptId)
        prefs.edit().putStringSet(PREF_ENABLED_SCRIPTS, current).apply()
        scripts = scripts.map { if (it.id == scriptId) it.copy(enabled = enabled) else it }
    }

    /** Writes [text] as a new user script and returns the file it landed in. */
    fun addUserScript(fileName: String, text: String): File {
        val file = File(userScriptsDir(), fileNameFor(fileName))
        file.writeText(text)
        return file
    }

    fun removeUserScript(fileName: String): Boolean = File(userScriptsDir(), fileName).delete()

    sealed class InstallResult {
        data class Installed(val script: PluginScript) : InstallResult()
        data class Rejected(val reason: String) : InstallResult()
    }

    /**
     * Validates, saves, and enables [text] as a new script - the single path both "add from URL"
     * and "install from a plugin store" go through. Installing a script whose id matches one
     * already installed overwrites it in place (update semantics) - there's no separate trusted
     * tier to protect against that anymore.
     */
    suspend fun installScript(text: String): InstallResult {
        val fallbackId = "script-${System.currentTimeMillis()}"
        val manifest = try {
            engine.probeManifest(text)
        } catch (e: Exception) {
            null
        } ?: return InstallResult.Rejected("Not a valid plugin script")

        val capabilities = extractCapabilities(manifest)
        if (capabilities.isEmpty()) return InstallResult.Rejected("Script declares no capability this app understands")

        val id = (manifest["id"] as? String)?.takeIf { it.isNotBlank() } ?: fallbackId
        val file = addUserScript(id, text)
        setEnabled(id, true)
        val script = PluginScript(
            fileName = file.name,
            id = id,
            label = (manifest["label"] as? String)?.takeIf { it.isNotBlank() } ?: id,
            description = manifest["description"] as? String,
            capabilities = capabilities,
            enabled = true,
            resolvesNatively = manifest["resolvesNatively"] as? Boolean ?: false,
            contentTypes = extractContentTypes(manifest),
        )
        discoverScripts()
        return InstallResult.Installed(script)
    }

    private suspend fun toPluginScript(
        fileName: String,
        fallbackId: String,
        text: String,
        enabled: Boolean,
    ): PluginScript? {
        val manifest = try {
            engine.probeManifest(text)
        } catch (e: Exception) {
            null
        } ?: return null

        val capabilities = extractCapabilities(manifest)
        if (capabilities.isEmpty()) return null

        val id = (manifest["id"] as? String)?.takeIf { it.isNotBlank() } ?: fallbackId
        return PluginScript(
            fileName = fileName,
            id = id,
            label = (manifest["label"] as? String)?.takeIf { it.isNotBlank() } ?: id,
            description = manifest["description"] as? String,
            capabilities = capabilities,
            enabled = enabled,
            resolvesNatively = manifest["resolvesNatively"] as? Boolean ?: false,
            contentTypes = extractContentTypes(manifest),
        )
    }

    private fun extractCapabilities(manifest: Map<String, Any?>): Set<String> =
        (manifest["capabilities"] as? List<*>)
            ?.mapNotNull { it as? String }
            ?.filter { it in KNOWN_CAPABILITIES }
            ?.toSet()
            .orEmpty()

    private fun extractContentTypes(manifest: Map<String, Any?>): Set<String> =
        (manifest["contentTypes"] as? List<*>)
            ?.mapNotNull { it as? String }
            ?.toSet()
            .orEmpty()

    private fun fileNameFor(fileName: String): String =
        fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".js")) it else "$it.js" }

    private fun userScriptsDir(): File = File(context.filesDir, "plugin_scripts").apply { mkdirs() }

    private fun enabledScriptIds(): Set<String> =
        prefs.getStringSet(PREF_ENABLED_SCRIPTS, emptySet()) ?: emptySet()

    companion object {
        private const val PREF_ENABLED_SCRIPTS = "plugin_enabled_scripts"
        private val KNOWN_CAPABILITIES = setOf(
            JsPluginContract.CAPABILITY_PROVIDER_DISCOVERY,
            JsPluginContract.CAPABILITY_STREAM_SEARCH,
        )
    }
}
