package com.nexuzy.publisher.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * DuckDuckGo Instant Answer + HTML scrape client.
 *
 * Used by AiPipeline BEFORE the offline model writes, so the writer always has
 * the latest publicly available context for a news topic — not stale
 * training data from 2024.
 *
 * Strategy:
 *   1. DDG Instant Answer API  (/api.duckduckgo.com?q=...&format=json)
 *      → fast, returns AbstractText + RelatedTopics snippets + source URLs
 *   2. DDG HTML search scrape  (html.duckduckgo.com/?q=...)
 *      → fallback: extracts result snippets + href links from the HTML response
 *
 * Both are free, no API key required.
 * Returns a concise plaintext summary (≤ 1200 chars) + up to 5 source links
 * ready to inject into the offline model prompt.
 */
class DuckDuckGoSearchClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class SearchResult(
        val success: Boolean,
        val summary: String,
        val snippets: List<String> = emptyList(),
        /** Source URLs extracted alongside the snippets — used as article source links. */
        val links: List<String> = emptyList(),
        val error: String = ""
    )

    /**
     * Search for [query] and return a summary of current web context.
     * [maxChars] caps the returned summary so it fits inside a model prompt.
     */
    suspend fun search(query: String, maxChars: Int = 1200): SearchResult =
        searchWithLinks(query, maxChars)

    /**
     * Same as [search] but explicitly named to signal that [SearchResult.links]
     * is populated. AiPipeline calls this to get both the summary AND source URLs.
     */
    suspend fun searchWithLinks(query: String, maxChars: Int = 1200): SearchResult =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext SearchResult(false, "", error = "Empty query")

            val encoded = URLEncoder.encode(query.take(200), "UTF-8")

            // ── Strategy 1: DDG Instant Answer JSON API ──────────────────────
            try {
                val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "NexuzyPublisher/1.0 (Android news app)")
                    .get()
                    .build()

                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)

                    val snippets = mutableListOf<String>()
                    val links    = mutableListOf<String>()

                    // Abstract (Wikipedia-style summary)
                    val abstract = json.optString("AbstractText", "").trim()
                    if (abstract.isNotBlank()) snippets.add(abstract)

                    val abstractUrl = json.optString("AbstractURL", "").trim()
                    if (abstractUrl.isNotBlank()) links.add(abstractUrl)

                    // Answer (short factual answer)
                    val answer = json.optString("Answer", "").trim()
                    if (answer.isNotBlank()) snippets.add(answer)

                    // Related topics snippets + their URLs
                    val related = json.optJSONArray("RelatedTopics")
                    if (related != null) {
                        for (i in 0 until minOf(related.length(), 5)) {
                            val item = related.optJSONObject(i) ?: continue
                            val text = item.optString("Text", "").trim()
                            val href = item.optString("FirstURL", "").trim()
                            if (text.isNotBlank()) snippets.add(text)
                            if (href.isNotBlank() && !links.contains(href)) links.add(href)
                        }
                    }

                    if (snippets.isNotEmpty()) {
                        val summary = snippets.joinToString("\n").take(maxChars)
                        Log.i("DDGSearch", "DDG JSON success: ${snippets.size} snippets, ${links.size} links for '$query'")
                        return@withContext SearchResult(true, summary, snippets, links)
                    }
                }
            } catch (e: Exception) {
                Log.w("DDGSearch", "DDG JSON failed: ${e.message}")
            }

            // ── Strategy 2: DDG HTML search scrape ───────────────────────────
            try {
                val url = "https://html.duckduckgo.com/html/?q=$encoded"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                    .header("Accept", "text/html")
                    .get()
                    .build()

                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""
                    val (snippets, links) = extractHtmlSnippetsAndLinks(html, limit = 5)
                    if (snippets.isNotEmpty()) {
                        val summary = snippets.joinToString("\n").take(maxChars)
                        Log.i("DDGSearch", "DDG HTML fallback: ${snippets.size} snippets, ${links.size} links for '$query'")
                        return@withContext SearchResult(true, summary, snippets, links)
                    }
                }
            } catch (e: Exception) {
                Log.w("DDGSearch", "DDG HTML failed: ${e.message}")
            }

            Log.w("DDGSearch", "All DDG strategies failed for '$query'")
            SearchResult(false, "", error = "DuckDuckGo returned no results for: $query")
        }

    /**
     * Extracts both snippets and href links from DuckDuckGo HTML search results.
     * Targets class="result__snippet" and class="result__url" / result__a href.
     */
    private fun extractHtmlSnippetsAndLinks(html: String, limit: Int = 5): Pair<List<String>, List<String>> {
        val snippets = mutableListOf<String>()
        val links    = mutableListOf<String>()

        // Extract snippets
        val snippetPattern = Regex(
            """class=["']result__snippet["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        for (match in snippetPattern.findAll(html)) {
            val raw = match.groupValues[1]
            val clean = raw
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (clean.length > 30) snippets.add(clean)
            if (snippets.size >= limit) break
        }

        // Extract result links (href of result__a anchors pointing to real sites)
        val linkPattern = Regex(
            """class=["']result__a["'][^>]*href=["'](https?://[^"'>\s]+)["']""",
            setOf(RegexOption.IGNORE_CASE)
        )
        for (match in linkPattern.findAll(html)) {
            val href = match.groupValues[1].trim()
            if (href.isNotBlank() && !href.contains("duckduckgo.com")) links.add(href)
            if (links.size >= limit) break
        }

        return snippets to links
    }
}
