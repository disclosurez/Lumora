package com.lumora.scraper.bridge

/**
 * Per-site odds and ends supplied by the `scraper_sites` manifest that aren't the site's own
 * [ScraperHosts] entry: its logo URL, and any extra named URL a provider needs beyond its one
 * `baseUrl` (an alternate API host, a CDN subdomain, a Referer/Origin it must send). Compiled-in
 * parsers keep the *logic*; every literal domain they touch comes from here instead, the same
 * split [ScraperHosts] makes for the primary host.
 *
 * Keyed by `"$providerName.$key"` rather than a nested map so [set] can stay a single flat
 * replace, matching how [ScraperSiteManifest] already rebuilds [ScraperHosts] on every apply.
 */
object ScraperExtras {

    @Volatile
    private var values: Map<String, String> = emptyMap()

    internal fun set(value: Map<String, String>) {
        values = value
    }

    internal fun clear() {
        values = emptyMap()
    }

    /** The named extra for [name], or "" if the manifest didn't supply one. */
    fun get(name: String?, key: String): String = values["$name.$key"].orEmpty()

    /** [get], or null instead of "" so a caller can fall back to a compiled-in default. */
    fun getOrNull(name: String?, key: String): String? = values["$name.$key"]
}
