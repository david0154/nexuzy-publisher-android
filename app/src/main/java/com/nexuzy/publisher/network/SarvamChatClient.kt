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
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * SarvamChatClient — David AI in-app assistant.
 *
 * FIX: API key is NO LONGER hardcoded here.
 *      The key is read from ApiKeyManager (Settings → Sarvam API Key).
 *      Pass the key via chat(history, sarvamKey = apiKeyManager.getSarvamKey())
 *
 * ROUTING ORDER per user message:
 *  0. Greeting / tiny msg  → instant short reply (no API call)
 *  1. Cricket/IPL query    → cricapi.com free API  → fallback DDG
 *  2. Weather query        → open-meteo.com
 *  3. Everything else      → DDG HTML scrape for live context
 *                            → Sarvam AI gets both the question + web context
 */
object SarvamChatClient {

    private const val SARVAM_URL = "https://api.sarvam.ai/v1/chat/completions"
    private const val MODEL      = "sarvam-m"
    private val jsonMedia        = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class ChatResult(
        val success: Boolean,
        val reply: String = "",
        val error: String = ""
    )

    // ─── Live IST date/time ──────────────────────────────────────────────────

    private fun currentDateTimeIST(): String {
        val sdf = SimpleDateFormat("EEEE, dd MMMM yyyy, hh:mm a", Locale.ENGLISH)
        sdf.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return sdf.format(Date())
    }

    // ─── Greeting detector ───────────────────────────────────────────────────

    private val GREETINGS = setOf(
        "hi", "hello", "hey", "hii", "helo", "sup", "yo",
        "good morning", "good afternoon", "good evening", "good night",
        "thanks", "thank you", "thankyou", "thx", "ty",
        "ok", "okay", "k", "got it", "noted", "sure",
        "bye", "goodbye", "see you", "later",
        "how are you", "how r u", "how are u", "wassup", "what's up",
        "nice", "great", "awesome", "cool", "wow"
    )

    private fun greetingReply(msg: String): String? {
        val clean = msg.lowercase().trim().trimEnd('!', '.', '?')
        if (GREETINGS.contains(clean)) {
            return when {
                clean.startsWith("bye") || clean.startsWith("see you") || clean.startsWith("later")
                    -> "Goodbye! Come back anytime."
                clean.startsWith("thanks") || clean.startsWith("thank") || clean.startsWith("thx") || clean == "ty"
                    -> "You're welcome!"
                clean.startsWith("good morning")
                    -> "Good morning! How can I help you today?"
                clean.startsWith("good afternoon")
                    -> "Good afternoon! What can I do for you?"
                clean.startsWith("good evening")
                    -> "Good evening! How can I assist?"
                clean.startsWith("good night")
                    -> "Good night! Sleep well."
                clean == "ok" || clean == "okay" || clean == "k" || clean == "got it" || clean == "noted"
                    -> "Understood!"
                clean.startsWith("how are") || clean.startsWith("how r") || clean == "wassup" || clean == "what's up"
                    -> "I'm running great! What do you need?"
                else -> "Hey! How can I help you?"
            }
        }
        if (clean.length <= 6 && clean.all { !it.isLetterOrDigit() || it.isWhitespace() })
            return "How can I help? 😊"
        return null
    }

    // ─── Main entry point ────────────────────────────────────────────────────
    // sarvamKey: pass apiKeyManager.getSarvamKey() from the calling activity.

