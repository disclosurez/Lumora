package com.lumora.cache

import com.lumora.parser.XtreamClient

/** In-memory cache of upcoming EPG entries per live channel, used by the guide grid. */
object EpgListCache {
    private val cache = mutableMapOf<String, List<XtreamClient.EpgProgram>?>()
    private val inFlight = mutableSetOf<String>()

    fun get(channelId: String): List<XtreamClient.EpgProgram>? = cache[channelId]

    fun has(channelId: String): Boolean = cache.containsKey(channelId)

    fun put(channelId: String, programs: List<XtreamClient.EpgProgram>?) {
        cache[channelId] = programs
        inFlight.remove(channelId)
    }

    fun markInFlight(channelId: String): Boolean {
        if (channelId in inFlight) return false
        inFlight.add(channelId)
        return true
    }

    /** Releases an in-flight claim without caching a result - for fetches that were
     *  cancelled mid-request, so the channel stays fetchable instead of stuck. */
    fun clearInFlight(channelId: String) {
        inFlight.remove(channelId)
    }
}
