package com.nexuzy.publisher.network

import android.util.Log
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Gemini API client.
 * Role 1: Write news articles from RSS item facts + live web context.
 * Role 2: Generate SEO data (tags, keywords, focus keyphrase, meta description).
 *
 * KEY ROTATION FIX:
 * - Each outer key-loop iteration reads a fresh key from keyManager via index offset
 *   so rotateGeminiKey() actually takes effect within the same call.
 * - Empty / safety-blocked responses are treated as a soft retryable failure so
 *   we try the next model before giving up — they are NOT hard errors.
 *
 * HUMAN WRITING FIX (v3):
 * - temperature raised to 1.05 for natural, varied sentence rhythm
 * - topP 0.97 for broader vocabulary selection
 * - Prompt rewritten as a seasoned journalist persona — no bullet rules, no AI instructions
 * - Explicitly forbids AI-style openers, structured headers, and robotic transitions
 *
 * MODEL CHAIN FIX (v4):
 * - gemini-1.5-flash and gemini-1.5-flash-8b removed — both return HTTP 404 on v1 API
 * - Working chain: gemini-2.0-flash → gemini-2.0-flash-lite → gemini-1.5-pro → gemini-1.0-pro
 * - Non-quota 404 errors now treated as model-skip (not hard fail) so rotation continues
 *
 * SEO PARSE FIX (v4):
 * - Sarvam backup SEO returns <think>...</think> block before JSON
 * - parseSeoResponse() now strips <think> blocks before JSON extraction
 */
