package com.nexuzy.publisher.network

import android.util.Log
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class GeminiApiClient(private val keyManager: ApiKeyManager) {

    companion object {
        const val DEFAULT_MODEL = "gemini-1.5-flash"
        private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val TAG = "GeminiApiClient"
    }

    // ──────────────────────────────────────────────────────────────────────
    // Result data classes
    // ──────────────────────────────────────────────────────────────────────

    data class WriteResult(
        val success: Boolean,
        val content: String = "",
        val error: String = ""
    )

    data class FactCheckResult(
        val success: Boolean,
        val isAccurate: Boolean = false,
        val confidenceScore: Float = 0f,
        val feedback: String = "",
        val correctedContent: String = "",
        val error: String = ""
    )

    data class SeoResult(
        val success: Boolean,
        val tags: List<String> = emptyList(),
        val metaKeywords: String = "",
        val focusKeyphrase: String = "",
        val metaDescription: String = "",
        val imageAltText: String = "",
        val error: String = ""
    )

    // ──────────────────────────────────────────────────────────────────────
    // writeNewsArticle
    // ──────────────────────────────────────────────────────────────────────

    suspend fun writeNewsArticle(
        rssTitle: String,
        rssDescription: String,
        rssFullContent: String = "",
        category: String = "",
        model: String = DEFAULT_MODEL,
        maxWords: Int = 800
    ): WriteResult = withContext(Dispatchers.IO) {
        val apiKey = keyManager.getGeminiApiKey()
        if (apiKey.isBlank()) return@withContext WriteResult(false, error = "No Gemini API key")

        val prompt = """
            Write a complete, factual news article in ${maxWords} words.
            Category: $category
            Title: $rssTitle
            Background: $rssDescription
            ${if (rssFullContent.isNotBlank()) "Additional context: ${rssFullContent.take(1000)}" else ""}
            
            Rules: No AI filler phrases. Active voice. Factual only.
            Output ONLY the article text, starting with the headline.
        """.trimIndent()

        callGemini(apiKey, model, prompt)?.let { text ->
            WriteResult(success = true, content = text)
        } ?: WriteResult(false, error = "Gemini API call failed")
    }

    // ──────────────────────────────────────────────────────────────────────
    // factCheckArticle
    // ──────────────────────────────────────────────────────────────────────

    suspend fun factCheckArticle(
        title: String,
        content: String
    ): FactCheckResult = withContext(Dispatchers.IO) {
        val apiKey = keyManager.getGeminiApiKey()
        if (apiKey.isBlank()) return@withContext FactCheckResult(false, error = "No Gemini API key")

        val prompt = """
            You are a fact-checking editor. Analyse the following news article for factual accuracy.
            Title: $title
            Article: ${content.take(3000)}
            
            Respond in JSON:
            {
              "is_accurate": true/false,
              "confidence_score": 0.0-1.0,
              "feedback": "brief explanation",
              "corrected_content": "corrected article text or empty string"
            }
        """.trimIndent()

        val raw = callGemini(apiKey, DEFAULT_MODEL, prompt)
            ?: return@withContext FactCheckResult(false, error = "Gemini API call failed")

        try {
            val json = extractJson(raw)
            val obj = JSONObject(json)
            FactCheckResult(
                success          = true,
                isAccurate       = obj.optBoolean("is_accurate", false),
                confidenceScore  = obj.optDouble("confidence_score", 0.0).toFloat(),
                feedback         = obj.optString("feedback", ""),
                correctedContent = obj.optString("corrected_content", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "factCheck parse error", e)
            FactCheckResult(false, error = "Parse error: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // generateSeoData
    // ──────────────────────────────────────────────────────────────────────

    suspend fun generateSeoData(
        title: String,
        articleContent: String,
        category: String = "",
        model: String = DEFAULT_MODEL
    ): SeoResult = withContext(Dispatchers.IO) {
        val apiKey = keyManager.getGeminiApiKey()
        if (apiKey.isBlank()) return@withContext SeoResult(false, error = "No Gemini API key")

        val prompt = """
            Generate SEO metadata for the following news article.
            Title: $title
            Category: $category
            Content: ${articleContent.take(1500)}
            
            Respond in JSON:
            {
              "tags": ["tag1","tag2","tag3"],
              "meta_keywords": "comma separated keywords",
              "focus_keyphrase": "main keyphrase",
              "meta_description": "155 char SEO description",
              "image_alt_text": "SEO image alt text"
            }
        """.trimIndent()

        val raw = callGemini(apiKey, model, prompt)
            ?: return@withContext SeoResult(false, error = "Gemini API call failed")

        try {
            val json = extractJson(raw)
            val obj = JSONObject(json)
            SeoResult(
                success         = true,
                tags            = obj.optJSONArray("tags")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                metaKeywords    = obj.optString("meta_keywords", ""),
                focusKeyphrase  = obj.optString("focus_keyphrase", ""),
                metaDescription = obj.optString("meta_description", ""),
                imageAltText    = obj.optString("image_alt_text", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "SEO parse error", e)
            SeoResult(false, error = "Parse error: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Private: HTTP call to Gemini REST API
    // ──────────────────────────────────────────────────────────────────────

    private fun callGemini(apiKey: String, model: String, prompt: String): String? {
        return try {
            val url = URL("$API_BASE/$model:generateContent?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 30_000
            conn.readTimeout    = 60_000

            val body = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.7)
                    put("maxOutputTokens", 2048)
                })
            }

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream.bufferedReader().readText()

            if (code !in 200..299) {
                Log.e(TAG, "Gemini HTTP $code: $response")
                return null
            }

            val json = JSONObject(response)
            json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        } catch (e: Exception) {
            Log.e(TAG, "callGemini exception", e)
            null
        }
    }

    private fun extractJson(raw: String): String {
        val start = raw.indexOfFirst { it == '{' }
        val end   = raw.indexOfLast  { it == '}' }
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else raw
    }
}
