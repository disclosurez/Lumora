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

/** True when [url] points at a playlist already on this device - a Storage Access Framework
 *  document URI, a file:// URI, or a raw absolute path - rather than at a remote server.
 *  Anything else (including a bare `host:port/path` paste without a scheme) is a network
 *  playlist. Local files are read through the content resolver / file APIs instead of
 *  OkHttp, and must not go through [normalizeServerUrl] (it would prefix a scheme onto a
 *  path that needs none). Single source of truth for the fetch path and the settings
 *  save validation. */
fun isLocalFileUrl(url: String): Boolean {
    val t = url.trim()
    return t.startsWith("content:", ignoreCase = true) ||
        t.startsWith("file:", ignoreCase = true) ||
        t.startsWith("/")
}

/** The file name of a local file URL, for display in provider rows: a content:// string is a
 *  long opaque document id that says nothing at a glance, so its final path segment is shown
 *  instead. Falls back to [url] when it has no readable segment. Decoded with
 *  java.net.URLDecoder (not android.net.Uri) so it stays usable from JVM unit tests. */
fun localFileDisplayName(url: String): String {
    val t = url.trim()
    // URLDecoder turns '+' into a space, but a '+' here is a literal filename character
    // (document ids percent-encode everything else) - shield it first.
    val decoded = try {
        java.net.URLDecoder.decode(t.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        t
    }
    return decoded.substringAfterLast('/').substringAfterLast(':').ifBlank { url }
}
