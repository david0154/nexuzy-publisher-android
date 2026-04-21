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
 * Sarvam AI API client.
 * ONLY used for grammar and spelling correction.
 * Runs after Gemini writes and OpenAI fact-checks the article.
 */
class SarvamApiClient(private val keyManager: ApiKeyManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    data class GrammarCheckResult(
        val success: Boolean,
        val correctedText: String,
        val issuesFound: List<String> = emptyList(),
        val error: String = ""
    )

    /**
     * Check and correct grammar/spelling in the article.
     * Uses Sarvam AI translate/text API to clean up the content.
     */
    suspend fun checkGrammarAndSpelling(content: String): GrammarCheckResult {
        val apiKey = keyManager.getSarvamKey()
        if (apiKey.isBlank()) {
            // Return original content if no key configured — don't block pipeline
            return GrammarCheckResult(true, content, emptyList(), "Sarvam key not set; grammar check skipped")
        }

        return try {
            // Split into chunks if content is very long (Sarvam has limits)
            val chunks = splitIntoChunks(content, 2000)
            val correctedChunks = mutableListOf<String>()
            val allIssues = mutableListOf<String>()

            for (chunk in chunks) {
                val result = callSarvamGrammarApi(apiKey, chunk)
                if (result.success) {
                    correctedChunks.add(result.correctedText)
                    allIssues.addAll(result.issuesFound)
                } else {
                    correctedChunks.add(chunk) // Keep original on error
                    Log.w("SarvamClient", "Grammar check failed for chunk: ${result.error}")
                }
            }

            GrammarCheckResult(
                success = true,
                correctedText = correctedChunks.joinToString(" "),
                issuesFound = allIssues
            )
        } catch (e: Exception) {
            GrammarCheckResult(false, content, emptyList(), e.message ?: "Error")
        }
    }

    private fun callSarvamGrammarApi(apiKey: String, text: String): GrammarCheckResult {
        return try {
            // Use Sarvam's text API for grammar correction
            val escapedText = com.google.gson.JsonPrimitive(text).toString()
            val requestBody = """
                {
                  "input": $escapedText,
                  "source_language_code": "en-IN",
                  "target_language_code": "en-IN",
                  "speaker_gender": "Male",
                  "mode": "formal",
                  "model": "mayura:v1",
                  "enable_preprocessing": true
                }
            """.trimIndent()

            val request = Request.Builder()
                .url("https://api.sarvam.ai/translate")
                .addHeader("api-subscription-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody(jsonMedia))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JsonParser.parseString(body).asJsonObject
                val translated = json.get("translated_text")?.asString ?: text
                GrammarCheckResult(true, translated.ifBlank { text })
            } else {
                Log.w("SarvamClient", "HTTP ${response.code}: $body")
                GrammarCheckResult(false, text, error = "HTTP ${response.code}")
            }
        } catch (e: Exception) {
            GrammarCheckResult(false, text, error = e.message ?: "Error")
        }
    }

    private fun splitIntoChunks(text: String, chunkSize: Int): List<String> {
        if (text.length <= chunkSize) return listOf(text)
        val chunks = mutableListOf<String>()
        val sentences = text.split(". ")
        val current = StringBuilder()
        for (sentence in sentences) {
            if (current.length + sentence.length > chunkSize) {
                if (current.isNotEmpty()) chunks.add(current.toString())
                current.clear()
            }
            current.append(sentence).append(". ")
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }
}
