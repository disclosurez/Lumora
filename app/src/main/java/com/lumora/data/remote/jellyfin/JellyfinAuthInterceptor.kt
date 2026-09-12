package com.lumora.data.remote.jellyfin

import okhttp3.Interceptor
import okhttp3.Response

/** Attaches the Jellyfin session token to any request hitting the currently-connected
 *  Jellyfin server's host - added to the app's shared OkHttpClient so generic image
 *  loading (PosterLoader etc, which have no idea a given URL is Jellyfin-specific)
 *  gets authenticated automatically instead of silently 401ing.
 *
 *  Jellyfin 12.0 removed the legacy token headers (`X-Emby-Token`,
 *  `X-MediaBrowser-Token`) and the `api_key` query parameter, so the only accepted
 *  forms left are the `Authorization: MediaBrowser Token="..."` header and the
 *  `ApiKey` query parameter. The header is used here; requests that already carry
 *  either credential (the provider's own calls, or URLs built with `ApiKey`) are left
 *  alone so a request never presents two tokens at once. */
class JellyfinAuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = JellyfinSession.serverHost
        val token = JellyfinSession.accessToken
        if (host == null || token == null || (request.url.host != host && !request.url.host.endsWith(".$host")) ||
            !request.header("Authorization").isNullOrBlank() ||
            request.url.queryParameter("ApiKey") != null ||
            request.url.queryParameter("api_key") != null
        ) {
            return chain.proceed(request)
        }
        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "MediaBrowser Token=\"$token\"")
                .build()
        )
    }
}
