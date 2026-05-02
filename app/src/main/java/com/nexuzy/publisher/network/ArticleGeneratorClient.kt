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
 * Generates a full news article draft when the user types a write/generate
 * command in the David AI chat.
 *
 * PIPELINE:
 *   Step 1 — Try Gemini (all 4 models × all saved keys)
 *   Step 2 — If ALL Gemini keys exhausted → fall back to Sarvam AI (sarvam-m)
 *   Step 3 — Sarvam grammar + spelling correction (whoever wrote it)
 *   Step 4 — OpenAI Key 2 clean output (remove AI preamble/artifacts)
 *   Step 5 — Return clean ArticleDraft to caller
 *
 * Returns [ArticleDraft] with:
 *   - title        : Compelling SEO headline
 *   - summary      : 2-3 sentence excerpt for meta description
 *   - content      : Full HTML article body (800-1200 words), cleaned
 *   - imageQuery   : Descriptive search query for a relevant image
 *   - category     : Detected category (Technology, Sports, etc.)
 *   - tags         : Comma-separated SEO tags
 *   - writtenBy    : "gemini" or "sarvam" (for logging/display)
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
        val writtenBy: String  = "",   // "gemini" | "sarvam"
        val error: String      = ""
    ) {
        /** Formatted chat reply shown to user after article generation */
        fun toChatReply(): String {
            if (!success) return "❌ Article generation failed: $error"
            val byLabel = when (writtenBy) {
                "sarvam" -> " *(written by Sarvam AI — Gemini unavailable)*"
                else     -> ""
            }
            return buildString {
                appendLine("📝 **Article Draft Generated**$byLabel")
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
                appendLine("✔️ *This article draft is ready to copy, edit, and publish on your WordPress site.*")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main entry point
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generate a full article draft from a user topic/command.
     *
     * @param topic       The subject the user wants to write about
     * @param weatherCtx  Optional weather context string to enrich local articles
     * @param keyManager  ApiKeyManager for Gemini, Sarvam, and OpenAI keys
     */
    suspend fun generate(
        topic: String,
        weatherCtx: String = "",
        keyManager: ApiKeyManager
    ): ArticleDraft {
        if (topic.isBlank()) {
            return ArticleDraft(
                success = false,
                error = "Please specify a topic. Example: \"generate article about solar energy\""
            )
        }

        // ── STEP 1: Try Gemini (all keys × all models) ──────────────────────
        val geminiKeys = keyManager.getGeminiKeys()
        var geminiRawContent: String? = null

        if (geminiKeys.isNotEmpty()) {
            outer@ for (key in geminiKeys) {
                for (model in MODEL_CHAIN) {
                    Log.d("ArticleGenerator", "[STEP 1] Trying Gemini model=$model")
                    val result = tryGemini(topic, weatherCtx, key, model)
                    if (result != null) {
                        geminiRawContent = result
                        Log.i("ArticleGenerator", "[STEP 1] ✔ Gemini success model=$model")
                        break@outer
                    }
                }
            }
        }

        // ── STEP 2: Sarvam fallback if Gemini completely failed ──────────────
        val (rawContent, writtenBy) = if (geminiRawContent != null) {
            Pair(geminiRawContent, "gemini")
        } else {
            Log.w("ArticleGenerator", "[STEP 2] All Gemini keys exhausted — falling back to Sarvam AI")
            val sarvamClient = SarvamApiClient(keyManager)
            val sarvamResult = sarvamClient.writeArticle(
                rssTitle       = topic,
                rssDescription = topic,
                category       = "General"
            )
            if (!sarvamResult.success || sarvamResult.content.isBlank()) {
                return ArticleDraft(
                    success = false,
                    error   = "Both Gemini and Sarvam AI are unavailable. " +
                              "Gemini: all keys exhausted. Sarvam: ${sarvamResult.error}"
                )
            }
            Log.i("ArticleGenerator", "[STEP 2] ✔ Sarvam fallback succeeded")
            // Sarvam returns plain text, not JSON — wrap into draft directly
            val draft = buildSarvamDraft(topic, sarvamResult.content, keyManager)
            // Grammar + clean already applied inside buildSarvamDraft
            return draft
        }

        // ── STEP 3: Parse Gemini JSON response ───────────────────────────────
        val parsed = parseGeminiJson(rawContent)
            ?: return ArticleDraft(success = false, error = "Failed to parse Gemini article JSON")

        // ── STEP 4: Sarvam grammar + spelling on Gemini content ──────────────
        Log.d("ArticleGenerator", "[STEP 4] Running Sarvam grammar check on Gemini article")
        val sarvamClient = SarvamApiClient(keyManager)
        val grammarResult = sarvamClient.checkGrammarAndSpelling(parsed.content)
        val grammarChecked = if (grammarResult.success) grammarResult.correctedText else parsed.content

        // ── STEP 5: OpenAI Key 2 clean output ────────────────────────────────
        Log.d("ArticleGenerator", "[STEP 5] Running OpenAI Key 2 clean on article")
        val openAiClient = OpenAiApiClient(keyManager)
        val cleanResult  = openAiClient.cleanArticleOutput(parsed.title, grammarChecked)
        val finalContent = if (cleanResult.success && cleanResult.cleanedContent.isNotBlank())
            cleanResult.cleanedContent else grammarChecked

        Log.i("ArticleGenerator", "[DONE] Article ready. writtenBy=gemini, grammarOk=${grammarResult.success}, cleanOk=${cleanResult.success}")

        return parsed.copy(content = finalContent, writtenBy = "gemini")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sarvam draft builder (plain text → ArticleDraft)
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun buildSarvamDraft(
        topic: String,
        rawText: String,
        keyManager: ApiKeyManager
    ): ArticleDraft {
        // Extract first line as title, rest as body
        val lines     = rawText.trim().lines().filter { it.isNotBlank() }
        val title     = lines.firstOrNull() ?: topic
        val bodyLines = if (lines.size > 1) lines.drop(1) else lines
        val body      = bodyLines.joinToString("\n\n")

        // Step 4: Grammar check on Sarvam text
        Log.d("ArticleGenerator", "[STEP 4-S] Grammar check on Sarvam article")
        val sarvamClient = SarvamApiClient(keyManager)
        val grammarResult = sarvamClient.checkGrammarAndSpelling(body)
        val grammarChecked = if (grammarResult.success) grammarResult.correctedText else body

        // Step 5: OpenAI Key 2 clean on Sarvam text
        Log.d("ArticleGenerator", "[STEP 5-S] OpenAI Key 2 clean on Sarvam article")
        val openAiClient = OpenAiApiClient(keyManager)
        val cleanResult  = openAiClient.cleanArticleOutput(title, grammarChecked)
        val finalContent = if (cleanResult.success && cleanResult.cleanedContent.isNotBlank())
            cleanResult.cleanedContent else grammarChecked

        Log.i("ArticleGenerator", "[DONE] Sarvam article ready. grammarOk=${grammarResult.success}, cleanOk=${cleanResult.success}")

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

    /**
     * Calls Gemini with a specific key + model.
     * Returns the raw text string on success, null on any failure.
     */
    private fun tryGemini(topic: String, weatherCtx: String, apiKey: String, model: String): String? {
        return try {
            val weatherLine = if (weatherCtx.isNotBlank()) "\n\nContext (use if relevant): $weatherCtx" else ""
            val prompt = buildGeminiPrompt(topic, weatherLine)

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

            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("User-Agent", "NexuzyPublisher/2.0")
                .build()

            val response = http.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w("ArticleGenerator", "Gemini $model HTTP ${response.code}")
                return null
            }

            val raw = response.body?.string() ?: return null
            val text = JSONObject(raw)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()

            if (text.isBlank()) null else text
        } catch (e: Exception) {
            Log.w("ArticleGenerator", "tryGemini $model exception: ${e.message}")
            null
        }
    }

    private fun buildGeminiPrompt(topic: String, weatherLine: String): String = """
        ███ CRITICAL OUTPUT RULES ███
        - Output ONLY the JSON object below. Nothing before it. Nothing after it.
        - Do NOT include thinking, reasoning, preamble, or commentary.
        - Do NOT start with "Okay", "Sure", "Let me", "Here is", or any intro words.
        - Begin your response with the opening { of the JSON object.
        ██████████████████████████

        You are a professional news journalist and SEO expert. Generate a complete, original news article draft.

        TOPIC: $topic$weatherLine

        Respond ONLY with a valid JSON object in this exact format (no markdown, no code fences):
        {
          "title": "Compelling SEO headline for the article (max 70 chars)",
          "summary": "2-3 sentence meta description / article excerpt that hooks the reader",
          "content": "Full HTML article body. Use <h2>, <p>, <ul>/<li> tags. 800-1200 words. Original, factual, well-structured.",
          "imageQuery": "A specific 5-8 word search query for a relevant copyright-free image",
          "category": "One of: Technology / Business / Politics / Sports / Entertainment / Health / Science / Environment / General",
          "tags": "5-8 comma-separated SEO tags relevant to this article"
        }
    """.trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    // JSON parser
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseGeminiJson(raw: String): ArticleDraft? {
        return try {
            val cleaned = raw
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```")
                .trim()
            val parsed = JSONObject(cleaned)
            ArticleDraft(
                success    = true,
                title      = parsed.optString("title", ""),
                summary    = parsed.optString("summary", ""),
                content    = parsed.optString("content", ""),
                imageQuery = parsed.optString("imageQuery", ""),
                category   = parsed.optString("category", "General"),
                tags       = parsed.optString("tags", "")
            )
        } catch (e: Exception) {
            Log.e("ArticleGenerator", "parseGeminiJson failed: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Topic extractor (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

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
