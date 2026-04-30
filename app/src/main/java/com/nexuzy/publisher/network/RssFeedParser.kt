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
 * PHASE 2 — Article enrichment (new):
 *   For each item, fetches the article URL (item.link) ONCE and extracts:
 *     a) og:image / twitter:image / first <img> → fills imageUrl if still empty
 *     b) Full article body text             → fills fullContent (used by Gemini for rewriting)
 *
 *   Budget: up to MAX_ENRICH_ITEMS per feed fetch to limit total network time.
 *   Articles that already have both imageUrl AND description skip the fetch.
 */
class RssFeedParser {

    companion object {
        /** Max items to fetch the article page for (image + full content). */
        private const val MAX_ENRICH_ITEMS = 20

        /** Max chars of full article content to keep (Gemini token budget). */
        private const val MAX_CONTENT_CHARS = 4000
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Fetch and parse an RSS feed URL.
     * Returns enriched RssItems: each item has imageUrl and fullContent populated.
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
                if (enrichItems) enrichItems(items) else items
            } else {
                Log.w("RssParser", "HTTP ${response.code} for $feedUrl")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("RssParser", "Error fetching $feedUrl: ${e.message}")
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

                        // RSS description / Atom summary / full content:encoded
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

                        // Image from <enclosure url="..." type="image/...">
                        "enclosure" -> {
                            if (inItem && imageUrl.isEmpty()) {
                                val url  = parser.getAttributeValue(null, "url") ?: ""
                                val type = parser.getAttributeValue(null, "type") ?: ""
                                if (url.isNotBlank() && (type.startsWith("image") || url.isImageUrl())) {
                                    imageUrl = url
                                }
                            }
                        }

                        // Image from <media:content url="..."> or <media:thumbnail url="...">
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
                                        // fullContent filled in Phase 2
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
            Log.e("RssParser", "Parse error: ${e.message}")
        }
        return items
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PHASE 2: Enrich items — fetch article page for IMAGE + FULL CONTENT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * For each item that needs enrichment (missing image OR fullContent is empty),
     * fetches the article URL ONCE and extracts both the image and full article body.
     *
     * Limited to MAX_ENRICH_ITEMS fetches per call to keep total time reasonable.
     * Items that already have an image AND a non-blank description are still enriched
     * for fullContent (the article body), but with lower priority in the budget.
     */
    private fun enrichItems(items: List<RssItem>): List<RssItem> {
        var budget = MAX_ENRICH_ITEMS
        return items.map { item ->
            if (budget <= 0) return@map item
            // Skip if link is empty or not http
            if (item.link.isBlank() || !item.link.startsWith("http")) return@map item

            // Always enrich — we always want fullContent for Gemini
            budget--
            try {
                val enriched = enrichItemFromPage(item)
                enriched
            } catch (e: Exception) {
                Log.d("RssParser", "Enrich failed for ${item.link}: ${e.message}")
                item
            }
        }
    }

    /**
     * Fetch the article page ONCE and extract:
     *   1. og:image / twitter:image / first article <img>  → imageUrl (if currently empty)
     *   2. Full article body text                          → fullContent (always)
     *
     * The article body is extracted in priority order:
     *   <article>, .entry-content, .post-content, .article-body,
     *   .story-body, .article__body, main, body (last resort)
     */
    private fun enrichItemFromPage(item: RssItem): RssItem {
        val request = Request.Builder()
            .url(item.link)
            .addHeader("User-Agent", "Mozilla/5.0 NexuzyPublisher/2.0 Android")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return item

        val html = response.body?.string() ?: return item
        val doc  = Jsoup.parse(html, item.link)

        // ── Image extraction (only if item doesn't have one yet) ──
        val resolvedImage = if (item.imageUrl.isBlank()) {
            extractImageFromDoc(doc, item.link)
        } else {
            item.imageUrl
        }

        // ── Full article body extraction ──
        val fullContent = extractArticleBody(doc)

        return item.copy(
            imageUrl    = resolvedImage,
            fullContent = fullContent
        )
    }

    /**
     * Extract the best article body text from a parsed HTML document.
     * Tries common CMS selectors before falling back to <main> or <body>.
     * Returns plain text (no HTML tags), max MAX_CONTENT_CHARS characters.
     */
    private fun extractArticleBody(doc: Document): String {
        val selectors = listOf(
            "article",                    // semantic article tag
            ".entry-content",             // WordPress classic
            ".post-content",              // common blog theme
            ".article-body",              // news sites
            ".story-body",                // BBC, Guardian style
            ".article__body",             // BEM naming
            ".td-post-content",           // TagDiv theme
            ".single-post-content",
            ".content-body",
            "[itemprop=articleBody]",      // schema.org
            ".article-text",
            "#article-body",
            "main"
        )

        for (selector in selectors) {
            val el = doc.selectFirst(selector) ?: continue
            // Remove nav, ads, related, comments, scripts from the selected element
            el.select("nav, aside, .related, .comments, script, style, .advertisement, .ad, .social-share").remove()
            val text = el.text().trim()
            if (text.length > 200) {
                return text.take(MAX_CONTENT_CHARS)
            }
        }

        // Final fallback: body text (noisy but better than nothing)
        doc.select("nav, header, footer, aside, script, style").remove()
        val bodyText = doc.body()?.text()?.trim() ?: ""
        return bodyText.take(MAX_CONTENT_CHARS)
    }

    /**
     * Extract the best image URL from a parsed article page.
     * Priority: og:image > twitter:image > article img (w>=200) > any img.
     */
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

    /** Extract first image src from an HTML string (e.g. from content:encoded). */
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
