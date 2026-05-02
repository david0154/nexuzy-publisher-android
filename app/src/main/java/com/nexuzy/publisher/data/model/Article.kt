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
    val metaKeywords: String = "",
    val focusKeyphrase: String = "",
    val metaDescription: String = "",
    val imageAltText: String = "",
    val sourceUrl: String = "",
    val sourceName: String = "",
    val imageUrl: String = "",
    val imagePath: String = "",
    val status: String = "draft",
    val wordpressSiteId: Long = 0,
    val geminiChecked: Boolean = false,
    val openaiChecked: Boolean = false,
    val sarvamChecked: Boolean = false,
    val factCheckPassed: Boolean = false,
    val factCheckFeedback: String = "",
    val confidenceScore: Float = 0f,
    val aiProvider: String = "",
    val sourceLinks: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
