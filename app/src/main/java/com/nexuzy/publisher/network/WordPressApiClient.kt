package com.nexuzy.publisher.network

import android.util.Base64
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nexuzy.publisher.data.model.Article
import com.nexuzy.publisher.data.model.NewsCategory
import com.nexuzy.publisher.data.model.WordPressSite
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WordPress REST API client.
 *
 * Category push strategy:
 *   - Uses NewsCategory.getCategoryChain() to resolve parent + child categories.
 *   - Example: article.category = "AI & Machine Learning"
 *     → WordPress gets IDs for ["Technology", "AI & Machine Learning"]
 *   - Falls back to NewsCategory.detectCategory() when article.category is blank.
 *   - Each category is resolved against the live WP site; created if missing.
 *
 * Maintained by: David | Nexuzy Lab
 */
class WordPressApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    data class PublishResult(
        val success: Boolean,
        val postId: Long = 0,
        val postUrl: String = "",
        val error: String = ""
    )

    /**
     * Full publish flow:
     * 1. Detect / resolve category chain (parent + child)
     * 2. Resolve or create tag IDs
     * 3. Upload featured image if local path exists
     * 4. Publish post with SEO meta (Yoast + RankMath compatible)
     */
    suspend fun publishPost(
        site: WordPressSite,
        article: Article,
        status: String = "draft",
        adsCode: String = ""
    ): PublishResult {
        return try {
            val siteUrl = site.siteUrl.trimEnd('/')
            val auth = buildAuthHeader(site)

            // Step 1 — Resolve category chain (parent + child) → list of IDs
            val effectiveCategory = article.category.ifBlank {
                NewsCategory.detectCategory(article.title, article.summary)
            }
            val categoryChain = NewsCategory.getCategoryChain(effectiveCategory)
            Log.d("WPClient", "Category chain for '$effectiveCategory': $categoryChain")
            val categoryIds = resolveCategoryIds(siteUrl, auth, categoryChain)

            // Step 2 — Resolve / create tag IDs
            val tagIds = resolveTagIds(siteUrl, auth, article.tags)

            // Step 3 — Upload featured image if we have a local file
            val featuredMediaId = if (article.imagePath.isNotBlank()) {
                uploadFeaturedImage(siteUrl, auth, article.imagePath, article.title)
            } else 0L

            // Step 4 — Build post body
            val postBody = JsonObject().apply {
                addProperty("title", article.title)
                addProperty("content", withAdsCode(article.content, adsCode))
                addProperty("excerpt", article.metaDescription.ifBlank { article.summary })
                addProperty("status", status)

                // Assign ALL resolved category IDs (parent + child)
                if (categoryIds.isNotEmpty()) {
                    val catsArr = JsonArray()
                    categoryIds.forEach { catsArr.add(it) }
                    add("categories", catsArr)
                }

                if (tagIds.isNotEmpty()) {
                    val tagsArr = JsonArray()
                    tagIds.forEach { tagsArr.add(it) }
                    add("tags", tagsArr)
                }
                if (featuredMediaId > 0) addProperty("featured_media", featuredMediaId)

                // SEO meta fields — Yoast + RankMath + generic
                val meta = JsonObject().apply {
                    // Yoast SEO
                    addProperty("_yoast_wpseo_title",    article.title)
                    addProperty("_yoast_wpseo_focuskw",   article.focusKeyphrase)
                    addProperty("_yoast_wpseo_metadesc",  article.metaDescription)
                    // RankMath SEO
                    addProperty("rank_math_title",           article.title)
                    addProperty("rank_math_focus_keyword",   article.focusKeyphrase)
                    addProperty("rank_math_description",     article.metaDescription)
                    // Nexuzy / generic
                    addProperty("nexuzy_meta_keywords",      article.metaKeywords)
                    addProperty("nexuzy_category",           effectiveCategory)
                }
                add("meta", meta)
            }

            val request = Request.Builder()
                .url("$siteUrl/wp-json/wp/v2/posts")
                .addHeader("Authorization", auth)
                .addHeader("Content-Type", "application/json")
                .post(postBody.toString().toRequestBody(jsonMedia))
                .build()

            val response  = client.newCall(request).execute()
            val body      = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JsonParser.parseString(body).asJsonObject
                PublishResult(
                    success  = true,
                    postId   = json.get("id")?.asLong ?: 0,
                    postUrl  = json.get("link")?.asString ?: ""
                )
            } else {
                Log.e("WPClient", "Publish failed ${response.code}: $body")
                PublishResult(false, error = "HTTP ${response.code}: $body")
            }
        } catch (e: Exception) {
            Log.e("WPClient", "Exception: ${e.message}")
            PublishResult(false, error = e.message ?: "Publish error")
        }
    }

    /**
     * Resolves a list of category names to WordPress category IDs.
     * For each name: searches existing categories, creates if not found.
     * Returns the list of resolved IDs (same order as input, skips failures).
     */
    private fun resolveCategoryIds(siteUrl: String, auth: String, categoryNames: List<String>): List<Long> {
        val ids = mutableListOf<Long>()
        for (name in categoryNames) {
            val id = resolveSingleCategoryId(siteUrl, auth, name)
            if (id > 0) ids.add(id)
        }
        return ids
    }

    /**
     * Get or create a single WordPress category by name. Returns category ID or 0 on failure.
     */
    private fun resolveSingleCategoryId(siteUrl: String, auth: String, categoryName: String): Long {
        if (categoryName.isBlank()) return 0
        return try {
            // Search existing categories
            val encoded = java.net.URLEncoder.encode(categoryName.trim(), "UTF-8")
            val searchReq = Request.Builder()
                .url("$siteUrl/wp-json/wp/v2/categories?search=$encoded&per_page=10")
                .addHeader("Authorization", auth)
                .build()
            val searchResp = client.newCall(searchReq).execute()
            val searchBody = searchResp.body?.string() ?: "[]"
            val arr = JsonParser.parseString(searchBody).asJsonArray

            // Find exact name match (case-insensitive)
            for (i in 0 until arr.size()) {
                val cat = arr[i].asJsonObject
                val wpName = cat.get("name")?.asString ?: ""
                if (wpName.equals(categoryName.trim(), ignoreCase = true)) {
                    val id = cat.get("id")?.asLong ?: 0
                    Log.d("WPClient", "Category found: '$categoryName' → ID $id")
                    return id
                }
            }

            // Not found — create it
            Log.d("WPClient", "Category '$categoryName' not found, creating…")
            val createBody = JsonObject().apply { addProperty("name", categoryName) }
            val createReq = Request.Builder()
                .url("$siteUrl/wp-json/wp/v2/categories")
                .addHeader("Authorization", auth)
                .addHeader("Content-Type", "application/json")
                .post(createBody.toString().toRequestBody(jsonMedia))
                .build()
            val createResp = client.newCall(createReq).execute()
            val createRespBody = createResp.body?.string() ?: "{}"
            val newId = JsonParser.parseString(createRespBody).asJsonObject.get("id")?.asLong ?: 0
            Log.d("WPClient", "Category '$categoryName' created → ID $newId")
            newId
        } catch (e: Exception) {
            Log.w("WPClient", "Category resolve failed for '$categoryName': ${e.message}")
            0
        }
    }

    /**
     * Resolve or create tag IDs from comma-separated tag string.
     */
    private fun resolveTagIds(siteUrl: String, auth: String, tagsRaw: String): List<Long> {
        if (tagsRaw.isBlank()) return emptyList()
        val tagNames = tagsRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val ids = mutableListOf<Long>()
        for (tag in tagNames) {
            try {
                val encoded = java.net.URLEncoder.encode(tag, "UTF-8")
                val searchReq = Request.Builder()
                    .url("$siteUrl/wp-json/wp/v2/tags?search=$encoded&per_page=5")
                    .addHeader("Authorization", auth)
                    .build()
                val resp = client.newCall(searchReq).execute()
                val arr  = JsonParser.parseString(resp.body?.string() ?: "[]").asJsonArray
                if (arr.size() > 0) {
                    ids.add(arr[0].asJsonObject.get("id")?.asLong ?: continue)
                } else {
                    val createBody = JsonObject().apply { addProperty("name", tag) }
                    val createReq = Request.Builder()
                        .url("$siteUrl/wp-json/wp/v2/tags")
                        .addHeader("Authorization", auth)
                        .addHeader("Content-Type", "application/json")
                        .post(createBody.toString().toRequestBody(jsonMedia))
                        .build()
                    val createResp = client.newCall(createReq).execute()
                    val newId = JsonParser.parseString(createResp.body?.string() ?: "{}").asJsonObject.get("id")?.asLong
                    if (newId != null && newId > 0) ids.add(newId)
                }
            } catch (e: Exception) {
                Log.w("WPClient", "Tag resolve failed for '$tag': ${e.message}")
            }
        }
        return ids
    }

    /**
     * Upload an image file to the WordPress media library.
     * Returns the media attachment ID (used as featured_media).
     */
    private fun uploadFeaturedImage(siteUrl: String, auth: String, imagePath: String, title: String): Long {
        return try {
            val file = File(imagePath)
            if (!file.exists()) return 0
            val mimeType = when (file.extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png"         -> "image/png"
                "webp"        -> "image/webp"
                "gif"         -> "image/gif"
                else          -> "image/jpeg"
            }
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.asRequestBody(mimeType.toMediaType()))
                .addFormDataPart("title", title)
                .addFormDataPart("alt_text", title)
                .build()
            val request = Request.Builder()
                .url("$siteUrl/wp-json/wp/v2/media")
                .addHeader("Authorization", auth)
                .post(requestBody)
                .build()
            val response = client.newCall(request).execute()
            val body     = response.body?.string() ?: "{}"
            if (response.isSuccessful) {
                val id = JsonParser.parseString(body).asJsonObject.get("id")?.asLong ?: 0
                Log.d("WPClient", "Image uploaded → media ID $id")
                id
            } else {
                Log.w("WPClient", "Image upload failed ${response.code}: $body")
                0
            }
        } catch (e: Exception) {
            Log.w("WPClient", "Image upload exception: ${e.message}")
            0
        }
    }

    suspend fun pushDraft(site: WordPressSite, article: Article, adsCode: String = ""): PublishResult =
        publishPost(site = site, article = article, status = "draft", adsCode = adsCode)

    suspend fun pushNewsDraftWithSeo(site: WordPressSite, article: Article, adsCode: String = ""): PublishResult =
        publishPost(site = site, article = article, status = "draft", adsCode = adsCode)

    /**
     * Test WordPress credentials by calling /users/me.
     */
    suspend fun testConnection(site: WordPressSite): Boolean {
        return try {
            val siteUrl = site.siteUrl.trimEnd('/')
            val request = Request.Builder()
                .url("$siteUrl/wp-json/wp/v2/users/me")
                .addHeader("Authorization", buildAuthHeader(site))
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) { false }
    }

    private fun withAdsCode(content: String, adsCode: String): String {
        if (adsCode.isBlank()) return content
        return "$content\n\n$adsCode"
    }

    private fun buildAuthHeader(site: WordPressSite): String {
        val credentials = "${site.username}:${site.appPassword}"
        return "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
    }
}
