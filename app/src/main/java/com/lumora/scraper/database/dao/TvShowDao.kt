package com.lumora.scraper.database.dao

import com.lumora.scraper.models.TvShow
import kotlinx.coroutines.flow.Flow

/**
 * The slice of upstream's Room DAO that the ported providers actually call. Kept as an interface
 * under the original name so [com.lumora.scraper.providers.AniWorldProvider] and
 * [com.lumora.scraper.providers.SerienStreamProvider] compile unchanged; the implementation is
 * [com.lumora.scraper.database.ScraperTvShowStore], which is a JSON file rather than a database.
 *
 * Everything upstream's version carried for favourites, watch history and artwork repair is gone
 * - Lumora owns all three itself, keyed on its own `Channel`, and a scraper writing a parallel
 * copy of that state would be a second source of truth for it.
 */
interface TvShowDao {

    /** The whole cached catalogue, re-emitted on every write. */
    fun getAll(): Flow<List<TvShow>>

    suspend fun getAllIds(): List<String>

    fun getById(id: String): TvShow?

    /** Case-insensitive substring match on title, paged. */
    suspend fun searchTvShows(query: String, limit: Int, offset: Int): List<TvShow>

    fun insertAll(tvShows: List<TvShow>)

    fun insert(tvShow: TvShow)

    fun update(tvShow: TvShow)

    fun deleteAll()
}
