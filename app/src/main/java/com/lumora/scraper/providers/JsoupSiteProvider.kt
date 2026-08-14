package com.lumora.scraper.providers

import com.lumora.scraper.bridge.HostScopedService
import com.lumora.scraper.bridge.ScraperHosts
import com.lumora.scraper.extractors.Extractor
import com.lumora.scraper.models.Video
import com.lumora.scraper.utils.DnsResolver
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Base class for the Retrofit+Jsoup anime scrapers.
 *
 * The ported anime providers share their plumbing and differ only in what they scrape and how:
 * every one resolves its host through [ScraperHosts], talks to the site over an OkHttp client
 * configured with DoH ([DnsResolver]) and 30s timeouts, wraps its Retrofit service in a
 * [HostScopedService] so a changed manifest host rebuilds it, and resolves a server URL through
 * [Extractor]. That boilerplate lives here; subclasses only declare the site-specific parts
 * (name/logo/language, their own service interface and parsing) and build their service on top
 * of [newHttpClient], [newRetrofit] and [hostScopedService].
 */
abstract class JsoupSiteProvider : Provider {

    abstract override val name: String
    abstract override val logo: String
    abstract override val language: String

    /** The site's base URL from the scraper manifest; see [ScraperHosts]. */
    override val baseUrl: String get() = ScraperHosts[name]

    /**
     * Shared OkHttp client: DoH resolution plus 30s connect/read timeouts, matching what the
     * trio configured individually before this base existed. Subclasses that need more (an
     * interceptor, a response cache) wrap the result with `newHttpClient().newBuilder()`.
     */
    protected open fun newHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .dns(DnsResolver.doh)
            .build()

    /** Shared Retrofit setup: [JsoupConverterFactory] over [client] against [baseUrl]. */
    protected fun newRetrofit(baseUrl: String, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(JsoupConverterFactory.create())
            .client(client)
            .build()

    /**
     * Shared host-scoped service holder: rebuilds the Retrofit service whenever the manifest
     * repoints [baseUrl]. See [HostScopedService].
     */
    protected fun <T> hostScopedService(build: () -> T): HostScopedService<T> =
        HostScopedService({ baseUrl }, build)

    /** Shared default: resolve a server through the generic [Extractor]. */
    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.id, server)
}
