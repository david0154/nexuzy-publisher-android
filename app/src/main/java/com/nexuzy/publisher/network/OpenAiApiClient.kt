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
 * OpenAI API client — TWO dedicated roles using specific key slots:
 *
 * KEY ROLES:
 *   Key 1 → Fact checking (primary)
 *   Key 2 → Article clean output only (dedicated)
 *   Key 3 → Fact checking (fallback when Key 1 exhausted)
 *
 * ROLE 1 — factCheckArticle():
 *   Verifies facts in Gemini/Sarvam-written articles.
 *   Uses Key 1 first, falls back to Key 3 on quota error.
 *   Key 2 is NEVER used for fact-checking.
 *
 * ROLE 2 — cleanArticleOutput():
 *   Cleans and polishes the final article text before WordPress publish.
 *   Uses Key 2 ONLY. If Key 2 is not set, returns original text unchanged.
 *
 * Pipeline order (called from publish flow):
 *   Gemini or Sarvam writes article
 *       ↓
 *   Sarvam grammar + spelling check
 *       ↓
 *   OpenAI Key 2 → cleanArticleOutput()  [remove AI artifacts, fix formatting]
 *       ↓
 *   OpenAI Key 1/3 → factCheckArticle()  [verify facts, correct errors]
 *       ↓
 *   Publish to WordPress
 */
