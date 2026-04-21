package com.nexuzy.publisher.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.nexuzy.publisher.data.model.RssFeed

@Dao
interface RssFeedDao {
    @Query("SELECT * FROM rss_feeds ORDER BY name ASC")
    fun getAllFeeds(): LiveData<List<RssFeed>>

    @Query("SELECT * FROM rss_feeds WHERE isActive = 1")
    suspend fun getActiveFeeds(): List<RssFeed>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(feed: RssFeed): Long

    @Update
    suspend fun update(feed: RssFeed)

    @Delete
    suspend fun delete(feed: RssFeed)
}
