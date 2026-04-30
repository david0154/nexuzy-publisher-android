package com.nexuzy.publisher.data.model

/**
 * Lightweight model for a WordPress category fetched from the WP REST API.
 * Used in the RSS feed category dropdown so users can pick an existing WP category.
 */
data class WpCategory(
    val id: Int,
    val name: String,
    val slug: String,
    val count: Int = 0
)
