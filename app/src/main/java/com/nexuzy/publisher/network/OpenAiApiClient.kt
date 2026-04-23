package com.nexuzy.publisher.network

import android.util.Log
import com.google.gson.JsonParser
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OpenAI API client for FACT VERIFICATION only.
 * - Verifies news article facts against current internet knowledge.
 * - NOT used for content generation (Gemini handles that).
 * - Supports up to 3 API keys with automatic rotation.
 */
class OpenAiApiClient(private val keyManager: ApiKeyManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = "https://api.openai.com/v1/chat/completions"

    data class FactCheckResult(
        val success: Boolean,
        val isAccurate: Boolean,
        val feedback: String,
        val correctedContent: String = "",
        val confidenceScore: Float = 0f,
        val error: String = "",
        val keyUsed: Int = 1
    )

    /**
     * Fact-check an article using OpenAI.
     * Rotates through all 3 keys automatically on quota errors.
     */
    suspend fun factCheckArticle(
        title: String,
        content: String,
        model: String = "gpt-4o-mini"
    ): FactCheckResult {
        val keys = keyManager.getOpenAiKeys()
        if (keys.isEmpty()) {
            return FactCheckResult(false, false, "", error = "No OpenAI API keys configured. Add them in Settings.")
        }

        val prompt = buildFactCheckPrompt(title, content)

        for ((index, key) in keys.withIndex()) {
            val result = callOpenAiApi(key, model, prompt)
            if (result.success) {
                return result.copy(keyUsed = index + 1)
            }
            if (isQuotaError(result.error)) {
                Log.w("OpenAiClient", "Key ${index + 1} quota exceeded, rotating")
                keyManager.rotateOpenAiKey()
                continue
            }
            return result
        }
        return FactCheckResult(false, false, "", error = "All OpenAI API keys exhausted.")
    }

    private fun callOpenAiApi(apiKey: String, model: String, prompt: String): FactCheckResult {
        return try {
            val requestBody = buildChatBody(model, prompt)
            val request = Request.Builder()
                .url(baseUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody(jsonMedia))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful) {
                parseFactCheckResponse(body)
            } else {
                FactCheckResult(false, false, "", error = "HTTP ${response.code}: $body")
            }
        } catch (e: Exception) {
            FactCheckResult(false, false, "", error = e.message ?: "Network error")
        }
    }

    private fun buildFactCheckPrompt(title: String, content: String): String {
        return """
            You are a professional fact-checker for a news agency. Review the following news article and check all factual claims.

            Article Title: $title

            Article Content:
            $content

            Your task:
            1. Identify any factual errors or unverifiable claims
            2. Check if statistics, dates, names are accurate
            3. Flag any misleading statements
            4. Verify against the latest publicly known internet/news context when possible
            5. Provide a confidence score (0-100)

            Respond ONLY in this exact JSON format:
            {
              "is_accurate": true/false,
              "confidence_score": 85,
              "issues_found": ["list of specific issues or empty array"],
              "feedback": "Brief overall assessment",
              "corrected_content": "Full corrected article content if changes needed, or empty string if accurate"
            }
        """.trimIndent()
    }

    private fun buildChatBody(model: String, prompt: String): String {
        val escapedPrompt = com.google.gson.JsonPrimitive(prompt).toString()
        return """
            {
              "model": "$model",
              "messages": [
                {"role": "system", "content": "You are an expert fact-checker. Always respond in valid JSON."},
                {"role": "user", "content": $escapedPrompt}
              ],
              "temperature": 0.2,
              "max_tokens": 2000,
              "response_format": {"type": "json_object"}
            }
        """.trimIndent()
    }

    private fun parseFactCheckResponse(body: String): FactCheckResult {
        return try {
            val json = JsonParser.parseString(body).asJsonObject
            val message = json.getAsJsonArray("choices")[0]
                .asJsonObject.getAsJsonObject("message")
                .get("content").asString

            val result = JsonParser.parseString(message).asJsonObject
            FactCheckResult(
                success = true,
                isAccurate = result.get("is_accurate")?.asBoolean ?: false,
                feedback = result.get("feedback")?.asString ?: "",
                correctedContent = result.get("corrected_content")?.asString ?: "",
                confidenceScore = result.get("confidence_score")?.asFloat ?: 0f
            )
        } catch (e: Exception) {
            Log.e("OpenAiClient", "Parse error: ${e.message}")
            FactCheckResult(false, false, "", error = "Failed to parse response: ${e.message}")
        }
    }

    private fun isQuotaError(error: String): Boolean {
        return error.contains("429") || error.contains("quota", ignoreCase = true) ||
               error.contains("rate_limit", ignoreCase = true) || error.contains("insufficient_quota")
    }
}
