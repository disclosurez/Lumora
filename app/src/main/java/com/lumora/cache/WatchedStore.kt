package com.lumora.cache

import android.content.Context
import android.util.Log
import java.io.File

private const val TAG = "WatchedStore"
private const val FILE_NAME = "watched_marks.txt"
/** Line separator is '\n', so a key can never contain one - see [MainActivity.watchedKeyFor]. */
private const val MAX_ENTRIES = 20_000

/**
 * Watched marks keyed by *what was watched* rather than by which copy of it played.
 *
 * [PlaybackPositionStore] is keyed by a provider-scoped id (an Xtream stream id, a qualified
 * Jellyfin item id, a Plex rating key), which is the right key for a resume position - that is
 * genuinely per-file - but the wrong one for "have I seen this". A library with the same show
 * on Plex, on Jellyfin and across three IPTV panels has five ids for one episode, so watching
 * it left the other four looking untouched.
 *
 * The key here is instead derived from the title and the season/episode numbers (see
 * `MainActivity.watchedKeyFor`), using the same normalisation that already decides two catalogue
 * entries are the same title when duplicates are grouped. Any copy therefore reads as watched,
 * including copies of a provider that has never been browsed and providers added later.
 *
 * Deliberately a separate file from the position store rather than more entries in it: that
 * store caps at 500 and evicts the oldest, because it carries a whole Channel snapshot per
 * entry for Continue Watching to resume from. A mark here is one short string, so the budget
 * can be far larger - a completed long-running show is hundreds of them and must not push
 * anybody's resume points out.
 *
 * Plain newline-delimited text for the same reason [ChannelCache] is: parsing tens of thousands
 * of short lines with `split` costs a fraction of what a JSON tree does on a TV stick, and this
 * is read during startup.
 */
object WatchedStore {

    // The reference is swapped wholesale on load/clear and read from background dispatchers
    // (shelf builds run on Dispatchers.Default), so it has to be volatile.
    @Volatile
    private var cache: MutableSet<String>? = null

    @Synchronized
    fun isWatched(context: Context, key: String): Boolean =
        key.isNotBlank() && ensureLoaded(context).contains(key)

    /** Adds or removes [key]. Returns true when the set actually changed. */
    @Synchronized
    fun setWatched(context: Context, key: String, watched: Boolean): Boolean {
        if (key.isBlank()) return false
        val set = ensureLoaded(context)
        val changed = if (watched) {
            // A full set is not a reason to drop the mark being added - it is the freshest
            // one there is. Nothing here records age, so eviction can only be arbitrary;
            // the ceiling exists to bound the file, not to curate it.
            if (set.size >= MAX_ENTRIES) set.iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
            set.add(key)
        } else {
            set.remove(key)
        }
        if (changed) persist(context, set)
        return changed
    }

    /**
     * Snapshot of every mark, for the passes that have to walk the whole set rather than ask
     * about one title - the Trakt backfill, which turns marks back into titles to push.
     *
     * A copy, not the live set: callers iterate it off the main thread while a mark written in
     * the meantime would otherwise be a ConcurrentModificationException.
     */
    @Synchronized
    fun allKeys(context: Context): Set<String> = LinkedHashSet(ensureLoaded(context))

    @Synchronized
    fun clearAll(context: Context) {
        cache = mutableSetOf()
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    private fun ensureLoaded(context: Context): MutableSet<String> {
        cache?.let { return it }
        val loaded = LinkedHashSet<String>()
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) {
                file.bufferedReader().use { reader ->
                    reader.forEachLine { line -> if (line.isNotBlank()) loaded.add(line) }
                }
            }
        }.onFailure { Log.w(TAG, "Failed to load watched marks: ${it.message}") }
        cache = loaded
        return loaded
    }

    /**
     * Rewrites the whole file. Called from the caller's thread rather than a background one:
     * a mark is written on a deliberate user action (finishing a title, toggling the
     * checkmark), not continuously the way positions are, and the file is a few hundred KB of
     * short lines at its very largest.
     *
     * Atomic swap, same as the other stores - a half-written file must never replace a good
     * one, and a torn last line would resurrect or lose a mark.
     */
    private fun persist(context: Context, set: Set<String>) {
        runCatching {
            val target = File(context.filesDir, FILE_NAME)
            val temp = File(target.absolutePath + ".tmp")
            temp.bufferedWriter().use { out ->
                for (key in set) {
                    out.append(key)
                    out.append('\n')
                }
            }
            temp.renameTo(target)
        }.onFailure { Log.w(TAG, "Failed to save watched marks: ${it.message}") }
    }
}
