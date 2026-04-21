package com.nexuzy.publisher.network

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads images from RSS feed URLs to the app's local cache directory.
 * The local path is stored in Article.imagePath and used by WordPressApiClient
 * to upload the image as the post's featured media.
 */
class ImageDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Download an image from [url] and save it to the app cache.
     * Returns the absolute local file path, or empty string on failure.
     */
    fun downloadImage(url: String, titleHint: String = ""): String {
        if (url.isBlank()) return ""
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "NexuzyPublisher/2.0")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w("ImageDownloader", "HTTP ${response.code} for $url")
                return ""
            }
            val bytes = response.body?.bytes() ?: return ""
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
            file.absolutePath
        } catch (e: Exception) {
            Log.e("ImageDownloader", "Download failed for $url: ${e.message}")
            ""
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
            lower.contains(".png") -> "png"
            lower.contains(".webp") -> "webp"
            lower.contains(".gif") -> "gif"
            contentType.contains("png") -> "png"
            contentType.contains("webp") -> "webp"
            contentType.contains("gif") -> "gif"
            else -> "jpg"
        }
    }
}
