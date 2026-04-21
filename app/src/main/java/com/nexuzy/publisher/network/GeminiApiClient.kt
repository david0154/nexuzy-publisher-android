package com.nexuzy.publisher.network

import android.util.Log
import com.google.gson.JsonPrimitive
import com.google.gson.JsonParser
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Gemini API client.
 * Role 1: Write news articles from RSS item facts.
 * Role 2: Generate SEO data (tags, keywords, focus keyphrase, meta description).
 * Supports up to 3 API keys with automatic rotation — no quota token is wasted.
 * OpenAI is NEVER used for writing — only for fact-checking.
 */
class GeminiApiClient(private val keyManager: ApiKeyManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models"

    data class GeminiResult(
        val success: Boolean,
        val content: String,
        val error: String = "",
        val keyUsed: Int = 1
    )

    data class SeoData(
        val success: Boolean,
        val tags: List<String> = emptyList(),           // e.g. ["AI", "Technology", "India"]
        val metaKeywords: String = "",                  // comma-separated for <meta keywords>
        val focusKeyphrase: String = "",               // primary Yoast/RankMath keyphrase
        val metaDescription: String = "",             // 155-char SEO description
        val error: String = ""
    )

    // ─────────────────────────────────────────────
    // ROLE 1: Write news article
    // ─────────────────────────────────────────────

    suspend fun writeNewsArticle(
        rssTitle: String,
        rssDescription: String,
        category: String,
        model: String = "gemini-1.5-flash",
        maxWords: Int = 800
    ): GeminiResult {
        val keys = keyManager.getGeminiKeys()
        if (keys.isEmpty()) {
            return GeminiResult(false, "", "No Gemini API keys configured. Please add in Settings.")
        }
        val prompt = buildArticlePrompt(rssTitle, rssDescription, category, maxWords)
        for ((index, key) in keys.withIndex()) {
            val result = callGemini(key, model, prompt)
            if (result.success) return result.copy(keyUsed = index + 1)
            if (isQuotaError(result.error)) {
                Log.w("GeminiClient", "Key ${index + 1} quota exceeded, trying next")
                keyManager.rotateGeminiKey()
                continue
            }
            return result
        }
        return GeminiResult(false, "", "All Gemini API keys have exceeded quota. Try again later.")
    }

    // ─────────────────────────────────────────────
    // ROLE 2: Generate SEO data from article
    // ─────────────────────────────────────────────

    suspend fun generateSeoData(
        title: String,
        articleContent: String,
        category: String,
        model: String = "gemini-1.5-flash"
    ): SeoData {
        val keys = keyManager.getGeminiKeys()
        if (keys.isEmpty()) return SeoData(false, error = "No Gemini keys configured.")

        val prompt = buildSeoPrompt(title, articleContent, category)

        for ((index, key) in keys.withIndex()) {
            val result = callGemini(key, model, prompt)
            if (result.success) {
                return parseSeoResponse(result.content)
            }
            if (isQuotaError(result.error)) {
                keyManager.rotateGeminiKey()
                continue
            }
            return SeoData(false, error = result.error)
        }
        return SeoData(false, error = "All Gemini keys exhausted for SEO generation.")
    }

    // ─────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────

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
                else GeminiResult(false, "", "Empty response from Gemini")
            } else {
                GeminiResult(false, "", "HTTP ${response.code}: $body")
            }
        } catch (e: Exception) {
            GeminiResult(false, "", e.message ?: "Unknown error")
        }
    }

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

    private fun parseSeoResponse(raw: String): SeoData {
        return try {
            // Strip markdown code fences if present
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
        error.contains("429") || error.contains("quota", ignoreCase = true) ||
        error.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
        error.contains("rate limit", ignoreCase = true)
}