class OpenAiApiClient(private val keyManager: ApiKeyManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = "https://api.openai.com/v1/chat/completions"

    // ─────────────────────────────────────────────────────────────────────────
    // Data classes
    // ─────────────────────────────────────────────────────────────────────────

    data class FactCheckResult(
        val success: Boolean,
        val isAccurate: Boolean,
        val feedback: String,
        val correctedContent: String = "",
        val confidenceScore: Float = 0f,
        val error: String = "",
        val keyUsed: Int = 1
    )

    data class CleanOutputResult(
        val success: Boolean,
        val cleanedContent: String,
        val error: String = ""
    )

    // ─────────────────────────────────────────────────────────────────────────
    // ROLE 1: Fact-check  — Key 1 (primary) + Key 3 (fallback)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fact-check an article.
     * Uses Key 1 first. If quota exceeded, falls back to Key 3.
     * Key 2 is intentionally skipped — it is reserved for clean output only.
     */
    suspend fun factCheckArticle(
        title: String,
        content: String,
        model: String = "gpt-4o-mini"
    ): FactCheckResult {
        // Key slots for fact-checking: 1 and 3 (skip 2)
        val factCheckKeyIndices = listOf(1, 3)
        val allKeys = (1..3).map { keyManager.getOpenAiKey(it) }

        val keysToTry = factCheckKeyIndices
            .map { idx -> Pair(idx, allKeys.getOrElse(idx - 1) { "" }) }
            .filter { (_, key) -> key.isNotBlank() }

        if (keysToTry.isEmpty()) {
            return FactCheckResult(
                false, false, "",
                error = "No OpenAI API keys for fact-checking. Set Key 1 or Key 3 in Settings."
            )
        }

        val prompt = buildFactCheckPrompt(title, content)

        for ((keyIndex, key) in keysToTry) {
            Log.d("OpenAiClient", "[FACT-CHECK] Trying Key $keyIndex")
            val result = callOpenAiApi(key, model, prompt)
            if (result.success) {
                Log.i("OpenAiClient", "[FACT-CHECK] ✔ Key $keyIndex succeeded")
                return result.copy(keyUsed = keyIndex)
            }
            if (isQuotaError(result.error)) {
                Log.w("OpenAiClient", "[FACT-CHECK] Key $keyIndex quota exceeded, trying next")
                keyManager.rotateOpenAiKey()
                continue
            }
            return result.copy(keyUsed = keyIndex)
        }
        return FactCheckResult(
            false, false, "",
            error = "Both fact-check keys (Key 1 & Key 3) are exhausted. Try again later or add keys in Settings."
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ROLE 2: Clean article output — Key 2 ONLY
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Clean and polish the article text using Key 2 exclusively.
     * Removes any remaining AI preamble, thinking text, duplicate lines,
     * and formatting artifacts before the article is fact-checked and published.
     *
     * If Key 2 is not configured, returns the original content unchanged
     * (non-blocking — cleaning is best-effort).
     */
    suspend fun cleanArticleOutput(
        title: String,
        content: String,
        model: String = "gpt-4o-mini"
    ): CleanOutputResult {
        val key2 = keyManager.getOpenAiKey(2)
        if (key2.isBlank()) {
            Log.w("OpenAiClient", "[CLEAN] Key 2 not set — skipping clean step, using original content")
            return CleanOutputResult(true, content, error = "Key 2 not configured; clean step skipped")
        }

        Log.d("OpenAiClient", "[CLEAN] Running article clean with Key 2")
        val prompt = buildCleanPrompt(title, content)
        return try {
            val requestBody = buildChatBody(model, prompt,
                systemPrompt = "You are a professional news editor. Output only clean, publishable article text. Never add commentary, preamble, or notes.")
            val request = Request.Builder()
                .url(baseUrl)
                .addHeader("Authorization", "Bearer $key2")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody(jsonMedia))
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val cleaned = extractMessageContent(body)
                if (cleaned.isNotBlank()) {
                    Log.i("OpenAiClient", "[CLEAN] ✔ Article cleaned with Key 2")
                    CleanOutputResult(true, cleaned)
                } else {
                    Log.w("OpenAiClient", "[CLEAN] Key 2 returned empty — using original")
                    CleanOutputResult(true, content, error = "Clean returned empty; using original")
                }
            } else {
                Log.w("OpenAiClient", "[CLEAN] Key 2 HTTP ${response.code} — using original")
                CleanOutputResult(true, content, error = "HTTP ${response.code}; using original content")
            }
        } catch (e: Exception) {
            Log.e("OpenAiClient", "[CLEAN] Exception: ${e.message} — using original")
            CleanOutputResult(true, content, error = e.message ?: "Clean error; using original")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal: HTTP call for fact-check
    // ─────────────────────────────────────────────────────────────────────────

    private fun callOpenAiApi(apiKey: String, model: String, prompt: String): FactCheckResult {
        return try {
            val requestBody = buildChatBody(
                model, prompt,
                systemPrompt = "You are an expert fact-checker. Always respond in valid JSON only. Never output anything outside the JSON object.",
                jsonMode = true
            )
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

    // ─────────────────────────────────────────────────────────────────────────
    // Prompt builders
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildFactCheckPrompt(title: String, content: String): String {
        return """
            CRITICAL OUTPUT RULES:
            - Respond ONLY with the JSON object specified below. Nothing before it, nothing after it.
            - If corrected_content is needed, output ONLY the clean corrected article text.
            - Do NOT include any thinking, reasoning, planning, or commentary in corrected_content.
            - Do NOT start corrected_content with "Okay", "Let me", "Here is", "I have", "Sure", or any preamble.
            - corrected_content must begin directly with the article headline and end with the last article paragraph.
            - corrected_content must contain ONLY the publishable article — no meta-text, no notes, no explanations.

            You are a professional fact-checker for a news agency. Review the following news article and check all factual claims.

            Article Title: $title

            Article Content:
            $content

            Your task:
            1. Identify any factual errors or unverifiable claims
            2. Check if statistics, dates, names, and titles are accurate
            3. Flag any misleading statements
            4. Verify against the latest publicly known internet/news context when possible
            5. If corrections are needed, rewrite ONLY the corrected article body in corrected_content (clean, publishable text only)
            6. Provide a confidence score (0-100)

            Respond ONLY in this exact JSON format:
            {
              "is_accurate": true/false,
              "confidence_score": 85,
              "issues_found": ["list of specific issues, or empty array if none"],
              "feedback": "Brief overall assessment in 1-2 sentences",
              "corrected_content": "Full corrected article text if changes were needed, or empty string if article is already accurate"
            }
        """.trimIndent()
    }

    private fun buildCleanPrompt(title: String, content: String): String {
        return """
            CRITICAL OUTPUT RULES:
            - Output ONLY the cleaned article text. Nothing before it. Nothing after it.
            - Do NOT add any commentary, notes, or explanations.
            - Do NOT start with "Okay", "Here is", "Sure", "I have cleaned", or any preamble.
            - Begin your response IMMEDIATELY with the article headline.
            - End your response with the last paragraph of the article.

            You are a professional news editor. Clean and polish the following article:

            Article Title: $title

            Article Content:
            $content

            Cleaning tasks:
            1. Remove any AI thinking text, preamble, or meta-commentary (e.g. "Okay, let me write...", "Here is the article:", "I need to...")
            2. Remove any duplicate headlines or repeated paragraphs
            3. Remove any bylines, image captions, or photo credit lines
            4. Fix any obvious formatting issues (double spaces, broken paragraphs)
            5. Preserve ALL original facts, quotes, and information — do NOT change the content
            6. Keep the article length the same — do NOT summarise or shorten

            Output the cleaned article now (headline first, then body paragraphs):
        """.trimIndent()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Request body builders
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildChatBody(
        model: String,
        prompt: String,
        systemPrompt: String,
        jsonMode: Boolean = false
    ): String {
        val escapedPrompt  = com.google.gson.JsonPrimitive(prompt).toString()
        val escapedSystem  = com.google.gson.JsonPrimitive(systemPrompt).toString()
        val responseFormat = if (jsonMode) ",\n  \"response_format\": {\"type\": \"json_object\"}" else ""
        return """
            {
              "model": "$model",
              "messages": [
                {"role": "system", "content": $escapedSystem},
                {"role": "user",   "content": $escapedPrompt}
              ],
              "temperature": 0.2,
              "max_tokens": 2000$responseFormat
            }
        """.trimIndent()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Response parsers
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseFactCheckResponse(body: String): FactCheckResult {
        return try {
            val json = JsonParser.parseString(body).asJsonObject
            val message = json.getAsJsonArray("choices")[0]
                .asJsonObject.getAsJsonObject("message")
                .get("content").asString
            val result = JsonParser.parseString(message).asJsonObject
            FactCheckResult(
                success          = true,
                isAccurate       = result.get("is_accurate")?.asBoolean ?: false,
                feedback         = result.get("feedback")?.asString ?: "",
                correctedContent = result.get("corrected_content")?.asString ?: "",
                confidenceScore  = result.get("confidence_score")?.asFloat ?: 0f
            )
        } catch (e: Exception) {
            Log.e("OpenAiClient", "Parse error: ${e.message}")
            FactCheckResult(false, false, "", error = "Failed to parse response: ${e.message}")
        }
    }

    private fun extractMessageContent(body: String): String {
        return try {
            JsonParser.parseString(body).asJsonObject
                .getAsJsonArray("choices")[0]
                .asJsonObject.getAsJsonObject("message")
                .get("content").asString.trim()
        } catch (e: Exception) {
            Log.e("OpenAiClient", "extractMessageContent error: ${e.message}")
            ""
        }
    }

    private fun isQuotaError(error: String): Boolean {
        return error.contains("429") || error.contains("quota", ignoreCase = true) ||
               error.contains("rate_limit", ignoreCase = true) || error.contains("insufficient_quota")
    }
}
