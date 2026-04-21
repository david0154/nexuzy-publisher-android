package com.nexuzy.publisher.network

import android.util.Base64
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nexuzy.publisher.data.model.Article
import com.nexuzy.publisher.data.model.WordPressSite
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class WordPressApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    data class PublishResult(
        val success: Boolean,
        val postId: Long = 0,
        val postUrl: String = "",
        val error: String = ""
    )

    suspend fun publishPost(site: WordPressSite, article: Article, status: String = "draft"): PublishResult {
        return try {
            val siteUrl = site.siteUrl.trimEnd('/')
            val credentials = "${site.username}:${site.appPassword}"
            val auth = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)

            val body = JsonObject().apply {
                addProperty("title", article.title)
                addProperty("content", article.content)
                addProperty("status", status)
                addProperty("excerpt", article.summary)
                if (article.category.isNotBlank()) addProperty("categories", article.category)
                if (article.tags.isNotBlank()) addProperty("tags", article.tags)
            }.toString()

            val request = Request.Builder()
                .url("$siteUrl/wp-json/wp/v2/posts")
                .addHeader("Authorization", "Basic $auth")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody(jsonMedia))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JsonParser.parseString(responseBody).asJsonObject
                PublishResult(
                    success = true,
                    postId = json.get("id")?.asLong ?: 0,
                    postUrl = json.get("link")?.asString ?: ""
                )
            } else {
                PublishResult(false, error = "HTTP ${response.code}: $responseBody")
            }
        } catch (e: Exception) {
            PublishResult(false, error = e.message ?: "Publish error")
        }
    }

    suspend fun testConnection(site: WordPressSite): Boolean {
        return try {
            val siteUrl = site.siteUrl.trimEnd('/')
            val credentials = "${site.username}:${site.appPassword}"
            val auth = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
            val request = Request.Builder()
                .url("$siteUrl/wp-json/wp/v2/users/me")
                .addHeader("Authorization", "Basic $auth")
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) { false }
    }
}
