package com.nexuzy.publisher.network

import android.util.Log
import com.nexuzy.publisher.data.model.RssItem
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

class RssFeedParser {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetchFeed(feedUrl: String, feedName: String = "", feedCategory: String = ""): List<RssItem> {
        return try {
            val request = Request.Builder().url(feedUrl).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val xml = response.body?.string() ?: return emptyList()
                parseRssXml(xml, feedName, feedCategory)
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
                        "item", "entry" -> { inItem = true; title = ""; description = ""; link = ""; pubDate = ""; imageUrl = "" }
                        "title" -> if (inItem) title = parser.nextText().trim()
                        "description", "summary", "content" -> if (inItem && description.isEmpty()) description = parseHtmlStrip(parser.nextText())
                        "link" -> if (inItem && link.isEmpty()) {
                            val href = parser.getAttributeValue(null, "href")
                            link = href ?: parser.nextText().trim()
                        }
                        "pubdate", "published", "updated", "dc:date" -> if (inItem) pubDate = parser.nextText().trim()
                        "enclosure", "media:content", "media:thumbnail" -> {
                            if (inItem && imageUrl.isEmpty()) {
                                val url = parser.getAttributeValue(null, "url")
                                if (!url.isNullOrBlank()) imageUrl = url
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name.lowercase() in listOf("item", "entry") && inItem) {
                        if (title.isNotBlank() && link.isNotBlank()) {
                            items.add(RssItem(title, description, link, pubDate, imageUrl, feedName, feedCategory))
                        }
                        inItem = false
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e("RssParser", "Parse error: ${e.message}")
        }
        return items
    }

    private fun parseHtmlStrip(html: String): String {
        return html.replace(Regex("<[^>]*>"), "").trim()
    }
}
