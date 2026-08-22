package com.lumora.data.remote.plex

import okhttp3.Interceptor
import okhttp3.Response

/** Attaches the Plex session token to any request hitting the currently-connected Plex
 *  server's host - added to the app's shared OkHttpClient so generic image loading
 *  (PosterLoader etc, which have no idea a given URL is Plex-specific) gets authenticated
 *  automatically instead of silently 401ing.
 *
 *  Only the header form is added here. Playback and subtitle URLs still carry
 *  `X-Plex-Token` in the query string, because Media3 fetches those through its own
 *  DefaultHttpDataSource and never sees this client at all. */
class PlexAuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = PlexSession.serverHost
        val token = PlexSession.accessToken
        if (host == null || token == null ||
            (request.url.host != host && !request.url.host.endsWith(".$host")) ||
            !request.header("X-Plex-Token").isNullOrBlank() ||
            !request.url.queryParameter("X-Plex-Token").isNullOrBlank()
        ) {
            return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder().header("X-Plex-Token", token).build())
    }
}
