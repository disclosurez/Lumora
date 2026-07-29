package com.lumora.cache

import com.lumora.parser.XtreamClient

private const val MAX_CACHE_SIZE = 500

/** In-memory cache of upcoming EPG entries per live channel, used by the guide grid. */
object EpgListCache {
    private val cache = mutableMapOf<String, List<XtreamClient.EpgProgram>?>()
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun get(channelId: String): List<XtreamClient.EpgProgram>? = cache[channelId]

    fun has(channelId: String): Boolean = cache.containsKey(channelId)

    fun put(channelId: String, programs: List<XtreamClient.EpgProgram>?) {
        cache[channelId] = programs
        inFlight.remove(channelId)
        evictIfNeeded()
    }

    /** Removes oldest entries when the cache exceeds the maximum size. */
    private fun evictIfNeeded() {
        while (cache.size > MAX_CACHE_SIZE) {
            val oldest = cache.entries.minByOrNull { it.value?.firstOrNull()?.startTimestamp ?: 0L }?.key
            if (oldest != null) cache.remove(oldest) else break
        }
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
