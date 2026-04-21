package com.nexuzy.publisher.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wordpress_sites")
data class WordPressSite(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val siteUrl: String,
    val username: String,
    val appPassword: String,
    val defaultCategory: String = "News",
    val defaultStatus: String = "draft",
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
