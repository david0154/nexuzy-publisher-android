package com.nexuzy.publisher.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "articles")
data class Article(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String = "",
    val content: String = "",
    val summary: String = "",
    val category: String = "",
    val tags: String = "",
    val sourceUrl: String = "",
    val sourceName: String = "",
    val imageUrl: String = "",
    val status: String = "draft", // draft, ready, published, failed
    val wordpressSiteId: Long = 0,
    val wordpressPostId: Long = 0,
    val geminiChecked: Boolean = false,
    val openaiChecked: Boolean = false,
    val sarvamChecked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val publishedAt: Long = 0,
    val aiProvider: String = "gemini"
)
