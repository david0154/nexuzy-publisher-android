package com.nexuzy.publisher.data.cache

import android.util.Log
import com.nexuzy.publisher.data.model.WordPressSite
import com.nexuzy.publisher.network.WordPressApiClient

/**
 * WpCategoryCache
 *
 * Singleton in-memory cache of WordPress categories per site.
 * TTL: 30 minutes. After TTL expires, re-fetches from WordPress.
 *
 * Usage:
 *   // In a coroutine:
 *   val cats = WpCategoryCache.get(wpSite)
 *   // cats is List<WordPressApiClient.WpCategory>
 *   // Use cats to populate a spinner in the RSS add dialog.
 *
 *   // Force refresh (e.g. after user adds a new WP category):
 *   WpCategoryCache.invalidate(wpSite.siteUrl)
 */
object WpCategoryCache {

    private const val TTL_MS = 30 * 60 * 1000L   // 30 minutes
    private val TAG = "WpCategoryCache"

    private data class Entry(
        val categories: List<WordPressApiClient.WpCategory>,
        val fetchedAt: Long = System.currentTimeMillis()
    ) {
        fun isExpired() = System.currentTimeMillis() - fetchedAt > TTL_MS
    }

    private val cache = mutableMapOf<String, Entry>()  // key = siteUrl
    private val client = WordPressApiClient()

    /**
     * Returns cached categories or fetches from WordPress.
     * Always returns a list (empty if site unreachable).
     */
    suspend fun get(site: WordPressSite): List<WordPressApiClient.WpCategory> {
        val key = site.siteUrl.trimEnd('/')
        val entry = cache[key]
        if (entry != null && !entry.isExpired()) {
            Log.d(TAG, "Cache hit: ${entry.categories.size} categories for $key")
            return entry.categories
        }
        return fetch(site)
    }

    /**
     * Force-fetches categories from WordPress and updates the cache.
     */
    suspend fun fetch(site: WordPressSite): List<WordPressApiClient.WpCategory> {
        val key = site.siteUrl.trimEnd('/')
        return try {
            val cats = client.fetchCategories(site)
            cache[key] = Entry(cats)
            Log.i(TAG, "Fetched & cached ${cats.size} categories for $key")
            cats
        } catch (e: Exception) {
            Log.e(TAG, "fetch failed: ${e.message}")
            emptyList()
        }
    }

    /** Clears cached categories for a specific site. */
    fun invalidate(siteUrl: String) {
        cache.remove(siteUrl.trimEnd('/'))
        Log.d(TAG, "Cache invalidated for $siteUrl")
    }

    /** Clears all cached data. */
    fun invalidateAll() {
        cache.clear()
    }

    /**
     * Returns category names only — useful for building a spinner adapter.
     * Always includes "Uncategorized" as the first option.
     */
    suspend fun getCategoryNames(site: WordPressSite): List<String> {
        val cats = get(site)
        val names = cats.map { it.name }.filter { it.isNotBlank() }
        return if (names.isEmpty()) listOf("Uncategorized")
        else names
    }

    /**
     * Looks up the WP category ID by name (case-insensitive).
     * Returns 0 if not found.
     */
    suspend fun getCategoryId(site: WordPressSite, name: String): Long {
        return get(site)
            .firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            ?.id ?: 0
    }
}
