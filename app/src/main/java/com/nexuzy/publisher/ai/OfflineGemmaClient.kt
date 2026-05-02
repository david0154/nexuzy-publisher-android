package com.nexuzy.publisher.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * OfflineGemmaClient — "Devil AI 2B"
 * ══════════════════════════════════════════════════════════════════════
 * On-device LLM using Google Gemma 3n E2B (LiteRT / int4 quantised).
 *
 * Model file : gemma-3n-E2B-it-int4.litertlm  (~2 GB)
 * Source     : huggingface.co/google/gemma-3n-E2B-it-litert-lm
 * Runtime    : Google MediaPipe tasks-genai
 *
 * build.gradle dependency:
 *   implementation 'com.google.mediapipe:tasks-genai:0.10.14'
 *
 * The model is gated on HuggingFace — save your token once via:
 *   offlineGemmaClient.getDownloadManager().saveHuggingFaceToken("hf_xxx")
 */
class OfflineGemmaClient(private val context: Context) {

    companion object {
        private const val TAG = "DevilAI2B"
        const val MODEL_DISPLAY_NAME = "Devil AI 2B"
        const val MODEL_DESCRIPTION  = "On-device AI writer • Gemma 3n E2B • No internet needed"
        private const val MAX_TOKENS  = 1200
        private const val TEMPERATURE = 0.72f
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
     * Run inference with Devil AI 2B on-device.
     * Must be called from a coroutine (IO dispatcher).
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int = MAX_TOKENS,
        onToken: ((String) -> Unit)? = null
    ): GenerateResult = withContext(Dispatchers.IO) {

        if (!isModelReady()) {
            return@withContext GenerateResult(
                success = false,
                error   = "Devil AI 2B model not downloaded yet."
            )
        }

        Log.i(TAG, "Inference start | file=${getModelFile().name} | maxTokens=$maxTokens")

        return@withContext try {
            runLiteRtInference(prompt, maxTokens, onToken)
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "MediaPipe not in classpath: ${e.message}")
            GenerateResult(
                success = false,
                error   = "Add to build.gradle: implementation 'com.google.mediapipe:tasks-genai:0.10.14'"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}", e)
            GenerateResult(success = false, error = "Inference error: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // MediaPipe LiteRT inference
    // ──────────────────────────────────────────────────────────────────

    private fun runLiteRtInference(
        prompt: String,
        maxTokens: Int,
        onToken: ((String) -> Unit)?
    ): GenerateResult {

        /*
         * Dynamic reflection so the app compiles even before MediaPipe
         * dependency is added. Once added, this resolves at runtime.
         *
         * API: com.google.mediapipe.tasks.genai.llminference.LlmInference
         */
        val llmCls  = Class.forName(
            "com.google.mediapipe.tasks.genai.llminference.LlmInference")
        val optsCls = Class.forName(
            "com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions")
        val bldrCls = Class.forName(
            "com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions\$Builder")

        // Build options
        val bldr = bldrCls.newInstance()
        bldrCls.getMethod("setModelPath",   String::class.java).invoke(bldr, getModelFile().absolutePath)
        bldrCls.getMethod("setMaxTokens",   Int::class.java   ).invoke(bldr, maxTokens)
        bldrCls.getMethod("setTemperature", Float::class.java ).invoke(bldr, TEMPERATURE)
        bldrCls.getMethod("setTopK",        Int::class.java   ).invoke(bldr, TOP_K)
        val opts = bldrCls.getMethod("build").invoke(bldr)

        // Create engine
        val llm = llmCls
            .getMethod("createFromOptions", Context::class.java, optsCls)
            .invoke(null, context, opts)

        val sb = StringBuilder()
        try {
            val out = llmCls
                .getMethod("generateResponse", String::class.java)
                .invoke(llm, prompt) as? String ?: ""
            sb.append(out)
            onToken?.invoke(out)
        } finally {
            try { llmCls.getMethod("close").invoke(llm) } catch (_: Exception) {}
        }

        val output = sb.toString().trim()
        return if (output.isNotBlank())
            GenerateResult(
                success         = true,
                text            = output,
                tokensGenerated = output.split(" ").size
            )
        else
            GenerateResult(success = false, error = "Model returned empty output")
    }
}
