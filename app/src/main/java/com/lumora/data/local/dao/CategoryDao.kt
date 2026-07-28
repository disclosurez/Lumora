package com.lumora.data.local.dao

import androidx.room.*
import com.lumora.data.local.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE providerId = :providerId ORDER BY customSortOrder ASC")
    fun getByProviderFlow(providerId: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE providerId = :providerId ORDER BY customSortOrder ASC")
    suspend fun getByProvider(providerId: String): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE providerId = :providerId AND mediaType = :mediaType ORDER BY customSortOrder ASC")
    suspend fun getByProviderAndType(providerId: String, mediaType: String): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("UPDATE categories SET isHidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: String, hidden: Boolean)

    @Query("UPDATE categories SET isLocked = :locked WHERE id = :id")
    suspend fun setLocked(id: String, locked: Boolean)

    @Query("UPDATE categories SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("DELETE FROM categories WHERE providerId = :providerId")
    suspend fun deleteByProvider(providerId: String)
}
