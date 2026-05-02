package com.nexuzy.publisher.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.nexuzy.publisher.data.model.RssFeed

@Dao
interface RssFeedDao {

    /** LiveData of ALL feeds ordered by name — used by RssFragment list. */
    @Query("SELECT * FROM rss_feeds ORDER BY name ASC")
    fun getAllFeeds(): LiveData<List<RssFeed>>

    /** Suspend query returning only active feeds — used by NewsWorkflowManager to fetch news. */
    @Query("SELECT * FROM rss_feeds WHERE isActive = 1")
    suspend fun getActiveFeeds(): List<RssFeed>

    /** One-shot suspend query of all feeds — used by DefaultFeedsSeeder for URL dedup. */
    @Query("SELECT * FROM rss_feeds ORDER BY name ASC")
    suspend fun getAllOnce(): List<RssFeed>

    /** One-shot query of all default feeds — used by RssFirestoreSync for cloud backup. */
    @Query("SELECT * FROM rss_feeds WHERE isDefault = 1 ORDER BY name ASC")
    suspend fun getDefaultFeeds(): List<RssFeed>

    /** Row count — used during seed check. */
    @Query("SELECT COUNT(*) FROM rss_feeds")
    suspend fun getCount(): Int

    /** Active feed count — used by RssFragment to show how many feeds will be fetched. */
    @Query("SELECT COUNT(*) FROM rss_feeds WHERE isActive = 1")
    suspend fun getActiveCount(): Int

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
