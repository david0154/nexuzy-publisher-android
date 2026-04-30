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
import java.util.concurrent.TimeUnit

/**
 * David AI - Pre-keyed Sarvam AI chat client.
 *
 * The API key is embedded by the developer (David / Nexuzy Lab).
 * End users do NOT need to configure any API key to use David AI.
 *
 * API    : Sarvam AI - OpenAI-compatible Chat Completions
 * Model  : sarvam-m
 * Brand  : David AI by Nexuzy Lab
 *
 * To update the API key, replace DAVID_AI_API_KEY below.
 */
object SarvamChatClient {

    // ---------------------------------------------------------------
    // Developer pre-embedded API key for David AI.
    // Replace this with your actual Sarvam AI API key.
    // Users do NOT see or configure this key.
    // ---------------------------------------------------------------
    private const val DAVID_AI_API_KEY = "YOUR_SARVAM_AI_API_KEY_HERE"

    private const val SARVAM_CHAT_URL = "https://api.sarvam.ai/v1/chat/completions"
    private const val MODEL            = "sarvam-m"
    private const val MAX_TOKENS       = 1024
    private const val TEMPERATURE      = 0.7

    private const val SYSTEM_PROMPT =
        "You are David AI, an intelligent and helpful assistant created by David and powered by Nexuzy Lab. " +
        "You specialize in: news article writing and rewriting, SEO optimization, WordPress publishing, " +
        "journalism, content strategy, fact-checking, AI tools, and general knowledge. " +
        "When asked who you are, always say you are David AI, created by David at Nexuzy Lab. " +
        "Be concise, accurate, and helpful. Respond in the same language the user writes in."

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    data class ChatResult(
        val success: Boolean,
        val reply: String = "",
        val error: String = ""
    )

    /**
     * Send the full conversation history to David AI and get a reply.
     *
     * @param history List of (role, content) pairs.
     *                role must be "user" or "assistant".
     * @return [ChatResult] with the assistant's reply, or an error message.
     */
    suspend fun chat(history: List<Pair<String, String>>): ChatResult = withContext(Dispatchers.IO) {
        try {
            val messagesArr = JsonArray()

            // System message (sets David AI identity)
            messagesArr.add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", SYSTEM_PROMPT)
            })

            // Conversation history
            for ((role, content) in history) {
                messagesArr.add(JsonObject().apply {
                    addProperty("role", role)
                    addProperty("content", content)
                })
            }

            val requestBody = JsonObject().apply {
                addProperty("model", MODEL)
                add("messages", messagesArr)
                addProperty("max_tokens", MAX_TOKENS)
                addProperty("temperature", TEMPERATURE)
            }

            val request = Request.Builder()
                .url(SARVAM_CHAT_URL)
                .addHeader("Authorization", "Bearer $DAVID_AI_API_KEY")
                .addHeader("api-subscription-key", DAVID_AI_API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response     = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            Log.d("DavidAI", "HTTP ${response.code}")

            if (response.isSuccessful) {
                val json = JsonParser.parseString(responseBody).asJsonObject
                val reply = json
                    .getAsJsonArray("choices")
                    ?.get(0)?.asJsonObject
                    ?.getAsJsonObject("message")
                    ?.get("content")?.asString
                    ?: "I'm sorry, I could not generate a response. Please try again."
                ChatResult(success = true, reply = reply.trim())
            } else {
                Log.e("DavidAI", "Error ${response.code}: $responseBody")
                ChatResult(
                    success = false,
                    error   = when (response.code) {
                        401  -> "API key invalid. Please contact Nexuzy Lab support."
                        429  -> "Too many requests. Please wait a moment and try again."
                        503  -> "David AI service is temporarily unavailable. Try again later."
                        else -> "Could not reach David AI (HTTP ${response.code})."
                    }
                )
            }
        } catch (e: java.net.UnknownHostException) {
            ChatResult(false, error = "No internet connection. Please check your network.")
        } catch (e: java.net.SocketTimeoutException) {
            ChatResult(false, error = "David AI took too long to respond. Please try again.")
        } catch (e: Exception) {
            Log.e("DavidAI", "Exception: ${e.message}")
            ChatResult(false, error = "Unexpected error: ${e.message}")
        }
    }
}
