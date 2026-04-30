package com.nexuzy.publisher.network

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Sarvam AI API client.
 *
 * TWO ROLES:
 *
 * ROLE 1 — writeArticle():
 *   Backup article writer when ALL Gemini keys/models are exhausted.
 *   Uses Sarvam's sarvam-m model via /v1/chat/completions.
 *   Uses the USER'S configured Sarvam API key (from Settings).
 *
 * ROLE 2 — checkGrammarAndSpelling():
 *   Grammar & spelling correction of written articles.
 *   Runs AFTER Gemini (or Sarvam fallback) writes the article.
 *   Also uses the user's configured Sarvam API key.
 *
 * NOTE: For the David AI in-app chat, see SarvamChatClient.kt (developer key, separate).
 */
class SarvamApiClient(private val keyManager: ApiKeyManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ─────────────────────────────────────────────────────────────────────────
    // Data classes
    // ─────────────────────────────────────────────────────────────────────────

    data class WriteArticleResult(
        val success: Boolean,
        val content: String = "",
        val error: String = ""
    )

    data class GrammarCheckResult(
        val success: Boolean,
        val correctedText: String,
        val issuesFound: List<String> = emptyList(),
        val error: String = ""
    )

    // ─────────────────────────────────────────────────────────────────────────
    // ROLE 1: Backup article writer (called when Gemini fails)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Write a news article using Sarvam AI (sarvam-m model).
     *
     * Called ONLY as a fallback when all Gemini keys/models are exhausted.
     * Uses the user's configured Sarvam API key from Settings.
     *
     * @param rssTitle       Original RSS headline.
     * @param rssDescription Short RSS summary.
     * @param rssFullContent Full scraped article body (if available). Passed to Sarvam
     *                       for accurate rewriting — same as Gemini gets.
     * @param category       Article category.
     * @param maxWords       Target article length.
     */
    suspend fun writeArticle(
        rssTitle: String,
        rssDescription: String,
        rssFullContent: String = "",
        category: String = "",
        maxWords: Int = 800
    ): WriteArticleResult {
        val apiKey = keyManager.getSarvamKey()
        if (apiKey.isBlank()) {
            return WriteArticleResult(
                false,
                error = "Sarvam API key not configured. " +
                        "Please add your Sarvam key in Settings to enable the backup writer."
            )
        }

        return try {
            Log.i("SarvamApiClient", "Gemini unavailable — writing article with Sarvam sarvam-m")
            val prompt = buildArticlePrompt(rssTitle, rssDescription, rssFullContent, category, maxWords)
            callSarvamChat(apiKey, prompt)
        } catch (e: Exception) {
            Log.e("SarvamApiClient", "writeArticle error: ${e.message}")
            WriteArticleResult(false, error = e.message ?: "Sarvam write error")
        }
    }

    private fun callSarvamChat(apiKey: String, prompt: String): WriteArticleResult {
        return try {
            val messages = JsonArray().apply {
                // System role
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty(
                        "content",
                        "You are a professional news journalist. Write clear, factual, " +
                        "engaging news articles in formal English. Never invent facts."
                    )
                })
                // User prompt with article facts
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", prompt)
                })
            }

            val body = JsonObject().apply {
                addProperty("model", "sarvam-m")
                add("messages", messages)
                addProperty("temperature", 0.7)
                addProperty("max_tokens", 2048)
            }

            val request = Request.Builder()
                .url("https://api.sarvam.ai/v1/chat/completions")
                .addHeader("api-subscription-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonMedia))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val content = parseChatReply(responseBody)
                if (content.isNotBlank()) {
                    Log.i("SarvamApiClient", "Sarvam backup writer success (${content.length} chars)")
                    WriteArticleResult(true, content)
                } else {
                    WriteArticleResult(false, error = "Sarvam returned empty article")
                }
            } else {
                Log.w("SarvamApiClient", "Sarvam write HTTP ${response.code}: $responseBody")
                WriteArticleResult(false, error = "Sarvam HTTP ${response.code}")
            }
        } catch (e: Exception) {
            WriteArticleResult(false, error = e.message ?: "Sarvam chat error")
        }
    }

    private fun buildArticlePrompt(
        title: String,
        desc: String,
        fullContent: String,
        category: String,
        maxWords: Int
    ): String {
        val sourceSection = if (fullContent.isNotBlank()) {
            """
            |--- ORIGINAL ARTICLE (scraped from source) ---
            |${fullContent.take(3500)}
            |----------------------------------------------
            |
            |Short RSS Summary: $desc
            """.trimMargin()
        } else {
            "RSS Summary/Description: $desc\n(Full article not available. Write from title and summary.)"
        }

        return """
            Write a complete professional news article.

            Original Headline : $title
            Category          : $category
            Target length     : approximately $maxWords words

            $sourceSection

            Requirements:
            - Strong lead paragraph (who, what, when, where, why)
            - Objective, professional journalistic tone
            - Rewrite in your own words — do NOT copy verbatim
            - Do NOT add quotes or statistics not present in the source
            - Write a new SEO-friendly headline first, then the article body
            - Use ONLY facts from the source provided above

            Write the complete article now (headline first, then body):
        """.trimIndent()
    }

    private fun parseChatReply(responseBody: String): String {
        return try {
            JsonParser.parseString(responseBody).asJsonObject
                .getAsJsonArray("choices")[0]
                .asJsonObject
                .getAsJsonObject("message")
                .get("content").asString.trim()
        } catch (e: Exception) {
            Log.e("SarvamApiClient", "Chat reply parse error: ${e.message}")
            ""
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ROLE 2: Grammar & spelling correction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Check and correct grammar/spelling in the written article.
     * Uses Sarvam AI translate API (en-IN formal mode).
     * Called after article is written (by Gemini OR Sarvam backup writer).
     */
    suspend fun checkGrammarAndSpelling(content: String): GrammarCheckResult {
        val apiKey = keyManager.getSarvamKey()
        if (apiKey.isBlank()) {
            return GrammarCheckResult(
                success = true,
                correctedText = content,
                error = "Sarvam key not set; grammar check skipped"
            )
        }

        return try {
            val chunks = splitIntoChunks(content, 2000)
            val correctedChunks = mutableListOf<String>()
            val allIssues = mutableListOf<String>()

            for (chunk in chunks) {
                val result = callSarvamTranslate(apiKey, chunk)
                if (result.success) {
                    correctedChunks.add(result.correctedText)
                    allIssues.addAll(result.issuesFound)
                } else {
                    correctedChunks.add(chunk)
                    Log.w("SarvamApiClient", "Grammar check failed for chunk: ${result.error}")
                }
            }

            GrammarCheckResult(
                success = true,
                correctedText = correctedChunks.joinToString(" "),
                issuesFound = allIssues
            )
        } catch (e: Exception) {
            Log.e("SarvamApiClient", "checkGrammarAndSpelling error: ${e.message}")
            GrammarCheckResult(false, content, emptyList(), e.message ?: "Error")
        }
    }

    private fun callSarvamTranslate(apiKey: String, text: String): GrammarCheckResult {
        return try {
            val escapedText = JsonPrimitive(text).toString()
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
                Log.w("SarvamApiClient", "Translate HTTP ${response.code}: $body")
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
