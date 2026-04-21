package com.nexuzy.publisher.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.nexuzy.publisher.data.model.WordPressSite

@Dao
interface WordPressSiteDao {
    @Query("SELECT * FROM wordpress_sites ORDER BY name ASC")
    fun getAllSites(): LiveData<List<WordPressSite>>

    @Query("SELECT * FROM wordpress_sites WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveSite(): WordPressSite?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(site: WordPressSite): Long

    @Update
    suspend fun update(site: WordPressSite)

    @Delete
    suspend fun delete(site: WordPressSite)
}
