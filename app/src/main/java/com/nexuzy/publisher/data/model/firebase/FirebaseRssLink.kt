package com.nexuzy.publisher.data.model.firebase

data class FirebaseRssLink(
    val id: String = "",
    val title: String = "",
    val url: String = "",
    val category: String = "General",
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
