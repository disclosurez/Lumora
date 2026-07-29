package com.lumora.data.remote.jellyfin

import okhttp3.Interceptor
import okhttp3.Response

/** Attaches the Jellyfin session token to any request hitting the currently-connected
 *  Jellyfin server's host - added to the app's shared OkHttpClient so generic image
 *  loading (PosterLoader etc, which have no idea a given URL is Jellyfin-specific)
 *  gets authenticated automatically instead of silently 401ing. */
class JellyfinAuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = JellyfinSession.serverHost
        val token = JellyfinSession.accessToken
        if (host == null || token == null || (!request.url.host.endsWith(host) && request.url.host != host) || !request.header("X-Emby-Token").isNullOrBlank()) {
            return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder().header("X-Emby-Token", token).build())
    }
}
