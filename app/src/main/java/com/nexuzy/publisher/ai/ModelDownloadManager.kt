package com.nexuzy.publisher.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ModelDownloadManager
 * ═══════════════════════════════════════════════════════════════
 * Auto-downloads Devil AI 2B (Gemma-2-2B-IT GGUF) from HuggingFace
 * into the app's private external storage so no user interaction
 * is needed beyond confirming the download once.
 *
 * Model source (public, free, no login required):
 *   https://huggingface.co/lmstudio-community/gemma-2-2b-it-GGUF
 *   File: gemma-2-2b-it-Q4_K_M.gguf  (~1.5 GB)
 *
 * Saved to:
 *   /sdcard/Android/data/<pkg>/files/models/devil_ai_2b.gguf
 */
class ModelDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloadManager"

        // HuggingFace direct download URL (no auth required for public models)
        const val MODEL_URL =
            "https://huggingface.co/lmstudio-community/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf"

        const val MODEL_FILENAME = "devil_ai_2b.gguf"
        const val EXPECTED_MIN_SIZE_BYTES = 1_000_000_000L  // 1 GB sanity check
    }

    data class DownloadProgress(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val percent: Int,
        val isDone: Boolean = false,
        val error: String = ""
    )

    fun getModelFile(): File =
        File(context.getExternalFilesDir("models"), MODEL_FILENAME)

    fun isModelReady(): Boolean {
        val f = getModelFile()
        return f.exists() && f.length() >= EXPECTED_MIN_SIZE_BYTES
    }

    /** Returns human-readable size string for UI display. */
    fun getModelSizeOnDisk(): String {
        val bytes = getModelFile().length()
        return when {
            bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576L    -> "%.0f MB".format(bytes / 1_048_576.0)
            else                   -> "$bytes B"
        }
    }

    /**
     * Download the model file from HuggingFace.
     * Resumes partial downloads automatically.
     * Call from a coroutine; progress is streamed via [onProgress].
     */
    suspend fun downloadModel(
        onProgress: (DownloadProgress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {

        val destFile = getModelFile()
        destFile.parentFile?.mkdirs()

        val existingBytes = if (destFile.exists()) destFile.length() else 0L
        Log.i(TAG, "Starting download. Already have $existingBytes bytes.")

        return@withContext try {
            val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout    = 60_000
                setRequestProperty("User-Agent", "NexuzyPublisher/1.0")
                if (existingBytes > 0) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
                instanceFollowRedirects = true
                connect()
            }

            val responseCode = conn.responseCode
            val isResume = responseCode == 206
            val totalBytes = when {
                isResume -> existingBytes + (conn.getHeaderFieldLong("Content-Length", -1))
                else     -> conn.getHeaderFieldLong("Content-Length", -1)
            }

            Log.i(TAG, "HTTP $responseCode | total=$totalBytes | resume=$isResume")

            if (responseCode !in 200..206) {
                onProgress(DownloadProgress(0, 0, 0, isDone = false,
                    error = "HTTP $responseCode from server"))
                return@withContext false
            }

            conn.inputStream.use { input ->
                FileOutputStream(destFile, isResume).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var downloaded = existingBytes
                    var read: Int
                    var lastReportedPercent = -1

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read

                        val pct = if (totalBytes > 0)
                            ((downloaded * 100) / totalBytes).toInt() else 0

                        if (pct != lastReportedPercent) {
                            lastReportedPercent = pct
                            onProgress(DownloadProgress(
                                bytesDownloaded = downloaded,
                                totalBytes      = totalBytes,
                                percent         = pct
                            ))
                        }
                    }
                }
            }

            val finalSize = destFile.length()
            Log.i(TAG, "Download complete. File size: $finalSize bytes")
            onProgress(DownloadProgress(
                bytesDownloaded = finalSize,
                totalBytes      = finalSize,
                percent         = 100,
                isDone          = true
            ))
            true

        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            onProgress(DownloadProgress(0, 0, 0, isDone = false, error = e.message ?: "Unknown error"))
            false
        }
    }

    /** Delete the model file (free up space). */
    fun deleteModel(): Boolean {
        val f = getModelFile()
        return if (f.exists()) f.delete() else true
    }
}
