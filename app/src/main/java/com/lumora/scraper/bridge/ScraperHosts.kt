package com.lumora.scraper.bridge

/**
 * Where each site lives, supplied by the `scraper_sites` plugin script rather than compiled in.
 *
 * A provider's parsing logic is specific to one site and has to ship in the app; its *address*
 * does not, and is the part that changes - these sites rotate domains constantly, and a
 * compiled-in host means an app release every time one moves.
 *
 * Every provider reads its base URL through here, so with no manifest loaded there is no host to
 * read and the provider cannot make a request at all. That is intentional and is the same gate
 * [ScraperSiteManifest] applies to the site list: the plugin is what makes the compiled-in
 * parsers usable, not merely what enables them.
 *
 * [get] returns an empty string rather than null for a site with no configured host. Providers
 * interpolate their base URL into request paths in dozens of places each, and a null would mean
 * auditing all of them; an empty string produces a malformed URL that fails the request, which is
 * the correct outcome and is already on every provider's error path.
 */
object ScraperHosts {

    @Volatile
    private var hosts: Map<String, String> = emptyMap()

    /** Replaces the whole map. Called only by [ScraperSiteManifest] as it applies a manifest. */
    internal fun set(value: Map<String, String>) {
        hosts = value
    }

    internal fun clear() {
        hosts = emptyMap()
    }

    /** True when [name] has a host configured, so a caller can skip the site rather than fail. */
    fun has(name: String?): Boolean = !hosts[name].isNullOrBlank()

    /**
     * The site's base URL, without a trailing slash, or "" when the manifest did not name one.
     * Providers that need the slash add it themselves, as they did with their own constants.
     *
     * [name] is nullable on purpose. Providers are Kotlin `object`s whose `name` is itself a
     * property, and `Provider.providers` touches all ~68 of them at once, so a host read that
     * happens during a provider's own `<clinit>` can arrive before that object's `name` has been
     * assigned - it is genuinely null there, whatever the declared type says. Taking a non-null
     * String meant Kotlin's parameter intrinsic threw an NPE inside the static initialiser, which
     * surfaces as ExceptionInInitializerError and takes down the whole app rather than
     * disabling one site. Returning "" instead degrades to a failed request for that provider.
     */
    operator fun get(name: String?): String = hosts[name]?.takeIf { it.isNotBlank() } ?: UNCONFIGURED

    /**
     * Stand-in host for a site the manifest has not named.
     *
     * Not the empty string, which was the obvious choice and is the wrong one: providers hand
     * their base URL straight to `Retrofit.Builder().baseUrl(...)`, and that rejects a value with
     * no scheme with `IllegalArgumentException`. Several providers build their service in a
     * property initialiser, so the throw happens inside `<clinit>` and comes out as
     * `ExceptionInInitializerError` - and because `Provider.providers` references all ~68
     * provider objects, one unconfigured site took the whole app down on launch.
     *
     * A syntactically valid URL keeps construction working and pushes the failure to where it
     * belongs: the request. `.invalid` is reserved by RFC 2606 and is guaranteed never to
     * resolve, so a provider that somehow gets used without a configured host fails its fetch
     * and is reported as a dead site, exactly like one that is genuinely down.
     */
    const val UNCONFIGURED = "https://unconfigured.invalid"
}
