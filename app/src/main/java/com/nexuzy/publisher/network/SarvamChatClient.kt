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
 * ROUTING ORDER per user message:
 *  1. Cricket/IPL query   → cricapi.com free API (live scores, today’s matches)
 *  2. Weather query       → open-meteo.com (no API key, always fresh)
 *  3. Internet/search     → DuckDuckGo HTML scrape (snippets from live web)
 *  4. Everything else     → Sarvam AI with live IST date+time in system prompt
 */
object SarvamChatClient {

    // TODO: Replace with your actual developer Sarvam API key
    private const val DEV_API_KEY = "your-sarvam-dev-key-here"

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

    // ─── Live date/time (IST) ────────────────────────────────────────────────

    private fun currentDateTimeIST(): String {
        val sdf = SimpleDateFormat("EEEE, dd MMMM yyyy, hh:mm a", Locale.ENGLISH)
        sdf.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return sdf.format(Date())
    }

    // ─── Main entry point ────────────────────────────────────────────────────

    suspend fun chat(history: List<Pair<String, String>>): ChatResult =
        withContext(Dispatchers.IO) {

            val userMessage = history.lastOrNull { it.first == "user" }?.second?.trim() ?: ""

            // 1) Cricket / IPL query → live cricapi
            if (isCricketQuery(userMessage)) {
                val result = fetchCricketInfo(userMessage)
                if (result.success) return@withContext result
                // fall through to DDG if cricapi fails
            }

            // 2) Weather query → open-meteo
            if (isWeatherQuery(userMessage)) {
                val city = extractCity(userMessage)
                return@withContext fetchWeather(city)
            }

            // 3) Internet/search → DuckDuckGo HTML scrape
            if (isSearchQuery(userMessage)) {
                val ddgResult = fetchDuckDuckGoHTML(userMessage)
                if (ddgResult.success) return@withContext ddgResult
            }

            // 4) Sarvam AI
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
                    Log.w("SarvamChat", "HTTP ${response.code}: $body")
                    ChatResult(false, error = "Server error (HTTP ${response.code})")
                }
            } catch (e: Exception) {
                Log.e("SarvamChat", "Chat error: ${e.message}")
                ChatResult(false, error = e.message ?: "Network error")
            }
        }

    // ─── Cricket / IPL ─ cricapi.com free endpoint ───────────────────────────

    private val CRICKET_TRIGGERS = listOf(
        "ipl", "cricket", "match", "score", "wicket", "run", "over",
        "batting", "bowling", "test match", "t20", "odi", "series",
        "india vs", "vs india", "rcb", "csk", "mi ", "kkr", "srh",
        "dc ", "pbks", "gt ", "lsg", "rr ", "playing today", "today match",
        "live score", "who won", "cricket today"
    )

    private fun isCricketQuery(msg: String): Boolean {
        val lower = msg.lowercase()
        return CRICKET_TRIGGERS.any { lower.contains(it) }
    }

    /**
     * Fetches live cricket matches from cricapi.com (free, no key for currentMatches).
     * Falls back to DDG HTML scrape with the query if API returns nothing.
     */
    private fun fetchCricketInfo(query: String): ChatResult {
        return try {
            val matchesUrl = "https://api.cricapi.com/v1/currentMatches?apikey=free&offset=0"

            val resp = http.newCall(
                Request.Builder()
                    .url(matchesUrl)
                    .addHeader("User-Agent", "NexuzyPublisher/2.0")
                    .build()
            ).execute()

            val body = resp.body?.string() ?: return fetchCricketFromWeb(query)

            if (!resp.isSuccessful) return fetchCricketFromWeb(query)

            val json   = JsonParser.parseString(body).asJsonObject
            val status = json.optString("status", "")
            if (status != "success") return fetchCricketFromWeb(query)

            val dataArr = json.getAsJsonArray("data") ?: return fetchCricketFromWeb(query)
            if (dataArr.size() == 0) return fetchCricketFromWeb(query)

            val queryLower = query.lowercase()
            val sb = StringBuilder()
            sb.appendLine("\uD83C\uDFCF **Live Cricket Matches**")
            sb.appendLine()

            var found = 0
            for (i in 0 until dataArr.size()) {
                val m       = dataArr[i].asJsonObject
                val name    = m.optString("name", "")
                val status2 = m.optString("status", "")
                val venue   = m.optString("venue", "")
                val date    = m.optString("date", "")
                val ms      = m.optString("matchType", "")

                val matchRelevant = queryLower.contains("today") ||
                    queryLower.contains("ipl") ||
                    name.lowercase().contains(queryLower.take(6)) ||
                    status2.lowercase().contains("live")

                if (!matchRelevant) continue
                found++

                sb.appendLine("**$name**")
                if (status2.isNotBlank()) sb.appendLine("\uD83D\uDFE2 $status2")
                if (venue.isNotBlank())   sb.appendLine("\uD83C\uDFDF\uFE0F $venue")
                if (date.isNotBlank())    sb.appendLine("\uD83D\uDCC5 $date")
                if (ms.isNotBlank())      sb.appendLine("\uD83C\uDFC6 $ms")

                val scores = m.getAsJsonArray("score")
                if (scores != null && scores.size() > 0) {
                    for (j in 0 until scores.size()) {
                        val sc  = scores[j].asJsonObject
                        val inn = sc.optString("inning", "")
                        val r   = sc.optString("r", "")
                        val w   = sc.optString("w", "")
                        val o   = sc.optString("o", "")
                        if (inn.isNotBlank()) sb.appendLine("  $inn: $r/$w ($o ov)")
                    }
                }
                sb.appendLine()
                if (found >= 5) break
            }

            if (found == 0) return fetchCricketFromWeb(query)

            sb.append("_Source: cricapi.com — live data_")
            ChatResult(true, sb.toString().trim())

        } catch (e: Exception) {
            Log.e("SarvamChat", "Cricket API error: ${e.message}")
            fetchCricketFromWeb(query)
        }
    }

    private fun fetchCricketFromWeb(query: String): ChatResult {
        val searchQ = if (query.lowercase().contains("ipl") || query.lowercase().contains("today"))
            "IPL 2025 today match score live"
        else
            "$query cricket score live"
        return fetchDuckDuckGoHTML(searchQ)
    }

    private fun JsonObject.optString(key: String, default: String = ""): String =
        if (has(key) && !get(key).isJsonNull) get(key).asString else default

    // ─── Weather — open-meteo.com ─────────────────────────────────────────────

    private fun isWeatherQuery(msg: String): Boolean {
        val lower = msg.lowercase()
        return lower.contains("weather") || lower.contains("temperature") ||
               lower.contains("forecast") || lower.contains("rain") ||
               lower.contains("humidity") || lower.contains("feels like")
    }

    private fun extractCity(msg: String): String {
        val inOf = Regex("weather\\s+(?:in|of|at|for)\\s+([a-z\\s]+)", RegexOption.IGNORE_CASE)
            .find(msg)?.groupValues?.get(1)?.trim()
        if (!inOf.isNullOrBlank()) return inOf
        val before = Regex("([a-z\\s]+?)\\s+(?:weather|temperature|forecast)", RegexOption.IGNORE_CASE)
            .find(msg)?.groupValues?.get(1)?.trim()
        if (!before.isNullOrBlank() && before.length > 2) return before
        return "Kolkata"
    }

    private fun fetchWeather(city: String): ChatResult {
        return try {
            val geoUrl  = "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(city, "UTF-8")}&count=1&language=en&format=json"
            val geoBody = http.newCall(Request.Builder().url(geoUrl).build()).execute().body?.string()
                ?: return ChatResult(false, error = "Could not geocode: $city")
            val geoJson  = JsonParser.parseString(geoBody).asJsonObject
            val results  = geoJson.getAsJsonArray("results") ?: return ChatResult(false, error = "City not found: $city")
            if (results.size() == 0) return ChatResult(false, error = "City not found: $city")
            val loc      = results[0].asJsonObject
            val lat      = loc.get("latitude").asDouble
            val lon      = loc.get("longitude").asDouble
            val cityName = loc.optString("name", city)
            val country  = loc.optString("country", "")
            val wxUrl = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code" +
                "&timezone=auto&forecast_days=3"
            val wxBody = http.newCall(Request.Builder().url(wxUrl).build()).execute().body?.string()
                ?: return ChatResult(false, error = "Weather fetch failed")
            val wx    = JsonParser.parseString(wxBody).asJsonObject
            val cur   = wx.getAsJsonObject("current")
            val daily = wx.getAsJsonObject("daily")
            val tempC    = cur.get("temperature_2m").asDouble
            val feelsC   = cur.get("apparent_temperature").asDouble
            val humidity = cur.get("relative_humidity_2m").asInt
            val wind     = cur.get("wind_speed_10m").asDouble
            val rain     = cur.get("precipitation").asDouble
            val condition = weatherCodeToText(cur.get("weather_code").asInt)
            val maxTemps  = daily.getAsJsonArray("temperature_2m_max")
            val minTemps  = daily.getAsJsonArray("temperature_2m_min")
            val rainDays  = daily.getAsJsonArray("precipitation_sum")
            val days      = daily.getAsJsonArray("time")
            val reply = buildString {
                appendLine("\uD83C\uDF24\uFE0F **Weather in $cityName${if (country.isNotBlank()) ", $country" else ""}**")
                appendLine()
                appendLine("**Now:** $condition")
                appendLine("**Temperature:** ${tempC}°C (feels like ${feelsC}°C)")
                appendLine("**Humidity:** $humidity%  •  **Wind:** ${wind} km/h")
                if (rain > 0) appendLine("**Rain:** ${rain} mm")
                appendLine()
                appendLine("\uD83D\uDCC5 **3-Day Forecast:**")
                for (i in 0 until minOf(3, days.size())) {
                    val sym = if (rainDays[i].asDouble > 1.0) "\uD83C\uDF27\uFE0F" else "\u2600\uFE0F"
                    appendLine("$sym **${days[i].asString}** — ${maxTemps[i].asDouble}°C / ${minTemps[i].asDouble}°C${if (rainDays[i].asDouble > 0) ", rain ${rainDays[i].asDouble}mm" else ""}")
                }
                append("\n_Source: open-meteo.com_")
            }
            ChatResult(true, reply)
        } catch (e: Exception) {
            ChatResult(false, error = "Could not fetch weather: ${e.message}")
        }
    }

    private fun weatherCodeToText(code: Int): String = when (code) {
        0       -> "Clear sky \u2600\uFE0F"
        1, 2, 3 -> "Partly cloudy \u26C5"
        45, 48  -> "Foggy \uD83C\uDF2B\uFE0F"
        51,53,55-> "Drizzle \uD83C\uDF26\uFE0F"
        61,63,65-> "Rain \uD83C\uDF27\uFE0F"
        71,73,75-> "Snow \u2744\uFE0F"
        80,81,82-> "Rain showers \uD83C\uDF26\uFE0F"
        95      -> "Thunderstorm \u26C8\uFE0F"
        96, 99  -> "Thunderstorm with hail \u26C8\uFE0F"
        else    -> "Unknown ($code)"
    }

    // ─── DuckDuckGo HTML scrape (live web snippets) ───────────────────────────

    private val SEARCH_TRIGGERS = listOf(
        "search", "look up", "find",
        "what is", "what are", "what was", "who is", "who was",
        "when is", "when was", "when did",
        "latest", "newest", "recent", "current", "today", "yesterday",
        "live", "breaking", "just announced",
        "price", "stock", "rate", "crypto", "bitcoin",
        "news", "update", "announcement", "launched", "released",
        "election", "result", "winner", "ceo", "prime minister"
    )

    private fun isSearchQuery(msg: String): Boolean {
        val lower = msg.lowercase()
        return SEARCH_TRIGGERS.any { lower.contains(it) }
    }

    private fun fetchDuckDuckGoHTML(query: String): ChatResult {
        return try {
            val encoded = URLEncoder.encode(query.take(200), "UTF-8")
            val url = "https://html.duckduckgo.com/html/?q=$encoded"
            val html = http.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                    .build()
            ).execute().body?.string() ?: return ChatResult(false, error = "No response")

            val doc      = Jsoup.parse(html)
            val snippets = doc.select(".result__snippet").take(5)
                .map { it.text().trim() }.filter { it.length > 30 }
            val titles   = doc.select(".result__title").take(5)
                .map { it.text().trim() }.filter { it.length > 5 }

            if (snippets.isEmpty() && titles.isEmpty())
                return ChatResult(false, error = "No DDG results")

            val combined = buildString {
                appendLine("\uD83D\uDD0D **Search results for: $query**")
                appendLine()
                titles.zip(snippets).forEachIndexed { i, (t, s) ->
                    appendLine("**${i + 1}. $t**")
                    appendLine(s)
                    appendLine()
                }
                titles.drop(snippets.size).forEach { appendLine("- $it") }
                append("_Source: DuckDuckGo web search_")
            }.take(1500)

            ChatResult(true, combined)
        } catch (e: Exception) {
            Log.e("SarvamChat", "DDG HTML error: ${e.message}")
            ChatResult(false, error = "Search failed: ${e.message}")
        }
    }

    // ─── Sarvam body builder — live date injected ────────────────────────────

    private fun buildChatBody(history: List<Pair<String, String>>): String {
        val messages     = JsonArray()
        val liveDateTime = currentDateTimeIST()

        val systemMsg = JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", """
                You are David AI, an intelligent assistant built by David, powered by Nexuzy Lab.
                You specialise in news article writing, SEO, WordPress publishing, and research.

                CURRENT DATE AND TIME: $liveDateTime (IST)
                Use this date whenever the user asks what today's date or time is.

                STRICT RULES:
                1. NEVER start with: "Okay", "Sure", "Of course", "Certainly", "Let me", "As an AI".
                2. NEVER explain what you are about to do. Just do it.
                3. NEVER mention which AI or service you are using internally.
                4. Answer DIRECTLY. First word must be useful content.
                5. Be concise. One sentence if that's enough.
                6. NEVER say "my knowledge cutoff", "as of my last update", or any training data phrase.
                   Just say "I don't have that information right now" if needed.
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

    private fun parseReply(body: String): String {
        return try {
            JsonParser.parseString(body).asJsonObject
                .getAsJsonArray("choices")[0].asJsonObject
                .getAsJsonObject("message")
                .get("content").asString.trim()
        } catch (e: Exception) { "" }
    }
}
