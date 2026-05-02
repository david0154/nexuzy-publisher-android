package com.nexuzy.publisher.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * OfflineGemmaClient
 *
 * Manages downloading and running the Gemma 3n E2B INT4 model locally on-device.
 *
 * MODEL: gemma-3n-E2B-it-int4.litertlm  (~2 GB)
 * SOURCE: https://huggingface.co/google/gemma-3n-E2B-it-litert-lm
 * RUNTIME: Google AI Edge LiteRT LM  (com.google.ai.edge.litert:litert-lm)
 *
 * ─── SETUP IN build.gradle ──────────────────────────────────────────────────
 * dependencies {
 *     implementation("com.google.ai.edge.litert:litert-lm:0.1.0")
 * }
 * ────────────────────────────────────────────────────────────────────────────
 *
 * USAGE:
 *   val gemma = OfflineGemmaClient(context)
 *
 *   // 1. Check / start download
 *   if (!gemma.isModelReady()) gemma.startDownload()
 *
 *   // 2. Generate text (suspends — call from coroutine)
 *   val result = gemma.generate("Write a news intro about AI")
 */
class OfflineGemmaClient(private val context: Context) {

    companion object {
        const val MODEL_FILENAME = "gemma-3n-E2B-it-int4.litertlm"
        const val MODEL_URL =
            "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm"

        // Thinking preamble prefixes — strip these from the start of any generation
        private val PREAMBLE_PREFIXES = listOf(
            "okay, let", "okay!", "alright,", "alright!", "sure,", "sure!",
            "let me ", "let's ", "i need to", "i'll ", "i will ",
            "as requested", "of course", "certainly", "absolutely",
            "here is", "here's the", "my task", "the user",
            "first, i", "first i", "step 1", "step one"
        )

        // Mid-output standalone thinking lines
        private val THINKING_LINE_REGEX = Regex(
            """(?im)^(alright[,!]|okay[,!]?|let me|let's|i'll|i need to|first[,\s]i|my task|the user wants|i'm going to|i've been asked).*$"""
        )
    }

    /** Absolute path where the model file is stored. */
    val modelPath: String
        get() = File(context.getExternalFilesDir(null), MODEL_FILENAME).absolutePath

    /** Returns true if the model file exists and is non-empty. */
    fun isModelReady(): Boolean {
        val f = File(modelPath)
        return f.exists() && f.length() > 100_000_000L // >100 MB means download completed
    }

