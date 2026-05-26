package com.nexuzy.publisher.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.lm.LlmInference
import com.google.ai.edge.litert.lm.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * OfflineGemmaClient — "Devil AI 2B"
 * ══════════════════════════════════════════════════════════════════════
 * On-device LLM using Google Gemma 2B IT (LiteRT INT4, GPU-optimised).
 *
 * Model file : gemma-2b-it-gpu-int4.bin (~500 MB)
 * Source     : Google public CDN — no token, no login, auto-downloads on first launch
 * Runtime    : Google LiteRT-LM  (com.google.ai.edge.litert:litert-lm:1.0.0)
 *
 * build.gradle (app):
 *   implementation 'com.google.ai.edge.litert:litert-lm:1.0.0'
 * settings.gradle:
 *   maven { url = uri("https://maven.google.com") }
 *
 * Model downloads automatically via ModelDownloadManager.autoDownloadIfNeeded().
 * No HuggingFace account or token required.
 */
class OfflineGemmaClient(private val context: Context) {

    companion object {
        private const val TAG = "DevilAI2B"
        const val MODEL_DISPLAY_NAME = "Devil AI 2B"
        const val MODEL_DESCRIPTION  = "On-device AI writer • Gemma 2B IT • Auto-downloads • No internet needed at runtime"
        private const val MAX_TOKENS  = 1024
        private const val TEMPERATURE = 0.7f
        private const val TOP_K       = 40
    }

    private val downloadManager = ModelDownloadManager(context)

    data class GenerateResult(
        val success: Boolean,
        val text: String = "",
        val error: String = "",
        val tokensGenerated: Int = 0
    )

    fun getModelFile(): File    = downloadManager.getModelFile()
    fun isModelReady(): Boolean = downloadManager.isModelReady()
    fun getDownloadManager(): ModelDownloadManager = downloadManager

    /**
     * Run inference with Devil AI 2B on-device using LiteRT-LM.
     * Supports both streaming (onToken) and synchronous modes.
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int = MAX_TOKENS,
        onToken: ((String) -> Unit)? = null
    ): GenerateResult = withContext(Dispatchers.IO) {

        if (!isModelReady()) {
            return@withContext GenerateResult(
                success = false,
                error   = "Devil AI 2B model not downloaded yet. It will be ready shortly."
            )
        }

        val modelPath = getModelFile().absolutePath
        Log.i(TAG, "Inference start | ${getModelFile().name} | maxTokens=$maxTokens")

        return@withContext try {
            val options = LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(maxTokens)
                .setTemperature(TEMPERATURE)
                .setTopK(TOP_K)
                .build()

            val llm = LlmInference.createFromOptions(context, options)

            try {
                if (onToken != null) runStreaming(llm, prompt, onToken)
                else                runSync(llm, prompt)
            } finally {
                try { llm.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}", e)
            GenerateResult(success = false, error = "Inference error: ${e.message}")
        }
    }

    private fun runSync(llm: LlmInference, prompt: String): GenerateResult {
        val output = llm.generateResponse(prompt)
        return if (output.isNullOrBlank())
            GenerateResult(success = false, error = "Model returned empty output")
        else
            GenerateResult(
                success         = true,
                text            = output.trim(),
                tokensGenerated = output.trim().split(" ").size
            )
    }

    private suspend fun runStreaming(
        llm: LlmInference,
        prompt: String,
        onToken: (String) -> Unit
    ): GenerateResult = suspendCancellableCoroutine { cont ->
        val sb = StringBuilder()
        llm.generateResponseAsync(prompt) { partialResult, done ->
            val token = partialResult ?: ""
            if (token.isNotBlank()) { sb.append(token); onToken(token) }
            if (done && cont.isActive) {
                val output = sb.toString().trim()
                cont.resume(
                    if (output.isNotBlank())
                        GenerateResult(success = true, text = output, tokensGenerated = output.split(" ").size)
                    else
                        GenerateResult(success = false, error = "Model returned empty output")
                )
            }
        }
    }
}
