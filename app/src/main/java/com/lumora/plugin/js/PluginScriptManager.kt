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

    /**
     * Which plugins are on, kept in a file of its own rather than in [prefs].
     *
     * Android Auto Backup excludes whole files, never individual keys. This set formerly sat in
     * the same SharedPreferences as the provider configs, which do want backing up — so on
     * reinstall the enabled set was restored and plugins came back switched on. Its own file
     * (excluded from backup via res/xml/backup_rules.xml) prevents that.
     *
     * The old key is deleted on sight. It must NOT be migrated: [prefs] is backed up by
     * Auto Backup, and restoring a backup that still contains stale enabled IDs would re-enable
     * a plugin the user never explicitly switched on this install. The migration already ran in
     * an earlier build; any copy that arrives via backup is stale and unsafe to restore.
     */
    private val pluginPrefs: SharedPreferences by lazy {
        // Delete the old key from the backed-up shared file — do NOT migrate its contents.
        // Migrating it would copy stale enabled state from a restored backup into the new
        // (excluded) file, and installScript() reads isEnabled(id) to decide whether a
        // freshly installed script lands enabled (see installScript kdoc).
        if (prefs.contains(PREF_ENABLED_SCRIPTS)) {
            prefs.edit().remove(PREF_ENABLED_SCRIPTS).apply()
        }
        context.getSharedPreferences(PLUGIN_PREFS_FILE, Context.MODE_PRIVATE)
    }

    suspend fun discoverScripts(): List<PluginScript> {
        val enabledIds = enabledScriptIds()
        val result = mutableListOf<PluginScript>()

        userScriptsDir().listFiles { f -> f.isFile && f.name.endsWith(".js") }?.forEach { file ->
            val text = runCatching { file.readText() }.getOrNull()
            if (text != null) {
                val fallbackId = file.name.removeSuffix(".js")
                // Built first, then asked whether it is enabled - the answer is keyed on the
                // script's own manifest id, which is what setEnabled() writes. It used to be
                // keyed on the file name, and the two only agree while the id contains nothing
                // fileNameFor() rewrites: an id with a space or a colon in it produced a file
                // whose name never matched the stored key, so the plugin read back with the
                // wrong enabled state in both directions.
                toPluginScript(file.name, fallbackId, text, enabled = false)?.let {
                    result.add(it.copy(enabled = it.id in enabledIds))
                }
            }
        }

        scripts = result.sortedBy { it.label.lowercase() }
        // Ids in the enabled set with no script behind them are dropped. They accumulate from
        // removals and, on a device that has restored an Android Auto Backup, from a previous
        // install entirely - and a stale entry silently switches a plugin on the moment one
        // with that id is installed, which is not something the user asked for.
        pruneEnabledIds(scripts.map { it.id }.toSet())
        return scripts
    }

    /** Drops enabled-ids that no installed script claims. No-op when there's nothing to drop,
     *  so this doesn't write to prefs on every discovery. */
    private fun pruneEnabledIds(installedIds: Set<String>) {
        val stored = enabledScriptIds()
        val kept = stored.filterTo(mutableSetOf()) { it in installedIds }
        if (kept.size != stored.size) {
            pluginPrefs.edit().putStringSet(PREF_ENABLED_SCRIPTS, kept).apply()
        }
    }

    fun getDiscoveredScripts(): List<PluginScript> = scripts

    fun readSource(script: PluginScript): String {
        // The file may be missing on disk even though the script is listed: it was removed
        // out from under the app, or a backup restore dropped the script dir. Callers run on
        // the UI thread (MainActivityPlugins.showStreamSearchDialog) and in coroutines
        // (runDiscovery, showPlayerFor), so an unguarded readText() crash there. An empty
        // source is a harmless no-op: the engine evaluates it and reports a script error
        // ("discover is not defined"), never a crash.
        return runCatching { File(userScriptsDir(), script.fileName).readText() }.getOrElse {
            PluginLog.w(TAG, "readSource failed for ${script.fileName}: ${it.message}")
            ""
        }
    }

    fun isEnabled(scriptId: String): Boolean = scriptId in enabledScriptIds()

    fun setEnabled(scriptId: String, enabled: Boolean) {
        val current = enabledScriptIds().toMutableSet()
        if (enabled) current.add(scriptId) else current.remove(scriptId)
        pluginPrefs.edit().putStringSet(PREF_ENABLED_SCRIPTS, current).apply()
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
     * Validates and saves [text] as a script - the single path both "add from URL" and "install
     * from a plugin store" go through. Installing a script whose id matches one already
     * installed overwrites it in place (update semantics) - there's no separate trusted tier to
     * protect against that anymore.
     *
     * Installing does *not* enable. It used to, which meant browsing a store and tapping Install
     * on something to have it available also switched it on - a stream_search plugin that is on
     * starts answering Find Stream and, for the anime one, pulls a whole catalogue into the
     * Series tab. Enabling is a separate, visible act on the plugin's own page.
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
        // Whatever the user had chosen for this id is left exactly as it is - untouched on a
        // re-install (an update must not resurrect a plugin that was switched off), and absent
        // on a first install, which is what leaves a newly installed plugin off.
        val enabled = isEnabled(id)
        val script = PluginScript(
            fileName = file.name,
            id = id,
            label = (manifest["label"] as? String)?.takeIf { it.isNotBlank() } ?: id,
            description = manifest["description"] as? String,
            capabilities = capabilities,
            enabled = enabled,
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
        pluginPrefs.getStringSet(PREF_ENABLED_SCRIPTS, emptySet()) ?: emptySet()

    companion object {
        private const val TAG = "PluginScriptManager"
        private const val PREF_ENABLED_SCRIPTS = "plugin_enabled_scripts"
        /** Excluded from Auto Backup - see the pluginPrefs kdoc. */
        private const val PLUGIN_PREFS_FILE = "plugin_prefs"
        private val KNOWN_CAPABILITIES = setOf(
            JsPluginContract.CAPABILITY_PROVIDER_DISCOVERY,
            JsPluginContract.CAPABILITY_STREAM_SEARCH,
            JsPluginContract.CAPABILITY_SCRAPER_SITES,
        )
    }
}
