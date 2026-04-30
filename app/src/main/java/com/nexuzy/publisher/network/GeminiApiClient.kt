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
 * KEY FIX: Model fallback chain.
 * Each Gemini model has a COMPLETELY SEPARATE quota pool on the same API key.
 * If gemini-2.0-flash hits quota (limit:0), gemini-1.5-flash on the SAME key
 * still has its own fresh quota. This multiplies effective capacity by 4x.
 *
 * Rotation strategy:
 *   For each API key:  try gemini-2.0-flash → gemini-1.5-flash
 *                                           → gemini-2.0-flash-lite → gemini-1.5-flash-8b
 *   If all 4 models exhausted on key N: move to key N+1 and repeat
 *
 * Threading: callGemini() is a blocking OkHttp call; both public suspend functions
 * now use withContext(Dispatchers.IO) to run off the main thread.
 */
class GeminiApiClient(private val keyManager: ApiKeyManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // v1 endpoint — supports all stable models (2.0-flash, 1.5-flash, 1.5-pro, etc.)
    private val baseUrl = "https://generativelanguage.googleapis.com/v1/models"

    /**
     * Fallback model chain.
     * Each entry is a SEPARATE quota pool on the same API key.
     * Order: newest/best first, smallest/most-available last.
     *
     * Free-tier RPD (requests-per-day) reference:
     *   gemini-2.0-flash       : 1500 RPD, 15 RPM  (but separate pool from others)
     *   gemini-1.5-flash       : 1500 RPD, 15 RPM
     *   gemini-2.0-flash-lite  : 1500 RPD, 30 RPM  (higher RPM!)
     *   gemini-1.5-flash-8b    : 1500 RPD, 15 RPM
     *
     * With 3 API keys × 4 models = up to 18 000 free requests/day.
     */
    private val MODEL_CHAIN = listOf(
        "gemini-2.0-flash",
        "gemini-1.5-flash",
        "gemini-2.0-flash-lite",
        "gemini-1.5-flash-8b"
    )

    // ────────────────────────────────────────────────────────────────────────
    // Data classes
    // ────────────────────────────────────────────────────────────────────────

    data class GeminiResult(
        val success: Boolean,
        val content: String,
        val error: String = "",
        val keyUsed: Int = 1,
        val modelUsed: String = DEFAULT_MODEL,
        /** Seconds to wait before retrying (parsed from Gemini 429 retryDelay). */
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

    // ────────────────────────────────────────────────────────────────────────
    // ROLE 1: Write news article
    // ────────────────────────────────────────────────────────────────────────

    suspend fun writeNewsArticle(
        rssTitle: String,
        rssDescription: String,
        category: String,
        model: String = DEFAULT_MODEL,
        maxWords: Int = 800
    ): GeminiResult = withContext(Dispatchers.IO) {

        val keys = keyManager.getGeminiKeys()
        if (keys.isEmpty()) {
            return@withContext GeminiResult(
                false, "",
                "No Gemini API keys configured. " +
                "Please add at least one Gemini API key in Settings → Gemini API Keys."
            )
        }

        val prompt = buildArticlePrompt(rssTitle, rssDescription, category, maxWords)
        // Which models to try: if caller passed a specific model, only try that one;
        // otherwise use the full fallback chain.
        val modelsToTry = if (model == DEFAULT_MODEL) MODEL_CHAIN else listOf(model)

        var lastError = ""
        var maxRetryAfter = 0

        // Outer: iterate every API key
        for ((keyIndex, key) in keys.withIndex()) {
            // Inner: try each model on this key
            for (modelName in modelsToTry) {
                Log.d("GeminiClient", "Trying key ${keyIndex + 1}/${ keys.size}, model=$modelName")
                val result = callGemini(key, modelName, prompt)

                if (result.success) {
                    Log.i("GeminiClient", "Success ✔ key=${keyIndex + 1}, model=$modelName")
                    return@withContext result.copy(keyUsed = keyIndex + 1, modelUsed = modelName)
                }

                if (isQuotaError(result.error)) {
                    val retryAfter = parseRetryDelay(result.error)
                    if (retryAfter > maxRetryAfter) maxRetryAfter = retryAfter
                    Log.w(
                        "GeminiClient",
                        "Key ${keyIndex + 1} / $modelName quota exceeded" +
                        (if (retryAfter > 0) " (retry in ${retryAfter}s)" else "") +
                        ", trying next model"
                    )
                    lastError = result.error
                    continue // try next model
                }

                // Non-quota error (401 invalid key, network error, parse error, etc.)
                // Return immediately — rotating keys won't help for auth errors.
                Log.e("GeminiClient", "Non-quota error on key ${keyIndex + 1} / $modelName: ${result.error}")
                return@withContext result.copy(keyUsed = keyIndex + 1, modelUsed = modelName)
            }

            // All models exhausted for this key — rotate to next
            Log.w("GeminiClient", "Key ${keyIndex + 1}: all ${modelsToTry.size} models quota exceeded, trying next key")
            keyManager.rotateGeminiKey()
        }

        // All keys × all models exhausted
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

    // ────────────────────────────────────────────────────────────────────────
    // ROLE 2: Generate SEO metadata from article
    // ────────────────────────────────────────────────────────────────────────

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

        for ((keyIndex, key) in keys.withIndex()) {
            for (modelName in modelsToTry) {
                val result = callGemini(key, modelName, prompt)
                if (result.success) return@withContext parseSeoResponse(result.content)
                if (isQuotaError(result.error)) {
                    Log.w("GeminiClient", "SEO: Key ${keyIndex + 1}/$modelName quota exceeded, trying next")
                    continue
                }
                return@withContext SeoData(false, error = result.error)
            }
            keyManager.rotateGeminiKey()
        }
        return@withContext SeoData(false, error = "All Gemini keys exhausted for SEO generation.")
    }

    // ────────────────────────────────────────────────────────────────────────
    // Internal: HTTP call (blocking — must be called from Dispatchers.IO)
    // ────────────────────────────────────────────────────────────────────────

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

    // ────────────────────────────────────────────────────────────────────────
    // Retry delay parser
    // Gemini 429 body contains: "Please retry in 57.870850784s."
    // ────────────────────────────────────────────────────────────────────────

    private fun parseRetryDelay(error: String): Int {
        val pattern = Regex("""[Rr]etry(?:\s+in)?\s+(\d+)(?:\.\d+)?\s*s""", RegexOption.IGNORE_CASE)
        return pattern.find(error)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    // ────────────────────────────────────────────────────────────────────────
    // Prompt builders
    // ────────────────────────────────────────────────────────────────────────

    private fun buildArticlePrompt(title: String, desc: String, category: String, maxWords: Int) = """
        You are a professional news journalist. Write a complete, factual, engaging news article.

        Original Headline: $title
        Summary/Description: $desc
        Category: $category

        Requirements:
        - Write approximately $maxWords words
        - Strong lead paragraph (who, what, when, where, why)
        - Objective journalistic tone
        - Add context and background
        - End with implications or outlook
        - Do NOT add fake quotes
        - Only use facts from the provided information

        Write the complete article now:
    """.trimIndent()

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

    // ────────────────────────────────────────────────────────────────────────
    // Response parsers
    // ────────────────────────────────────────────────────────────────────────

    private fun parseSeoResponse(raw: String): SeoData {
        return try {
            val cleaned = raw.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val json = JsonParser.parseString(cleaned).asJsonObject
            val tagsArr = json.getAsJsonArray("tags")
            val tags = (0 until tagsArr.size()).map { tagsArr[it].asString }
            SeoData(
                success = true,
                tags = tags,
                metaKeywords = json.get("meta_keywords")?.asString ?: tags.joinToString(", "),
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
        /** Default model — also the first in MODEL_CHAIN. */
        const val DEFAULT_MODEL = "gemini-2.0-flash"
    }
}
