package com.lumora.util

/** Providers are frequently pasted/typed without a scheme ("ip.example.net" instead of
 *  "http://ip.example.net"), which OkHttp rejects outright. Defaults to [defaultScheme]
 *  rather than fail when none's given, and the user can always type a scheme explicitly
 *  to override it. Xtream/Stalker panels are overwhelmingly plain HTTP on a bare LAN IP,
 *  so "http" stays the default for those - but Jellyfin servers are typically reached by
 *  a real domain name behind a reverse proxy (TLS-only, nothing listening on 80 at all),
 *  so callers there should pass "https" instead; a bare hostname silently defaulting to
 *  http just hangs/fails to connect rather than erroring in any obvious way. */
fun normalizeServerUrl(url: String, defaultScheme: String = "http"): String {
    val trimmed = url.trim().trimEnd('/')
    return if (trimmed.contains("://")) trimmed else "$defaultScheme://$trimmed"
}
