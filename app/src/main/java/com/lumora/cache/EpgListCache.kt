package com.lumora.cache

import com.lumora.parser.XtreamClient
import java.util.concurrent.ConcurrentHashMap

private const val MAX_CACHE_SIZE = 500

/** In-memory cache of upcoming EPG entries per live channel, used by the guide grid. */
object EpgListCache {
    // ConcurrentHashMap: guide fetches can put/remove from background scopes, and a plain
    // HashMap mutated off the main thread would throw ConcurrentModificationException or lose
    // entries. Iteration here is weakly consistent, so the eviction scan never races a write.
    private val cache = ConcurrentHashMap<String, List<XtreamClient.EpgProgram>?>()
    private val lastAccess = ConcurrentHashMap<String, Long>()
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun get(channelId: String): List<XtreamClient.EpgProgram>? {
        val programs = cache[channelId]
        if (programs != null || cache.containsKey(channelId)) {
            lastAccess[channelId] = System.currentTimeMillis()
        }
        return programs
    }

    fun has(channelId: String): Boolean = cache.containsKey(channelId)

    fun put(channelId: String, programs: List<XtreamClient.EpgProgram>?) {
        cache[channelId] = programs
        lastAccess[channelId] = System.currentTimeMillis()
        inFlight.remove(channelId)
        evictIfNeeded()
    }

    /** Least-recently-used eviction: drops the entry that has gone longest without a get/put.
     *  Evicting by next-program start was evicting exactly the live channel the guide needs
     *  most. Entries still being fetched are skipped so an in-flight fetch isn't evicted out
     *  from under its own put(). */
    private fun evictIfNeeded() {
        while (cache.size > MAX_CACHE_SIZE) {
            val lru = cache.keys
                .asSequence()
                .filterNot { inFlight.contains(it) }
                .minByOrNull { lastAccess[it] ?: 0L }
            if (lru != null) {
                cache.remove(lru)
                lastAccess.remove(lru)
            } else break
        }
    }

    fun markInFlight(channelId: String): Boolean {
        synchronized(inFlight) {
            if (channelId in inFlight) return false
            inFlight.add(channelId)
            return true
        }
    }

    /** Releases an in-flight claim without caching a result - for fetches that were
     *  cancelled mid-request, so the channel stays fetchable instead of stuck. */
    fun clearInFlight(channelId: String) {
        inFlight.remove(channelId)
    }
}
