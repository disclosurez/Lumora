package com.lumora.scraper.bridge

import android.content.Context
import com.lumora.scraper.providers.Provider

/**
 * Which of the ported sites are allowed to be queried for streams.
 *
 * Default is on. A disabled-by-default list would make the feature look broken on first use -
 * the user asks for sources, nothing is searched, nothing comes back - and there is no way for
 * them to know which of seventy names is the one worth enabling. So the opt-out is stored, not
 * the opt-in: only the names the user has explicitly switched off are persisted, and a site
 * added by a later update starts enabled like the rest.
 *
 * Keyed by provider `name` rather than an index, because the registry order changes whenever a
 * site is added or dropped and an index would silently come to mean a different site.
 */
object ScraperSiteStore {

    private const val PREFS_NAME = "lumora_scraper_prefs"
    private const val KEY_DISABLED = "disabled_sites"

    /** Master switch. Off means the scrapers are never consulted at all. */
    private const val KEY_ENABLED = "scraper_enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun disabledNames(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DISABLED, emptySet()) ?: emptySet()

    fun isSiteEnabled(context: Context, provider: Provider): Boolean =
        provider.name !in disabledNames(context)

    fun setSiteEnabled(context: Context, provider: Provider, enabled: Boolean) {
        // getStringSet returns a set the preferences own; mutating it in place is documented as
        // undefined, so this always writes a fresh one.
        val updated = disabledNames(context).toMutableSet()
        if (enabled) updated.remove(provider.name) else updated.add(provider.name)
        prefs(context).edit().putStringSet(KEY_DISABLED, updated).apply()
    }

    /**
     * The sites a stream search should actually query.
     *
     * Empty unless all three agree: the master switch is on, a `scraper_sites` plugin script has
     * supplied a list at all, and that list names the site. The manifest is the gate - with no
     * script installed there are no scraper sources, however many parsers are compiled in.
     *
     * The user's own per-site toggle is applied last and is final: the manifest can only take a
     * site away, never hand one back to someone who switched it off.
     */
    fun activeProviders(context: Context): List<Provider> {
        if (!isEnabled(context) || !ScraperSiteManifest.isLoaded) return emptyList()
        val disabled = disabledNames(context)
        return ScraperCatalog.allProviders().filter {
            ScraperSiteManifest.allows(it.name) && it.name !in disabled
        }
    }
}