class GeminiApiClient(private val keyManager: ApiKeyManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = "https://generativelanguage.googleapis.com/v1/models"

    // gemini-1.5-flash and gemini-1.5-flash-8b return HTTP 404 on the v1 API — removed.
    private val MODEL_CHAIN = listOf(
        "gemini-2.0-flash",
        "gemini-2.0-flash-lite",
        "gemini-1.5-pro",
        "gemini-1.0-pro"
    )

    data class GeminiResult(
        val success: Boolean,
        val content: String,
        val error: String = "",
        val keyUsed: Int = 1,
        val modelUsed: String = DEFAULT_MODEL,
        val retryAfterSeconds: Int = 0
    )

    data class SeoData(
        val success: Boolean,
        val tags: List<String> = emptyList(),
        val metaKeywords: String = "",
        val focusKeyphrase: String = "",
        val metaDescription: String = "",
        val error: String = ""
    )

    // ─────────────────────────────────────────────────────────────────────────
    // ROLE 1: Write news article
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun writeNewsArticle(
        rssTitle: String,
        rssDescription: String,
        rssFullContent: String = "",
        rssPubDate: String = "",
        rssSourceUrl: String = "",
        liveWebContext: String = "",
        category: String,
        model: String = DEFAULT_MODEL,
        maxWords: Int = 800
    ): GeminiResult = withContext(Dispatchers.IO) {

        val keys = keyManager.getGeminiKeys()
        if (keys.isEmpty()) {
            return@withContext GeminiResult(
                false, "",
                "No Gemini API keys configured. Please add at least one Gemini API key in Settings."
            )
        }

        val prompt = buildArticlePrompt(
            rssTitle, rssDescription, rssFullContent,
            rssPubDate, rssSourceUrl, liveWebContext,
            category, maxWords
        )
        val modelsToTry = if (model == DEFAULT_MODEL) MODEL_CHAIN else listOf(model)

        var lastError = ""
        var maxRetryAfter = 0

        for (keyIndex in keys.indices) {
            val currentKeys = keyManager.getGeminiKeys()
            if (keyIndex >= currentKeys.size) break
            val key = currentKeys[keyIndex]

            for (modelName in modelsToTry) {
                Log.d("GeminiClient", "Trying key ${keyIndex + 1}/${currentKeys.size}, model=$modelName")
                val result = callGemini(key, modelName, prompt)

                if (result.success) {
                    Log.i("GeminiClient", "Success ✔ key=${keyIndex + 1}, model=$modelName")
                    return@withContext result.copy(keyUsed = keyIndex + 1, modelUsed = modelName)
                }

                if (isQuotaError(result.error)) {
                    val retryAfter = parseRetryDelay(result.error)
                    if (retryAfter > maxRetryAfter) maxRetryAfter = retryAfter
                    Log.w("GeminiClient", "Key ${keyIndex + 1} / $modelName quota exceeded, trying next model")
                    lastError = result.error
                    continue
                }

                // Treat 404 (model not found) as a skip — try next model instead of hard-failing
                if (isModelNotFound(result.error)) {
                    Log.w("GeminiClient", "Key ${keyIndex + 1} / $modelName not found (404), skipping model")
                    lastError = result.error
                    continue
                }

                if (result.content.isBlank() && result.error.contains("Empty response", ignoreCase = true)) {
                    Log.w("GeminiClient", "Key ${keyIndex + 1} / $modelName returned empty (safety/block), trying next model")
                    lastError = result.error
                    continue
                }

                Log.e("GeminiClient", "Non-quota error on key ${keyIndex + 1} / $modelName: ${result.error}")
                return@withContext result.copy(keyUsed = keyIndex + 1, modelUsed = modelName)
            }
            Log.w("GeminiClient", "Key ${keyIndex + 1}: all ${modelsToTry.size} models failed, rotating to next key")
            keyManager.rotateGeminiKey()
        }

        val retryMsg = if (maxRetryAfter > 0)
            " Gemini resets in about ${maxRetryAfter}s — please wait and try again."
        else
            " Try again in 60 seconds, or add more API keys in Settings."

        return@withContext GeminiResult(
            false, "",
            "All ${keys.size} Gemini key(s) × ${modelsToTry.size} models have exceeded quota.$retryMsg",
            retryAfterSeconds = maxRetryAfter
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ROLE 2: Generate SEO metadata
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun generateSeoData(
        title: String,
        articleContent: String,
        category: String,
        model: String = DEFAULT_MODEL
    ): SeoData = withContext(Dispatchers.IO) {
        val keys = keyManager.getGeminiKeys()
        if (keys.isEmpty()) return@withContext SeoData(false, error = "No Gemini keys configured.")

        val prompt = buildSeoPrompt(title, articleContent, category)
        val modelsToTry = if (model == DEFAULT_MODEL) MODEL_CHAIN else listOf(model)

        for (keyIndex in keys.indices) {
            val currentKeys = keyManager.getGeminiKeys()
            if (keyIndex >= currentKeys.size) break
            val key = currentKeys[keyIndex]

            for (modelName in modelsToTry) {
                val result = callGemini(key, modelName, prompt)
                if (result.success) return@withContext parseSeoResponse(result.content)
                if (isQuotaError(result.error)) {
                    Log.w("GeminiClient", "SEO: Key ${keyIndex + 1}/$modelName quota exceeded, trying next")
                    continue
                }
                if (isModelNotFound(result.error)) {
                    Log.w("GeminiClient", "SEO: Key ${keyIndex + 1}/$modelName not found (404), skipping")
                    continue
                }
                if (result.content.isBlank() && result.error.contains("Empty response", ignoreCase = true)) {
                    continue
                }
                return@withContext SeoData(false, error = result.error)
            }
            keyManager.rotateGeminiKey()
        }
        return@withContext SeoData(false, error = "All Gemini keys exhausted for SEO generation.")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal: HTTP call
    // ─────────────────────────────────────────────────────────────────────────

    private fun callGemini(apiKey: String, model: String, prompt: String): GeminiResult {
        return try {
            val requestBody = buildRequestBody(prompt)
            val request = Request.Builder()
                .url("$baseUrl/$model:generateContent?key=$apiKey")
                .post(requestBody.toRequestBody(jsonMedia))
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val content = extractText(body)
                if (content.isNotBlank()) GeminiResult(true, content)
                else GeminiResult(false, "", "Empty response from Gemini ($model)")
            } else {
                Log.e("GeminiClient", "Gemini failed: HTTP ${response.code}: $body")
                GeminiResult(false, "", "HTTP ${response.code}: $body")
            }
        } catch (e: Exception) {
            Log.e("GeminiClient", "callGemini exception: ${e.message}")
            GeminiResult(false, "", e.message ?: "Unknown error")
        }
    }

    private fun parseRetryDelay(error: String): Int {
        val pattern = Regex("""[Rr]etry(?:\s+in)?\s+(\d+)(?:\.\d+)?\s*s""", RegexOption.IGNORE_CASE)
        return pattern.find(error)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prompt builders
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildArticlePrompt(
        title: String,
        desc: String,
        fullContent: String,
        pubDate: String,
        sourceUrl: String,
        liveWebContext: String,
        category: String,
        maxWords: Int
    ): String {
        val today = SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH).format(Date())

        val sourceMaterial = buildString {
            if (pubDate.isNotBlank())     appendLine("Published: $pubDate")
            if (sourceUrl.isNotBlank())   appendLine("Source: $sourceUrl")
            appendLine()
            if (fullContent.isNotBlank()) {
                appendLine("--- SOURCE ARTICLE ---")
                appendLine(fullContent.take(3500))
                appendLine("--- END SOURCE ---")
                appendLine()
                appendLine("RSS summary: $desc")
            } else {
                appendLine("RSS summary: $desc")
                appendLine("(Full article unavailable — write from title, summary and web context.)")
            }
            if (liveWebContext.isNotBlank()) {
                appendLine()
                appendLine("--- ADDITIONAL WEB CONTEXT (fetched today) ---")
                appendLine(liveWebContext)
                appendLine("--- END WEB CONTEXT ---")
            }
        }

        // ── THE CORE PROMPT — persona-driven, no bullet-point rules ──
        return """
You are a seasoned news journalist with 15 years of experience writing for major digital publications. You have a sharp, direct voice — you get to the point fast, you never pad sentences, and your writing never sounds like it came from a machine.

Today is $today. You are writing a fresh news article for the "$category" section.

SOURCE MATERIAL:
$sourceMaterial

YOUR TASK:
Write a compelling, publish-ready news article of approximately $maxWords words based only on the facts in the source material above. Do not invent quotes, statistics, or events not present in the source.

WRITING STYLE — this is the most important part:
- Sound like a human journalist, not an AI. Vary your sentence length. Some sentences are short. Others build context and add weight before landing the point.
- Write a punchy SEO headline first. No colons, no "How", no "Why", no "Everything You Need to Know". Just a direct, specific headline.
- Your first sentence should drop the reader straight into the story — no "In a significant development", no "As the world watches", no "In today's rapidly evolving landscape". Just the news.
- Never use these AI-filler phrases: "It is worth noting", "Furthermore", "Moreover", "In conclusion", "It is important to highlight", "Delve into", "In summary", "Shed light on", "Underscore", "Notably", "This underscores", "Landscape", "In an era of", "Navigate", "Pivotal".
- No section headers. No bullet points. No numbered lists. Pure flowing article prose only.
- Write like you are telling a story to a smart reader who has 2 minutes. Be direct, be human, be clear.
- Vary paragraph length. Not every paragraph is 3 sentences. Some are 1. Some are 4.
- End the article naturally — a forward-looking sentence, a quote if one exists in the source, or a crisp final observation. Never end with "Only time will tell" or "remains to be seen".
- Do not add any byline, photo captions, or "Tags:" at the end.
- Do not explain what you are about to write. Begin immediately with the headline.

Write the article now:
        """.trimIndent()
    }

    private fun buildSeoPrompt(title: String, content: String, category: String) = """
        You are an SEO expert. Analyze this news article and generate SEO metadata.

        Article Title: $title
        Category: $category
        Article (first 1000 chars): ${content.take(1000)}

        Generate SEO data and respond ONLY in this exact JSON format:
        {
          "tags": ["tag1", "tag2", "tag3", "tag4", "tag5"],
          "meta_keywords": "keyword1, keyword2, keyword3, keyword4, keyword5",
          "focus_keyphrase": "main focus keyphrase here",
          "meta_description": "Compelling 120-155 character meta description for search engines."
        }

        Rules:
        - tags: 5-8 relevant single or two-word tags
        - meta_keywords: 5-10 comma-separated keywords
        - focus_keyphrase: 2-4 words, most important search phrase
        - meta_description: 120-155 characters, compelling and includes focus keyphrase
        - Respond ONLY with valid JSON, no extra text, no <think> blocks
    """.trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    // Response parsers
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseSeoResponse(raw: String): SeoData {
        return try {
            // Strip <think>...</think> reasoning blocks emitted by Sarvam/other models
            val stripped = raw.replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "").trim()
            val cleaned = stripped
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            // Extract first JSON object in case there is any trailing text
            val jsonStart = cleaned.indexOf('{')
            val jsonEnd = cleaned.lastIndexOf('}')
            if (jsonStart == -1 || jsonEnd == -1 || jsonEnd < jsonStart) {
                return SeoData(false, error = "No JSON object found in SEO response")
            }
            val jsonStr = cleaned.substring(jsonStart, jsonEnd + 1)
            val json = JsonParser.parseString(jsonStr).asJsonObject
            val tagsArr = json.getAsJsonArray("tags")
            val tags = (0 until tagsArr.size()).map { tagsArr[it].asString }
            SeoData(
                success         = true,
                tags            = tags,
                metaKeywords    = json.get("meta_keywords")?.asString ?: tags.joinToString(", "),
                focusKeyphrase  = json.get("focus_keyphrase")?.asString ?: "",
                metaDescription = json.get("meta_description")?.asString ?: ""
            )
        } catch (e: Exception) {
            Log.e("GeminiClient", "parseSeoJson error: ${e.message} | raw=$raw")
            SeoData(false, error = "SEO parse failed: ${e.message}")
        }
    }

    private fun buildRequestBody(prompt: String): String {
        val escaped = JsonPrimitive(prompt).toString()
        return """
            {
              "contents": [{"parts": [{"text": $escaped}]}],
              "generationConfig": {
                "temperature": 1.05,
                "topK": 50,
                "topP": 0.97,
                "maxOutputTokens": 2048,
                "frequencyPenalty": 0.4,
                "presencePenalty": 0.3
              },
              "safetySettings": [
                {"category": "HARM_CATEGORY_HARASSMENT", "threshold": "BLOCK_MEDIUM_AND_ABOVE"},
                {"category": "HARM_CATEGORY_HATE_SPEECH", "threshold": "BLOCK_MEDIUM_AND_ABOVE"}
              ]
            }
        """.trimIndent()
    }

    private fun extractText(responseBody: String): String {
        return try {
            val json = JsonParser.parseString(responseBody).asJsonObject
            val candidates = json.getAsJsonArray("candidates")
            if (candidates != null && candidates.size() > 0) {
                candidates[0].asJsonObject
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")[0]
                    .asJsonObject.get("text").asString.trim()
            } else ""
        } catch (e: Exception) {
            Log.e("GeminiClient", "Parse error: ${e.message}")
            ""
        }
    }

    private fun isQuotaError(error: String) =
        error.contains("429") ||
        error.contains("quota", ignoreCase = true) ||
        error.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
        error.contains("rate limit", ignoreCase = true) ||
        error.contains("rateLimitExceeded", ignoreCase = true)

    private fun isModelNotFound(error: String) =
        error.contains("404") ||
        error.contains("NOT_FOUND", ignoreCase = true) ||
        error.contains("is not found for API version", ignoreCase = true) ||
        error.contains("not supported for generateContent", ignoreCase = true)

    companion object {
        const val DEFAULT_MODEL = "gemini-2.0-flash"
    }
}
