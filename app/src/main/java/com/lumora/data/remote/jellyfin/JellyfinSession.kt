package com.lumora.data.remote.jellyfin

import java.net.URI

/** Tracks the currently-authenticated Jellyfin connection so the app's *shared* OkHttp
 *  client (used for poster/logo/backdrop image fetches, not just JellyfinProvider's own
 *  API calls) knows to attach an auth token - without this, image requests to a server
 *  that doesn't allow anonymous image access just fail. */
object JellyfinSession {
    @Volatile var serverHost: String? = null
        private set
    @Volatile var accessToken: String? = null
        private set

    fun update(serverBase: String, token: String) {
        serverHost = runCatching { URI(serverBase).host }.getOrNull()
        accessToken = token
    }

    fun clear() {
        serverHost = null
        accessToken = null
    }
}
