package com.lumora.data.remote.plex

import java.net.URI

/** Tracks the currently-authenticated Plex connection so the app's *shared* OkHttp client
 *  (used for poster/backdrop image fetches, not just PlexProvider's own API calls) knows to
 *  attach an auth token. Plex serves artwork off the same authenticated `/library/...` paths
 *  as everything else, so without this every poster from the server 401s.
 *
 *  Mirrors JellyfinSession - the two media-server slots are independent and can both be
 *  connected at once, so each keeps its own host/token pair. */
object PlexSession {
    @Volatile var serverHost: String? = null
        private set
    @Volatile var accessToken: String? = null
        private set

    fun update(serverBase: String, token: String) {
        serverHost = runCatching { URI(serverBase).host }.getOrNull()
        accessToken = token
    }
}
