package com.nexuzy.publisher.network

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * SarvamChatClient — David AI in-app assistant.
 *
 * Powers the chat screen in DavidAiChatActivity.
 * Uses Sarvam's sarvam-m model via /v1/chat/completions endpoint.
 *
 * FEATURES:
 *  - Weather queries    → real weather data from open-meteo.com (no API key needed)
 *  - Internet questions → DuckDuckGo Instant Answer API (no API key needed)
 *  - All other queries  → Sarvam AI with strict no-preamble prompt
 *
 * This is a SEPARATE client from SarvamApiClient:
 *   - SarvamApiClient  → grammar correction for articles (uses user's key)
 *   - SarvamChatClient → David AI in-app chat (uses developer pre-embedded key)
 */
object SarvamChatClient {

    // TODO: Replace with your actual developer Sarvam API key from https://dashboard.sarvam.ai
    private const val DEV_API_KEY = "your-sarvam-dev-key-here"

    private const val SARVAM_URL = "https://api.sarvam.ai/v1/chat/completions"
    private const val MODEL      = "sarvam-m"
    private val jsonMedia        = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class ChatResult(
        val success: Boolean,
        val reply: String = "",
        val error: String = ""
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Main entry point
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Send conversation history to David AI and get a direct reply.
     * Automatically routes weather / internet queries before hitting Sarvam.
     *
     * @param history List of (role, content) pairs. role = "user" or "assistant".
     */
    suspend fun chat(history: List<Pair<String, String>>): ChatResult =
        withContext(Dispatchers.IO) {

            val userMessage = history.lastOrNull { it.first == "user" }?.second?.trim() ?: ""

            // 1) Weather query → open-meteo (no API key, always fresh)
            if (isWeatherQuery(userMessage)) {
                val city = extractCity(userMessage)
                return@withContext fetchWeather(city)
            }

            // 2) Internet/search query → DuckDuckGo Instant Answer
            if (isSearchQuery(userMessage)) {
                val ddgResult = fetchDuckDuckGo(userMessage)
                if (ddgResult.success) return@withContext ddgResult
                // if DDG returns nothing useful, fall through to Sarvam
            }

            // 3) Sarvam AI for everything else
            if (DEV_API_KEY == "your-sarvam-dev-key-here" || DEV_API_KEY.isBlank()) {
                return@withContext ChatResult(
                    false,
                    error = "David AI is not yet configured. Please set the developer API key."
                )
            }

            try {
                val request = Request.Builder()
                    .url(SARVAM_URL)
                    .addHeader("api-subscription-key", DEV_API_KEY)
                    .addHeader("Content-Type", "application/json")
                    .post(buildChatBody(history).toRequestBody(jsonMedia))
                    .build()

                val response = http.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val reply = parseReply(body)
                    if (reply.isNotBlank()) ChatResult(true, reply)
                    else ChatResult(false, error = "Empty response from Sarvam AI")
                } else {
                    Log.w("SarvamChatClient", "HTTP ${response.code}: $body")
                    ChatResult(false, error = "Server error (HTTP ${response.code})")
                }
            } catch (e: Exception) {
                Log.e("SarvamChatClient", "Chat error: ${e.message}")
                ChatResult(false, error = e.message ?: "Network error")
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Weather — open-meteo.com (free, no key)
    // ─────────────────────────────────────────────────────────────────────────

    private fun isWeatherQuery(msg: String): Boolean {
        val lower = msg.lowercase()
        return (lower.contains("weather") || lower.contains("temperature") ||
                lower.contains("forecast") || lower.contains("rain") ||
                lower.contains("humidity") || lower.contains("feels like"))
    }

    private fun extractCity(msg: String): String {
        val lower = msg.lowercase()
        // Common patterns: "weather in X", "weather of X", "X weather", "X temperature"
        val inOf = Regex("weather\\s+(?:in|of|at|for)\\s+([a-z\\s]+)", RegexOption.IGNORE_CASE)
            .find(msg)?.groupValues?.get(1)?.trim()
        if (!inOf.isNullOrBlank()) return inOf

        val before = Regex("([a-z\\s]+?)\\s+(?:weather|temperature|forecast)", RegexOption.IGNORE_CASE)
            .find(msg)?.groupValues?.get(1)?.trim()
        if (!before.isNullOrBlank() && before.length > 2) return before

        return "Kolkata"  // default to app's home city
    }

    private fun fetchWeather(city: String): ChatResult {
        return try {
            // Step A: geocode the city name
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(city, "UTF-8")}&count=1&language=en&format=json"
            val geoResp = http.newCall(Request.Builder().url(geoUrl).build()).execute()
            val geoBody = geoResp.body?.string() ?: return ChatResult(false, error = "Could not geocode city: $city")
            val geoJson = JsonParser.parseString(geoBody).asJsonObject
            val results = geoJson.getAsJsonArray("results")
                ?: return ChatResult(false, error = "City not found: $city")
            if (results.size() == 0) return ChatResult(false, error = "City not found: $city")

            val loc      = results[0].asJsonObject
            val lat      = loc.get("latitude").asDouble
            val lon      = loc.get("longitude").asDouble
            val cityName = loc.optString("name", city)
            val country  = loc.optString("country", "")

            // Step B: fetch current weather
            val wxUrl = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature," +
                "precipitation,weather_code,wind_speed_10m,wind_direction_10m" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code" +
                "&timezone=auto&forecast_days=3"

            val wxResp = http.newCall(Request.Builder().url(wxUrl).build()).execute()
            val wxBody = wxResp.body?.string() ?: return ChatResult(false, error = "Weather fetch failed")
            val wx     = JsonParser.parseString(wxBody).asJsonObject

            val cur    = wx.getAsJsonObject("current")
            val daily  = wx.getAsJsonObject("daily")

            val tempC       = cur.get("temperature_2m").asDouble
            val feelsC      = cur.get("apparent_temperature").asDouble
            val humidity    = cur.get("relative_humidity_2m").asInt
            val wind        = cur.get("wind_speed_10m").asDouble
            val rain        = cur.get("precipitation").asDouble
            val wCode       = cur.get("weather_code").asInt
            val condition   = weatherCodeToText(wCode)

            val maxTemps = daily.getAsJsonArray("temperature_2m_max")
            val minTemps = daily.getAsJsonArray("temperature_2m_min")
            val rainDays = daily.getAsJsonArray("precipitation_sum")
            val days     = daily.getAsJsonArray("time")

            val reply = buildString {
                appendLine("🌤️ **Weather in $cityName${if (country.isNotBlank()) ", $country" else ""}**")
                appendLine()
                appendLine("**Now:** $condition")
                appendLine("**Temperature:** ${tempC}°C (feels like ${feelsC}°C)")
                appendLine("**Humidity:** $humidity%")
                appendLine("**Wind:** ${wind} km/h")
                if (rain > 0) appendLine("**Rain:** ${rain} mm")
                appendLine()
                appendLine("📅 **3-Day Forecast:**")
                for (i in 0 until minOf(3, days.size())) {
                    val day  = days[i].asString
                    val hi   = maxTemps[i].asDouble
                    val lo   = minTemps[i].asDouble
                    val rain3= rainDays[i].asDouble
                    val sym  = if (rain3 > 1.0) "🌧️" else "☀️"
                    appendLine("$sym **$day** — ${hi}°C / ${lo}°C${if (rain3 > 0) ", rain ${rain3}mm" else ""}")
                }
                appendLine()
                append("_Source: open-meteo.com_")
            }

            ChatResult(true, reply)
        } catch (e: Exception) {
            Log.e("SarvamChatClient", "Weather error: ${e.message}")
            ChatResult(false, error = "Could not fetch weather: ${e.message}")
        }
    }

    private fun weatherCodeToText(code: Int): String = when (code) {
        0            -> "Clear sky ☀️"
        1, 2, 3      -> "Partly cloudy ⛅"
        45, 48       -> "Foggy 🌫️"
        51, 53, 55   -> "Drizzle 🌦️"
        61, 63, 65   -> "Rain 🌧️"
        71, 73, 75   -> "Snow ❄️"
        80, 81, 82   -> "Rain showers 🌦️"
        95           -> "Thunderstorm ⛈️"
        96, 99       -> "Thunderstorm with hail ⛈️"
        else         -> "Unknown ($code)"
    }

    private fun com.google.gson.JsonObject.optString(key: String, default: String): String =
        if (has(key) && !get(key).isJsonNull) get(key).asString else default

    // ─────────────────────────────────────────────────────────────────────────
    // DuckDuckGo Instant Answer (internet search)
    // ─────────────────────────────────────────────────────────────────────────

    private val SEARCH_TRIGGERS = listOf(
        "search", "look up", "find", "what is", "who is", "who are",
        "tell me about", "latest news", "current", "today", "right now",
        "price of", "stock", "live", "breaking"
    )

    private fun isSearchQuery(msg: String): Boolean {
        val lower = msg.lowercase()
        return SEARCH_TRIGGERS.any { lower.contains(it) }
    }

    private fun fetchDuckDuckGo(query: String): ChatResult {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val resp = http.newCall(Request.Builder().url(url)
                .addHeader("User-Agent", "NexuzyPublisher/2.0")
                .build()).execute()
            val body = resp.body?.string() ?: return ChatResult(false, error = "No DDG response")
            val json = JsonParser.parseString(body).asJsonObject

            val abstract_ = json.optString("AbstractText", "")
            val answer    = json.optString("Answer", "")
            val heading   = json.optString("Heading", "")
            val url_      = json.optString("AbstractURL", "")

            val text = when {
                answer.isNotBlank()    -> answer
                abstract_.isNotBlank() -> "**$heading**\n\n$abstract_${if (url_.isNotBlank()) "\n\n_Source: $url__" else ""}"
                else -> return ChatResult(false, error = "No DDG result")
            }
            ChatResult(true, text)
        } catch (e: Exception) {
            Log.e("SarvamChatClient", "DDG error: ${e.message}")
            ChatResult(false, error = "Search failed: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sarvam chat body builder
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildChatBody(history: List<Pair<String, String>>): String {
        val messages = JsonArray()

        // Strict system prompt — no preamble, no meta commentary
        val systemMsg = JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", """
                You are David AI, an intelligent assistant built by David, powered by Nexuzy Lab.
                You specialise in news article writing, SEO, WordPress publishing, and research.

                STRICT RULES — follow every time, no exceptions:
                1. NEVER start your reply with phrases like:
                   "Okay", "Sure", "Of course", "Certainly", "The user is asking",
                   "I'll use", "Let me", "I will now", "Great question", "As an AI".
                2. NEVER explain what you are about to do. Just do it.
                3. NEVER mention which AI or service you are using internally.
                4. Answer DIRECTLY. First word of your reply must be useful content.
                5. Be concise. If the answer fits in one sentence, use one sentence.
                6. Use markdown (bold, bullet points) only when it genuinely helps clarity.
            """.trimIndent())
        }
        messages.add(systemMsg)

        for ((role, content) in history) {
            messages.add(JsonObject().apply {
                addProperty("role", role)
                addProperty("content", content)
            })
        }

        return JsonObject().apply {
            addProperty("model", MODEL)
            add("messages", messages)
            addProperty("temperature", 0.6)
            addProperty("max_tokens", 800)
        }.toString()
    }

    private fun parseReply(responseBody: String): String {
        return try {
            JsonParser.parseString(responseBody).asJsonObject
                .getAsJsonArray("choices")[0]
                .asJsonObject
                .getAsJsonObject("message")
                .get("content").asString
                .trim()
        } catch (e: Exception) {
            Log.e("SarvamChatClient", "Parse error: ${e.message}")
            ""
        }
    }
}
