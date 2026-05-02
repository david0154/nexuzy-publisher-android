package com.nexuzy.publisher.network

import android.util.Log
import com.nexuzy.publisher.data.model.RssItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * RSS Feed Parser — batch-fetch optimised.
 *
 * BATCH STRATEGY:
 *   - fetchAllFeeds() accepts any number of feed URLs.
 *   - Feeds are processed in BATCHES of BATCH_SIZE (20) concurrently.
 *   - Hard cap: only the first MAX_FEEDS (100) feed URLs are ever used.
 *   - Each batch is launched in parallel with coroutineScope + async.
 *   - Within each feed, up to MAX_ENRICH_ITEMS (20) articles are enriched.
 *
 * TWO-PHASE PER FEED:
 *   Phase 1 — XML: title, description, link, pubDate, imageUrl from RSS XML.
 *   Phase 2 — Enrich: fetch article page for og:image + full body text.
 *
 * IMAGE SKIP: articles with no image after both phases are dropped.
 */
class RssFeedParser {

    companion object {
        private const val BATCH_SIZE       = 20   // feeds fetched concurrently per batch
        private const val MAX_FEEDS        = 100  // hard cap on total RSS URLs processed
        private const val MAX_ENRICH_ITEMS = 20   // max articles enriched per feed
        private const val MAX_CONTENT_CHARS = 4000
        private const val TAG              = "RssParser"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC: batch-fetch many feeds
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetch multiple RSS feeds in parallel batches of [BATCH_SIZE].
     * Maximum [MAX_FEEDS] URLs are processed (extras ignored).
     *
     * @param feeds List of Triple(feedUrl, feedName, feedCategory)
     * @param enrichItems Whether to enrich each article (fetch page for image/content)
     * @return Combined, deduplicated list of RssItems sorted newest-first
     */
    suspend fun fetchAllFeeds(
        feeds: List<Triple<String, String, String>>,
        enrichItems: Boolean = true
    ): List<RssItem> = coroutineScope {

        val capped = feeds.take(MAX_FEEDS)
        val allItems = mutableListOf<RssItem>()

        // Process in batches of BATCH_SIZE
        capped.chunked(BATCH_SIZE).forEachIndexed { batchIdx, batch ->
            Log.d(TAG, "Batch ${batchIdx + 1}: fetching ${batch.size} feeds concurrently")
            val batchResults = batch.map { (url, name, category) ->
                async {
                    fetchFeed(url, name, category, enrichItems)
                }
            }.awaitAll()
            batchResults.forEach { allItems.addAll(it) }
            Log.d(TAG, "Batch ${batchIdx + 1} done. Total so far: ${allItems.size}")
        }

        // Deduplicate by link, sort newest first (pubDate desc, then insertion order)
        allItems
            .distinctBy { it.link }
            .sortedByDescending { it.pubDate }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC: single feed fetch
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetch and parse a single RSS feed URL.
     * Returns enriched RssItems that have a non-blank imageUrl.
     */
    suspend fun fetchFeed(
        feedUrl: String,
        feedName: String = "",
        feedCategory: String = "",
        enrichItems: Boolean = true
    ): List<RssItem> {
        return try {
            val request = Request.Builder()
                .url(feedUrl)
                .addHeader("User-Agent", "NexuzyPublisher/2.0 Android RSS Reader")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val xml = response.body?.string() ?: return emptyList()
                val items = parseRssXml(xml, feedName, feedCategory)
                val enriched = if (enrichItems) enrichItems(items) else items
                val withImage = enriched.filter { it.imageUrl.isNotBlank() }
                Log.d(TAG, "$feedName: ${items.size} parsed → ${withImage.size} with image")
                withImage
            } else {
                Log.w(TAG, "HTTP ${response.code} for $feedUrl")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching $feedUrl: ${e.message}")
            emptyList()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PHASE 1: Parse RSS/Atom XML
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseRssXml(xml: String, feedName: String, feedCategory: String): List<RssItem> {
        val items = mutableListOf<RssItem>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
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
                        "title" -> if (inItem) title = parser.nextText().trim()

                        "description", "summary", "content", "content:encoded" -> {
                            if (inItem) {
                                val raw = parser.nextText()
                                if (imageUrl.isEmpty()) imageUrl = extractImageFromHtml(raw)
                                if (description.isEmpty()) description = stripHtml(raw).take(500)
                            }
                        }

                        "link" -> if (inItem && link.isEmpty()) {
                            val href = parser.getAttributeValue(null, "href")
                            link = href?.trim() ?: parser.nextText().trim()
                        }

                        "pubdate", "published", "updated", "dc:date" -> {
                            if (inItem) pubDate = parser.nextText().trim()
                        }

                        "enclosure" -> {
                            if (inItem && imageUrl.isEmpty()) {
                                val url  = parser.getAttributeValue(null, "url") ?: ""
                                val type = parser.getAttributeValue(null, "type") ?: ""
                                if (url.isNotBlank() && (type.startsWith("image") || url.isImageUrl())) {
                                    imageUrl = url
                                }
                            }
                        }

                        "media:content", "media:thumbnail" -> {
                            if (inItem && imageUrl.isEmpty()) {
                                val url    = parser.getAttributeValue(null, "url") ?: ""
                                val medium = parser.getAttributeValue(null, "medium") ?: ""
                                if (url.isNotBlank() &&
                                    (medium == "image" || url.isImageUrl() || medium.isEmpty())) {
                                    imageUrl = url
                                }
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (parser.name.lowercase() in listOf("item", "entry") && inItem) {
                            if (title.isNotBlank() && link.isNotBlank()) {
                                items.add(
                                    RssItem(
                                        title        = title,
                                        description  = description,
                                        link         = link,
                                        pubDate      = pubDate,
                                        imageUrl     = imageUrl,
                                        feedName     = feedName,
                                        feedCategory = feedCategory
                                    )
                                )
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

    // ─────────────────────────────────────────────────────────────────────────
    // PHASE 2: Enrich items — fetch article page for IMAGE + FULL CONTENT
    // ─────────────────────────────────────────────────────────────────────────

    private fun enrichItems(items: List<RssItem>): List<RssItem> {
        var budget = MAX_ENRICH_ITEMS
        return items.map { item ->
            if (budget <= 0) return@map item
            if (item.link.isBlank() || !item.link.startsWith("http")) return@map item
            budget--
            try {
                enrichItemFromPage(item)
            } catch (e: Exception) {
                Log.d(TAG, "Enrich failed for ${item.link}: ${e.message}")
                item
            }
        }
    }

    private fun enrichItemFromPage(item: RssItem): RssItem {
        val request = Request.Builder()
            .url(item.link)
            .addHeader("User-Agent", "Mozilla/5.0 NexuzyPublisher/2.0 Android")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return item

        val html = response.body?.string() ?: return item
        val doc  = Jsoup.parse(html, item.link)

        val resolvedImage = if (item.imageUrl.isBlank()) {
            extractImageFromDoc(doc, item.link)
        } else {
            item.imageUrl
        }

        val fullContent = extractArticleBody(doc)

        return item.copy(
            imageUrl    = resolvedImage,
            fullContent = fullContent
        )
    }

    private fun extractArticleBody(doc: Document): String {
        val selectors = listOf(
            "article", ".entry-content", ".post-content", ".article-body",
            ".story-body", ".article__body", ".td-post-content",
            ".single-post-content", ".content-body", "[itemprop=articleBody]",
            ".article-text", "#article-body", "main"
        )
        for (selector in selectors) {
            val el = doc.selectFirst(selector) ?: continue
            el.select("nav, aside, .related, .comments, script, style, .advertisement, .ad, .social-share").remove()
            val text = el.text().trim()
            if (text.length > 200) return text.take(MAX_CONTENT_CHARS)
        }
        doc.select("nav, header, footer, aside, script, style").remove()
        return doc.body()?.text()?.trim()?.take(MAX_CONTENT_CHARS) ?: ""
    }

    private fun extractImageFromDoc(doc: Document, baseUrl: String): String {
        val ogImage = doc.select("meta[property=og:image]").attr("content")
        if (ogImage.isNotBlank()) return resolveUrl(ogImage, baseUrl)

        val twImage = doc.select("meta[name=twitter:image]").attr("content")
        if (twImage.isNotBlank()) return resolveUrl(twImage, baseUrl)

        val imgs = doc.select("article img, .entry-content img, .post-content img, main img")
        for (img in imgs) {
            val src = img.attr("abs:src")
            val w   = img.attr("width").toIntOrNull() ?: 300
            if (src.isNotBlank() && w >= 200 && src.isImageUrl()) return src
        }

        return doc.select("img[src]").firstOrNull { it.attr("abs:src").isImageUrl() }
            ?.attr("abs:src") ?: ""
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun extractImageFromHtml(html: String): String {
        return try {
            Jsoup.parse(html).select("img[src]").firstOrNull()?.attr("src") ?: ""
        } catch (e: Exception) { "" }
    }

    private fun resolveUrl(imageUrl: String, baseUrl: String): String {
        return try {
            if (imageUrl.startsWith("http")) imageUrl
            else URI(baseUrl).resolve(imageUrl).toString()
        } catch (e: Exception) { imageUrl }
    }

    private fun stripHtml(html: String): String {
        return try {
            Jsoup.parse(html).text().trim()
        } catch (e: Exception) {
            html.replace(Regex("<[^>]*>"), "").trim()
        }
    }

    private fun String.isImageUrl(): Boolean {
        val lower = this.lowercase()
        return lower.contains(".jpg") || lower.contains(".jpeg") ||
               lower.contains(".png")  || lower.contains(".webp") ||
               lower.contains(".gif")  || lower.contains(".avif")
    }
}
