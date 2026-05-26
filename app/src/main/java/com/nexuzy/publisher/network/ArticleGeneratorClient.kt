package com.nexuzy.publisher.network

import android.content.Context
import android.util.Log
import com.nexuzy.publisher.ai.OfflineArticleWriter
import com.nexuzy.publisher.ai.OfflineGemmaClient
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
 * PIPELINE (v3 — Sarvam removed, Devil AI 2B integrated):
 *   Step 1 — Try Gemini (all 4 models x all saved keys)
 *   Step 2 — If ALL Gemini keys exhausted → Offline Gemma 2B (Devil AI, 100% on-device)
 *   Step 3 — If offline model not ready → OpenAI fallback
 *   Step 4 — Gemini flash-lite grammar + humanise polish (replaces broken Sarvam)
 *   Step 5 — OpenAI Key 2 final clean pass
 *   Step 6 — Return clean ArticleDraft to caller
 *
 * Sarvam AI has been removed from this pipeline.
 * SarvamApiClient / SarvamChatClient are kept for future use but not called here.
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

    // Gemini model used for grammar + humanise polish (cheapest/fastest)
    private const val POLISH_MODEL = "gemini-2.0-flash-lite"

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
        /** Formatted chat reply shown to user */
        fun toChatReply(): String {
            if (!success) return "\u274c Article generation failed: $error"
            return buildString {
                appendLine("📝 **Article Draft Generated**")
                appendLine()
                appendLine("📰 **Title:**")
                appendLine(title)
                appendLine()
                appendLine("📋 **Summary:**")
                appendLine(summary)
                appendLine()
                appendLine("🖼\ufe0f **Image Search Reference:**")
                appendLine(imageQuery)
                appendLine()
                appendLine("🏷\ufe0f **Category:** $category")
                appendLine("🔗 **Tags:** $tags")
                appendLine()
                appendLine("————————————————————")
                appendLine("📰 **Full Article Content:**")
                appendLine()
                appendLine(content)
                appendLine()
                appendLine("✔\ufe0f *Ready to copy, edit, and publish on your WordPress site.*")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main entry point
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun generate(
        topic: String,
        weatherCtx: String = "",
        keyManager: ApiKeyManager,
        context: Context? = null
    ): ArticleDraft {
        if (topic.isBlank()) {
            return ArticleDraft(
                success = false,
                error   = "Please specify a topic. Example: \"generate article about solar energy\""
            )
        }

        // ── STEP 1: Try Gemini ────────────────────────────────────────────────
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

        // ── STEP 2: Offline Gemma 2B (Devil AI) fallback ─────────────────────
        if (geminiRaw == null && context != null) {
            Log.w("ArticleGenerator", "All Gemini keys exhausted — trying Devil AI 2B offline")
            val offlineResult = tryOfflineGemma(topic, context)
            if (offlineResult != null) {
                return polishAndReturn(offlineResult, topic, "devil-ai-2b", keyManager)
            }
        }

        // ── STEP 3: OpenAI fallback ───────────────────────────────────────────
        if (geminiRaw == null) {
            Log.w("ArticleGenerator", "Devil AI 2B not ready — trying OpenAI fallback")
            val openAiClient = OpenAiApiClient(keyManager)
            val openAiResult = openAiClient.generateArticle(topic)
            if (openAiResult.success && openAiResult.cleanedContent.isNotBlank()) {
                return buildPlainDraft(topic, openAiResult.cleanedContent, "openai")
            }
            return ArticleDraft(
                success = false,
                error   = "All AI providers unavailable (Gemini, Devil AI 2B, OpenAI). " +
                          "Please check your API keys in Settings."
            )
        }

        // ── STEP 4: Parse Gemini JSON ─────────────────────────────────────────
        val parsed = parseGeminiJson(geminiRaw)
            ?: return ArticleDraft(success = false, error = "Failed to parse Gemini article JSON")

        return polishAndReturn(parsed, topic, "gemini", keyManager)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Polish pass — Gemini grammar/humanise + OpenAI final clean
    // (replaces the old Sarvam grammar check which was broken)
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun polishAndReturn(
        draft: ArticleDraft,
        topic: String,
        writtenBy: String,
        keyManager: ApiKeyManager
    ): ArticleDraft {
        val geminiKeys = keyManager.getGeminiKeys()

        // Grammar + humanise via Gemini flash-lite (replaces Sarvam)
        var polished = draft.content
        if (geminiKeys.isNotEmpty()) {
            val grammarResult = geminiPolish(draft.content, geminiKeys.first())
            if (!grammarResult.isNullOrBlank()) polished = grammarResult
        }

        // OpenAI final clean pass
        val openAiClient = OpenAiApiClient(keyManager)
        val cleanResult  = openAiClient.cleanArticleOutput(draft.title, polished)
        val finalContent = if (cleanResult.success && cleanResult.cleanedContent.isNotBlank())
            cleanResult.cleanedContent else polished

        return draft.copy(content = finalContent, writtenBy = writtenBy)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Offline Gemma 2B (Devil AI) — on-device article generation
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun tryOfflineGemma(
        topic: String,
        context: Context
    ): ArticleDraft? {
        return try {
            val gemmaClient = OfflineGemmaClient(context)
            if (!gemmaClient.isModelReady()) {
                Log.w("ArticleGenerator", "Devil AI 2B model not downloaded yet")
                return null
            }
            val writer = OfflineArticleWriter(gemmaClient)
            val result = writer.write(
                title       = topic,
                description = topic,
                category    = "General",
                targetWords = 800
            )
            if (!result.success || result.content.isBlank()) return null

            val lines   = result.content.trim().lines().filter { it.isNotBlank() }
            val headline = lines.firstOrNull() ?: topic
            val body     = if (lines.size > 1) lines.drop(1).joinToString("\n\n") else result.content

            ArticleDraft(
                success    = true,
                title      = headline,
                summary    = body.take(220).trimEnd() + "…",
                content    = result.content,
                imageQuery = "$topic news photo",
                category   = "General",
                tags       = topic.split(" ").take(6).joinToString(", "),
                writtenBy  = "devil-ai-2b"
            )
        } catch (e: Exception) {
            Log.e("ArticleGenerator", "Devil AI 2B error: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gemini grammar + humanise polish
    // (lightweight call to flash-lite — replaces Sarvam checkGrammarAndSpelling)
    // ─────────────────────────────────────────────────────────────────────────

    private fun geminiPolish(articleText: String, apiKey: String): String? {
        return try {
            val prompt = """
You are a professional copy editor. Fix any grammar, spelling, punctuation, and awkward phrasing in the article below. 
Make it sound natural and human. Keep the same structure, length, and facts. 
Return ONLY the corrected article text. No explanation.

ARTICLE:
$articleText
            """.trimIndent()

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
                    put("temperature",     0.3)
                    put("maxOutputTokens", 2048)
                })
            }
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$POLISH_MODEL:generateContent?key=$apiKey"
            val response = http.newCall(
                Request.Builder().url(url)
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("User-Agent", "NexuzyPublisher/2.0")
                    .build()
            ).execute()
            if (!response.isSuccessful) return null
            val raw = response.body?.string() ?: return null
            JSONObject(raw)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0)
                .getString("text").trim().ifBlank { null }
        } catch (e: Exception) { null }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gemini article generation
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
            val cleaned = raw
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
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
    // Plain draft builder (for OpenAI fallback path)
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildPlainDraft(
        topic: String,
        content: String,
        writtenBy: String
    ): ArticleDraft {
        val lines    = content.trim().lines().filter { it.isNotBlank() }
        val headline = lines.firstOrNull() ?: topic
        return ArticleDraft(
            success    = true,
            title      = headline,
            summary    = content.take(220).trimEnd() + "…",
            content    = content,
            imageQuery = "$topic news photo",
            category   = "General",
            tags       = topic.split(" ").take(6).joinToString(", "),
            writtenBy  = writtenBy
        )
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
