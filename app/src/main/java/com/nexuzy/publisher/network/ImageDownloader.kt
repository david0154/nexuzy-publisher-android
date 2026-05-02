package com.nexuzy.publisher.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads images from RSS feed URLs to the app's local cache directory.
 * The local path is stored in Article.imagePath and used by WordPressApiClient
 * to upload the image as the post's featured media.
 *
 * WATERMARK DETECTION: After downloading, the image is checked for watermarks
 * (text overlays, semi-transparent bands, repeated pixel patterns in corners).
 * If a watermark is detected, falls back to Wikipedia/Wikimedia Commons for a
 * clean, freely-licensed replacement image. No API key required.
 */
class ImageDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Download an image from [url] and save it to the app cache.
     * If watermark is detected, searches Wikipedia/Wikimedia for a clean replacement.
     * Returns the absolute local file path, or empty string on failure.
     */
    fun downloadImage(
        url: String,
        titleHint: String = "",
        searchQuery: String = titleHint,
        apiKeyManager: ApiKeyManager? = null
    ): String {
        if (url.isBlank()) return ""

        // Step 1: Download the primary image
        val (primaryPath, primaryBytes) = downloadToBytes(url, titleHint)
        if (primaryPath.isBlank() || primaryBytes == null) return ""

        // Step 2: Check for watermark
        val hasWatermark = detectWatermark(primaryBytes)
        if (!hasWatermark) {
            Log.d("ImageDownloader", "No watermark detected — using RSS image")
            return primaryPath
        }

        Log.w("ImageDownloader", "Watermark detected in RSS image — searching Wikipedia for clean replacement")

        // Step 3: Watermark found — search Wikipedia/Wikimedia for a clean image (no API key needed)
        if (searchQuery.isNotBlank()) {
            val cleanImageUrl = searchCleanImage(searchQuery, apiKeyManager)
            if (cleanImageUrl.isNotBlank()) {
                val (cleanPath, _) = downloadToBytes(cleanImageUrl, titleHint)
                if (cleanPath.isNotBlank()) {
                    // Delete the watermarked file
                    File(primaryPath).delete()
                    Log.i("ImageDownloader", "Replaced watermarked image with Wikipedia clean result")
                    return cleanPath
                }
            }
        }

        // Step 4: Could not find clean image — still return original (better than nothing)
        Log.w("ImageDownloader", "Could not find watermark-free replacement — using original")
        return primaryPath
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Watermark Detection
    // ──────────────────────────────────────────────────────────────────────────

    private fun detectWatermark(imageBytes: ByteArray): Boolean {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: return false

            val w = bitmap.width
            val h = bitmap.height
            if (w < 10 || h < 10) return false

            val cornerSize = (minOf(w, h) * 0.18).toInt().coerceAtLeast(20)

            val corners = listOf(
                Pair(0, 0),
                Pair(w - cornerSize, 0),
                Pair(0, h - cornerSize),
                Pair(w - cornerSize, h - cornerSize)
            )

            var suspiciousCorners = 0
            for ((startX, startY) in corners) {
                if (isWatermarkRegion(bitmap, startX, startY, cornerSize, cornerSize)) {
                    suspiciousCorners++
                }
            }

            val stripHeight = (h * 0.08).toInt().coerceAtLeast(12)
            val bottomStripSuspicious = isWatermarkStrip(bitmap, 0, h - stripHeight, w, stripHeight)

            bitmap.recycle()

            suspiciousCorners >= 1 || bottomStripSuspicious
        } catch (e: Exception) {
            Log.w("ImageDownloader", "Watermark detection error: ${e.message}")
            false
        }
    }

    private fun isWatermarkRegion(bitmap: Bitmap, x: Int, y: Int, w: Int, h: Int): Boolean {
        val endX = (x + w).coerceAtMost(bitmap.width)
        val endY = (y + h).coerceAtMost(bitmap.height)
        var suspiciousPixels = 0
        var totalPixels = 0

        val stepX = maxOf(1, (endX - x) / 20)
        val stepY = maxOf(1, (endY - y) / 20)

        for (px in x until endX step stepX) {
            for (py in y until endY step stepY) {
                val pixel = bitmap.getPixel(px, py)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val brightness = (r + g + b) / 3
                val isNearWhite = brightness > 220
                val isNearGray = (maxOf(r, g, b) - minOf(r, g, b)) < 25 && brightness in 140..225
                if (isNearWhite || isNearGray) suspiciousPixels++
                totalPixels++
            }
        }

        if (totalPixels == 0) return false
        return (suspiciousPixels.toFloat() / totalPixels) > 0.55f
    }

    private fun isWatermarkStrip(bitmap: Bitmap, x: Int, y: Int, w: Int, h: Int): Boolean {
        val endX = (x + w).coerceAtMost(bitmap.width)
        val endY = (y + h).coerceAtMost(bitmap.height)
        if (endX <= x || endY <= y) return false

        var suspiciousPixels = 0
        var totalPixels = 0
        val stepX = maxOf(1, (endX - x) / 40)
        val stepY = maxOf(1, (endY - y) / 6)

        for (px in x until endX step stepX) {
            for (py in y until endY step stepY) {
                val pixel = bitmap.getPixel(px, py)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val brightness = (r + g + b) / 3
                if (brightness > 200 || brightness < 55) suspiciousPixels++
                totalPixels++
            }
        }

        if (totalPixels == 0) return false
        return (suspiciousPixels.toFloat() / totalPixels) > 0.70f
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Free Image Search Fallback
    //
    // Strategy (no API key required, completely free):
    //   1. Wikipedia PageImages API  — best quality, CC-licensed images
    //   2. Wikimedia Commons search  — broader search across all media
    //
    // Replaces the old Google Custom Search JSON API which required a paid key.
    // ──────────────────────────────────────────────────────────────────────────

    private fun searchCleanImage(query: String, apiKeyManager: ApiKeyManager?): String {
        // ── Strategy 1: Wikipedia PageImages API ──────────────────────────────
        try {
            val encoded = java.net.URLEncoder.encode(query.take(80), "UTF-8")
            val wikiUrl = "https://en.wikipedia.org/w/api.php" +
                "?action=query" +
                "&prop=pageimages" +
                "&format=json" +
                "&piprop=original" +
                "&titles=$encoded" +
                "&redirects=1"

            val request = Request.Builder()
                .url(wikiUrl)
                .addHeader("User-Agent", "NexuzyPublisher/2.0 (Android; nexuzy.com)")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val parsed = com.google.gson.JsonParser.parseString(body).asJsonObject
                val pages = parsed.getAsJsonObject("query")
                    ?.getAsJsonObject("pages")
                if (pages != null) {
                    for ((_, page) in pages.entrySet()) {
                        val imgUrl = page.asJsonObject
                            .getAsJsonObject("original")
                            ?.get("source")?.asString
                        if (!imgUrl.isNullOrBlank() && !isKnownWatermarkedDomain(imgUrl.lowercase())) {
                            Log.i("ImageDownloader", "Wikipedia PageImages fallback: $imgUrl")
                            return imgUrl
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("ImageDownloader", "Wikipedia PageImages search failed: ${e.message}")
        }

        // ── Strategy 2: Wikimedia Commons search ─────────────────────────────
        try {
            val encoded = java.net.URLEncoder.encode(query.take(80), "UTF-8")
            val commonsUrl = "https://commons.wikimedia.org/w/api.php" +
                "?action=query" +
                "&generator=search" +
                "&gsrnamespace=6" +
                "&gsrsearch=$encoded" +
                "&gsrlimit=5" +
                "&prop=imageinfo" +
                "&iiprop=url" +
                "&format=json"

            val request = Request.Builder()
                .url(commonsUrl)
                .addHeader("User-Agent", "NexuzyPublisher/2.0 (Android; nexuzy.com)")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val parsed = com.google.gson.JsonParser.parseString(body).asJsonObject
                val pages = parsed.getAsJsonObject("query")
                    ?.getAsJsonObject("pages")
                if (pages != null) {
                    for ((_, page) in pages.entrySet()) {
                        val imageInfoArray = page.asJsonObject
                            .getAsJsonArray("imageinfo") ?: continue
                        if (imageInfoArray.size() > 0) {
                            val imgUrl = imageInfoArray[0].asJsonObject
                                .get("url")?.asString
                            if (!imgUrl.isNullOrBlank() && !isKnownWatermarkedDomain(imgUrl.lowercase())) {
                                Log.i("ImageDownloader", "Wikimedia Commons fallback: $imgUrl")
                                return imgUrl
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("ImageDownloader", "Wikimedia Commons search failed: ${e.message}")
        }

        Log.w("ImageDownloader", "All free image search strategies exhausted for: $query")
        return ""
    }

    /** Domains known to always watermark their images — skip them in search results. */
    private fun isKnownWatermarkedDomain(url: String): Boolean {
        val blockedDomains = listOf(
            "gettyimages.com", "istockphoto.com", "shutterstock.com",
            "alamy.com", "depositphotos.com", "dreamstime.com",
            "123rf.com", "stock.adobe.com", "vectorstock.com"
        )
        return blockedDomains.any { url.contains(it) }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Core download helper — returns (localFilePath, rawBytes)
    // ──────────────────────────────────────────────────────────────────────────

    private fun downloadToBytes(url: String, titleHint: String): Pair<String, ByteArray?> {
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "NexuzyPublisher/2.0")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w("ImageDownloader", "HTTP ${response.code} for $url")
                return Pair("", null)
            }
            val bytes = response.body?.bytes() ?: return Pair("", null)
            val ext = guessExtension(url, response.header("Content-Type") ?: "")
            val safeTitle = titleHint
                .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                .take(40)
                .ifBlank { "article" }
            val fileName = "${safeTitle}_${System.currentTimeMillis()}.$ext"
            val cacheDir = File(context.cacheDir, "nexuzy_images").also { it.mkdirs() }
            val file = File(cacheDir, fileName)
            file.writeBytes(bytes)
            Log.d("ImageDownloader", "Saved ${bytes.size} bytes to ${file.absolutePath}")
            Pair(file.absolutePath, bytes)
        } catch (e: Exception) {
            Log.e("ImageDownloader", "Download failed for $url: ${e.message}")
            Pair("", null)
        }
    }

    /**
     * Delete all cached images older than 7 days to free up space.
     */
    fun clearOldCache() {
        try {
            val cacheDir = File(context.cacheDir, "nexuzy_images")
            if (!cacheDir.exists()) return
            val cutoff = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
            cacheDir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) file.delete()
            }
        } catch (e: Exception) {
            Log.w("ImageDownloader", "Cache clear failed: ${e.message}")
        }
    }

    private fun guessExtension(url: String, contentType: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".jpg") || lower.contains(".jpeg") -> "jpg"
            lower.contains(".png")  -> "png"
            lower.contains(".webp") -> "webp"
            lower.contains(".gif")  -> "gif"
            contentType.contains("png")  -> "png"
            contentType.contains("webp") -> "webp"
            contentType.contains("gif")  -> "gif"
            else -> "jpg"
        }
    }
}