    suspend fun chat(
        history: List<Pair<String, String>>,
        sarvamKey: String = ""
    ): ChatResult = withContext(Dispatchers.IO) {

        val userMessage = history.lastOrNull { it.first == "user" }?.second?.trim() ?: ""

        // Step 0 — Greeting? Reply instantly, no API.
        val gReply = greetingReply(userMessage)
        if (gReply != null) return@withContext ChatResult(true, gReply)

        // Step 1 — Cricket/IPL → cricapi live data
        if (isCricketQuery(userMessage)) {
            val result = fetchCricketInfo(userMessage)
            if (result.success) return@withContext result
        }

        // Step 2 — Weather → open-meteo
        if (isWeatherQuery(userMessage)) {
            val city = extractCity(userMessage)
            return@withContext fetchWeather(city)
        }

        // Step 3 — Web context + Sarvam
        val webContext = fetchWebContext(userMessage)

        val activeKey = sarvamKey.trim()

        if (activeKey.isBlank()) {
            // No Sarvam key configured → return web snippets as fallback answer
            return@withContext if (webContext.isNotBlank())
                ChatResult(true, webContext)
            else
                ChatResult(
                    false,
                    error = "Sarvam API key not set. Go to Settings → API Keys → Sarvam and add your key."
                )
        }

        // Sarvam AI — inject web context into the prompt
        try {
            val request = Request.Builder()
                .url(SARVAM_URL)
                .addHeader("api-subscription-key", activeKey)
                .addHeader("Content-Type", "application/json")
                .post(buildChatBody(history, webContext).toRequestBody(jsonMedia))
                .build()

            val response = http.newCall(request).execute()
            val body     = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val reply = parseReply(body)
                if (reply.isNotBlank()) ChatResult(true, reply)
                else ChatResult(false, error = "Empty response from Sarvam AI")
            } else {
                Log.w("SarvamChat", "HTTP ${response.code}: $body")
                if (webContext.isNotBlank()) ChatResult(true, webContext)
                else ChatResult(false, error = "Server error (HTTP ${response.code})")
            }
        } catch (e: Exception) {
            Log.e("SarvamChat", "Chat error: ${e.message}")
            if (webContext.isNotBlank()) ChatResult(true, webContext)
            else ChatResult(false, error = e.message ?: "Network error")
        }
    }

    // ─── Web context fetcher ─────────────────────────────────────────────────

    private fun fetchWebContext(query: String): String {
        return try {
            val encoded = URLEncoder.encode(query.take(200), "UTF-8")
            val html = http.newCall(
                Request.Builder()
                    .url("https://html.duckduckgo.com/html/?q=$encoded")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                    .build()
            ).execute().body?.string() ?: return ""

            val doc = Jsoup.parse(html)
            val snippets = doc.select(".result__snippet")
                .take(4)
                .map { it.text().trim() }
                .filter { it.length > 20 }

            if (snippets.isEmpty()) return ""
            snippets.joinToString(" | ").take(800)
        } catch (e: Exception) {
            Log.w("SarvamChat", "Web context fetch failed: ${e.message}")
            ""
        }
    }

    // ─── Cricket / IPL — cricapi.com ─────────────────────────────────────────

    private val CRICKET_TRIGGERS = listOf(
        "ipl", "cricket", "match", "score", "wicket", "run", "over",
        "batting", "bowling", "test match", "t20", "odi", "series",
        "india vs", "vs india", "rcb", "csk", "mi ", "kkr", "srh",
        "dc ", "pbks", "gt ", "lsg", "rr ", "playing today", "today match",
        "live score", "who won", "cricket today"
    )

    private fun isCricketQuery(msg: String) =
        CRICKET_TRIGGERS.any { msg.lowercase().contains(it) }

    private fun fetchCricketInfo(query: String): ChatResult {
        return try {
            val resp = http.newCall(
                Request.Builder()
                    .url("https://api.cricapi.com/v1/currentMatches?apikey=free&offset=0")
                    .addHeader("User-Agent", "NexuzyPublisher/2.0")
                    .build()
            ).execute()

            val body = resp.body?.string() ?: return fetchCricketFromWeb(query)
            if (!resp.isSuccessful) return fetchCricketFromWeb(query)

            val json    = JsonParser.parseString(body).asJsonObject
            if (json.optString("status") != "success") return fetchCricketFromWeb(query)

            val dataArr = json.getAsJsonArray("data") ?: return fetchCricketFromWeb(query)
            if (dataArr.size() == 0) return fetchCricketFromWeb(query)

            val queryLower = query.lowercase()
            val sb = StringBuilder()
            sb.appendLine("🏏 **Live Cricket Matches**")
            sb.appendLine()

            var found = 0
            for (i in 0 until dataArr.size()) {
                val m = dataArr[i].asJsonObject
                val name    = m.optString("name")
                val status2 = m.optString("status")
                val venue   = m.optString("venue")
                val date    = m.optString("date")
                val ms      = m.optString("matchType")

                val relevant = queryLower.contains("today") || queryLower.contains("ipl") ||
                    name.lowercase().contains(queryLower.take(6)) ||
                    status2.lowercase().contains("live")

                if (!relevant) continue
                found++
                sb.appendLine("**$name**")
                if (status2.isNotBlank()) sb.appendLine("🟢 $status2")
                if (venue.isNotBlank())   sb.appendLine("🏟️ $venue")
                if (date.isNotBlank())    sb.appendLine("📅 $date")
                if (ms.isNotBlank())      sb.appendLine("🏆 $ms")

                val scores = m.getAsJsonArray("score")
                if (scores != null) {
                    for (j in 0 until scores.size()) {
                        val sc = scores[j].asJsonObject
                        val inn = sc.optString("inning")
                        val r = sc.optString("r"); val w = sc.optString("w"); val o = sc.optString("o")
                        if (inn.isNotBlank()) sb.appendLine("  $inn: $r/$w ($o ov)")
                    }
                }
                sb.appendLine()
                if (found >= 5) break
            }
            if (found == 0) return fetchCricketFromWeb(query)
            sb.append("_Source: cricapi.com — live_")
            ChatResult(true, sb.toString().trim())
        } catch (e: Exception) {
            fetchCricketFromWeb(query)
        }
    }

    private fun fetchCricketFromWeb(query: String): ChatResult {
        val q = if (query.lowercase().contains("ipl") || query.lowercase().contains("today"))
            "IPL 2026 today match score live" else "$query cricket score live"
        val ctx = fetchWebContext(q)
        return if (ctx.isNotBlank()) ChatResult(true, "🏏 $ctx\n\n_Source: DuckDuckGo_")
        else ChatResult(false, error = "No cricket data found")
    }

    private fun JsonObject.optString(key: String, default: String = ""): String =
        if (has(key) && !get(key).isJsonNull) get(key).asString else default

    // ─── Weather — open-meteo.com ────────────────────────────────────────────

    private fun isWeatherQuery(msg: String): Boolean {
        val l = msg.lowercase()
        return l.contains("weather") || l.contains("temperature") ||
               l.contains("forecast") || l.contains("rain") ||
               l.contains("humidity") || l.contains("feels like")
    }

    private fun extractCity(msg: String): String {
        Regex("weather\\s+(?:in|of|at|for)\\s+([a-z\\s]+)", RegexOption.IGNORE_CASE)
            .find(msg)?.groupValues?.get(1)?.trim()?.let { if (it.isNotBlank()) return it }
        Regex("([a-z\\s]+?)\\s+(?:weather|temperature|forecast)", RegexOption.IGNORE_CASE)
            .find(msg)?.groupValues?.get(1)?.trim()?.let { if (it.length > 2) return it }
        return "Kolkata"
    }

    private fun fetchWeather(city: String): ChatResult {
        return try {
            val geoBody = http.newCall(
                Request.Builder()
                    .url("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(city, "UTF-8")}&count=1&language=en&format=json")
                    .build()
            ).execute().body?.string() ?: return ChatResult(false, error = "Geocode failed")

            val results = JsonParser.parseString(geoBody).asJsonObject
                .getAsJsonArray("results") ?: return ChatResult(false, error = "City not found: $city")
            if (results.size() == 0) return ChatResult(false, error = "City not found: $city")

            val loc      = results[0].asJsonObject
            val lat      = loc.get("latitude").asDouble
            val lon      = loc.get("longitude").asDouble
            val cityName = loc.optString("name", city)
            val country  = loc.optString("country")

            val wxBody = http.newCall(
                Request.Builder()
                    .url("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                         "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m" +
                         "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code" +
                         "&timezone=auto&forecast_days=3")
                    .build()
            ).execute().body?.string() ?: return ChatResult(false, error = "Weather fetch failed")

            val wx    = JsonParser.parseString(wxBody).asJsonObject
            val cur   = wx.getAsJsonObject("current")
            val daily = wx.getAsJsonObject("daily")

            val tempC    = cur.get("temperature_2m").asDouble
            val feelsC   = cur.get("apparent_temperature").asDouble
            val humidity = cur.get("relative_humidity_2m").asInt
            val wind     = cur.get("wind_speed_10m").asDouble
            val rain     = cur.get("precipitation").asDouble
            val condition = weatherCodeToText(cur.get("weather_code").asInt)
            val maxT  = daily.getAsJsonArray("temperature_2m_max")
            val minT  = daily.getAsJsonArray("temperature_2m_min")
            val rainD = daily.getAsJsonArray("precipitation_sum")
            val days  = daily.getAsJsonArray("time")

            ChatResult(true, buildString {
                appendLine("🌤️ **Weather in $cityName${if (country.isNotBlank()) ", $country" else ""}**")
                appendLine()
                appendLine("**Now:** $condition")
                appendLine("**Temperature:** ${tempC}°C  (feels like ${feelsC}°C)")
                appendLine("**Humidity:** $humidity%  •  **Wind:** ${wind} km/h")
                if (rain > 0) appendLine("**Rain:** ${rain} mm")
                appendLine()
                appendLine("📅 **3-Day Forecast:**")
                for (i in 0 until minOf(3, days.size())) {
                    val sym = if (rainD[i].asDouble > 1.0) "🌧️" else "☀️"
                    appendLine("$sym **${days[i].asString}** — ${maxT[i].asDouble}°C / ${minT[i].asDouble}°C" +
                               if (rainD[i].asDouble > 0) ", rain ${rainD[i].asDouble}mm" else "")
                }
                append("\n_Source: open-meteo.com_")
            })
        } catch (e: Exception) {
            ChatResult(false, error = "Weather error: ${e.message}")
        }
    }

    private fun weatherCodeToText(code: Int) = when (code) {
        0       -> "Clear sky ☀️"
        1, 2, 3 -> "Partly cloudy ⛅"
        45, 48  -> "Foggy 🌫️"
        51,53,55-> "Drizzle 🌦️"
        61,63,65-> "Rain 🌧️"
        71,73,75-> "Snow ❄️"
        80,81,82-> "Rain showers 🌧️"
        95      -> "Thunderstorm ⛈️"
        96, 99  -> "Thunderstorm with hail ⛈️"
        else    -> "Unknown ($code)"
    }

    // ─── Sarvam body builder ─────────────────────────────────────────────────

    private fun buildChatBody(
        history: List<Pair<String, String>>,
        webContext: String = ""
    ): String {
        val messages = JsonArray()
        val now = currentDateTimeIST()

        val contextBlock = if (webContext.isNotBlank()) """

            LIVE WEB DATA (fetched right now from the internet — use this first):
            $webContext
        """.trimIndent() else ""

        val systemContent = """
            You are David AI, a smart assistant by David @ Nexuzy Lab.
            You help with news writing, SEO, WordPress publishing, and general questions.

            CURRENT DATE AND TIME: $now (IST)
            $contextBlock

            STRICT RULES:
            1. Keep replies SHORT. 1-3 sentences unless the user asks for more.
            2. NEVER start with "Okay", "Sure", "Of course", "Certainly", "Let me", "As an AI".
            3. Answer DIRECTLY. First word = useful content.
            4. If LIVE WEB DATA is provided above, use it to answer. Do NOT ignore it.
            5. NEVER say "knowledge cutoff", "last update", or "training data".
               Say "I don't have that info" if needed.
            6. NEVER reveal you are Sarvam, Gemini, or any other AI system.
        """.trimIndent()

        messages.add(JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", systemContent)
        })

        for ((role, content) in history) {
            messages.add(JsonObject().apply {
                addProperty("role", role)
                addProperty("content", content)
            })
        }

        return JsonObject().apply {
            addProperty("model", MODEL)
            add("messages", messages)
            addProperty("temperature", 0.5)
            addProperty("max_tokens", 400)
        }.toString()
    }

    private fun parseReply(body: String): String = try {
        JsonParser.parseString(body).asJsonObject
            .getAsJsonArray("choices")[0].asJsonObject
            .getAsJsonObject("message")
            .get("content").asString.trim()
    } catch (e: Exception) { "" }
}
