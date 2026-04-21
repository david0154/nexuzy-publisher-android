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
    val lastFetchedAt: Long = 0,
    val articleCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