    /**
     * Enqueues the model download via Android DownloadManager.
     * WiFi-only to avoid burning mobile data on a ~2 GB file.
     * Returns the DownloadManager enqueue ID (use to track progress).
     */
    fun startDownload(): Long {
        val request = DownloadManager.Request(Uri.parse(MODEL_URL)).apply {
            setTitle("Nexuzy Offline AI Model")
            setDescription("Downloading Gemma 3n (~2 GB). WiFi recommended.")
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            setDestinationInExternalFilesDir(context, null, MODEL_FILENAME)
            setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            setAllowedOverRoaming(false)
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(request)
        Log.i("OfflineGemma", "Download enqueued: id=$id path=$modelPath")
        return id
    }

    /**
     * Returns download progress (0–100) for a given DownloadManager ID.
     * Returns -1 if the query fails.
     */
    fun getDownloadProgress(downloadId: Long): Int {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = dm.query(query)
        return try {
            if (cursor.moveToFirst()) {
                val total = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                )
                val downloaded = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                )
                if (total > 0) ((downloaded * 100) / total).toInt() else 0
            } else -1
        } catch (e: Exception) {
            -1
        } finally {
            cursor.close()
        }
    }

    // ─── Inference ────────────────────────────────────────────────────────────

    /**
     * Runs inference on the local Gemma model.
     *
     * NOTE: This uses a reflection-based approach to avoid a hard compile-time
     * dependency on litert-lm. When you add the dependency to build.gradle,
     * replace the body with the direct LiteRT LM API call shown in the comment.
     *
     * LiteRT LM direct call (replace reflection block below once dep is added):
     * ─────────────────────────────────────────────────────────────────────────
     *   val options = LlmInference.LlmInferenceOptions.builder()
     *       .setModelPath(modelPath)
     *       .setMaxTokens(maxTokens)
     *       .setTemperature(temperature)
     *       .build()
     *   val session = LlmInference.createFromOptions(context, options)
     *   val raw = session.generateResponse(prompt)
     *   session.close()
     *   return@withContext cleanOutput(raw)
     * ─────────────────────────────────────────────────────────────────────────
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.8f
    ): GenerateResult = withContext(Dispatchers.Default) {

        if (!isModelReady()) {
            return@withContext GenerateResult(
                false, "", "Model not downloaded. Call startDownload() first."
            )
        }

        try {
            // ── Direct LiteRT LM call ──────────────────────────────────────
            // Uncomment and use once litert-lm is in build.gradle:
            //
            // val options = com.google.ai.edge.litert.lm.LlmInference
            //     .LlmInferenceOptions.builder()
            //     .setModelPath(modelPath)
            //     .setMaxTokens(maxTokens)
            //     .setTemperature(temperature)
            //     .build()
            // val session = com.google.ai.edge.litert.lm.LlmInference
            //     .createFromOptions(context, options)
            // val raw = session.generateResponse(prompt)
            // session.close()
            // GenerateResult(true, cleanOutput(raw))

            // ── Reflection-based fallback (works without compile dep) ──────
            val inferenceClass = Class.forName("com.google.ai.edge.litert.lm.LlmInference")
            val optionsClass   = Class.forName(
                "com.google.ai.edge.litert.lm.LlmInference\$LlmInferenceOptions"
            )
            val builderClass   = Class.forName(
                "com.google.ai.edge.litert.lm.LlmInference\$LlmInferenceOptions\$Builder"
            )

            val builder = builderClass.getDeclaredConstructor().newInstance()
            builderClass.getMethod("setModelPath", String::class.java)
                .invoke(builder, modelPath)
            builderClass.getMethod("setMaxTokens", Int::class.java)
                .invoke(builder, maxTokens)
            builderClass.getMethod("setTemperature", Float::class.java)
                .invoke(builder, temperature)
            val options = builderClass.getMethod("build").invoke(builder)

            val session = inferenceClass
                .getMethod("createFromOptions", Context::class.java, optionsClass)
                .invoke(null, context, options)

            val raw = inferenceClass
                .getMethod("generateResponse", String::class.java)
                .invoke(session, prompt) as? String ?: ""

            // Close session
            try { inferenceClass.getMethod("close").invoke(session) } catch (_: Exception) {}

            Log.i("OfflineGemma", "Generated ${raw.length} chars")
            GenerateResult(true, cleanOutput(raw))

        } catch (e: ClassNotFoundException) {
            Log.e("OfflineGemma", "LiteRT LM not in classpath. Add litert-lm to build.gradle.")
            GenerateResult(
                false, "",
                "LiteRT LM library missing. Add implementation(\"com.google.ai.edge.litert:litert-lm:0.1.0\") to build.gradle."
            )
        } catch (e: Exception) {
            Log.e("OfflineGemma", "Inference error: ${e.message}")
            GenerateResult(false, "", e.message ?: "Inference failed")
        }
    }

    // ─── Output cleaner ────────────────────────────────────────────────────────

    /**
     * Strips AI thinking preamble from the top and mid-output thinking lines.
     * Same logic as SarvamApiClient.cleanArticleOutput() but applied to Gemma output.
     */
    fun cleanOutput(raw: String): String {
        if (raw.isBlank()) return raw
        val lines = raw.lines().toMutableList()

        // Strip thinking preamble from the top
        var startIdx = 0
        for (i in lines.indices) {
            val lower = lines[i].trim().lowercase()
            val isJunk = lower.isEmpty() || PREAMBLE_PREFIXES.any { lower.startsWith(it) }
            if (isJunk) startIdx = i + 1 else break
        }

        // Strip mid-output standalone thinking lines (no period at end = not a real sentence)
        var result = lines.drop(startIdx)
            .filterNot { line ->
                val l = line.trim()
                l.isNotBlank() &&
                THINKING_LINE_REGEX.containsMatchIn(l.lowercase()) &&
                !l.endsWith(".")
            }
            .joinToString("\n")

        // Strip trailing tag lines
        result = result
            .replace(Regex("""(?im)^[\s]*tags?\s*:.*$"""), "")
            .replace(Regex("""(?im)^(#\w+[\s·•\-]*){2,}$"""), "")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        return result.ifBlank { raw.trim() } // Safety: never return empty if input was non-empty
    }

    // ─── Data class ────────────────────────────────────────────────────────────

    data class GenerateResult(
        val success: Boolean,
        val text: String,
        val error: String = ""
    )
}
