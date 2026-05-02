package com.nexuzy.publisher.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * OfflineGemmaClient — "Devil AI 2B"
 * ═══════════════════════════════════════════════════════════════════════
 * On-device LLM client using the Devil AI 2B model
 * (Gemma-2-2B-IT GGUF, ~1.5 GB, auto-downloaded from HuggingFace).
 *
 * Uses Google MediaPipe tasks-genai for inference.
 * Dependency: implementation 'com.google.mediapipe:tasks-genai:0.10.14'
 *
 * If MediaPipe is not available, gracefully returns an error — it never
 * crashes the app.
 */
class OfflineGemmaClient(private val context: Context) {

    companion object {
        private const val TAG = "DevilAI2B"
        const val MODEL_DISPLAY_NAME = "Devil AI 2B"
        const val MODEL_DESCRIPTION  = "On-device AI writer • No internet needed • Gemma 2B"
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

    fun getModelFile(): File = downloadManager.getModelFile()

    /** True only when the model binary is fully downloaded and ready. */
    fun isModelReady(): Boolean = downloadManager.isModelReady()

    /** Expose download manager so UI can call downloadModel() directly. */
    fun getDownloadManager(): ModelDownloadManager = downloadManager

    /**
     * Run inference on the Devil AI 2B model.
     * @param prompt       Full formatted prompt
     * @param maxTokens    Max output tokens
     * @param onToken      Optional streaming callback (token by token)
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int = MAX_TOKENS,
        onToken: ((String) -> Unit)? = null
    ): GenerateResult = withContext(Dispatchers.IO) {

        if (!isModelReady()) {
            return@withContext GenerateResult(
                success = false,
                error   = "${MODEL_DISPLAY_NAME} model not downloaded. " +
                          "Downloading now in background…"
            )
        }

        Log.i(TAG, "Starting inference | model=${getModelFile().name} | maxTokens=$maxTokens")

        return@withContext try {
            runMediaPipeInference(prompt, maxTokens, onToken)
        } catch (classMissing: ClassNotFoundException) {
            Log.e(TAG, "MediaPipe not in classpath: ${classMissing.message}")
            GenerateResult(
                success = false,
                error   = "MediaPipe tasks-genai not found. Add to build.gradle: " +
                          "implementation 'com.google.mediapipe:tasks-genai:0.10.14'"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}", e)
            GenerateResult(success = false, error = "Inference error: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // MediaPipe LLM Inference
    // ──────────────────────────────────────────────────────────────────────

    private fun runMediaPipeInference(
        prompt: String,
        maxTokens: Int,
        onToken: ((String) -> Unit)?
    ): GenerateResult {
        val llmClass     = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
        val optionsClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions")
        val builderClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions\$Builder")

        val builder = builderClass.newInstance()
        builderClass.getMethod("setModelPath", String::class.java)
            .invoke(builder, getModelFile().absolutePath)
        builderClass.getMethod("setMaxTokens", Int::class.java)
            .invoke(builder, maxTokens)
        builderClass.getMethod("setTemperature", Float::class.java)
            .invoke(builder, TEMPERATURE)
        builderClass.getMethod("setTopK", Int::class.java)
            .invoke(builder, TOP_K)
        val options = builderClass.getMethod("build").invoke(builder)

        val llm = llmClass
            .getMethod("createFromOptions", Context::class.java, optionsClass)
            .invoke(null, context, options)

        val sb = StringBuilder()
        try {
            val result = llmClass
                .getMethod("generateResponse", String::class.java)
                .invoke(llm, prompt) as? String ?: ""
            sb.append(result)
            onToken?.invoke(result)
        } finally {
            try { llmClass.getMethod("close").invoke(llm) } catch (_: Exception) {}
        }

        val output = sb.toString().trim()
        return if (output.isNotBlank())
            GenerateResult(success = true, text = output,
                tokensGenerated = output.split(" ").size)
        else
            GenerateResult(success = false, error = "Model returned empty output")
    }
}
