package com.nexuzy.publisher.network

import android.util.Log
import com.nexuzy.publisher.data.model.RssItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * RSS Feed Parser — speed-optimised, dead-URL-clean.
 *
 * ┌───────────────────────────────────────────────────────────────────────────
 * | SPEED CHANGES vs old version                                              |
 * |                                                                           |
 * |  Old               →  New                  Why faster                   |
 * |  15s connect / 30s →  6s connect / 8s read  Dead URLs fail fast          |
 * |  batch 20 feeds    →  batch 5 feeds          Avoids Android net throttle  |
 * |  enrich 20 per feed→  enrich 3 per feed      Fewer page fetches           |
 * |  enrich sequential →  enrich concurrent      Parallel enrichment          |
 * |  always enrich     →  skip if image found    Skip unnecessary fetches     |
 * |  no URL filter     →  dead URLs removed      Never waste time on 404s     |
 * └───────────────────────────────────────────────────────────────────────────
 *
 * BATCH STRATEGY:
 *   - fetchAllFeeds(): max MAX_FEEDS (100) URLs, batches of BATCH_SIZE (5)
 *   - Each batch: all feeds fetched in parallel with async/awaitAll
 *   - Enrich phase: parallel per feed, max ENRICH_PER_FEED (3) articles
 *   - Articles that already have an imageUrl skip enrich entirely
 */
class RssFeedParser {

    companion object {
        private const val BATCH_SIZE      = 5    // feeds per parallel batch (Android sweet spot)
        private const val MAX_FEEDS       = 100  // hard cap on total RSS URLs
        private const val ENRICH_PER_FEED = 3    // max articles enriched per feed
        private const val MAX_CONTENT_CHARS = 3000
        private const val TAG             = "RssParser"

        /**
         * Known dead / inactive RSS feed domains & URL fragments.
         * Any feed URL containing one of these strings is silently skipped.
         * Add more here as you discover broken feeds.
         */
        private val DEAD_URL_PATTERNS = listOf(
            // Permanently dead / redirected to non-RSS pages
            "feedburner.google.com",
            "feeds.feedburner.com/~r",
            "rss.cnn.com",
            "rss.msnbc.msn.com",
            "feeds.foxnews.com",
            "newsrss.bbc.co.uk",
            "feeds.reuters.com/reuters/",          // old Reuters RSS (migrated)
            "feeds.washingtonpost.com",
            "rss.nytimes.com",
            "hosted.ap.org",
            "www.hindustantimes.com/rss",           // HT dropped open RSS
            "feeds.livemint.com",
            "www.business-standard.com/rss/home_page_1.rss",
            "feeds.financialexpress.com",
            "rss.oneindia.com",
            "zeenews.india.com/rss",
            "www.firstpost.com/rss",
            "www.news18.com/rss",
            "rss.indiatimes.com",
            "timesofindia.feedsportal.com",
            "economictimes.feedsportal.com",
            "feeds.feedburner.com/ndtvnews",
            "feeds.feedburner.com/TheHindu",
            "feeds.feedburner.com/CNBC",
            "feeds.feedburner.com/ABPLive",
            "feeds2.feedburner.com",
            // Generic dead patterns
            "/feed/rss2",    // many old WP installs redirect this
            "example.com",
            "localhost",
            "127.0.0.1"
        )

        /** Returns true if this URL is known dead and should be skipped. */
        fun isDeadUrl(url: String): Boolean {
            val lower = url.lowercase()
            return DEAD_URL_PATTERNS.any { lower.contains(it) }
        }
    }

