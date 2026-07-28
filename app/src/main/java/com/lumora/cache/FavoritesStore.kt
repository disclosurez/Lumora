package com.lumora.cache

import android.content.Context

private const val PREFS_NAME = "iptv_prefs"
private const val KEY_FAVORITE_SERIES = "favorite_series_ids"
private const val KEY_FAVORITE_CHANNELS = "favorite_channel_ids"

/** Favorited series (shown on Home) and favorited live channels (Favourites category in Live TV). */
object FavoritesStore {

    fun isFavoriteSeries(context: Context, id: String): Boolean = id in getFavoriteSeriesIds(context)

    fun toggleFavoriteSeries(context: Context, id: String): Boolean = toggle(context, KEY_FAVORITE_SERIES, id)

    fun getFavoriteSeriesIds(context: Context): Set<String> = readSet(context, KEY_FAVORITE_SERIES)

    fun isFavoriteChannel(context: Context, id: String): Boolean = id in getFavoriteChannelIds(context)

    fun toggleFavoriteChannel(context: Context, id: String): Boolean = toggle(context, KEY_FAVORITE_CHANNELS, id)

    fun getFavoriteChannelIds(context: Context): Set<String> = readSet(context, KEY_FAVORITE_CHANNELS)

    /** Returns the new membership state (true = now favorited). */
    private fun toggle(context: Context, key: String, id: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = readSet(context, key).toMutableSet()
        val nowFavorite = if (!current.remove(id)) { current.add(id); true } else false
        prefs.edit().putStringSet(key, current).apply()
        return nowFavorite
    }

    private fun readSet(context: Context, key: String): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getStringSet(key, emptySet()) ?: emptySet()
}
