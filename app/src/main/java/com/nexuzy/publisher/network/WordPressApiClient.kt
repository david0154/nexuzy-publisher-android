package com.nexuzy.publisher.network

import android.util.Base64
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nexuzy.publisher.data.model.Article
import com.nexuzy.publisher.data.model.NewsCategory
import com.nexuzy.publisher.data.model.WordPressSite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class WordPressApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ─── Data classes ──────────────────────────────────────────────────────────

    data class PublishResult(
        val success: Boolean,
        val postId: Long = 0,
        val postUrl: String = "",
        val error: String = ""
    )

    /**
     * A WordPress category as returned by /wp-json/wp/v2/categories.
     */
    data class WpCategory(
        val id: Long,
        val name: String,
        val slug: String,
        val parentId: Long = 0,
        val count: Int = 0
    )

    // ─── Fetch all WordPress categories ───────────────────────────────────────

    /**
     * Fetches ALL categories from the WordPress site.
     * Used to populate the category spinner when adding an RSS feed,
     * and to validate category before pushing an article.
     *
     * Paginates automatically (100 per page) so large sites return everything.
     */
    suspend fun fetchCategories(site: WordPressSite): List<WpCategory> =
        withContext(Dispatchers.IO) {
            val siteUrl = site.siteUrl.trimEnd('/')
            val auth    = buildAuthHeader(site)
            val all     = mutableListOf<WpCategory>()
            var page    = 1

            while (true) {
                try {
                    val request = Request.Builder()
                        .url("$siteUrl/wp-json/wp/v2/categories?per_page=100&page=$page&orderby=name&order=asc")
                        .addHeader("Authorization", auth)
                        .build()
                    val response = client.newCall(request).execute()
                    val body     = response.body?.string() ?: "[]"
                    if (!response.isSuccessful) break

                    val arr = JsonParser.parseString(body).asJsonArray
                    if (arr.size() == 0) break

                    for (i in 0 until arr.size()) {
                        val obj = arr[i].asJsonObject
                        all.add(
                            WpCategory(
                                id       = obj.get("id")?.asLong  ?: 0,
                                name     = obj.getAsJsonObject("name")?.asString
                                             ?: obj.get("name")?.asString ?: "",
                                slug     = obj.get("slug")?.asString   ?: "",
                                parentId = obj.get("parent")?.asLong   ?: 0,
                                count    = obj.get("count")?.asInt      ?: 0
                            )
                        )
                    }
                    // If fewer than 100 returned, we've reached the last page
                    if (arr.size() < 100) break
                    page++
                } catch (e: Exception) {
                    Log.w("WPClient", "fetchCategories page $page error: ${e.message}")
                    break
                }
            }
            Log.i("WPClient", "Fetched ${all.size} categories from ${site.siteUrl}")
            all
        }

    // ─── Publish post (category-validated) ───────────────────────────────────

    /**
     * Full publish flow:
     * 1. Validate article category exists on WordPress site (skip push if not found).
     * 2. Resolve / create tag IDs.
     * 3. Upload featured image (local file → media library).
     *    If local file missing, download from imageUrl and upload.
     * 4. Publish draft with full SEO meta.
     *
     * Category-push rule:
     *   - Only push if article.category matches an existing WP category.
     *   - If category is absent on WP, return PublishResult with skipReason.
     *   - Set allowCategoryCreate = true to create missing categories automatically.
     */
    suspend fun publishPost(
        site: WordPressSite,
        article: Article,
        status: String = "draft",
        adsCode: String = "",
        allowCategoryCreate: Boolean = true
    ): PublishResult = withContext(Dispatchers.IO) {
        try {
            val siteUrl = site.siteUrl.trimEnd('/')
            val auth    = buildAuthHeader(site)

            // Step 1 — Resolve category (validate or create)
            val effectiveCategory = article.category.ifBlank {
                NewsCategory.detectCategory(article.title, article.summary)
            }

            val categoryIds: List<Long>
            if (!allowCategoryCreate) {
                // Strict mode: only push if category EXISTS on WordPress
                val wpCategories = fetchCategoriesRaw(siteUrl, auth)
                val matched = wpCategories.filter {
                    it.name.equals(effectiveCategory.trim(), ignoreCase = true) ||
                    it.slug.equals(
                        effectiveCategory.trim().lowercase().replace(" ", "-"),
                        ignoreCase = true
                    )
                }
                if (matched.isEmpty()) {
                    Log.w("WPClient", "Category '$effectiveCategory' not found on WP, skipping push")
                    return@withContext PublishResult(
                        false,
                        error = "CATEGORY_NOT_FOUND:$effectiveCategory"
                    )
                }
                categoryIds = matched.map { it.id }
            } else {
                // Default: create category if missing (original behaviour)
                val chain = NewsCategory.getCategoryChain(effectiveCategory)
                categoryIds = resolveCategoryIds(siteUrl, auth, chain)
            }

            // Step 2 — Resolve / create tag IDs
            val tagIds = resolveTagIds(siteUrl, auth, article.tags)

            // Step 3 — Upload featured image
            //   Priority: local file → remote URL → no image
            val featuredMediaId = when {
                article.imagePath.isNotBlank() && File(article.imagePath).exists() ->
                    uploadFeaturedImage(siteUrl, auth, article.imagePath, article.title)
                article.imageUrl.isNotBlank() ->
                    uploadFeaturedImageFromUrl(siteUrl, auth, article.imageUrl, article.title)
                else -> 0L
            }

            // Step 4 — Build and send post
            val postBody = JsonObject().apply {
                addProperty("title",   article.title)
                addProperty("content", withAdsCode(article.content, adsCode))
                addProperty("excerpt", article.metaDescription.ifBlank { article.summary })
                addProperty("status",  status)

                if (categoryIds.isNotEmpty()) {
                    add("categories", JsonArray().also { arr ->
                        categoryIds.forEach { arr.add(it) }
                    })
                }
                if (tagIds.isNotEmpty()) {
                    add("tags", JsonArray().also { arr ->
                        tagIds.forEach { arr.add(it) }
                    })
                }
                if (featuredMediaId > 0) addProperty("featured_media", featuredMediaId)

                add("meta", JsonObject().apply {
                    addProperty("_yoast_wpseo_title",          article.title)
                    addProperty("_yoast_wpseo_focuskw",        article.focusKeyphrase)
                    addProperty("_yoast_wpseo_metadesc",       article.metaDescription)
                    addProperty("rank_math_title",             article.title)
                    addProperty("rank_math_focus_keyword",     article.focusKeyphrase)
                    addProperty("rank_math_description",       article.metaDescription)
                    addProperty("nexuzy_meta_keywords",        article.metaKeywords)
                    addProperty("nexuzy_category",             effectiveCategory)
                })
            }

            val request = Request.Builder()
                .url("$siteUrl/wp-json/wp/v2/posts")
                .addHeader("Authorization", auth)
                .addHeader("Content-Type", "application/json")
                .post(postBody.toString().toRequestBody(jsonMedia))
                .build()

            val response = client.newCall(request).execute()
            val body     = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JsonParser.parseString(body).asJsonObject
                PublishResult(
                    success = true,
                    postId  = json.get("id")?.asLong ?: 0,
                    postUrl = json.get("link")?.asString ?: ""
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

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private fun fetchCategoriesRaw(siteUrl: String, auth: String): List<WpCategory> {
        val all  = mutableListOf<WpCategory>()
        var page = 1
        while (true) {
            try {
                val resp = client.newCall(
                    Request.Builder()
                        .url("$siteUrl/wp-json/wp/v2/categories?per_page=100&page=$page")
                        .addHeader("Authorization", auth)
                        .build()
                ).execute()
                val arr = JsonParser.parseString(resp.body?.string() ?: "[]").asJsonArray
                if (arr.size() == 0) break
                for (i in 0 until arr.size()) {
                    val obj = arr[i].asJsonObject
                    all.add(WpCategory(
                        id   = obj.get("id")?.asLong ?: 0,
                        name = obj.get("name")?.asString ?: "",
                        slug = obj.get("slug")?.asString ?: ""
                    ))
                }
                if (arr.size() < 100) break
                page++
            } catch (e: Exception) { break }
        }
        return all
    }

    private fun resolveCategoryIds(siteUrl: String, auth: String, names: List<String>): List<Long> =
        names.mapNotNull { resolveSingleCategoryId(siteUrl, auth, it).takeIf { id -> id > 0 } }

    private fun resolveSingleCategoryId(siteUrl: String, auth: String, name: String): Long {
        if (name.isBlank()) return 0
        return try {
            val enc  = java.net.URLEncoder.encode(name.trim(), "UTF-8")
            val resp = client.newCall(
                Request.Builder()
                    .url("$siteUrl/wp-json/wp/v2/categories?search=$enc&per_page=10")
                    .addHeader("Authorization", auth).build()
            ).execute()
            val arr = JsonParser.parseString(resp.body?.string() ?: "[]").asJsonArray
            for (i in 0 until arr.size()) {
                val obj = arr[i].asJsonObject
                if ((obj.get("name")?.asString ?: "").equals(name.trim(), ignoreCase = true))
                    return obj.get("id")?.asLong ?: 0
            }
            // Not found — create
            val create = client.newCall(
                Request.Builder()
                    .url("$siteUrl/wp-json/wp/v2/categories")
                    .addHeader("Authorization", auth)
                    .addHeader("Content-Type", "application/json")
                    .post(JsonObject().apply { addProperty("name", name) }.toString().toRequestBody(jsonMedia))
                    .build()
            ).execute()
            JsonParser.parseString(create.body?.string() ?: "{}").asJsonObject.get("id")?.asLong ?: 0
        } catch (e: Exception) { 0 }
    }

    private fun resolveTagIds(siteUrl: String, auth: String, tagsRaw: String): List<Long> {
        if (tagsRaw.isBlank()) return emptyList()
        return tagsRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }.mapNotNull { tag ->
            try {
                val enc  = java.net.URLEncoder.encode(tag, "UTF-8")
                val resp = client.newCall(
                    Request.Builder()
                        .url("$siteUrl/wp-json/wp/v2/tags?search=$enc&per_page=5")
                        .addHeader("Authorization", auth).build()
                ).execute()
                val arr = JsonParser.parseString(resp.body?.string() ?: "[]").asJsonArray
                if (arr.size() > 0) {
                    arr[0].asJsonObject.get("id")?.asLong
                } else {
                    val create = client.newCall(
                        Request.Builder()
                            .url("$siteUrl/wp-json/wp/v2/tags")
                            .addHeader("Authorization", auth)
                            .addHeader("Content-Type", "application/json")
                            .post(JsonObject().apply { addProperty("name", tag) }.toString().toRequestBody(jsonMedia))
                            .build()
                    ).execute()
                    JsonParser.parseString(create.body?.string() ?: "{}").asJsonObject.get("id")?.asLong
                }
            } catch (e: Exception) { null }
        }
    }

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
            val rb = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.asRequestBody(mimeType.toMediaType()))
                .addFormDataPart("title", title)
                .addFormDataPart("alt_text", title)
                .build()
            val resp = client.newCall(
                Request.Builder()
                    .url("$siteUrl/wp-json/wp/v2/media")
                    .addHeader("Authorization", auth)
                    .post(rb).build()
            ).execute()
            val body = resp.body?.string() ?: "{}"
            if (resp.isSuccessful)
                JsonParser.parseString(body).asJsonObject.get("id")?.asLong ?: 0
            else 0
        } catch (e: Exception) { 0 }
    }

    /**
     * Download an image from a URL and upload it to WordPress media library.
     * Used when local file is not available but imageUrl is set.
     */
    private fun uploadFeaturedImageFromUrl(siteUrl: String, auth: String, imageUrl: String, title: String): Long {
        return try {
            Log.d("WPClient", "Uploading image from URL: $imageUrl")
            // Download image bytes
            val dlResp  = client.newCall(Request.Builder().url(imageUrl).build()).execute()
            if (!dlResp.isSuccessful) return 0
            val bytes   = dlResp.body?.bytes() ?: return 0
            val mimeType = dlResp.header("Content-Type", "image/jpeg") ?: "image/jpeg"
            val ext = when {
                mimeType.contains("png")  -> "png"
                mimeType.contains("webp") -> "webp"
                mimeType.contains("gif")  -> "gif"
                else                      -> "jpg"
            }
            val fileName = "nexuzy_${System.currentTimeMillis()}.$ext"

            val rb = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", fileName,
                    okhttp3.RequestBody.create(mimeType.toMediaType(), bytes)
                )
                .addFormDataPart("title", title)
                .addFormDataPart("alt_text", title)
                .build()

            val resp = client.newCall(
                Request.Builder()
                    .url("$siteUrl/wp-json/wp/v2/media")
                    .addHeader("Authorization", auth)
                    .post(rb).build()
            ).execute()
            val body = resp.body?.string() ?: "{}"
            if (resp.isSuccessful) {
                val id = JsonParser.parseString(body).asJsonObject.get("id")?.asLong ?: 0
                Log.i("WPClient", "Image from URL uploaded → media ID $id")
                id
            } else {
                Log.w("WPClient", "URL image upload failed ${resp.code}")
                0
            }
        } catch (e: Exception) {
            Log.w("WPClient", "uploadFeaturedImageFromUrl error: ${e.message}")
            0
        }
    }

    suspend fun pushDraft(site: WordPressSite, article: Article, adsCode: String = ""): PublishResult =
        publishPost(site = site, article = article, status = "draft", adsCode = adsCode)

    suspend fun pushNewsDraftWithSeo(site: WordPressSite, article: Article, adsCode: String = ""): PublishResult =
        publishPost(site = site, article = article, status = "draft", adsCode = adsCode)

    suspend fun testConnection(site: WordPressSite): Boolean {
        return try {
            val resp = client.newCall(
                Request.Builder()
                    .url("${site.siteUrl.trimEnd('/')}/wp-json/wp/v2/users/me")
                    .addHeader("Authorization", buildAuthHeader(site))
                    .build()
            ).execute()
            resp.isSuccessful
        } catch (e: Exception) { false }
    }

    private fun withAdsCode(content: String, adsCode: String): String {
        if (adsCode.isBlank()) return content
        return "$content\n\n$adsCode"
    }

    private fun buildAuthHeader(site: WordPressSite): String {
        val creds = "${site.username}:${site.appPassword}"
        return "Basic " + Base64.encodeToString(creds.toByteArray(), Base64.NO_WRAP)
    }
}
