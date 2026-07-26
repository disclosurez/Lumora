package com.lumora.util

/** Providers are frequently pasted/typed without a scheme ("ip.example.net" instead of
 *  "http://ip.example.net"), which OkHttp rejects outright. Default to http:// rather
 *  than fail - Xtream panels are overwhelmingly plain HTTP, and the user can always type
 *  https:// explicitly if their provider needs it. */
fun normalizeServerUrl(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    return if (trimmed.contains("://")) trimmed else "http://$trimmed"
}
