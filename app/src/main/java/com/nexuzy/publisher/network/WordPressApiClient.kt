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

    // ─── Publish post ─────────────────────────────────────────────────────────

    /**
     * Full publish flow:
     * 1. Validate / create article category on WordPress.
     * 2. Resolve / create tag IDs.
     * 3. Upload featured image (local file → URL → skip).
     * 4. Build final article content:
     *    - Article body split in two halves
     *    - Article-related image injected as Gutenberg wp:image block at the midpoint
     *    - Ads code appended as wp:html block
     *    - Tags section appended at the bottom with #tag chips
     *    - SEO focus keyphrase & meta description stored as HTML comments
     * 5. Publish draft with full Yoast + Rank Math SEO meta.
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
                val chain = NewsCategory.getCategoryChain(effectiveCategory)
                categoryIds = resolveCategoryIds(siteUrl, auth, chain)
            }

            // Step 2 — Resolve / create tag IDs
            val tagIds = resolveTagIds(siteUrl, auth, article.tags)

            // Step 3 — Upload featured image
            val featuredMediaId = when {
                article.imagePath.isNotBlank() && File(article.imagePath).exists() ->
                    uploadFeaturedImage(siteUrl, auth, article.imagePath, article.title)
                article.imageUrl.isNotBlank() ->
                    uploadFeaturedImageFromUrl(siteUrl, auth, article.imageUrl, article.title)
                else -> 0L
            }

            // Step 4 — Build full article content with image in mid + tags + ads + SEO
            val finalContent = buildArticleContent(
                content          = article.content,
                adsCode          = adsCode,
                imageUrl         = article.imageUrl,
                title            = article.title,
                tags             = article.tags,
                focusKeyphrase   = article.focusKeyphrase,
                metaDescription  = article.metaDescription
            )

            // Step 5 — Build and send post
            val postBody = JsonObject().apply {
                addProperty("title",   article.title)
                addProperty("content", finalContent)
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

    // ─── Build article content ────────────────────────────────────────────────

    /**
     * Builds the final WordPress post HTML content:
     *
     * Structure:
     *   [First half of article paragraphs]
     *   [Article-related image as Gutenberg wp:image block — center aligned]
     *   [Second half of article paragraphs]
     *   [Ads code as wp:html block]
     *   [Tags section with #tag chips]
     *   [SEO meta comments (hidden, used by Yoast/RankMath)]
     *
     * Supports both HTML (<p>...</p>) and plain text (double-newline separated)
     * input content from Gemini.
     */
    private fun buildArticleContent(
        content: String,
        adsCode: String,
        imageUrl: String,
        title: String,
        tags: String,
        focusKeyphrase: String,
        metaDescription: String
    ): String {
        val sb = StringBuilder()

        // ── Split content into paragraph blocks ──
        val paragraphs: List<String> = if (content.contains("</p>", ignoreCase = true)) {
            // HTML content from Gemini: split on closing </p> tag
            content.split(Regex("(?<=</p>)", RegexOption.IGNORE_CASE))
                .map { it.trim() }
                .filter { it.isNotBlank() }
        } else {
            // Plain text: split on double newlines and wrap in <p>
            content.split(Regex("\\n\\n+"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { para ->
                    if (para.startsWith("<")) para
                    else "<!-- wp:paragraph -->\n<p>$para</p>\n<!-- /wp:paragraph -->"
                }
        }

        if (paragraphs.isEmpty()) {
            // Fallback: use raw content if parsing yields nothing
            sb.append(content)
        } else {
            val midPoint = (paragraphs.size / 2).coerceAtLeast(1)

            // ── First half of article body ──
            paragraphs.take(midPoint).forEach { p ->
                sb.append(p).append("\n\n")
            }

            // ── Article-related image injected in the middle ──
            if (imageUrl.isNotBlank()) {
                sb.append("""
<!-- wp:image {"align":"center","sizeSlug":"large","linkDestination":"none","className":"nexuzy-article-mid-image"} -->
<figure class="wp-block-image aligncenter size-large nexuzy-article-mid-image">
<img src="$imageUrl" alt="$title" loading="lazy" decoding="async" class="nexuzy-mid-img"/>
<figcaption class="wp-element-caption">$title</figcaption>
</figure>
<!-- /wp:image -->
""".trimIndent())
                sb.append("\n\n")
            }

            // ── Second half of article body ──
            paragraphs.drop(midPoint).forEach { p ->
                sb.append(p).append("\n\n")
            }
        }

        // ── Ads code (after article body, before tags) ──
        if (adsCode.isNotBlank()) {
            sb.append("\n<!-- wp:html -->\n")
            sb.append(adsCode)
            sb.append("\n<!-- /wp:html -->\n\n")
        }

        // ── Tags section at the bottom of the article ──
        if (tags.isNotBlank()) {
            val tagList = tags.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (tagList.isNotEmpty()) {
                val tagChips = tagList.joinToString(" &nbsp;·&nbsp; ") { tag ->
                    "<span class=\"nexuzy-tag-chip\">#$tag</span>"
                }
                sb.append("""\n<!-- wp:paragraph {"className":"nexuzy-article-tags"} -->\n""")
                sb.append("""<p class="nexuzy-article-tags" style="margin-top:2rem;padding-top:1rem;border-top:1px solid #e5e7eb;font-size:0.88em;color:#6b7280;">""")
                sb.append("<strong>\uD83C\uDFF7\uFE0F Tags:</strong> $tagChips")
                sb.append("</p>\n<!-- /wp:paragraph -->\n\n")
            }
        }

        // ── Hidden SEO meta comments (used by Yoast / Rank Math via post content parsing) ──
        if (metaDescription.isNotBlank()) {
            sb.append("\n<!-- nexuzy:meta-desc:${metaDescription.take(160).replace("--", "-")} -->")
        }
        if (focusKeyphrase.isNotBlank()) {
            sb.append("\n<!-- nexuzy:focus-kw:${focusKeyphrase.trim().replace("--", "-")} -->")
        }

        return sb.toString().trim()
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

    private fun buildAuthHeader(site: WordPressSite): String {
        val creds = "${site.username}:${site.appPassword}"
        return "Basic " + Base64.encodeToString(creds.toByteArray(), Base64.NO_WRAP)
    }
}
