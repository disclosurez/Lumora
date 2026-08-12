package com.lumora.scraper.bridge

/**
 * Holds a provider's Retrofit service and rebuilds it whenever the site's host changes.
 *
 * Nearly every ported provider is a Kotlin `object` that built its service once, at object scope:
 *
 * ```kotlin
 * private val service = Service.build()   // Retrofit baseUrl captured here, forever
 * ```
 *
 * That was correct while the host was a compile-time constant. Now it comes from the
 * `scraper_sites` plugin ([ScraperHosts]), and object initialisation runs on first touch - which
 * can be before a manifest has been applied, and is certainly before the user can change one. A
 * plain `by lazy` has the same defect one step later: it would pin whatever host happened to be
 * loaded at first use and keep it for the life of the process, so installing an updated site list
 * would appear to do nothing until the app was restarted.
 *
 * So the built service is cached against the host it was built for, and a changed host discards
 * it. Rebuilding a Retrofit instance is cheap next to the network round trip that follows, and it
 * only happens when the manifest actually changes.
 */
class HostScopedService<T>(
    private val host: () -> String,
    private val builder: () -> T,
) {

    private var builtFor: String? = null
    private var value: T? = null

    /**
     * The service for the current host.
     *
     * Synchronised because providers fan out across coroutines - [ScraperCatalog.findSources]
     * runs several sites at once, and one provider's own methods can overlap - so two threads can
     * reach a cold holder together. Building twice would be harmless; publishing a half-built
     * reference would not.
     */
    @Synchronized
    fun get(): T {
        val current = host()
        val cached = value
        if (cached != null && builtFor == current) return cached
        return builder().also {
            value = it
            builtFor = current
        }
    }
}
