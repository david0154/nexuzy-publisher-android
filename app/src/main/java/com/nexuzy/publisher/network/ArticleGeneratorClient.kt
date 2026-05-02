package com.nexuzy.publisher.network

import android.util.Log
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ArticleGeneratorClient
 *
 * Generates a full news article draft using Gemini AI when the user types
 * a generate/write command in the David AI chat.
 *
 * Trigger phrases (case-insensitive, checked in DavidAiChatActivity):
 *   "generate article", "write article", "create article",
 *   "generate news", "write news", "new article about", "article about"
 *
 * Returns [ArticleDraft] with:
 *   - title        : Compelling SEO headline
 *   - summary      : 2-3 sentence excerpt for meta description
 *   - content      : Full HTML article body (800-1200 words)
 *   - imageQuery   : Descriptive search query to find a relevant image
 *   - category     : Detected category (Technology, Sports, etc.)
 *   - tags         : Comma-separated SEO tags
 */
object ArticleGeneratorClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class ArticleDraft(
        val success: Boolean,
        val title: String        = "",
        val summary: String      = "",
        val content: String      = "",
        val imageQuery: String   = "",
        val category: String     = "",
        val tags: String         = "",
        val error: String        = ""
    ) {
        /** Formatted chat reply shown to user after article generation */
        fun toChatReply(): String {
            if (!success) return "❌ Article generation failed: $error"
            return buildString {
                appendLine("📝 **Article Draft Generated**")
                appendLine()
                appendLine("📑 **Title:**")
                appendLine(title)
                appendLine()
                appendLine("📋 **Summary:**")
                appendLine(summary)
                appendLine()
                appendLine("🖼️ **Image Search Reference:**")
                appendLine(imageQuery)
                appendLine()
                appendLine("🏷️ **Category:** $category")
                appendLine("🔗 **Tags:** $tags")
                appendLine()
                appendLine("————————————————————")
                appendLine("📰 **Full Article Content:**")
                appendLine()
                appendLine(content)
                appendLine()
                appendLine("✔️ *This article draft is ready to copy, edit, and publish on your WordPress site.*")
            }
        }
    }

    /**
     * Generates a full article draft from a user topic/command.
     *
     * @param topic       The subject the user wants to write about (extracted from command)
     * @param weatherCtx  Optional weather context string (from WeatherClient) to enrich local articles
     * @param apiKey      Gemini API key from ApiKeyManager
     */
    suspend fun generate(
        topic: String,
        weatherCtx: String = "",
        apiKey: String
    ): ArticleDraft {
        if (apiKey.isBlank()) {
            return ArticleDraft(success = false, error = "Gemini API key not configured. Please add it in Settings.")
        }
        if (topic.isBlank()) {
            return ArticleDraft(success = false, error = "Please specify a topic. Example: \"generate article about solar energy\"")
        }

        val weatherLine = if (weatherCtx.isNotBlank())
            "\n\nContext (use if relevant): $weatherCtx" else ""

        val prompt = """
You are a professional news journalist and SEO expert. Generate a complete, original news article draft.

TOPIC: $topic$weatherLine

Respond ONLY with a valid JSON object in this exact format (no markdown, no code fences):
{
  "title": "Compelling SEO headline for the article (max 70 chars)",
  "summary": "2-3 sentence meta description / article excerpt that hooks the reader",
  "content": "Full HTML article body. Use <h2>, <p>, <ul>/<li> tags. 800-1200 words. Original, factual, well-structured. Include an intro, 3-4 body sections with subheadings, and a conclusion.",
  "imageQuery": "A specific descriptive search query (5-8 words) to find a relevant, copyright-free image for this article on Wikipedia or Wikimedia Commons",
  "category": "Single category: Technology / Business / Politics / Sports / Entertainment / Health / Science / Environment / General",
  "tags": "5-8 comma-separated SEO tags relevant to this article"
}
""".trimIndent()

        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"

            val bodyJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", prompt))
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.8)
                    put("maxOutputTokens", 2048)
                })
            }

            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("User-Agent", "NexuzyPublisher/2.0")
                .build()

            val response = http.newCall(request).execute()
            if (!response.isSuccessful) {
                return ArticleDraft(success = false, error = "Gemini API error: HTTP ${response.code}")
            }

            val raw = response.body?.string() ?: return ArticleDraft(success = false, error = "Empty response from Gemini")
            val responseJson = JSONObject(raw)
            val text = responseJson
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()

            // Strip code fences if Gemini wraps in ```json ... ```
            val cleaned = text
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```")
                .trim()

            val parsed = JSONObject(cleaned)
            ArticleDraft(
                success    = true,
                title      = parsed.optString("title", ""),
                summary    = parsed.optString("summary", ""),
                content    = parsed.optString("content", ""),
                imageQuery = parsed.optString("imageQuery", topic),
                category   = parsed.optString("category", "General"),
                tags       = parsed.optString("tags", "")
            )
        } catch (e: Exception) {
            Log.e("ArticleGeneratorClient", "Generation failed: ${e.message}")
            ArticleDraft(success = false, error = e.message ?: "Unknown error")
        }
    }

    /**
     * Detects if the user's message is an article generation command.
     * Returns the extracted topic string, or null if not a generate command.
     */
    fun extractTopic(message: String): String? {
        val lower = message.lowercase().trim()
        val triggers = listOf(
            "generate article about ", "generate article on ",
            "write article about ",   "write article on ",
            "create article about ",  "create article on ",
            "generate news about ",   "generate news on ",
            "write news about ",      "write news on ",
            "new article about ",     "new article on ",
            "article about ",         "article on ",
            "generate article",       "write article",
            "create article",         "generate news"
        )
        for (trigger in triggers) {
            if (lower.startsWith(trigger)) {
                val topic = message.substring(trigger.length).trim()
                return topic.ifBlank { message }
            }
            if (lower == trigger.trim()) return ""
        }
        return null
    }
}
