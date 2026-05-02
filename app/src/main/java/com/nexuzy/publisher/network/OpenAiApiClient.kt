package com.nexuzy.publisher.network

import android.util.Log
import com.google.gson.JsonParser
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OpenAI API client — dedicated key roles:
 *
 * KEY ROLES:
 *   Key 1 → Fact checking (primary)
 *   Key 2 → Humanize / clean / title rewrite (creative tasks)
 *   Key 3 → Fact checking fallback when Key 1 exhausted
 *
 * METHODS:
 *   factCheckArticle()  — Key 1 → Key 3 fallback
 *   cleanArticleOutput() — Key 2 only (legacy clean step)
 *   humanizeArticle()   — Key 2 → Key 1 fallback  [Stage 3 fallback]
 *   rewriteTitle()      — Key 2 → Key 1 fallback  [Stage 6 fallback]
 */
class OpenAiApiClient(private val keyManager: ApiKeyManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val baseUrl   = "https://api.openai.com/v1/chat/completions"

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

    /** Result for Stage 3 — Humanize fallback via OpenAI */
    data class HumanizeResult(
        val success: Boolean,
        val humanizedContent: String,
        val error: String = ""
    )

    /** Result for Stage 6 — Title rewrite fallback via OpenAI */
    data class RewriteTitleResult(
        val success: Boolean,
        val title: String,
        val error: String = ""
    )

    // ─────────────────────────────────────────────────────────────────────────
    // ROLE 1: Fact-check — Key 1 (primary) + Key 3 (fallback)
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun factCheckArticle(
        title: String,
        content: String,
        model: String = "gpt-4o-mini"
    ): FactCheckResult = withContext(Dispatchers.IO) {
        val keysToTry = listOf(1, 3)
            .map { idx -> Pair(idx, keyManager.getOpenAiKey(idx)) }
            .filter { (_, key) -> key.isNotBlank() }

        if (keysToTry.isEmpty()) {
            return@withContext FactCheckResult(
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
                return@withContext result.copy(keyUsed = keyIndex)
            }
            if (isQuotaError(result.error)) {
                Log.w("OpenAiClient", "[FACT-CHECK] Key $keyIndex quota exceeded, trying next")
                keyManager.rotateOpenAiKey()
                continue
            }
            return@withContext result.copy(keyUsed = keyIndex)
        }
        FactCheckResult(
            false, false, "",
            error = "Both fact-check keys (Key 1 & Key 3) are exhausted."
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ROLE 2: Clean + Humanise article output — Key 2 ONLY (legacy)
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun cleanArticleOutput(
        title: String,
        content: String,
        model: String = "gpt-4o-mini"
    ): CleanOutputResult = withContext(Dispatchers.IO) {
        val key2 = keyManager.getOpenAiKey(2)
        if (key2.isBlank()) {
            Log.w("OpenAiClient", "[CLEAN] Key 2 not set — skipping clean step")
            return@withContext CleanOutputResult(true, content, error = "Key 2 not configured; clean step skipped")
        }

        Log.d("OpenAiClient", "[CLEAN] Running article clean+humanise with Key 2")
        val prompt = buildCleanPrompt(title, content)
        try {
            val request = Request.Builder()
                .url(baseUrl)
                .addHeader("Authorization", "Bearer $key2")
                .addHeader("Content-Type", "application/json")
                .post(buildCleanChatBody(model, prompt).toRequestBody(jsonMedia))
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val cleaned = extractMessageContent(body)
                if (cleaned.isNotBlank()) {
                    Log.i("OpenAiClient", "[CLEAN] ✔ Cleaned with Key 2")
                    CleanOutputResult(true, cleaned)
                } else {
                    CleanOutputResult(true, content, error = "Clean returned empty; using original")
                }
            } else {
                CleanOutputResult(true, content, error = "HTTP ${response.code}; using original")
            }
        } catch (e: Exception) {
            Log.e("OpenAiClient", "[CLEAN] Exception: ${e.message}")
            CleanOutputResult(true, content, error = e.message ?: "Clean error")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ROLE 3: Humanize article — Stage 3 OpenAI fallback
    //
    // Called by AiPipeline Stage 3 when Gemini humanize fails.
    // Uses Key 2 first (creative slot), falls back to Key 1 on quota.
    // Facts are NEVER changed — only style/tone is rewritten.
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun humanizeArticle(
        title: String,
        content: String,
        model: String = "gpt-4o-mini"
    ): HumanizeResult = withContext(Dispatchers.IO) {
        val keysToTry = listOf(2, 1)
            .map { idx -> Pair(idx, keyManager.getOpenAiKey(idx)) }
            .filter { (_, key) -> key.isNotBlank() }

        if (keysToTry.isEmpty()) {
            return@withContext HumanizeResult(
                false, content,
                error = "No OpenAI keys available for humanize. Set Key 2 or Key 1 in Settings."
            )
        }

        val prompt = buildHumanizePrompt(title, content)

        for ((keyIndex, key) in keysToTry) {
            Log.d("OpenAiClient", "[HUMANIZE] Trying Key $keyIndex")
            try {
                val request = Request.Builder()
                    .url(baseUrl)
                    .addHeader("Authorization", "Bearer $key")
                    .addHeader("Content-Type", "application/json")
                    .post(buildCreativeChatBody(model, prompt).toRequestBody(jsonMedia))
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val humanized = extractMessageContent(body)
                    if (humanized.isNotBlank() && humanized.length >= content.length / 2) {
                        Log.i("OpenAiClient", "[HUMANIZE] ✔ Key $keyIndex succeeded")
                        return@withContext HumanizeResult(true, humanized)
                    } else {
                        Log.w("OpenAiClient", "[HUMANIZE] Key $keyIndex returned too-short response")
                        return@withContext HumanizeResult(false, content, error = "Response too short from Key $keyIndex")
                    }
                } else {
                    if (isQuotaError(body)) {
                        Log.w("OpenAiClient", "[HUMANIZE] Key $keyIndex quota — trying next")
                        keyManager.rotateOpenAiKey()
                        continue
                    }
                    return@withContext HumanizeResult(false, content, error = "HTTP ${response.code} from Key $keyIndex")
                }
            } catch (e: Exception) {
                Log.e("OpenAiClient", "[HUMANIZE] Key $keyIndex exception: ${e.message}")
                return@withContext HumanizeResult(false, content, error = e.message ?: "Network error")
            }
        }
        HumanizeResult(false, content, error = "All OpenAI keys exhausted for humanize.")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ROLE 4: Rewrite headline — Stage 6 OpenAI fallback
    //
    // Called by AiPipeline Stage 6 when both Gemini and Sarvam title
    // rewrites fail. Produces a clean ≤70-char SEO headline.
    // Uses Key 2 first, falls back to Key 1.
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun rewriteTitle(
        originalTitle: String,
        articleContent: String,
        focusKeyphrase: String,
        category: String,
        model: String = "gpt-4o-mini"
    ): RewriteTitleResult = withContext(Dispatchers.IO) {
        val keysToTry = listOf(2, 1)
            .map { idx -> Pair(idx, keyManager.getOpenAiKey(idx)) }
            .filter { (_, key) -> key.isNotBlank() }

        if (keysToTry.isEmpty()) {
            return@withContext RewriteTitleResult(
                false, originalTitle,
                error = "No OpenAI keys available for title rewrite."
            )
        }

        val prompt = """
            Write ONE publish-ready news headline.
            Focus keyphrase : $focusKeyphrase
            Category        : $category
            Original title  : $originalTitle
            Article preview : ${articleContent.take(300)}

            Headline rules:
            - Under 70 characters
            - Factual, specific, direct — no clickbait
            - No "How", "Why", "Ultimate guide", "Everything you need to know"
            - Include the focus keyphrase or a close variant if it fits naturally
            - No AI filler words
            - No colons unless necessary

            Output ONLY the headline text. No quotes. No punctuation at end.
        """.trimIndent()

        for ((keyIndex, key) in keysToTry) {
            Log.d("OpenAiClient", "[TITLE] Trying Key $keyIndex")
            try {
                val request = Request.Builder()
                    .url(baseUrl)
                    .addHeader("Authorization", "Bearer $key")
                    .addHeader("Content-Type", "application/json")
                    .post(buildTitleChatBody(model, prompt).toRequestBody(jsonMedia))
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val headline = extractMessageContent(body)
                        .lines()
                        .firstOrNull { it.trim().isNotBlank() }
                        ?.trim()
                        ?.removeSurrounding("\"")
                        ?.removeSurrounding("*")
                        ?.take(120)
                    if (!headline.isNullOrBlank() && headline.length >= 10) {
                        Log.i("OpenAiClient", "[TITLE] ✔ Key $keyIndex: $headline")
                        return@withContext RewriteTitleResult(true, headline)
                    } else {
                        return@withContext RewriteTitleResult(false, originalTitle, error = "Headline too short from Key $keyIndex")
                    }
                } else {
                    if (isQuotaError(body)) {
                        Log.w("OpenAiClient", "[TITLE] Key $keyIndex quota — trying next")
                        keyManager.rotateOpenAiKey()
                        continue
                    }
                    return@withContext RewriteTitleResult(false, originalTitle, error = "HTTP ${response.code} from Key $keyIndex")
                }
            } catch (e: Exception) {
                Log.e("OpenAiClient", "[TITLE] Key $keyIndex exception: ${e.message}")
                return@withContext RewriteTitleResult(false, originalTitle, error = e.message ?: "Network error")
            }
        }
        RewriteTitleResult(false, originalTitle, error = "All OpenAI keys exhausted for title rewrite.")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal: HTTP call for fact-check (returns FactCheckResult with JSON parsing)
    // ─────────────────────────────────────────────────────────────────────────

    private fun callOpenAiApi(apiKey: String, model: String, prompt: String): FactCheckResult {
        return try {
            val request = Request.Builder()
                .url(baseUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(buildFactCheckChatBody(model, prompt).toRequestBody(jsonMedia))
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (response.isSuccessful) parseFactCheckResponse(body)
            else FactCheckResult(false, false, "", error = "HTTP ${response.code}: $body")
        } catch (e: Exception) {
            FactCheckResult(false, false, "", error = e.message ?: "Network error")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prompt builders
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildHumanizePrompt(title: String, content: String): String = """
        You are a senior news editor. A reporter filed this article and it reads like machine-generated text.
        Rewrite it so it sounds like a real human journalist wrote it.

        Headline: $title

        Article:
        $content

        STRICT RULES:
        • Do NOT change any facts, numbers, names, dates, or direct quotes.
        • Do NOT add or remove information.
        • Remove ALL of these AI filler phrases: "notably", "in conclusion", "this underscores",
          "pivotal", "landscape", "delve", "shed light on", "it's worth noting",
          "it is important to note", "this highlights", "in summary", "furthermore",
          "moreover", "nevertheless", "in today's fast-paced world", "game-changer",
          "paradigm shift", "To be honest", "Arguably", "Certainly!", "Absolutely!"
        • Vary sentence length naturally: short punchy sentences mixed with longer ones.
        • Use natural contractions: "it's", "don't", "isn't", "they're", "we've".
        • Active voice preferred over passive.
        • Keep the same paragraph structure and order.
        • Output ONLY the rewritten article. Start directly with the headline. No preamble.
    """.trimIndent()

    private fun buildCleanPrompt(title: String, content: String): String = """
        You are a senior news editor at a major digital publication. A junior reporter just filed this
        article and it reads like it was written by a machine. Rewrite it in a natural human journalist voice.

        Original headline: $title

        Filed article:
        $content

        Keep every fact, figure, and quote. Do not add or remove information.
        Do not use: "Furthermore", "Moreover", "In conclusion", "It is worth noting", "Notably",
        "Underscore", "Delve", "Landscape", "Pivotal", "Navigate", "In an era of",
        "This underscores", "Shed light on".

        Output ONLY the finished article. Start directly with the headline.
    """.trimIndent()

    private fun buildFactCheckPrompt(title: String, content: String): String = """
        You are a professional fact-checker for a news agency. Review the following news article.

        Article Title: $title

        Article Content:
        $content

        Check all factual claims. Respond ONLY in this exact JSON format:
        {
          "is_accurate": true/false,
          "confidence_score": 85,
          "issues_found": ["list of specific issues, or empty array if none"],
          "feedback": "Brief overall assessment in 1-2 sentences",
          "corrected_content": "Full corrected article if changes were needed, or empty string if accurate"
        }
    """.trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    // Request body builders
    // ─────────────────────────────────────────────────────────────────────────

    /** Creative tasks (humanize, title rewrite): high temperature, penalty for repetition. */
    private fun buildCreativeChatBody(model: String, prompt: String): String {
        val p = com.google.gson.JsonPrimitive(prompt).toString()
        val s = com.google.gson.JsonPrimitive(
            "You are a senior news editor. Output ONLY the finished article — headline first, then body. No preamble."
        ).toString()
        return """{"model":"$model","messages":[{"role":"system","content":$s},{"role":"user","content":$p}],"temperature":1.0,"frequency_penalty":0.5,"presence_penalty":0.4,"max_tokens":2000}"""
    }

    /** Title rewrite: low temperature for precision, short output. */
    private fun buildTitleChatBody(model: String, prompt: String): String {
        val p = com.google.gson.JsonPrimitive(prompt).toString()
        val s = com.google.gson.JsonPrimitive(
            "You are a news headline editor. Output ONLY the headline text. No quotes. No preamble. One line."
        ).toString()
        return """{"model":"$model","messages":[{"role":"system","content":$s},{"role":"user","content":$p}],"temperature":0.4,"max_tokens":50}"""
    }

    /** Fact-check: low temperature for accuracy, JSON mode enforced. */
    private fun buildCleanChatBody(model: String, prompt: String): String {
        val p = com.google.gson.JsonPrimitive(prompt).toString()
        val s = com.google.gson.JsonPrimitive(
            "You are a senior news editor. Rewrite the article in a natural human journalist voice. " +
            "Output ONLY the finished article — headline first, then body. No preamble, no notes."
        ).toString()
        return """{"model":"$model","messages":[{"role":"system","content":$s},{"role":"user","content":$p}],"temperature":1.0,"frequency_penalty":0.5,"presence_penalty":0.4,"max_tokens":2000}"""
    }

    private fun buildFactCheckChatBody(model: String, prompt: String): String {
        val p = com.google.gson.JsonPrimitive(prompt).toString()
        val s = com.google.gson.JsonPrimitive(
            "You are an expert fact-checker. Always respond in valid JSON only."
        ).toString()
        return """{"model":"$model","messages":[{"role":"system","content":$s},{"role":"user","content":$p}],"temperature":0.1,"max_tokens":2000,"response_format":{"type":"json_object"}}"""
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

    private fun isQuotaError(error: String): Boolean =
        error.contains("429") ||
        error.contains("quota", ignoreCase = true) ||
        error.contains("rate_limit", ignoreCase = true) ||
        error.contains("insufficient_quota")
}
