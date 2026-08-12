package com.lumora.scraper.database

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lumora.scraper.database.dao.TvShowDao
import com.lumora.scraper.models.TvShow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Local index of a scraper's full show list.
 *
 * AniWorld and SerienStream both publish a complete A-Z catalogue as one page and then expect
 * search to happen client-side - their sites have no usable search endpoint. Upstream held that
 * index in a per-provider Room database. Here it is a JSON file per provider instead, for two
 * reasons: [TvShow] is a hand-annotated Room entity whose shape (nested `@Ignore` lists, an
 * `@Embedded` watch-history record, a `lateinit` field) would have to be reworked to be
 * code-generated against Lumora's schema, and the data is a disposable cache of someone else's
 * catalogue - it has no business sitting in the same database as the user's own recordings and
 * provider configs, where a migration would have to carry it forward forever.
 *
 * Reads serve from memory after the first load; writes rewrite the file. Both are fine for a
 * list that is fetched once and then only searched.
 */
internal class ScraperTvShowStore(
    context: Context,
    fileName: String,
) : TvShowDao {

    private val file = File(context.cacheDir, fileName)
    private val gson = Gson()
    private val lock = ReentrantLock()

    /** Insertion-ordered so [getAll] preserves the catalogue's own A-Z ordering. */
    private var shows: LinkedHashMap<String, TvShow>? = null

    private val flow = MutableStateFlow<List<TvShow>>(emptyList())

    private fun loaded(): LinkedHashMap<String, TvShow> = lock.withLock {
        shows ?: run {
            val parsed = runCatching {
                if (!file.exists()) return@runCatching emptyList<TvShow>()
                val type = object : TypeToken<List<TvShow>>() {}.type
                gson.fromJson<List<TvShow>>(file.readText(), type) ?: emptyList()
            }.getOrElse {
                Log.w(TAG, "Could not read ${file.name}, starting empty", it)
                emptyList()
            }
            LinkedHashMap<String, TvShow>().apply {
                parsed.forEach { put(it.id, it) }
            }.also {
                shows = it
                flow.value = it.values.toList()
            }
        }
    }

    private fun persist(map: LinkedHashMap<String, TvShow>) {
        flow.value = map.values.toList()
        runCatching { file.writeText(gson.toJson(map.values.toList())) }
            .onFailure { Log.w(TAG, "Could not write ${file.name}", it) }
    }

    override fun getAll(): Flow<List<TvShow>> {
        loaded()
        return flow.asStateFlow()
    }

    override suspend fun getAllIds(): List<String> = loaded().keys.toList()

    override fun getById(id: String): TvShow? = loaded()[id]

    override suspend fun searchTvShows(query: String, limit: Int, offset: Int): List<TvShow> {
        val needle = query.lowercase()
        return loaded().values
            .asSequence()
            .filter { it.title.lowercase().contains(needle) }
            .drop(offset)
            .take(limit)
            .toList()
    }

    override fun insertAll(tvShows: List<TvShow>) = lock.withLock {
        val map = loaded()
        tvShows.forEach { map[it.id] = it }
        persist(map)
    }

    override fun insert(tvShow: TvShow) = insertAll(listOf(tvShow))

    override fun update(tvShow: TvShow) = insertAll(listOf(tvShow))

    override fun deleteAll() = lock.withLock {
        val map = loaded()
        map.clear()
        persist(map)
    }

    private companion object {
        const val TAG = "ScraperTvShowStore"
    }
}

/**
 * Holders mirroring upstream's `XDatabase.getInstance(context).tvShowDao()` call shape, so the
 * two providers that use them need no edit. One store per provider - the catalogues are
 * different sites and share no ids.
 */
object AniWorldDatabase {
    @Volatile
    private var store: ScraperTvShowStore? = null

    fun getInstance(context: Context): AniWorldDatabase {
        if (store == null) synchronized(this) {
            if (store == null) {
                store = ScraperTvShowStore(context.applicationContext, "aniworld_tvshows.json")
            }
        }
        return this
    }

    fun tvShowDao(): TvShowDao = store ?: error("AniWorldDatabase.getInstance() has not run")
}

object SerienStreamDatabase {
    @Volatile
    private var store: ScraperTvShowStore? = null

    fun getInstance(context: Context): SerienStreamDatabase {
        if (store == null) synchronized(this) {
            if (store == null) {
                store = ScraperTvShowStore(context.applicationContext, "serienstream_tvshows.json")
            }
        }
        return this
    }

    fun tvShowDao(): TvShowDao = store ?: error("SerienStreamDatabase.getInstance() has not run")
}
