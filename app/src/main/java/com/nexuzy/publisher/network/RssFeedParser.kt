package com.nexuzy.publisher.network

import android.util.Log
import com.nexuzy.publisher.data.model.RssItem
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URI
import java.util.concurrent.TimeUnit

class RssFeedParser {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Fetch and parse an RSS feed URL.
     * Returns a list of RssItem with images resolved via:
     *   1. <enclosure> / <media:content> / <media:thumbnail> in RSS XML
     *   2. Jsoup og:image scrape of the article page as fallback
     */
    suspend fun fetchFeed(
        feedUrl: String,
        feedName: String = "",
        feedCategory: String = "",
        scrapeImages: Boolean = true
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
                if (scrapeImages) resolveImages(items) else items
            } else {
                Log.w("RssParser", "HTTP ${response.code} for $feedUrl")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("RssParser", "Error fetching $feedUrl: ${e.message}")
            emptyList()
        }
    }

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
                                // extract image from HTML content before stripping
                                if (imageUrl.isEmpty()) {
                                    imageUrl = extractImageFromHtml(raw)
                                }
                                if (description.isEmpty()) description = stripHtml(raw)
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
                                val url = parser.getAttributeValue(null, "url") ?: ""
                                val type = parser.getAttributeValue(null, "type") ?: ""
                                if (url.isNotBlank() && (type.startsWith("image") || url.isImageUrl())) {
                                    imageUrl = url
                                }
                            }
                        }
                        "media:content", "media:thumbnail" -> {
                            if (inItem && imageUrl.isEmpty()) {
                                val url = parser.getAttributeValue(null, "url") ?: ""
                                val medium = parser.getAttributeValue(null, "medium") ?: ""
                                if (url.isNotBlank() && (medium == "image" || url.isImageUrl() || medium.isEmpty())) {
                                    imageUrl = url
                                }
                            }
                        }
                        "image", "logo" -> {
                            // Some feeds put item image here
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.lowercase() in listOf("item", "entry") && inItem) {
                            if (title.isNotBlank() && link.isNotBlank()) {
                                items.add(
                                    RssItem(
                                        title = title,
                                        description = description,
                                        link = link,
                                        pubDate = pubDate,
                                        imageUrl = imageUrl,
                                        feedName = feedName,
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
            Log.e("RssParser", "Parse error: ${e.message}")
        }
        return items
    }

    /**
     * For items missing an image, scrape the article URL for og:image / twitter:image / first <img>
     */
    private fun resolveImages(items: List<RssItem>): List<RssItem> {
        return items.map { item ->
            if (item.imageUrl.isNotBlank()) return@map item
            try {
                val scraped = scrapeImageFromUrl(item.link)
                if (scraped.isNotBlank()) item.copy(imageUrl = scraped) else item
            } catch (e: Exception) {
                item
            }
        }
    }

    /**
     * Scrape og:image or first meaningful <img> from an article page.
     */
    private fun scrapeImageFromUrl(url: String): String {
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 NexuzyBot/2.0")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return ""
            val html = response.body?.string() ?: return ""
            val doc = Jsoup.parse(html, url)

            // Priority 1: og:image
            val ogImage = doc.select("meta[property=og:image]").attr("content")
            if (ogImage.isNotBlank()) return resolveUrl(ogImage, url)

            // Priority 2: twitter:image
            val twImage = doc.select("meta[name=twitter:image]").attr("content")
            if (twImage.isNotBlank()) return resolveUrl(twImage, url)

            // Priority 3: first article img with width > 200
            val imgs = doc.select("article img, .entry-content img, .post-content img, main img")
            for (img in imgs) {
                val src = img.attr("abs:src")
                val w = img.attr("width").toIntOrNull() ?: 300
                if (src.isNotBlank() && w >= 200) return src
            }

            // Priority 4: any img
            val anyImg = doc.select("img[src]").firstOrNull { it.attr("abs:src").isImageUrl() }
            anyImg?.attr("abs:src") ?: ""
        } catch (e: Exception) {
            Log.d("RssParser", "Image scrape failed for $url: ${e.message}")
            ""
        }
    }

    private fun extractImageFromHtml(html: String): String {
        return try {
            val doc = Jsoup.parse(html)
            val img = doc.select("img[src]").firstOrNull()
            img?.attr("src") ?: ""
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
               lower.contains(".png") || lower.contains(".webp") ||
               lower.contains(".gif") || lower.contains(".avif")
    }
}
