package com.nexuzy.publisher.network

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Sarvam AI Chat Client — David AI in-app assistant.
 *
 * Powers the chat screen in DavidAiChatActivity.
 * Uses Sarvam's sarvam-m model via /v1/chat/completions endpoint.
 *
 * This is a SEPARATE client from SarvamApiClient:
 *   - SarvamApiClient  → grammar correction for written articles (uses user's key)
 *   - SarvamChatClient → David AI in-app chat (uses developer pre-embedded key)
 *
 * End users do NOT need to configure an API key for the chat feature.
 * Replace DEV_API_KEY with your Sarvam developer key.
 */
object SarvamChatClient {

    // TODO: Replace with your actual developer Sarvam API key from https://dashboard.sarvam.ai
    private const val DEV_API_KEY = "your-sarvam-dev-key-here"

    private const val BASE_URL = "https://api.sarvam.ai/v1/chat/completions"
    private const val MODEL    = "sarvam-m"

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class ChatResult(
        val success: Boolean,
        val reply: String = "",
        val error: String = ""
    )

    /**
     * Send conversation history to Sarvam AI and get the assistant reply.
     * @param history List of (role, content) pairs. role = "user" or "assistant".
     */
    suspend fun chat(history: List<Pair<String, String>>): ChatResult =
        withContext(Dispatchers.IO) {
            if (DEV_API_KEY == "your-sarvam-dev-key-here" || DEV_API_KEY.isBlank()) {
                return@withContext ChatResult(
                    false,
                    error = "David AI is not yet configured. Please set the developer API key."
                )
            }
            try {
                val requestBody = buildChatBody(history)
                val request = Request.Builder()
                    .url(BASE_URL)
                    .addHeader("api-subscription-key", DEV_API_KEY)
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toRequestBody(jsonMedia))
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val reply = parseReply(body)
                    if (reply.isNotBlank()) ChatResult(true, reply)
                    else ChatResult(false, error = "Empty response from Sarvam AI")
                } else {
                    Log.w("SarvamChatClient", "HTTP ${response.code}: $body")
                    ChatResult(false, error = "HTTP ${response.code}: Server error")
                }
            } catch (e: Exception) {
                Log.e("SarvamChatClient", "Chat error: ${e.message}")
                ChatResult(false, error = e.message ?: "Network error")
            }
        }

    private fun buildChatBody(history: List<Pair<String, String>>): String {
        val messages = JsonArray()

        val systemMsg = JsonObject().apply {
            addProperty("role", "system")
            addProperty(
                "content",
                "You are David AI, an intelligent assistant created by David and powered by Nexuzy Lab. " +
                "You specialise in news article writing, SEO optimisation, WordPress publishing, " +
                "fact-checking, and research. Be concise, helpful, and professional."
            )
        }
        messages.add(systemMsg)

        for ((role, content) in history) {
            val msg = JsonObject().apply {
                addProperty("role", role)
                addProperty("content", content)
            }
            messages.add(msg)
        }

        val body = JsonObject().apply {
            addProperty("model", MODEL)
            add("messages", messages)
            addProperty("temperature", 0.7)
            addProperty("max_tokens", 1024)
        }
        return body.toString()
    }

    private fun parseReply(responseBody: String): String {
        return try {
            val json = JsonParser.parseString(responseBody).asJsonObject
            json.getAsJsonArray("choices")[0]
                .asJsonObject
                .getAsJsonObject("message")
                .get("content").asString.trim()
        } catch (e: Exception) {
            Log.e("SarvamChatClient", "Parse error: ${e.message}")
            ""
        }
    }
}