    // Short timeouts: dead URLs fail fast, healthy feeds respond in <3s
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Separate client for article enrichment (page fetches can be slightly slower)
    private val enrichClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ─────────────────────────────────────────────────────────────────────────────
    // PUBLIC: batch-fetch many feeds with callback for progressive loading
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Fetch multiple RSS feeds in parallel small batches.
     *
     * @param feeds        List of Triple(feedUrl, feedName, feedCategory)
     * @param onBatchReady Optional callback: called after EACH batch completes
     *                     so you can show partial results immediately in the UI.
     * @return Combined, deduplicated, newest-first list of RssItems
     */
    suspend fun fetchAllFeeds(
        feeds: List<Triple<String, String, String>>,
        onBatchReady: ((List<RssItem>) -> Unit)? = null
    ): List<RssItem> = coroutineScope {

        // 1. Remove dead/inactive URLs
        val live = feeds
            .take(MAX_FEEDS)
            .filter { (url, _, _) -> !isDeadUrl(url) }

        Log.d(TAG, "fetchAllFeeds: ${feeds.size} feeds input, ${live.size} live after dead-URL filter")

        val allItems = mutableListOf<RssItem>()

        // 2. Process in small batches
        live.chunked(BATCH_SIZE).forEachIndexed { batchIdx, batch ->
            Log.d(TAG, "Batch ${batchIdx + 1}/${(live.size + BATCH_SIZE - 1) / BATCH_SIZE}: " +
                       "${batch.size} feeds")

            val batchResults = batch.map { (url, name, category) ->
                async {
                    withTimeoutOrNull(12_000L) {   // 12 s hard cap per feed
                        fetchFeed(url, name, category)
                    } ?: emptyList()
                }
            }.awaitAll()

            val batchItems = batchResults
                .flatten()
                .distinctBy { it.link }
                .sortedByDescending { it.pubDate }

            allItems.addAll(batchItems)

            // Progressive callback — UI can show these immediately
            if (batchItems.isNotEmpty()) onBatchReady?.invoke(batchItems)

            Log.d(TAG, "Batch ${batchIdx + 1} done: ${batchItems.size} items. " +
                       "Running total: ${allItems.size}")
        }

        // Final dedup + sort
        allItems
            .distinctBy { it.link }
            .sortedByDescending { it.pubDate }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // PUBLIC: single feed
    // ─────────────────────────────────────────────────────────────────────────────

    suspend fun fetchFeed(
        feedUrl: String,
        feedName: String = "",
        feedCategory: String = ""
    ): List<RssItem> {
        if (isDeadUrl(feedUrl)) {
            Log.d(TAG, "Skipping dead URL: $feedUrl")
            return emptyList()
        }
        return try {
            val response = client.newCall(
                Request.Builder()
                    .url(feedUrl)
                    .addHeader("User-Agent", "NexuzyPublisher/2.0 Android RSS")
                    .build()
            ).execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} for $feedUrl")
                return emptyList()
            }

            val xml = response.body?.string() ?: return emptyList()
            val items = parseRssXml(xml, feedName, feedCategory)

            // Phase 2: enrich CONCURRENTLY, skip items that already have an image
            val enriched = enrichConcurrent(items)
            val withImage = enriched.filter { it.imageUrl.isNotBlank() }

            Log.d(TAG, "$feedName: ${items.size} parsed → ${withImage.size} with image")
            withImage

        } catch (e: Exception) {
            Log.w(TAG, "fetchFeed error [$feedUrl]: ${e.message}")
            emptyList()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Phase 1 — Parse RSS/Atom XML
    // ─────────────────────────────────────────────────────────────────────────────

    private fun parseRssXml(
        xml: String,
        feedName: String,
        feedCategory: String
    ): List<RssItem> {
        val items = mutableListOf<RssItem>()
        try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            val parser  = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var inItem = false
            var title = ""; var description = ""; var link = ""
            var pubDate = ""; var imageUrl = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                        "item", "entry" -> {
                            inItem = true
                            title = ""; description = ""; link = ""; pubDate = ""; imageUrl = ""
                        }
                        "title"  -> if (inItem) title = parser.nextText().trim()
                        "description", "summary", "content", "content:encoded" -> {
                            if (inItem) {
                                val raw = parser.nextText()
                                if (imageUrl.isBlank()) imageUrl = extractImageFromHtml(raw)
                                if (description.isBlank()) description = stripHtml(raw).take(400)
                            }
                        }
                        "link" -> if (inItem && link.isBlank()) {
                            val href = parser.getAttributeValue(null, "href")
                            link = href?.trim() ?: parser.nextText().trim()
                        }
                        "pubdate", "published", "updated", "dc:date" -> {
                            if (inItem && pubDate.isBlank()) pubDate = parser.nextText().trim()
                        }
                        "enclosure" -> {
                            if (inItem && imageUrl.isBlank()) {
                                val u = parser.getAttributeValue(null, "url") ?: ""
                                val t = parser.getAttributeValue(null, "type") ?: ""
                                if (u.isNotBlank() && (t.startsWith("image") || u.isImageUrl()))
                                    imageUrl = u
                            }
                        }
                        "media:content", "media:thumbnail" -> {
                            if (inItem && imageUrl.isBlank()) {
                                val u = parser.getAttributeValue(null, "url") ?: ""
                                val m = parser.getAttributeValue(null, "medium") ?: ""
                                if (u.isNotBlank() && (m == "image" || u.isImageUrl() || m.isBlank()))
                                    imageUrl = u
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.lowercase() in listOf("item", "entry") && inItem) {
                            if (title.isNotBlank() && link.isNotBlank()) {
                                items.add(RssItem(
                                    title        = title,
                                    description  = description,
                                    link         = link,
                                    pubDate      = pubDate,
                                    imageUrl     = imageUrl,
                                    feedName     = feedName,
                                    feedCategory = feedCategory
                                ))
                            }
                            inItem = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
        return items
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Phase 2 — Concurrent enrichment
    //   KEY OPTIMISATION: articles that already have imageUrl skip this entirely.
    //   Only the first ENRICH_PER_FEED articles without images are enriched.
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun enrichConcurrent(items: List<RssItem>): List<RssItem> = coroutineScope {
        var enrichBudget = ENRICH_PER_FEED

        items.map { item ->
            // Skip enrich if image already found from RSS XML
            if (item.imageUrl.isNotBlank()) {
                async { item }   // already has image — return immediately
            } else if (enrichBudget > 0) {
                enrichBudget--
                async {
                    withTimeoutOrNull(6_000L) {
                        enrichItemFromPage(item)
                    } ?: item    // timeout → keep original
                }
            } else {
                async { item }   // budget exhausted — keep as-is
            }
        }.awaitAll()
    }

    private fun enrichItemFromPage(item: RssItem): RssItem {
        return try {
            val response = enrichClient.newCall(
                Request.Builder()
                    .url(item.link)
                    .addHeader("User-Agent", "Mozilla/5.0 NexuzyPublisher/2.0")
                    .build()
            ).execute()

            if (!response.isSuccessful) return item
            val html = response.body?.string() ?: return item
            val doc  = Jsoup.parse(html, item.link)

            item.copy(
                imageUrl    = if (item.imageUrl.isBlank()) extractImageFromDoc(doc, item.link)
                              else item.imageUrl,
                fullContent = extractArticleBody(doc)
            )
        } catch (e: Exception) { item }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private fun extractArticleBody(doc: Document): String {
        val selectors = listOf(
            "article", ".entry-content", ".post-content", ".article-body",
            ".story-body", ".article__body", ".td-post-content",
            ".content-body", "[itemprop=articleBody]", "main"
        )
        for (sel in selectors) {
            val el = doc.selectFirst(sel) ?: continue
            el.select("nav,aside,.related,.comments,script,style,.ad,.advertisement,.social-share").remove()
            val text = el.text().trim()
            if (text.length > 200) return text.take(MAX_CONTENT_CHARS)
        }
        doc.select("nav,header,footer,aside,script,style").remove()
        return doc.body()?.text()?.trim()?.take(MAX_CONTENT_CHARS) ?: ""
    }

    private fun extractImageFromDoc(doc: Document, baseUrl: String): String {
        doc.select("meta[property=og:image]").attr("content")
            .let { if (it.isNotBlank()) return resolveUrl(it, baseUrl) }
        doc.select("meta[name=twitter:image]").attr("content")
            .let { if (it.isNotBlank()) return resolveUrl(it, baseUrl) }
        for (img in doc.select("article img, .entry-content img, .post-content img, main img")) {
            val src = img.attr("abs:src")
            val w = img.attr("width").toIntOrNull() ?: 300
            if (src.isNotBlank() && w >= 150 && src.isImageUrl()) return src
        }
        return doc.select("img[src]").firstOrNull { it.attr("abs:src").isImageUrl() }
            ?.attr("abs:src") ?: ""
    }

    private fun extractImageFromHtml(html: String) = try {
        Jsoup.parse(html).select("img[src]").firstOrNull()?.attr("src") ?: ""
    } catch (e: Exception) { "" }

    private fun resolveUrl(imageUrl: String, baseUrl: String) = try {
        if (imageUrl.startsWith("http")) imageUrl
        else URI(baseUrl).resolve(imageUrl).toString()
    } catch (e: Exception) { imageUrl }

    private fun stripHtml(html: String) = try {
        Jsoup.parse(html).text().trim()
    } catch (e: Exception) {
        html.replace(Regex("<[^>]*>"), "").trim()
    }

    private fun String.isImageUrl(): Boolean {
        val l = this.lowercase()
        return l.contains(".jpg") || l.contains(".jpeg") || l.contains(".png") ||
               l.contains(".webp") || l.contains(".gif")  || l.contains(".avif")
    }
}
