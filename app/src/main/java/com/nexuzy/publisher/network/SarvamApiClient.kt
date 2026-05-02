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
 * THREE ROLES:
 *
 * ROLE 1 — writeArticle():
 *   Backup article writer when ALL Gemini keys/models are exhausted.
 *
 * ROLE 2 — generateSeoData():
 *   Backup SEO generator when Gemini SEO also fails.
 *   Called in AiPipeline STEP 4 fallback.
 *
 * ROLE 3 — checkGrammarAndSpelling():
 *   Grammar & spelling correction after writing, before OpenAI clean step.
 *   Uses sarvam-m chat with explicit grammar+spelling prompt.
 *   Does NOT use the translate API (which only translates, not grammar-checks).
 */
class SarvamApiClient(private val keyManager: ApiKeyManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ─── Data classes ───────────────────────────────────────────────────────────

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

    // ─── ROLE 1: Backup article writer ───────────────────────────────────────

    suspend fun writeArticle(
        rssTitle: String,
        rssDescription: String,
        rssFullContent: String = "",
        category: String = "",
        maxWords: Int = 800
    ): WriteArticleResult {
        val apiKey = keyManager.getSarvamKey()
        if (apiKey.isBlank()) return WriteArticleResult(
            false,
            error = "Sarvam API key not configured. Add your key in Settings."
        )
        return try {
            Log.i("SarvamClient", "[ROLE 1] Writing article as Gemini backup with sarvam-m")
            val prompt = buildArticlePrompt(rssTitle, rssDescription, rssFullContent, category, maxWords)
            callChat(
                apiKey,
                systemPrompt = "You are a professional news journalist. Write clear, factual, engaging articles in formal English. Never invent facts. Output the article directly — no preamble, no commentary.",
                userPrompt = prompt
            ).let { (ok, text, err) ->
                if (ok) WriteArticleResult(true, text)
                else WriteArticleResult(false, error = err)
            }
        } catch (e: Exception) {
            WriteArticleResult(false, error = e.message ?: "Sarvam write error")
        }
    }

    // ─── ROLE 2: Backup SEO generator ────────────────────────────────────

    fun generateSeoData(
        title: String,
        articleContent: String,
        category: String
    ): GeminiApiClient.SeoData {
        val apiKey = keyManager.getSarvamKey()
        if (apiKey.isBlank()) return GeminiApiClient.SeoData(false, error = "Sarvam key not set")

        return try {
            Log.i("SarvamClient", "[ROLE 2] Generating SEO with sarvam-m backup")
            val prompt = """
                You are an SEO expert. Analyze this news article and generate SEO metadata.

                Article Title: $title
                Category: $category
                Article (first 800 chars): ${articleContent.take(800)}

                Respond ONLY in this exact JSON format (no extra text, no markdown):
                {
                  "tags": ["tag1","tag2","tag3","tag4","tag5"],
                  "meta_keywords": "keyword1, keyword2, keyword3, keyword4, keyword5",
                  "focus_keyphrase": "main focus keyphrase here",
                  "meta_description": "Compelling 120-155 character meta description."
                }
            """.trimIndent()

            val (ok, text, err) = callChatSync(
                apiKey,
                systemPrompt = "You are an SEO expert. Always respond with valid JSON only.",
                userPrompt   = prompt
            )

            if (!ok || text.isBlank()) return GeminiApiClient.SeoData(false, error = "Sarvam SEO error: $err")

            parseSeoJson(text)
        } catch (e: Exception) {
            Log.e("SarvamClient", "generateSeoData error: ${e.message}")
            GeminiApiClient.SeoData(false, error = e.message ?: "SEO error")
        }
    }

    private fun parseSeoJson(raw: String): GeminiApiClient.SeoData {
        return try {
            val cleaned = raw.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val json = JsonParser.parseString(cleaned).asJsonObject
            val arr  = json.getAsJsonArray("tags")
            val tags = (0 until arr.size()).map { arr[it].asString }
            GeminiApiClient.SeoData(
                success         = true,
                tags            = tags,
                metaKeywords    = json.get("meta_keywords")?.asString  ?: tags.joinToString(", "),
                focusKeyphrase  = json.get("focus_keyphrase")?.asString ?: "",
                metaDescription = json.get("meta_description")?.asString ?: ""
            )
        } catch (e: Exception) {
            Log.e("SarvamClient", "parseSeoJson error: ${e.message} | raw=$raw")
            GeminiApiClient.SeoData(false, error = "SEO parse failed: ${e.message}")
        }
    }

    // ─── ROLE 3: Grammar & spelling check via sarvam-m chat ────────────────────

    /**
     * Grammar and spelling correction using sarvam-m chat model.
     *
     * Processes the article in 1800-character chunks to stay within
     * context limits. Each chunk is corrected independently and
     * reassembled in order.
     *
     * IMPORTANT: Only fixes grammar/spelling — does NOT change facts,
     * tone, or article structure. If Sarvam key is not set, returns
     * original content unchanged (non-blocking).
     */
    suspend fun checkGrammarAndSpelling(content: String): GrammarCheckResult {
        val apiKey = keyManager.getSarvamKey()
        if (apiKey.isBlank()) {
            Log.w("SarvamClient", "[ROLE 3] Sarvam key not set — skipping grammar check")
            return GrammarCheckResult(
                true, content,
                error = "Sarvam key not configured; grammar check skipped"
            )
        }

        return try {
            Log.i("SarvamClient", "[ROLE 3] Running grammar+spelling check with sarvam-m")
            val chunks = splitIntoChunks(content, 1800)
            val corrected = mutableListOf<String>()

            for ((i, chunk) in chunks.withIndex()) {
                Log.d("SarvamClient", "[ROLE 3] Correcting chunk ${i + 1}/${chunks.size}")
                val (ok, text, err) = callChatSync(
                    apiKey,
                    systemPrompt = """
                        You are a professional English copy editor specialising in news journalism.
                        Your ONLY job is grammar and spelling correction.
                        CRITICAL RULES:
                        - Fix ONLY grammar mistakes and spelling errors.
                        - Do NOT change any facts, names, numbers, dates, or statistics.
                        - Do NOT rewrite sentences unless grammar is broken.
                        - Do NOT add or remove content.
                        - Do NOT add any preamble, notes, or commentary.
                        - Output ONLY the corrected text — nothing else.
                    """.trimIndent(),
                    userPrompt = """
                        Correct only the grammar and spelling in the following news article text.
                        Output the corrected text only — no preamble, no explanation:

                        $chunk
                    """.trimIndent()
                )
                corrected.add(if (ok && text.isNotBlank()) text else chunk)
                if (!ok) Log.w("SarvamClient", "[ROLE 3] Chunk ${i + 1} error: $err")
            }

            GrammarCheckResult(true, corrected.joinToString(" "))
        } catch (e: Exception) {
            Log.e("SarvamClient", "[ROLE 3] Grammar check error: ${e.message}")
            GrammarCheckResult(false, content, error = e.message ?: "Grammar check error")
        }
    }

    // ─── Shared chat calls ───────────────────────────────────────────────────

    private fun callChatSync(
        apiKey: String,
        systemPrompt: String,
        userPrompt: String
    ): Triple<Boolean, String, String> {
        return try {
            val messages = JsonArray().apply {
                add(JsonObject().apply { addProperty("role", "system"); addProperty("content", systemPrompt) })
                add(JsonObject().apply { addProperty("role", "user");   addProperty("content", userPrompt)   })
            }
            val body = JsonObject().apply {
                addProperty("model",       "sarvam-m")
                add("messages",            messages)
                addProperty("temperature", 0.3)
                addProperty("max_tokens",  2048)
            }
            val response = client.newCall(
                Request.Builder()
                    .url("https://api.sarvam.ai/v1/chat/completions")
                    .addHeader("api-subscription-key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(jsonMedia))
                    .build()
            ).execute()
            val respBody = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val content = parseChatReply(respBody)
                if (content.isNotBlank()) Triple(true, content, "")
                else Triple(false, "", "Empty response")
            } else {
                Triple(false, "", "HTTP ${response.code}")
            }
        } catch (e: Exception) {
            Triple(false, "", e.message ?: "Chat error")
        }
    }

    private suspend fun callChat(
        apiKey: String,
        systemPrompt: String,
        userPrompt: String
    ): Triple<Boolean, String, String> = callChatSync(apiKey, systemPrompt, userPrompt)

    private fun parseChatReply(responseBody: String): String {
        return try {
            JsonParser.parseString(responseBody).asJsonObject
                .getAsJsonArray("choices")[0].asJsonObject
                .getAsJsonObject("message")
                .get("content").asString.trim()
        } catch (e: Exception) { "" }
    }

    private fun buildArticlePrompt(
        title: String, desc: String, full: String, category: String, maxWords: Int
    ): String {
        val source = if (full.isNotBlank())
            "--- ORIGINAL ARTICLE ---\n${full.take(3500)}\n---\nSummary: $desc"
        else "Summary: $desc"
        return """
            ███ CRITICAL OUTPUT RULES ███
            - Output the final news article ONLY.
            - Do NOT include thinking, reasoning, or any preamble.
            - Do NOT start with "Okay", "Let me", "I'll", "Sure", "First", or any intro text.
            - Begin IMMEDIATELY with the SEO headline.
            █████████████████████████████

            Write a complete professional news article.
            Headline : $title
            Category : $category
            Target   : ~$maxWords words
            $source
            Requirements: strong lead paragraph, formal tone, rewrite in own words, new SEO headline first.
        """.trimIndent()
    }

    private fun splitIntoChunks(text: String, size: Int): List<String> {
        if (text.length <= size) return listOf(text)
        val chunks = mutableListOf<String>()
        val buf = StringBuilder()
        for (sentence in text.split(". ")) {
            if (buf.length + sentence.length > size) {
                if (buf.isNotEmpty()) chunks.add(buf.toString().trim())
                buf.clear()
            }
            buf.append(sentence).append(". ")
        }
        if (buf.isNotEmpty()) chunks.add(buf.toString().trim())
        return chunks
    }
}
