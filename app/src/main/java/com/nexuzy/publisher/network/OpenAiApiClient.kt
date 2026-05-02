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
 *   Strips any AI thinking text / artifacts left by Gemini or Sarvam,
 *   then rewrites the article to sound fully human.
 *   Uses Key 2 ONLY. If Key 2 is not set, returns original text unchanged.
 *
 * Pipeline order (called from publish flow):
 *   Gemini or Sarvam writes article
 *       ↓
 *   Sarvam grammar + spelling check
 *       ↓
 *   OpenAI Key 2 → cleanArticleOutput()  [remove AI artifacts, humanise writing]
 *       ↓
 *   OpenAI Key 1/3 → factCheckArticle()  [verify facts, correct errors]
 *       ↓
 *   Publish to WordPress
 *
 * HUMAN WRITING FIX (v2):
 *   - cleanArticleOutput temperature raised from 0.2 → 1.0
 *   - frequencyPenalty 0.5 + presencePenalty 0.4 added to break repetitive phrasing
 *   - Clean prompt rewritten as a journalist persona — tells GPT to REWRITE
 *     in a human voice, not just "remove artifacts"
 *   - Banned AI-filler word list injected into clean prompt
 *   - factCheck temperature stays at 0.1 (accuracy task, not creative)
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

    suspend fun factCheckArticle(
        title: String,
        content: String,
        model: String = "gpt-4o-mini"
    ): FactCheckResult {
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
    // ROLE 2: Clean + Humanise article output — Key 2 ONLY
    // ─────────────────────────────────────────────────────────────────────────

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

        Log.d("OpenAiClient", "[CLEAN] Running article clean+humanise with Key 2")
        val prompt = buildCleanPrompt(title, content)
        return try {
            val requestBody = buildCleanChatBody(model, prompt)
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
                    Log.i("OpenAiClient", "[CLEAN] ✔ Article cleaned and humanised with Key 2")
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
            val requestBody = buildFactCheckChatBody(model, prompt)
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

    private fun buildCleanPrompt(title: String, content: String): String {
        return """
You are a senior news editor at a major digital publication. A junior reporter just filed this article and it has problems — it reads like it was written by a machine. Your job is to rewrite it so it sounds like a real human journalist wrote it.

Original headline: $title

Filed article:
$content

Your editing job:
Strip out any AI thinking text that leaked into the article ("Okay, let's tackle this", "Let me", "I need to", "Here is the article", "Sure", or any planning/reasoning text before the actual story). Then rewrite the article in a natural, human journalist voice. Keep every fact, figure, and quote that exists in the filed article — do not add or remove information. Just fix the voice.

The finished article must sound like it was written by a person, not a machine. Vary sentence lengths. Some sentences are short and punchy. Others carry more context. Do not use these words anywhere in the rewrite: "Furthermore", "Moreover", "In conclusion", "It is worth noting", "Notably", "Underscore", "Delve", "Landscape", "Pivotal", "Navigate", "In an era of", "This underscores", "Shed light on".

No section headers. No bullet points. Pure flowing news prose only. Start directly with the headline. End with the final paragraph of the story. Do not add any editor's notes, bylines, or tags.

Rewritten article:
        """.trimIndent()
    }

    private fun buildFactCheckPrompt(title: String, content: String): String {
        return """
You are a professional fact-checker for a news agency. Review the following news article and check all factual claims.

Article Title: $title

Article Content:
$content

Your task:
1. Identify any factual errors or unverifiable claims
2. Check if statistics, dates, names, and titles are accurate
3. Flag any misleading statements
4. Verify against the latest publicly known internet/news context when possible
5. If corrections are needed, rewrite ONLY the corrected article body in corrected_content (clean, publishable text only — start directly with the headline, no preamble)
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

    // ─────────────────────────────────────────────────────────────────────────
    // Request body builders — separate configs for clean vs fact-check
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Clean/humanise step: higher temperature for natural varied writing.
     * frequencyPenalty + presencePenalty break repetitive phrasing.
     */
    private fun buildCleanChatBody(model: String, prompt: String): String {
        val escapedPrompt = com.google.gson.JsonPrimitive(prompt).toString()
        val systemMsg = com.google.gson.JsonPrimitive(
            "You are a senior news editor. Rewrite the article in a natural human journalist voice. " +
            "Output ONLY the finished article — headline first, then body. No preamble, no notes, no commentary."
        ).toString()
        return """
            {
              "model": "$model",
              "messages": [
                {"role": "system", "content": $systemMsg},
                {"role": "user",   "content": $escapedPrompt}
              ],
              "temperature": 1.0,
              "frequency_penalty": 0.5,
              "presence_penalty": 0.4,
              "max_tokens": 2000
            }
        """.trimIndent()
    }

    /**
     * Fact-check step: low temperature for accuracy, JSON mode enforced.
     */
    private fun buildFactCheckChatBody(model: String, prompt: String): String {
        val escapedPrompt = com.google.gson.JsonPrimitive(prompt).toString()
        val systemMsg = com.google.gson.JsonPrimitive(
            "You are an expert fact-checker. Always respond in valid JSON only. Never output anything outside the JSON object."
        ).toString()
        return """
            {
              "model": "$model",
              "messages": [
                {"role": "system", "content": $systemMsg},
                {"role": "user",   "content": $escapedPrompt}
              ],
              "temperature": 0.1,
              "max_tokens": 2000,
              "response_format": {"type": "json_object"}
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
