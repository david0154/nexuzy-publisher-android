package com.nexuzy.publisher.network

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Sarvam AI API client.
 *
 * THREE ROLES:
 *
 * ROLE 1 — writeArticle():
 *   Backup article writer when ALL Gemini keys/models are exhausted.
 *   Uses the same journalist-persona prompt style as GeminiApiClient
 *   so backup articles sound as human as primary ones.
 *
 * ROLE 2 — generateSeoData():
 *   Backup SEO generator when Gemini SEO also fails.
 *
 * ROLE 3 — checkGrammarAndSpelling():
 *   Grammar + spelling correction PLUS humanise pass after writing.
 *   Strips AI thinking leakage, fixes grammar, rewrites robotic phrasing
 *   in a natural journalist voice. Non-blocking: falls back to original
 *   if Sarvam key is not set.
 *
 * HUMAN WRITING FIX (v2):
 *   - writeArticle: temperature 0.3 → 1.05, journalist persona prompt,
 *     banned AI-filler word list, no bullet-rule instructions
 *   - checkGrammarAndSpelling: temperature 0.3 → 1.0, rewritten as senior
 *     editor humanise pass (not just grammar cop)
 *   - frequencyPenalty + presencePenalty added to both writing calls
 */
class SarvamApiClient(private val keyManager: ApiKeyManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ─── Data classes ─────────────────────────────────────────────────────────────────────

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

    // ─── ROLE 1: Backup article writer ─────────────────────────────────────────────────────

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
                systemPrompt = "You are a seasoned news journalist with 15 years of experience. " +
                    "Write directly in a natural human voice — no preamble, no thinking out loud, no commentary. " +
                    "Start immediately with the headline.",
                userPrompt = prompt,
                temperature = 1.05,
                frequencyPenalty = 0.4,
                presencePenalty = 0.3
            ).let { (ok, text, err) ->
                if (ok) WriteArticleResult(true, text)
                else WriteArticleResult(false, error = err)
            }
        } catch (e: Exception) {
            WriteArticleResult(false, error = e.message ?: "Sarvam write error")
        }
    }

    // ─── ROLE 2: Backup SEO generator ───────────────────────────────────────────────────

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
                userPrompt   = prompt,
                temperature  = 0.2
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

    // ─── ROLE 3: Grammar check + Humanise pass ──────────────────────────────────────────

    /**
     * Grammar + spelling correction AND humanise pass using sarvam-m.
     *
     * This does TWO things in one pass:
     *   1. Strips any AI thinking text that leaked in ("Okay, let me...", etc.)
     *   2. Rewrites robotic AI phrasing into natural journalist language
     *   3. Fixes grammar and spelling errors
     *
     * Processes in 1800-char chunks. Non-blocking: falls back to original
     * if Sarvam key not set.
     */
    suspend fun checkGrammarAndSpelling(content: String): GrammarCheckResult {
        val apiKey = keyManager.getSarvamKey()
        if (apiKey.isBlank()) {
            Log.w("SarvamClient", "[ROLE 3] Sarvam key not set — skipping grammar/humanise step")
            return GrammarCheckResult(
                true, content,
                error = "Sarvam key not configured; grammar check skipped"
            )
        }

        return try {
            Log.i("SarvamClient", "[ROLE 3] Running grammar + humanise pass with sarvam-m")
            val chunks = splitIntoChunks(content, 1800)
            val corrected = mutableListOf<String>()

            for ((i, chunk) in chunks.withIndex()) {
                Log.d("SarvamClient", "[ROLE 3] Processing chunk ${i + 1}/${chunks.size}")
                val (ok, text, err) = callChatSync(
                    apiKey,
                    systemPrompt = "You are a senior news editor at a major publication. " +
                        "Your job is to fix grammar, fix spelling, strip any AI thinking text that leaked in, " +
                        "and rewrite any robotic phrasing into natural journalist language. " +
                        "Keep all facts, names, numbers and quotes exactly as they are. " +
                        "Output only the rewritten text — no notes, no commentary.",
                    userPrompt = buildHumaniseChunkPrompt(chunk),
                    temperature = 1.0,
                    frequencyPenalty = 0.5,
                    presencePenalty  = 0.4
                )
                corrected.add(if (ok && text.isNotBlank()) text else chunk)
                if (!ok) Log.w("SarvamClient", "[ROLE 3] Chunk ${i + 1} error: $err")
            }

            GrammarCheckResult(true, corrected.joinToString(" "))
        } catch (e: Exception) {
            Log.e("SarvamClient", "[ROLE 3] Error: ${e.message}")
            GrammarCheckResult(false, content, error = e.message ?: "Grammar/humanise error")
        }
    }

    // ─── Prompt builders ───────────────────────────────────────────────────────────────────

    private fun buildArticlePrompt(
        title: String, desc: String, full: String, category: String, maxWords: Int
    ): String {
        val today = SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH).format(Date())
        val source = if (full.isNotBlank())
            "--- SOURCE ARTICLE ---\n${full.take(3500)}\n--- END SOURCE ---\nRSS summary: $desc"
        else
            "RSS summary: $desc\n(Full article unavailable — write from title and summary.)"

        return """
You are a seasoned news journalist with 15 years of experience writing for major digital publications. You have a sharp, direct voice — you get to the point fast and your writing never sounds like it came from a machine.

Today is $today. Write a fresh news article for the "$category" section.

SOURCE MATERIAL:
Headline: $title
$source

Write a compelling, publish-ready news article of approximately $maxWords words based only on the facts above. Do not invent quotes, statistics, or events not present in the source.

WRITING STYLE:
- Sound like a human journalist, not an AI. Vary your sentence length. Some sentences are short. Others build context before landing the point.
- Write a punchy SEO headline first. Direct and specific — no colons, no "How", no "Why", no "Everything You Need to Know".
- Your first sentence drops the reader straight into the story. No "In a significant development", no "As the world watches", no "In today's rapidly evolving landscape".
- Never use: "Furthermore", "Moreover", "In conclusion", "It is worth noting", "Delve into", "This underscores", "Notably", "Landscape", "In an era of", "Navigate", "Pivotal", "Shed light on".
- No section headers. No bullet points. Pure flowing article prose only.
- Vary paragraph length. Not every paragraph is 3 sentences. Some are one. Some are four.
- End naturally — a forward-looking sentence, a real quote from the source, or a crisp observation. Never "Only time will tell" or "remains to be seen".
- Do not add bylines, photo captions, or Tags at the end.

Write the article now (headline first, then body paragraphs):
        """.trimIndent()
    }

    private fun buildHumaniseChunkPrompt(chunk: String): String {
        return """
You are a senior news editor. A reporter filed this text and parts of it sound robotic or have AI thinking text mixed in. Fix it.

Do all of the following in one pass:
1. Remove any AI thinking text that leaked in ("Okay, let's tackle this", "Let me", "I need to", "Sure", "Here is", or any planning text)
2. Fix all grammar and spelling errors
3. Rewrite any sentence that sounds like an AI wrote it — make it sound like a human journalist
4. Do NOT change any facts, names, numbers, quotes, or dates
5. Do not use: "Furthermore", "Moreover", "Notably", "This underscores", "Landscape", "Pivotal", "Delve", "In an era of"

Output only the rewritten text. No notes, no commentary, no explanation.

Filed text:
$chunk

Rewritten text:
        """.trimIndent()
    }

    // ─── Shared chat calls ───────────────────────────────────────────────────────────────────

    private fun callChatSync(
        apiKey: String,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double = 0.7,
        frequencyPenalty: Double = 0.0,
        presencePenalty: Double = 0.0
    ): Triple<Boolean, String, String> {
        return try {
            val messages = JsonArray().apply {
                add(JsonObject().apply { addProperty("role", "system"); addProperty("content", systemPrompt) })
                add(JsonObject().apply { addProperty("role", "user");   addProperty("content", userPrompt)   })
            }
            val body = JsonObject().apply {
                addProperty("model",             "sarvam-m")
                add("messages",                  messages)
                addProperty("temperature",       temperature)
                addProperty("max_tokens",        2048)
                if (frequencyPenalty != 0.0) addProperty("frequency_penalty", frequencyPenalty)
                if (presencePenalty  != 0.0) addProperty("presence_penalty",  presencePenalty)
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
        userPrompt: String,
        temperature: Double = 0.7,
        frequencyPenalty: Double = 0.0,
        presencePenalty: Double = 0.0
    ): Triple<Boolean, String, String> =
        callChatSync(apiKey, systemPrompt, userPrompt, temperature, frequencyPenalty, presencePenalty)

    private fun parseChatReply(responseBody: String): String {
        return try {
            JsonParser.parseString(responseBody).asJsonObject
                .getAsJsonArray("choices")[0].asJsonObject
                .getAsJsonObject("message")
                .get("content").asString.trim()
        } catch (e: Exception) { "" }
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
