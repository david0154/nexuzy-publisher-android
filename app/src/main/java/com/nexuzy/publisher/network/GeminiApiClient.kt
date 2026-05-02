package com.nexuzy.publisher.network

import android.util.Log
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Gemini API client.
 * Role 1: Write news articles from RSS item facts.
 * Role 2: Generate SEO data (tags, keywords, focus keyphrase, meta description).
 *
 * KEY ROTATION FIX:
 * - Each outer key-loop iteration reads a fresh key from keyManager via index offset
 *   so rotateGeminiKey() actually takes effect within the same call.
 * - Empty / safety-blocked responses are treated as a soft retryable failure so
 *   we try the next model before giving up — they are NOT hard errors.
 */
class GeminiApiClient(private val keyManager: ApiKeyManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = "https://generativelanguage.googleapis.com/v1/models"

    private val MODEL_CHAIN = listOf(
        "gemini-2.0-flash",
        "gemini-1.5-flash",
        "gemini-2.0-flash-lite",
        "gemini-1.5-flash-8b"
    )

    data class GeminiResult(
        val success: Boolean,
        val content: String,
        val error: String = "",
        val keyUsed: Int = 1,
        val modelUsed: String = DEFAULT_MODEL,
        val retryAfterSeconds: Int = 0
    )

    data class SeoData(
        val success: Boolean,
        val tags: List<String> = emptyList(),
        val metaKeywords: String = "",
        val focusKeyphrase: String = "",
        val metaDescription: String = "",
        val error: String = ""
    )

    // ─────────────────────────────────────────────────────────────────────────
    // ROLE 1: Write news article
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun writeNewsArticle(
        rssTitle: String,
        rssDescription: String,
        rssFullContent: String = "",
        category: String,
        model: String = DEFAULT_MODEL,
        maxWords: Int = 800
    ): GeminiResult = withContext(Dispatchers.IO) {

        val keys = keyManager.getGeminiKeys()
        if (keys.isEmpty()) {
            return@withContext GeminiResult(
                false, "",
                "No Gemini API keys configured. Please add at least one Gemini API key in Settings."
            )
        }

        val prompt = buildArticlePrompt(rssTitle, rssDescription, rssFullContent, category, maxWords)
        val modelsToTry = if (model == DEFAULT_MODEL) MODEL_CHAIN else listOf(model)

        var lastError = ""
        var maxRetryAfter = 0

        for (keyIndex in keys.indices) {
            // ── Always re-read keys so rotation applied by rotateGeminiKey() is visible ──
            val currentKeys = keyManager.getGeminiKeys()
            if (keyIndex >= currentKeys.size) break
            val key = currentKeys[keyIndex]

            for (modelName in modelsToTry) {
                Log.d("GeminiClient", "Trying key ${keyIndex + 1}/${currentKeys.size}, model=$modelName")
                val result = callGemini(key, modelName, prompt)

                if (result.success) {
                    Log.i("GeminiClient", "Success ✔ key=${keyIndex + 1}, model=$modelName")
                    return@withContext result.copy(keyUsed = keyIndex + 1, modelUsed = modelName)
                }

                if (isQuotaError(result.error)) {
                    val retryAfter = parseRetryDelay(result.error)
                    if (retryAfter > maxRetryAfter) maxRetryAfter = retryAfter
                    Log.w("GeminiClient", "Key ${keyIndex + 1} / $modelName quota exceeded, trying next model")
                    lastError = result.error
                    continue // try next model with same key
                }

                // Empty/safety-blocked response → treat as soft fail, try next model
                if (result.content.isBlank() && result.error.contains("Empty response", ignoreCase = true)) {
                    Log.w("GeminiClient", "Key ${keyIndex + 1} / $modelName returned empty (safety/block), trying next model")
                    lastError = result.error
                    continue
                }

                // Any other non-quota hard error → rethrow immediately
                Log.e("GeminiClient", "Non-quota error on key ${keyIndex + 1} / $modelName: ${result.error}")
                return@withContext result.copy(keyUsed = keyIndex + 1, modelUsed = modelName)
            }
            Log.w("GeminiClient", "Key ${keyIndex + 1}: all ${modelsToTry.size} models failed, rotating to next key")
            keyManager.rotateGeminiKey()
        }

        val retryMsg = if (maxRetryAfter > 0)
            " Gemini resets in about ${maxRetryAfter}s — please wait and try again."
        else
            " Try again in 60 seconds, or add more API keys in Settings."

        return@withContext GeminiResult(
            false, "",
            "All ${keys.size} Gemini key(s) × ${modelsToTry.size} models have exceeded quota.$retryMsg",
            retryAfterSeconds = maxRetryAfter
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ROLE 2: Generate SEO metadata
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun generateSeoData(
        title: String,
        articleContent: String,
        category: String,
        model: String = DEFAULT_MODEL
    ): SeoData = withContext(Dispatchers.IO) {
        val keys = keyManager.getGeminiKeys()
        if (keys.isEmpty()) return@withContext SeoData(false, error = "No Gemini keys configured.")

        val prompt = buildSeoPrompt(title, articleContent, category)
        val modelsToTry = if (model == DEFAULT_MODEL) MODEL_CHAIN else listOf(model)

        for (keyIndex in keys.indices) {
            val currentKeys = keyManager.getGeminiKeys()
            if (keyIndex >= currentKeys.size) break
            val key = currentKeys[keyIndex]

            for (modelName in modelsToTry) {
                val result = callGemini(key, modelName, prompt)
                if (result.success) return@withContext parseSeoResponse(result.content)
                if (isQuotaError(result.error)) {
                    Log.w("GeminiClient", "SEO: Key ${keyIndex + 1}/$modelName quota exceeded, trying next")
                    continue
                }
                if (result.content.isBlank() && result.error.contains("Empty response", ignoreCase = true)) {
                    continue // safety block — try next model
                }
                return@withContext SeoData(false, error = result.error)
            }
            keyManager.rotateGeminiKey()
        }
        return@withContext SeoData(false, error = "All Gemini keys exhausted for SEO generation.")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal: HTTP call
    // ─────────────────────────────────────────────────────────────────────────

    private fun callGemini(apiKey: String, model: String, prompt: String): GeminiResult {
        return try {
            val requestBody = buildRequestBody(prompt)
            val request = Request.Builder()
                .url("$baseUrl/$model:generateContent?key=$apiKey")
                .post(requestBody.toRequestBody(jsonMedia))
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val content = extractText(body)
                if (content.isNotBlank()) GeminiResult(true, content)
                else GeminiResult(false, "", "Empty response from Gemini ($model)")
            } else {
                Log.e("GeminiClient", "Gemini failed: HTTP ${response.code}: $body")
                GeminiResult(false, "", "HTTP ${response.code}: $body")
            }
        } catch (e: Exception) {
            Log.e("GeminiClient", "callGemini exception: ${e.message}")
            GeminiResult(false, "", e.message ?: "Unknown error")
        }
    }

    private fun parseRetryDelay(error: String): Int {
        val pattern = Regex("""[Rr]etry(?:\s+in)?\s+(\d+)(?:\.\d+)?\s*s""", RegexOption.IGNORE_CASE)
        return pattern.find(error)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prompt builders
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildArticlePrompt(
        title: String,
        desc: String,
        fullContent: String,
        category: String,
        maxWords: Int
    ): String {
        val sourceSection = if (fullContent.isNotBlank()) {
            """
            |─── ORIGINAL ARTICLE (scraped from source) ───────────────────────
            |${fullContent.take(3500)}
            |──────────────────────────────────────────────────────────────────
            |
            |Short RSS Summary (for context): $desc
            """.trimMargin()
        } else {
            """
            |RSS Summary/Description: $desc
            |(Note: Full article not available. Write based on title and summary only.)
            """.trimMargin()
        }

        return """
            You are a professional news journalist. Write a complete, factual, engaging news article.

            Original Headline : $title
            Category          : $category
            Target length     : approximately $maxWords words

            $sourceSection

            Writing requirements:
            - Create a strong lead paragraph (who, what, when, where, why)
            - Objective, professional journalistic tone
            - Add relevant context and background using the source material above
            - End with implications, reactions, or outlook
            - Rewrite completely in your own words — do NOT copy the original verbatim
            - Do NOT add quotes that are not present in the source
            - Do NOT invent statistics or facts not in the source
            - Write a NEW SEO-friendly headline
            - Use only the facts provided in the source above
            - Do NOT mention, credit, or reference the original author, journalist, reporter, or writer by name anywhere in the article
            - Do NOT include any byline, "By [Name]", "Written by", "Reported by", or "According to [journalist name]" lines
            - Do NOT mention the original publication's staff, contributors, or editors by name
            - Do NOT include image captions, photo credits, or image HTML tags — output plain text article body only
            - Do NOT repeat or duplicate the headline or any paragraph within the article body

            Write the complete rewritten article now (headline first, then body paragraphs only):
        """.trimIndent()
    }

    private fun buildSeoPrompt(title: String, content: String, category: String) = """
        You are an SEO expert. Analyze this news article and generate SEO metadata.

        Article Title: $title
        Category: $category
        Article (first 1000 chars): ${content.take(1000)}

        Generate SEO data and respond ONLY in this exact JSON format:
        {
          "tags": ["tag1", "tag2", "tag3", "tag4", "tag5"],
          "meta_keywords": "keyword1, keyword2, keyword3, keyword4, keyword5",
          "focus_keyphrase": "main focus keyphrase here",
          "meta_description": "Compelling 120-155 character meta description for search engines."
        }

        Rules:
        - tags: 5-8 relevant single or two-word tags
        - meta_keywords: 5-10 comma-separated keywords
        - focus_keyphrase: 2-4 words, most important search phrase
        - meta_description: 120-155 characters, compelling and includes focus keyphrase
        - Respond ONLY with valid JSON, no extra text
    """.trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    // Response parsers
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseSeoResponse(raw: String): SeoData {
        return try {
            val cleaned = raw.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val json = JsonParser.parseString(cleaned).asJsonObject
            val tagsArr = json.getAsJsonArray("tags")
            val tags = (0 until tagsArr.size()).map { tagsArr[it].asString }
            SeoData(
                success        = true,
                tags           = tags,
                metaKeywords   = json.get("meta_keywords")?.asString ?: tags.joinToString(", "),
                focusKeyphrase = json.get("focus_keyphrase")?.asString ?: "",
                metaDescription = json.get("meta_description")?.asString ?: ""
            )
        } catch (e: Exception) {
            Log.e("GeminiClient", "SEO parse error: ${e.message} | raw: $raw")
            SeoData(false, error = "SEO parse failed: ${e.message}")
        }
    }

    private fun buildRequestBody(prompt: String): String {
        val escaped = JsonPrimitive(prompt).toString()
        return """
            {
              "contents": [{"parts": [{"text": $escaped}]}],
              "generationConfig": {
                "temperature": 0.7,
                "topK": 40,
                "topP": 0.95,
                "maxOutputTokens": 2048
              },
              "safetySettings": [
                {"category": "HARM_CATEGORY_HARASSMENT", "threshold": "BLOCK_MEDIUM_AND_ABOVE"},
                {"category": "HARM_CATEGORY_HATE_SPEECH", "threshold": "BLOCK_MEDIUM_AND_ABOVE"}
              ]
            }
        """.trimIndent()
    }

    private fun extractText(responseBody: String): String {
        return try {
            val json = JsonParser.parseString(responseBody).asJsonObject
            val candidates = json.getAsJsonArray("candidates")
            if (candidates != null && candidates.size() > 0) {
                candidates[0].asJsonObject
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")[0]
                    .asJsonObject.get("text").asString.trim()
            } else ""
        } catch (e: Exception) {
            Log.e("GeminiClient", "Parse error: ${e.message}")
            ""
        }
    }

    private fun isQuotaError(error: String) =
        error.contains("429") ||
        error.contains("quota", ignoreCase = true) ||
        error.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
        error.contains("rate limit", ignoreCase = true) ||
        error.contains("rateLimitExceeded", ignoreCase = true)

    companion object {
        const val DEFAULT_MODEL = "gemini-2.0-flash"
    }
}
