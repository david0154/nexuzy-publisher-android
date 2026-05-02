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

class SarvamApiClient(private val keyManager: ApiKeyManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // Sarvam starter tier: max 2048 output tokens. 450 words ≈ 620 tokens.
    private val SARVAM_MAX_TOKENS = 2048
    private val SARVAM_MIN_WORDS  = 300
    private val SARVAM_MAX_WORDS  = 450

    // Thinking preamble prefixes to strip from top of output
    private val THINKING_PREFIXES = listOf(
        "okay, let's", "okay, i'll", "okay! let's", "let me ", "let's tackle",
        "let's think", "alright,", "sure! here", "sure, here", "here is",
        "here's the", "i need to", "i'll ", "i will ", "as requested,",
        "of course,", "certainly,", "absolutely,", "the user wants",
        "the user is asking", "my task", "i'll start", "first, i'll",
        "first i'll", "i'm going to", "i've been asked"
    )
    private val TAGS_LINE_REGEX = Regex("""(?im)^[\uFE0F🏷️\s]*tags?\s*:.*$""")
    private val TRAILING_HASHTAG_REGEX = Regex("""(?im)^(#\w+[\s·•\-]*){2,}$""")

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
        maxWords: Int = 400
    ): WriteArticleResult {
        val apiKey = keyManager.getSarvamKey()
        if (apiKey.isBlank()) return WriteArticleResult(
            false, error = "Sarvam API key not configured. Add your key in Settings."
        )

        val safeWords = maxWords.coerceIn(SARVAM_MIN_WORDS, SARVAM_MAX_WORDS)
        Log.i("SarvamClient", "[ROLE 1] Writing article ($safeWords words) as Gemini backup")

        // Attempt 1: full prompt
        val attempt1 = tryWriteArticle(
            apiKey, rssTitle, rssDescription, rssFullContent, category, safeWords,
            useSimplePrompt = false
        )
        if (attempt1.success) return attempt1

        // Attempt 2: stripped-down prompt (model returned only junk on first try)
        Log.w("SarvamClient", "[ROLE 1] First attempt failed/junk — retrying with simpler prompt")
        return tryWriteArticle(
            apiKey, rssTitle, rssDescription, rssFullContent, category, safeWords,
            useSimplePrompt = true
        )
    }

    private suspend fun tryWriteArticle(
        apiKey: String,
        rssTitle: String,
        rssDescription: String,
        rssFullContent: String,
        category: String,
        safeWords: Int,
        useSimplePrompt: Boolean
    ): WriteArticleResult {
        return try {
            val prompt = if (useSimplePrompt)
                buildSimpleArticlePrompt(rssTitle, rssDescription, safeWords)
            else
                buildArticlePrompt(rssTitle, rssDescription, rssFullContent, category, safeWords)

            val sysPrompt = if (useSimplePrompt)
                "You are a news journalist. Write the article immediately. No preamble. No tags at the end."
            else
                "You are a seasoned news journalist with 15 years of experience. " +
                "Write directly in a natural human voice — no preamble, no thinking out loud. " +
                "Start immediately with the article headline. " +
                "Do NOT include any Tags, hashtags, or bylines at the end."

            callChat(
                apiKey,
                systemPrompt = sysPrompt,
                userPrompt = prompt,
                temperature = if (useSimplePrompt) 0.8 else 1.05,
                maxTokens = SARVAM_MAX_TOKENS,
                frequencyPenalty = 0.4,
                presencePenalty = 0.3
            ).let { (ok, text, err) ->
                if (ok && text.isNotBlank()) {
                    val cleaned = cleanArticleOutput(text)
                    if (cleaned.isBlank()) {
                        Log.e("SarvamClient", "[ROLE 1] Output entirely junk after cleaning (simplePrompt=$useSimplePrompt)")
                        WriteArticleResult(false, error = "Sarvam returned only preamble/junk")
                    } else {
                        Log.i("SarvamClient", "[ROLE 1] Success — ${cleaned.split("\\s+".toRegex()).size} words (simplePrompt=$useSimplePrompt)")
                        WriteArticleResult(true, cleaned)
                    }
                } else {
                    Log.e("SarvamClient", "[ROLE 1] Failed: $err")
                    WriteArticleResult(false, error = err)
                }
            }
        } catch (e: Exception) {
            Log.e("SarvamClient", "[ROLE 1] Exception: ${e.message}")
            WriteArticleResult(false, error = e.message ?: "Sarvam write error")
        }
    }

    // ─── Output cleaner ──────────────────────────────────────────────────────

    private fun cleanArticleOutput(raw: String): String {
        val lines = raw.lines().toMutableList()

        // Strip thinking preamble from the top
        var startIdx = 0
        for (i in lines.indices) {
            val lower = lines[i].trim().lowercase()
            val isJunk = lower.isEmpty() || THINKING_PREFIXES.any { lower.startsWith(it) }
            if (isJunk) startIdx = i + 1 else break
        }

        var result = lines.drop(startIdx).joinToString("\n")
        result = TAGS_LINE_REGEX.replace(result, "")
        result = TRAILING_HASHTAG_REGEX.replace(result, "")
        result = result.replace(Regex("\n{3,}"), "\n\n").trim()

        // Safety: if we stripped >70% of content something went wrong — fall back to tags-only clean
        if (result.length < raw.length * 0.3) {
            Log.w("SarvamClient", "[cleanArticleOutput] Over-stripped — falling back to tags-only clean")
            return TAGS_LINE_REGEX.replace(raw, "")
                .replace(TRAILING_HASHTAG_REGEX, "").trim()
        }
        return result
    }

    // ─── ROLE 2: Backup SEO generator ────────────────────────────────────────

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
                temperature  = 0.2,
                maxTokens    = 512
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

    // ─── ROLE 3: Grammar check + Humanise pass ───────────────────────────────

    suspend fun checkGrammarAndSpelling(content: String): GrammarCheckResult {
        if (content.isBlank()) return GrammarCheckResult(true, content)

        val apiKey = keyManager.getSarvamKey()
        if (apiKey.isBlank()) {
            Log.w("SarvamClient", "[ROLE 3] Sarvam key not set — skipping grammar/humanise step")
            return GrammarCheckResult(true, content, error = "Sarvam key not configured; grammar check skipped")
        }

        return try {
            Log.i("SarvamClient", "[ROLE 3] Running grammar + humanise pass")
            val chunks = splitIntoChunks(content, 1800)
            val corrected = mutableListOf<String>()

            for ((i, chunk) in chunks.withIndex()) {
                Log.d("SarvamClient", "[ROLE 3] Processing chunk ${i + 1}/${chunks.size}")
                val (ok, text, err) = callChatSync(
                    apiKey,
                    systemPrompt = "You are a senior news editor. Fix grammar, spelling, and robotic phrasing. " +
                        "Remove AI thinking text and tag lines. Keep all facts unchanged. " +
                        "Output only the rewritten text — no notes, no tags, no hashtags.",
                    userPrompt = buildHumaniseChunkPrompt(chunk),
                    temperature = 1.0,
                    maxTokens = SARVAM_MAX_TOKENS,
                    frequencyPenalty = 0.5,
                    presencePenalty  = 0.4
                )
                val safeResult = if (ok && text.isNotBlank()) cleanArticleOutput(text) else chunk
                if (!ok || text.isBlank()) {
                    Log.w("SarvamClient", "[ROLE 3] Chunk ${i + 1} error ($err) — keeping original")
                }
                corrected.add(safeResult)
            }

            val joined = corrected.joinToString(" ")
            if (joined.length < content.length / 2) {
                Log.w("SarvamClient", "[ROLE 3] Result too short — returning original")
                return GrammarCheckResult(true, content, error = "Grammar result too short — original kept")
            }
            GrammarCheckResult(true, joined)
        } catch (e: Exception) {
            Log.e("SarvamClient", "[ROLE 3] Error: ${e.message} — returning original")
            GrammarCheckResult(false, content, error = e.message ?: "Grammar/humanise error")
        }
    }

    // ─── Prompt builders ─────────────────────────────────────────────────────

    private fun buildArticlePrompt(
        title: String, desc: String, full: String, category: String, maxWords: Int
    ): String {
        val today = SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH).format(Date())
        val source = if (full.isNotBlank())
            "--- SOURCE ARTICLE ---\n${full.take(3000)}\n--- END SOURCE ---\nRSS summary: $desc"
        else
            "RSS summary: $desc\n(Full article unavailable — write from title and summary.)"
        return """
You are a seasoned news journalist. Today is $today. Write for the "$category" section.

SOURCE MATERIAL:
Headline: $title
$source

STRICT OUTPUT RULES:
- Start with the headline. Nothing before it.
- End with the last sentence. No Tags, no hashtags, no bylines after it.
- No bullet points, no section headers.

WRITING STYLE:
- ~$maxWords words. Direct voice. Vary sentence length.
- No AI openers. No "Furthermore", "Moreover", "Notably", "Pivotal", "Delve into".
- Flowing prose only. End naturally.

Write the article now:
        """.trimIndent()
    }

    /** Simpler fallback prompt used on retry when the model returns only junk on first attempt. */
    private fun buildSimpleArticlePrompt(
        title: String, desc: String, maxWords: Int
    ): String {
        return "Write a $maxWords-word news article based on the following.\n" +
            "Start with the headline. End with the last sentence. No tags, no hashtags.\n\n" +
            "Headline: $title\nSummary: $desc\n\nArticle:"
    }

    private fun buildHumaniseChunkPrompt(chunk: String) = """
Fix the filed text below. Remove AI thinking text and tag lines. Fix grammar and robotic phrasing.
Do NOT change facts, names, numbers, or dates. Output only the rewritten text.

Filed text:
$chunk

Rewritten text:
    """.trimIndent()

    // ─── Shared chat calls ───────────────────────────────────────────────────

    private fun callChatSync(
        apiKey: String,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double = 0.7,
        maxTokens: Int = SARVAM_MAX_TOKENS,
        frequencyPenalty: Double = 0.0,
        presencePenalty: Double = 0.0
    ): Triple<Boolean, String, String> {
        return try {
            val messages = JsonArray().apply {
                add(JsonObject().apply { addProperty("role", "system"); addProperty("content", systemPrompt) })
                add(JsonObject().apply { addProperty("role", "user");   addProperty("content", userPrompt)   })
            }
            val body = JsonObject().apply {
                addProperty("model",       "sarvam-m")
                add("messages",            messages)
                addProperty("temperature", temperature)
                addProperty("max_tokens",  maxTokens)
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
                else Triple(false, "", "Empty response from Sarvam")
            } else {
                Log.e("SarvamClient", "HTTP ${response.code}: $respBody")
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
        maxTokens: Int = SARVAM_MAX_TOKENS,
        frequencyPenalty: Double = 0.0,
        presencePenalty: Double = 0.0
    ): Triple<Boolean, String, String> =
        callChatSync(apiKey, systemPrompt, userPrompt, temperature, maxTokens, frequencyPenalty, presencePenalty)

    private fun parseChatReply(responseBody: String): String {
        return try {
            JsonParser.parseString(responseBody).asJsonObject
                .getAsJsonArray("choices")[0].asJsonObject
                .getAsJsonObject("message")
                .get("content").asString.trim()
        } catch (e: Exception) {
            Log.e("SarvamClient", "parseChatReply error: ${e.message} | body=${responseBody.take(200)}")
            ""
        }
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
