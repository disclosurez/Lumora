package com.lumora.data

import android.content.SharedPreferences
import com.lumora.data.remote.trakt.TraktClient

/**
 * The stored Trakt session and its two feature toggles.
 *
 * Deliberately not part of [IptvProviderStore] or [MediaServerStore]: Trakt is neither a source
 * of content nor a library to browse. Nothing here ever produces a [com.lumora.model.Channel] -
 * it is one account whose only job is to be told what was watched. So it lives as four flat
 * prefs keys in the same "iptv_prefs" file everything else uses, rather than as an entry in a
 * JSON array of configs that all describe catalogues.
 *
 * The two toggles are kept apart on purpose, because they answer different questions and people
 * want them set differently: [isScrobbleEnabled] governs live reporting from the player (which
 * shows up on your Trakt profile as you watch), while [isWatchedSyncEnabled] governs history
 * writes and the startup pull (which quietly reconciles state). Someone happy for Lumora to
 * mirror their watched marks is not necessarily happy for every play to broadcast.
 */
object TraktStore {

    private const val KEY_ACCESS_TOKEN = "trakt_access_token"
    private const val KEY_REFRESH_TOKEN = "trakt_refresh_token"
    private const val KEY_EXPIRES_AT = "trakt_expires_at"
    private const val KEY_USERNAME = "trakt_username"
    private const val KEY_SCROBBLE = "trakt_scrobble_enabled"
    private const val KEY_WATCHED_SYNC = "trakt_watched_sync_enabled"
    /** Timestamp of the last successful `/sync/watched` pull, so a cold start that already
     *  reconciled today doesn't do it again on every launch. */
    private const val KEY_LAST_PULL = "trakt_last_watched_pull"

    fun tokens(prefs: SharedPreferences): TraktClient.Tokens? {
        val access = prefs.getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        return TraktClient.Tokens(access, refresh, prefs.getLong(KEY_EXPIRES_AT, 0L))
    }

    fun saveTokens(prefs: SharedPreferences, tokens: TraktClient.Tokens) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
            .putString(KEY_REFRESH_TOKEN, tokens.refreshToken)
            .putLong(KEY_EXPIRES_AT, tokens.expiresAtMs)
            .apply()
    }

    fun username(prefs: SharedPreferences): String? =
        prefs.getString(KEY_USERNAME, null)?.takeIf { it.isNotBlank() }

    fun saveUsername(prefs: SharedPreferences, username: String?) {
        prefs.edit().putString(KEY_USERNAME, username.orEmpty()).apply()
    }

    fun isSignedIn(prefs: SharedPreferences): Boolean = tokens(prefs) != null

    /**
     * Drops the whole session.
     *
     * The toggles are deliberately left alone: they are a preference about how Trakt should
     * behave, not part of the session, and someone who signs out and back in a minute later
     * shouldn't have to set them again. They are inert while signed out anyway - every caller
     * checks for a token first.
     */
    fun clear(prefs: SharedPreferences) {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_USERNAME)
            .remove(KEY_LAST_PULL)
            .apply()
    }

    /** Real-time /scrobble reporting from the player. Default on: it is the thing people
     *  connect Trakt for, and it does nothing at all until they sign in. */
    fun isScrobbleEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_SCROBBLE, true)

    fun setScrobbleEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCROBBLE, enabled).apply()
    }

    /** History writes plus the startup pull into WatchedStore. Default off: it writes to the
     *  cross-provider watched state, which is a bigger thing to turn on behind someone's back
     *  than a scrobble that only ever adds to a Trakt profile. */
    fun isWatchedSyncEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_WATCHED_SYNC, false)

    fun setWatchedSyncEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WATCHED_SYNC, enabled).apply()
    }

    // ── TMDB id memo (backfill) ─────────────────

    private const val KEY_ID_MEMO = "trakt_tmdb_id_memo"
    /** Entries are short; the ceiling exists to bound the pref, not to curate it. A library
     *  big enough to hit this is far past the point where the backfill has already pushed
     *  everything that resolves. */
    private const val ID_MEMO_MAX = 8_000

    /**
     * Remembers what a normalised title resolved to on TMDB, so the backfill doesn't re-search
     * the same show on every run.
     *
     * Stored as `type|normalised|id` lines in one string set. A **negative** id is a memoised
     * "TMDB has never heard of this" - kept deliberately, because without it every run would
     * spend its whole budget re-searching the same handful of titles that will never match.
     */
    fun idMemo(prefs: SharedPreferences): Map<String, Int> {
        val raw = prefs.getStringSet(KEY_ID_MEMO, emptySet()) ?: return emptyMap()
        val out = HashMap<String, Int>(raw.size)
        for (line in raw) {
            val cut = line.lastIndexOf('|')
            if (cut <= 0) continue
            val id = line.substring(cut + 1).toIntOrNull() ?: continue
            out[line.substring(0, cut)] = id
        }
        return out
    }

    fun saveIdMemo(prefs: SharedPreferences, memo: Map<String, Int>) {
        val trimmed = if (memo.size <= ID_MEMO_MAX) memo else memo.entries.take(ID_MEMO_MAX).associate { it.toPair() }
        prefs.edit()
            .putStringSet(KEY_ID_MEMO, trimmed.map { (key, id) -> "$key|$id" }.toSet())
            .apply()
    }

    fun lastWatchedPullMs(prefs: SharedPreferences): Long = prefs.getLong(KEY_LAST_PULL, 0L)

    fun markWatchedPulled(prefs: SharedPreferences) {
        prefs.edit().putLong(KEY_LAST_PULL, System.currentTimeMillis()).apply()
    }
}
