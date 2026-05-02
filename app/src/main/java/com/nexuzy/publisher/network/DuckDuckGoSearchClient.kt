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
 * Used by AiPipeline BEFORE Gemini writes, so the writer always has
 * the latest publicly available context for a news topic — not stale
 * training data from 2024.
 *
 * Strategy:
 *   1. DDG Instant Answer API  (/api.duckduckgo.com?q=...&format=json)
 *      → fast, returns AbstractText + RelatedTopics snippets
 *   2. DDG HTML search scrape  (html.duckduckgo.com/?q=...)
 *      → fallback: extracts result snippets from the HTML response
 *
 * Both are free, no API key required.
 * Returns a concise plaintext summary (≤ 1200 chars) ready to inject into the Gemini prompt.
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
        val error: String = ""
    )

    /**
     * Search for [query] and return a summary of current web context.
     * [maxChars] caps the returned summary so it fits inside a Gemini prompt.
     */
    suspend fun search(query: String, maxChars: Int = 1200): SearchResult =
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

                    // Abstract (Wikipedia-style summary)
                    val abstract = json.optString("AbstractText", "").trim()
                    if (abstract.isNotBlank()) snippets.add(abstract)

                    // Answer (short factual answer)
                    val answer = json.optString("Answer", "").trim()
                    if (answer.isNotBlank()) snippets.add(answer)

                    // Related topics snippets
                    val related = json.optJSONArray("RelatedTopics")
                    if (related != null) {
                        for (i in 0 until minOf(related.length(), 5)) {
                            val item = related.optJSONObject(i) ?: continue
                            val text = item.optString("Text", "").trim()
                            if (text.isNotBlank()) snippets.add(text)
                        }
                    }

                    if (snippets.isNotEmpty()) {
                        val summary = snippets.joinToString("\n").take(maxChars)
                        Log.i("DDGSearch", "DDG JSON success: ${snippets.size} snippets for '$query'")
                        return@withContext SearchResult(true, summary, snippets)
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
                    val snippets = extractHtmlSnippets(html).take(5)
                    if (snippets.isNotEmpty()) {
                        val summary = snippets.joinToString("\n").take(maxChars)
                        Log.i("DDGSearch", "DDG HTML fallback: ${snippets.size} snippets for '$query'")
                        return@withContext SearchResult(true, summary, snippets)
                    }
                }
            } catch (e: Exception) {
                Log.w("DDGSearch", "DDG HTML failed: ${e.message}")
            }

            Log.w("DDGSearch", "All DDG strategies failed for '$query'")
            SearchResult(false, "", error = "DuckDuckGo returned no results for: $query")
        }

    /**
     * Simple regex-based extractor for DuckDuckGo HTML search result snippets.
     * Targets the <a class="result__snippet"> elements in the HTML response.
     */
    private fun extractHtmlSnippets(html: String): List<String> {
        val snippets = mutableListOf<String>()
        // DDG HTML search results use class="result__snippet"
        val pattern = Regex(
            """class=["']result__snippet["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        for (match in pattern.findAll(html)) {
            val raw = match.groupValues[1]
            val clean = raw
                .replace(Regex("<[^>]+>"), "")       // strip HTML tags
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (clean.length > 30) snippets.add(clean)
        }
        return snippets
    }
}
