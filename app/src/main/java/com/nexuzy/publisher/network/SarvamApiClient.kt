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
 *   maxWords is clamped to 300–450 so Sarvam never over-runs its token budget.
 *   max_tokens set to 2048 — the maximum allowed on the starter subscription tier.
 *   Output is post-processed to strip AI thinking preamble and tags/footer junk.
 *
 * ROLE 2 — generateSeoData():
 *   Backup SEO generator when Gemini SEO also fails.
 *
 * ROLE 3 — checkGrammarAndSpelling():
 *   Grammar + spelling correction PLUS humanise pass after writing.
 *   Safe fallback: if Sarvam returns blank or errors, the ORIGINAL content
 *   is returned unchanged — never replaces good content with a broken response.
 *   max_tokens set to 2048 for grammar pass too (starter tier limit).
 */
class SarvamApiClient(private val keyManager: ApiKeyManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ─── ARTICLE TOKEN BUDGET ─────────────────────────────────────────────────────────────
    // Sarvam starter tier allows a maximum of 2048 output tokens.
    // 450 words ≈ ~620 tokens output — well within the 2048 limit.
    // Grammar chunks are ≤1800 chars so they also stay safely under 2048 tokens.
    private val SARVAM_MAX_TOKENS = 2048
    private val SARVAM_MIN_WORDS  = 300
    private val SARVAM_MAX_WORDS  = 450

    // ─── Regex patterns for post-processing article output ───────────────────────────────
    // Strips AI thinking preamble lines that leak through at the top
    private val THINKING_PREFIXES = listOf(
        "okay, let's", "okay, i'll", "okay! let's", "let me ", "let's tackle",
        "let's think", "alright,", "sure! here", "sure, here", "here is",
        "here's the", "i need to", "i'll ", "i will ", "as requested,",
        "of course,", "certainly,", "absolutely,"
    )
    // Regex to strip tags footer: matches "🏷️ Tags:", "Tags:", "#word" lines at end
    private val TAGS_LINE_REGEX = Regex(
        """(?im)^[\uFE0F🏷️\s]*tags?\s*:.*$"""
    )
    private val TRAILING_HASHTAG_REGEX = Regex(
        """(?im)^(#\w+[\s·•\-]*){2,}$"""
    )

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
        maxWords: Int = 400
    ): WriteArticleResult {
        val apiKey = keyManager.getSarvamKey()
        if (apiKey.isBlank()) return WriteArticleResult(
            false,
            error = "Sarvam API key not configured. Add your key in Settings."
        )

        val safeWords = maxWords.coerceIn(SARVAM_MIN_WORDS, SARVAM_MAX_WORDS)
        Log.i("SarvamClient", "[ROLE 1] Writing article ($safeWords words) as Gemini backup")

        return try {
            val prompt = buildArticlePrompt(rssTitle, rssDescription, rssFullContent, category, safeWords)
            callChat(
                apiKey,
                systemPrompt = "You are a seasoned news journalist with 15 years of experience. " +
                    "Write directly in a natural human voice — no preamble, no thinking out loud, no commentary. " +
                    "Start immediately with the article headline. " +
                    "Do NOT include any Tags, hashtags, or bylines at the end.",
                userPrompt = prompt,
                temperature = 1.05,
                maxTokens = SARVAM_MAX_TOKENS,
                frequencyPenalty = 0.4,
                presencePenalty = 0.3
            ).let { (ok, text, err) ->
                if (ok && text.isNotBlank()) {
                    val cleaned = cleanArticleOutput(text)
                    if (cleaned.isBlank()) {
                        Log.e("SarvamClient", "[ROLE 1] Output was entirely junk after cleaning")
                        WriteArticleResult(false, error = "Sarvam returned only preamble/junk")
                    } else {
                        Log.i("SarvamClient", "[ROLE 1] Success — ${cleaned.split("\\s+".toRegex()).size} words written")
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

    /**
     * Cleans raw Sarvam article output:
     * 1. Strips AI thinking/preamble lines from the top
     * 2. Strips "Tags:" footer lines and trailing hashtag lines from the bottom
     */
    private fun cleanArticleOutput(raw: String): String {
        val lines = raw.lines().toMutableList()

        // Strip thinking preamble from the top — drop leading lines that start with
        // known AI planning phrases (case-insensitive). Stop as soon as a clean line is found.
        var startIdx = 0
        for (i in lines.indices) {
            val lower = lines[i].trim().lowercase()
            val isThinking = THINKING_PREFIXES.any { lower.startsWith(it) }
                || lower.isEmpty() // also skip blank preamble lines
            if (isThinking) {
                startIdx = i + 1
            } else {
                // First non-thinking non-blank line — stop stripping
                break
            }
        }

        // Strip Tags footer and trailing hashtag blocks from the bottom
        var result = lines.drop(startIdx).joinToString("\n")
        result = TAGS_LINE_REGEX.replace(result, "")
        result = TRAILING_HASHTAG_REGEX.replace(result, "")

        // Clean up extra blank lines left by removals
        result = result.replace(Regex("\n{3,}"), "\n\n").trim()

        if (result.length < raw.length * 0.3) {
            // Sanity check: if we stripped more than 70% of content, something is wrong —
            // return the raw version with only the tags footer stripped
            Log.w("SarvamClient", "[cleanArticleOutput] Over-stripped — falling back to tags-only clean")
            return TAGS_LINE_REGEX.replace(raw, "")
                .replace(TRAILING_HASHTAG_REGEX, "")
                .trim()
        }

        return result
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

    // ─── ROLE 3: Grammar check + Humanise pass ──────────────────────────────────────────

    suspend fun checkGrammarAndSpelling(content: String): GrammarCheckResult {
        if (content.isBlank()) return GrammarCheckResult(true, content)

        val apiKey = keyManager.getSarvamKey()
        if (apiKey.isBlank()) {
            Log.w("SarvamClient", "[ROLE 3] Sarvam key not set — skipping grammar/humanise step")
            return GrammarCheckResult(
                true, content,
                error = "Sarvam key not configured; grammar check skipped"
            )
        }

        return try {
            Log.i("SarvamClient", "[ROLE 3] Running grammar + humanise pass")
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
                        "Output only the rewritten text — no notes, no commentary, no tags, no hashtags.",
                    userPrompt = buildHumaniseChunkPrompt(chunk),
                    temperature = 1.0,
                    maxTokens = SARVAM_MAX_TOKENS,
                    frequencyPenalty = 0.5,
                    presencePenalty  = 0.4
                )
                val safeResult = if (ok && text.isNotBlank()) cleanArticleOutput(text) else chunk
                if (!ok || text.isBlank()) {
                    Log.w("SarvamClient", "[ROLE 3] Chunk ${i + 1} returned blank/error ($err) — keeping original")
                }
                corrected.add(safeResult)
            }

            val joined = corrected.joinToString(" ")
            if (joined.length < content.length / 2) {
                Log.w("SarvamClient", "[ROLE 3] Result suspiciously short (${joined.length} vs ${content.length}) — returning original")
                return GrammarCheckResult(true, content, error = "Grammar result too short — original kept")
            }

            GrammarCheckResult(true, joined)
        } catch (e: Exception) {
            Log.e("SarvamClient", "[ROLE 3] Error: ${e.message} — returning original content")
            GrammarCheckResult(false, content, error = e.message ?: "Grammar/humanise error")
        }
    }

    // ─── Prompt builders ───────────────────────────────────────────────────────────────────

    private fun buildArticlePrompt(
        title: String, desc: String, full: String, category: String, maxWords: Int
    ): String {
        val today = SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH).format(Date())
        val source = if (full.isNotBlank())
            "--- SOURCE ARTICLE ---\n${full.take(3000)}\n--- END SOURCE ---\nRSS summary: $desc"
        else
            "RSS summary: $desc\n(Full article unavailable — write from title and summary.)"

        return """
You are a seasoned news journalist with 15 years of experience writing for major digital publications.

Today is $today. Write a fresh news article for the "$category" section.

SOURCE MATERIAL:
Headline: $title
$source

Write a compelling, publish-ready news article of approximately $maxWords words (between ${maxWords - 50} and ${maxWords + 50} words) based only on the facts above. Do not invent quotes, statistics, or events not present in the source.

STRICT OUTPUT RULES — violating any of these will cause the article to be rejected:
- Begin your response with the article headline. Nothing before it — no "Okay", no "Sure", no "Here is", no thinking text.
- End your response with the last sentence of the article. Nothing after it — no Tags, no hashtags (#), no "Tags:", no bylines, no photo captions.
- Pure article prose only: no bullet points, no section headers, no lists.

WRITING STYLE:
- Sharp, direct voice. Vary your sentence length.
- First sentence drops the reader straight into the story.
- Never use: "Furthermore", "Moreover", "In conclusion", "It is worth noting", "Delve into", "Notably", "Landscape", "In an era of", "Pivotal", "Shed light on".
- Vary paragraph length. Some one sentence. Some three or four.
- End naturally.

Write the article now:
        """.trimIndent()
    }

    private fun buildHumaniseChunkPrompt(chunk: String): String {
        return """
You are a senior news editor. Fix the filed text below.

1. Remove any AI thinking text ("Okay, let's tackle this", "Let me", "I need to", "Sure", "Here is", or any planning text)
2. Remove any tags/hashtag lines at the end (lines starting with # or containing "Tags:")
3. Fix all grammar and spelling errors
4. Rewrite robotic phrasing into natural journalist language
5. Do NOT change any facts, names, numbers, quotes, or dates
6. Do not use: "Furthermore", "Moreover", "Notably", "This underscores", "Pivotal", "Delve"

Output only the rewritten article text. No notes, no commentary, no tags.

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
