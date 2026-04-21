package com.nexuzy.publisher.network

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Gemini API client.
 * - Writes news articles based on RSS item facts.
 * - Supports up to 3 API keys with automatic rotation when quota is exceeded.
 * - OpenAI is used as a separate fact-checker — NOT as a writing fallback.
 */
class GeminiApiClient(private val keyManager: ApiKeyManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models"

    data class GeminiResult(
        val success: Boolean,
        val content: String,
        val error: String = "",
        val keyUsed: Int = 1
    )

    /**
     * Write a news article using Gemini AI.
     * Automatically rotates keys if quota/rate limit hit.
     */
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

        // Try each key in sequence — no quota token wasted
        for ((index, key) in keys.withIndex()) {
            val result = callGeminiApi(key, model, prompt)
            if (result.success) {
                return result.copy(keyUsed = index + 1)
            }
            if (isQuotaError(result.error)) {
                Log.w("GeminiClient", "Key ${index + 1} quota exceeded, trying next key")
                keyManager.rotateGeminiKey()
                continue
            }
            // Non-quota error — return immediately
            return result
        }
        return GeminiResult(false, "", "All Gemini API keys have exceeded their quota. Please try again later.")
    }

    private fun callGeminiApi(apiKey: String, model: String, prompt: String): GeminiResult {
        return try {
            val requestBody = buildRequestBody(prompt)
            val request = Request.Builder()
                .url("$baseUrl/$model:generateContent?key=$apiKey")
                .post(requestBody.toRequestBody(jsonMedia))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val content = extractGeminiContent(body)
                if (content.isNotBlank()) {
                    GeminiResult(true, content)
                } else {
                    GeminiResult(false, "", "Empty response from Gemini")
                }
            } else {
                GeminiResult(false, "", "HTTP ${response.code}: $body")
            }
        } catch (e: Exception) {
            GeminiResult(false, "", e.message ?: "Unknown error")
        }
    }

    private fun buildArticlePrompt(title: String, description: String, category: String, maxWords: Int): String {
        return """
            You are a professional news journalist. Write a complete, factual, engaging news article based on the following information.

            Original Headline: $title
            Summary/Description: $description
            Category: $category

            Requirements:
            - Write approximately $maxWords words
            - Start with a strong lead paragraph
            - Include who, what, when, where, why (5 W's)
            - Use objective, journalistic tone
            - Add context and background where appropriate
            - End with implications or outlook
            - Do NOT add fake quotes
            - Only use facts from the provided information

            Write the complete article now:
        """.trimIndent()
    }

    private fun buildRequestBody(prompt: String): String {
        return """
            {
              "contents": [
                {
                  "parts": [
                    {"text": ${com.google.gson.JsonPrimitive(prompt)}}
                  ]
                }
              ],
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

    private fun extractGeminiContent(responseBody: String): String {
        return try {
            val json = JsonParser.parseString(responseBody).asJsonObject
            val candidates = json.getAsJsonArray("candidates")
            if (candidates != null && candidates.size() > 0) {
                val content = candidates[0].asJsonObject
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")[0]
                    .asJsonObject.get("text").asString
                content.trim()
            } else ""
        } catch (e: Exception) {
            Log.e("GeminiClient", "Parse error: ${e.message}")
            ""
        }
    }

    private fun isQuotaError(error: String): Boolean {
        return error.contains("429") || error.contains("quota", ignoreCase = true) ||
               error.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
               error.contains("rate limit", ignoreCase = true)
    }
}
