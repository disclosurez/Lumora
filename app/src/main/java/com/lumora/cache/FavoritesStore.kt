package com.lumora.cache

import android.content.Context

private const val PREFS_NAME = "iptv_prefs"
private const val KEY_FAVORITE_SERIES = "favorite_series_ids"
private const val KEY_FAVORITE_CHANNELS = "favorite_channel_ids"

/** Favorited series (shown on Home) and favorited live channels (Favourites category in Live TV). */
object FavoritesStore {
    private val lock = Any()

    fun isFavoriteSeries(context: Context, id: String): Boolean = id in getFavoriteSeriesIds(context)

    fun toggleFavoriteSeries(context: Context, id: String): Boolean = toggle(context, KEY_FAVORITE_SERIES, id)

    fun getFavoriteSeriesIds(context: Context): Set<String> = readSet(context, KEY_FAVORITE_SERIES)

    /** Sets membership outright rather than flipping it - for reconciling against a server
     *  that owns the truth (Jellyfin's UserData.IsFavorite), where a toggle can't express
     *  "the server says this is no longer a favourite". Returns true if anything changed. */
    fun setFavoriteSeries(context: Context, id: String, favorite: Boolean): Boolean = synchronized(lock) {
        if (id.isBlank()) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = readSet(context, KEY_FAVORITE_SERIES).toMutableSet()
        val changed = if (favorite) current.add(id) else current.remove(id)
        if (changed) prefs.edit().putStringSet(KEY_FAVORITE_SERIES, current).apply()
        changed
    }

    fun isFavoriteChannel(context: Context, id: String): Boolean = id in getFavoriteChannelIds(context)

    fun toggleFavoriteChannel(context: Context, id: String): Boolean = toggle(context, KEY_FAVORITE_CHANNELS, id)

    fun getFavoriteChannelIds(context: Context): Set<String> = readSet(context, KEY_FAVORITE_CHANNELS)

    /** Returns the new membership state (true = now favorited). */
    private fun toggle(context: Context, key: String, id: String): Boolean = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = readSet(context, key).toMutableSet()
        val nowFavorite = if (!current.remove(id)) { current.add(id); true } else false
        prefs.edit().putStringSet(key, current).apply()
        nowFavorite
    }

    private fun readSet(context: Context, key: String): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getStringSet(key, emptySet())?.toSet() ?: emptySet()
}
