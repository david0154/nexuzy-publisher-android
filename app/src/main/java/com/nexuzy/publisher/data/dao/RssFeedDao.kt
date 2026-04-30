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

    /** One-shot suspend query — used by RssFirestoreSync for cloud backup / migration. */
    @Query("SELECT * FROM rss_feeds ORDER BY name ASC")
    suspend fun getAllOnce(): List<RssFeed>

    /** Row count — used by RssFirestoreSync to detect an empty local DB (new install). */
    @Query("SELECT COUNT(*) FROM rss_feeds")
    suspend fun getCount(): Int

    /** Insert a new feed and return its auto-generated row ID. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(feed: RssFeed): Long

    /**
     * Insert-or-replace by primary key.
     * Used when restoring feeds from Firestore so the original Room ID is preserved.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(feed: RssFeed)

    @Update
    suspend fun update(feed: RssFeed)

    @Delete
    suspend fun delete(feed: RssFeed)
}
