package com.nexuzy.publisher.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rss_feeds")
data class RssFeed(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val url: String,
    val category: String = "General",
    val isActive: Boolean = true,
    /** true = seeded by DefaultFeedsSeeder; false = added manually by user.
     *  Both can be deleted. This flag is informational only. */
    val isDefault: Boolean = false,
    val lastFetchedAt: Long = 0,
    val articleCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
