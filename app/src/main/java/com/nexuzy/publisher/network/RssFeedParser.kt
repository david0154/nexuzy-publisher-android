package com.nexuzy.publisher.network

import android.util.Log
import com.nexuzy.publisher.data.model.RssItem
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
 * RSS Feed Parser with full article enrichment.
 *
 * Two-phase fetch:
 *
 * PHASE 1 — XML parsing:
 *   Extracts title, description, link, pubDate, imageUrl from the RSS XML.
 *   Image is taken from <enclosure>, <media:content>, <media:thumbnail>, or HTML in <content:encoded>.
 *
 * PHASE 2 — Article enrichment:
 *   For each item, fetches the article URL (item.link) ONCE and extracts:
 *     a) og:image / twitter:image / first <img> → fills imageUrl if still empty
 *     b) Full article body text                 → fills fullContent (used by Gemini)
 *
 *   Budget: up to MAX_ENRICH_ITEMS per feed fetch to limit total network time.
 *
 * IMAGE SKIP POLICY:
 *   After both phases, any article that STILL has no image URL is DROPPED.
 *   This ensures only visually rich articles are shown in the app.
 */
class RssFeedParser {

    companion object {
        private const val MAX_ENRICH_ITEMS = 20
        private const val MAX_CONTENT_CHARS = 4000
        private const val TAG = "RssParser"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Fetch and parse an RSS feed URL.
     * Returns enriched RssItems that have a non-blank imageUrl.
     * Articles without any image are automatically filtered out.
     *
     * @param feedUrl      URL of the RSS/Atom feed.
     * @param feedName     Human-readable feed name (stored in RssItem).
     * @param feedCategory Category label (stored in RssItem).
     * @param enrichItems  If true (default), fetch each article page for image + full content.
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
                // ── IMAGE SKIP: drop any article that has no image after enrichment ──
                val withImage = enriched.filter { it.imageUrl.isNotBlank() }
                Log.d(TAG, "Feed $feedName: ${items.size} parsed, ${enriched.size} enriched, ${withImage.size} with image")
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
            "article",
            ".entry-content",
            ".post-content",
            ".article-body",
            ".story-body",
            ".article__body",
            ".td-post-content",
            ".single-post-content",
            ".content-body",
            "[itemprop=articleBody]",
            ".article-text",
            "#article-body",
            "main"
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
