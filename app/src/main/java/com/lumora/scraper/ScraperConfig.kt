package com.lumora.scraper

import com.lumora.data.remote.tmdb.TmdbClient

/**
 * Stands in for the upstream app's generated `BuildConfig` fields. Lumora's own BuildConfig has
 * none of these, and they are not build-time secrets worth a `buildConfigField` plumbing job:
 * two are optional and one already exists elsewhere in the app.
 */
object ScraperConfig {

    /** Mirrors Lumora's own debug flag so the scraper's OkHttp logging follows the app build. */
    val DEBUG: Boolean = com.lumora.BuildConfig.DEBUG

    /**
     * The scrapers' metadata lookups reuse Lumora's existing TMDB keys rather than carrying a
     * second key of their own - [TmdbClient] already rotates through its list when one is dead or
     * rate-limited, so taking the first is the right default and an empty string degrades the
     * same way upstream did (metadata-only calls no-op, scraping still works).
     */
    val TMDB_API_KEY: String get() = TmdbClient.firstKeyOrEmpty()

    /**
     * Upstream pointed this at a self-hosted helper that decrypts Rabbitstream/Megacloud's
     * source payload. There is no such host here, so the affected extractor falls back to its
     * in-app decryption path; leaving it blank is the documented "no helper configured" state.
     */
    const val RABBITSTREAM_SOURCE_API: String = ""
}
