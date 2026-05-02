package com.nexuzy.publisher.network

import android.util.Log
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * ArticleGeneratorClient
 *
 * Generates a full news article draft when the user types a write/generate
 * command in the David AI chat.
 *
 * PIPELINE:
 *   Step 1 — Try Gemini (all 4 models × all saved keys)
 *   Step 2 — If ALL Gemini keys exhausted → fall back to Sarvam AI (sarvam-m)
 *   Step 3 — Sarvam grammar + humanise correction (whoever wrote it)
 *   Step 4 — OpenAI Key 2 clean + humanise output
 *   Step 5 — Return clean ArticleDraft to caller
 *
 * HUMAN WRITING FIX (v2):
 *   - Gemini temperature 0.8 → 1.05, topP 0.97, frequencyPenalty 0.4, presencePenalty 0.3
 *   - buildGeminiPrompt rewritten as journalist persona (no bullet-rule OUTPUT RULES block)
 *   - Sarvam fallback: corrected writeArticle() call args (rssTitle + rssDescription both = topic)
 *   - Both Gemini and Sarvam paths go through full grammar+humanise → OpenAI clean pipeline
 */
object ArticleGeneratorClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val MODEL_CHAIN = listOf(
        "gemini-2.0-flash",
        "gemini-1.5-flash",
        "gemini-2.0-flash-lite",
        "gemini-1.5-flash-8b"
    )

    data class ArticleDraft(
        val success: Boolean,
        val title: String      = "",
        val summary: String    = "",
        val content: String    = "",
        val imageQuery: String = "",
        val category: String   = "",
        val tags: String       = "",
        val writtenBy: String  = "",   // internal only — never shown to user
        val error: String      = ""
    ) {
        /** Formatted chat reply shown to user — no AI branding or internal labels */
        fun toChatReply(): String {
            if (!success) return "❌ Article generation failed: $error"
            return buildString {
                appendLine("📝 **Article Draft Generated**")
                appendLine()
                appendLine("📰 **Title:**")
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
                appendLine("✔️ *Ready to copy, edit, and publish on your WordPress site.*")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main entry point
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun generate(
        topic: String,
        weatherCtx: String = "",
        keyManager: ApiKeyManager
    ): ArticleDraft {
        if (topic.isBlank()) {
            return ArticleDraft(success = false, error = "Please specify a topic. Example: \"generate article about solar energy\"")
        }

        // STEP 1: Try Gemini
        val geminiKeys = keyManager.getGeminiKeys()
        var geminiRaw: String? = null
        if (geminiKeys.isNotEmpty()) {
            outer@ for (key in geminiKeys) {
                for (model in MODEL_CHAIN) {
                    val result = tryGemini(topic, weatherCtx, key, model)
                    if (result != null) { geminiRaw = result; break@outer }
                }
            }
        }

        // STEP 2: Sarvam fallback
        val (rawContent, writtenBy) = if (geminiRaw != null) {
            Pair(geminiRaw, "gemini")
        } else {
            Log.w("ArticleGenerator", "All Gemini keys exhausted — falling back to Sarvam AI")
            val sarvamClient = SarvamApiClient(keyManager)
            // Pass topic as both rssTitle and rssDescription so Sarvam has full context
            val sarvamResult = sarvamClient.writeArticle(
                rssTitle       = topic,
                rssDescription = topic,
                rssFullContent = "",
                category       = "General",
                maxWords       = 800
            )
            if (!sarvamResult.success || sarvamResult.content.isBlank()) {
                return ArticleDraft(success = false, error = "Both Gemini and Sarvam AI are unavailable. " +
                    "Please check your API keys in Settings.")
            }
            return buildSarvamDraft(topic, sarvamResult.content, keyManager)
        }

        // STEP 3: Parse Gemini JSON
        val parsed = parseGeminiJson(rawContent)
            ?: return ArticleDraft(success = false, error = "Failed to parse Gemini article JSON")

        // STEP 4: Sarvam grammar + humanise
        val sarvamClient  = SarvamApiClient(keyManager)
        val grammarResult = sarvamClient.checkGrammarAndSpelling(parsed.content)
        val grammarChecked = if (grammarResult.success) grammarResult.correctedText else parsed.content

        // STEP 5: OpenAI Key 2 clean + humanise
        val openAiClient = OpenAiApiClient(keyManager)
        val cleanResult  = openAiClient.cleanArticleOutput(parsed.title, grammarChecked)
        val finalContent = if (cleanResult.success && cleanResult.cleanedContent.isNotBlank())
            cleanResult.cleanedContent else grammarChecked

        return parsed.copy(content = finalContent, writtenBy = writtenBy)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sarvam draft builder
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun buildSarvamDraft(
        topic: String,
        rawText: String,
        keyManager: ApiKeyManager
    ): ArticleDraft {
        val lines  = rawText.trim().lines().filter { it.isNotBlank() }
        val title  = lines.firstOrNull() ?: topic
        val body   = if (lines.size > 1) lines.drop(1).joinToString("\n\n") else rawText

        // Grammar + humanise
        val sarvamClient  = SarvamApiClient(keyManager)
        val grammarResult = sarvamClient.checkGrammarAndSpelling(body)
        val grammarChecked = if (grammarResult.success) grammarResult.correctedText else body

        // OpenAI clean + humanise
        val openAiClient = OpenAiApiClient(keyManager)
        val cleanResult  = openAiClient.cleanArticleOutput(title, grammarChecked)
        val finalContent = if (cleanResult.success && cleanResult.cleanedContent.isNotBlank())
            cleanResult.cleanedContent else grammarChecked

        return ArticleDraft(
            success    = true,
            title      = title,
            summary    = finalContent.take(200).trimEnd() + "…",
            content    = finalContent,
            imageQuery = "$topic news photo",
            category   = "General",
            tags       = topic.split(" ").take(5).joinToString(", "),
            writtenBy  = "sarvam"
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gemini API call
    // ─────────────────────────────────────────────────────────────────────────

    private fun tryGemini(
        topic: String,
        weatherCtx: String,
        apiKey: String,
        model: String
    ): String? {
        return try {
            val weatherLine = if (weatherCtx.isNotBlank()) "\n\nLocal context: $weatherCtx" else ""
            val bodyJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", buildGeminiPrompt(topic, weatherLine)))
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature",      1.05)
                    put("topP",             0.97)
                    put("topK",             50)
                    put("maxOutputTokens",  2048)
                    put("frequencyPenalty", 0.4)
                    put("presencePenalty",  0.3)
                })
            }
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val response = http.newCall(
                Request.Builder().url(url)
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("User-Agent", "NexuzyPublisher/2.0")
                    .build()
            ).execute()
            if (!response.isSuccessful) return null
            val raw  = response.body?.string() ?: return null
            val text = JSONObject(raw)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0)
                .getString("text").trim()
            if (text.isBlank()) null else text
        } catch (e: Exception) { null }
    }

    private fun buildGeminiPrompt(topic: String, weatherLine: String): String {
        val today = SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH).format(Date())
        return """
You are a seasoned news journalist with 15 years of experience writing for major digital publications. Today is $today.

Write a complete, publish-ready news article about: $topic$weatherLine

Your writing style:
- Sharp, direct, human voice. Vary sentence lengths. Some short. Some longer.
- SEO headline first — specific and punchy, no colons, no "How", no "Why".
- First sentence drops straight into the story. No "In a significant development" or "As the world watches".
- Never use: "Furthermore", "Moreover", "In conclusion", "It is worth noting", "Delve into", "This underscores", "Notably", "Landscape", "Pivotal", "Navigate", "In an era of".
- No section headers. No bullet points. Pure flowing prose.
- End naturally with a forward-looking observation or real quote.

Return your response as a JSON object ONLY (no text before or after the JSON):
{
  "title": "SEO headline (max 70 chars)",
  "summary": "2-3 sentence meta description",
  "content": "Full article body as plain text, 800-1000 words, natural paragraphs separated by \\n\\n",
  "imageQuery": "5-8 word image search query",
  "category": "Technology/Business/Politics/Sports/Entertainment/Health/Science/Environment/General",
  "tags": "5-8 comma-separated SEO tags"
}
        """.trimIndent()
    }

    private fun parseGeminiJson(raw: String): ArticleDraft? {
        return try {
            val cleaned = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val parsed  = JSONObject(cleaned)
            ArticleDraft(
                success    = true,
                title      = parsed.optString("title", ""),
                summary    = parsed.optString("summary", ""),
                content    = parsed.optString("content", ""),
                imageQuery = parsed.optString("imageQuery", ""),
                category   = parsed.optString("category", "General"),
                tags       = parsed.optString("tags", "")
            )
        } catch (e: Exception) { null }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Topic extractor
    // ─────────────────────────────────────────────────────────────────────────

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
