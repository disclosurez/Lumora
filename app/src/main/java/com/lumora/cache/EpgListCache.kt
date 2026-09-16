package com.lumora.cache

import com.lumora.parser.XtreamClient
import java.util.concurrent.ConcurrentHashMap

private const val MAX_CACHE_SIZE = 500

/** How long a fetched guide is served before the guide revalidates it through
 *  [com.lumora.MainActivity.resolveEpgPrograms]. Without this the cache was absolute: the
 *  first fetch for a channel was reused for the rest of the process's life - and an Android TV
 *  stick keeps the process alive for days - so the guide never refreshed and its programmes
 *  eventually ran out with the rows left showing a stale schedule.
 *
 *  Revalidation is disk-first (see resolveEpgPrograms), so expiry normally costs one Room read,
 *  not a provider request. */
private const val ENTRY_TTL_MS = 15 * 60 * 1000L

/** In-memory cache of upcoming EPG entries per live channel, used by the guide grid. */
object EpgListCache {
    // ConcurrentHashMap: guide fetches can put/remove from background scopes, and a plain
    // HashMap mutated off the main thread would throw ConcurrentModificationException or lose
    // entries. Iteration here is weakly consistent, so the eviction scan never races a write.
    // Values are non-null: a channel known to have no EPG is stored as an empty list, not as
    // a null. ConcurrentHashMap rejects null values outright, so `cache[id] = null` - which is
    // exactly what put() did for a channel whose fetch came back empty or threw - raised an
    // NPE from putVal() on the main thread and took the process down. Every reader already
    // treats empty and absent-data the same way (see renderPrograms/renderNow), and an empty
    // list still makes has() true, which is what stops the guide refetching it forever.
    private val cache = ConcurrentHashMap<String, List<XtreamClient.EpgProgram>>()
    private val lastAccess = ConcurrentHashMap<String, Long>()

    /** When each entry was written - the age test behind [has]/[get]/[isStale]. */
    private val cachedAt = ConcurrentHashMap<String, Long>()
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** True only for an entry young enough to serve without revalidating. A stale entry is
     *  still readable through [peek] (the guide paints it while the re-fetch runs). */
    fun has(channelId: String): Boolean = cache.containsKey(channelId) && !isStale(channelId)

    fun get(channelId: String): List<XtreamClient.EpgProgram>? {
        val programs = peek(channelId) ?: return null
        return if (isStale(channelId)) null else programs
    }

    /** Whatever is cached, stale or not - for painting a row before its revalidating fetch
     *  lands, instead of blanking it every [ENTRY_TTL_MS]. */
    fun peek(channelId: String): List<XtreamClient.EpgProgram>? {
        val programs = cache[channelId] ?: return null
        lastAccess[channelId] = System.currentTimeMillis()
        return programs
    }

    /** Absent counts as stale: there is nothing current to serve. */
    fun isStale(channelId: String): Boolean {
        val writtenAt = cachedAt[channelId] ?: return true
        return System.currentTimeMillis() - writtenAt >= ENTRY_TTL_MS
    }

    fun put(channelId: String, programs: List<XtreamClient.EpgProgram>?) {
        cache[channelId] = programs ?: emptyList()
        val now = System.currentTimeMillis()
        cachedAt[channelId] = now
        lastAccess[channelId] = now
        inFlight.remove(channelId)
        evictIfNeeded()
    }

    /** Drops every entry - for provider reloads, where the channel ids on screen may now
     *  belong to a different provider whose guide has nothing to do with the cached one. */
    fun clear() {
        cache.clear()
        lastAccess.clear()
        cachedAt.clear()
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
                cachedAt.remove(lru)
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
