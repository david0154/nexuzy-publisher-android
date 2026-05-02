package com.nexuzy.publisher.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * OfflineGemmaClient — "Devil AI 2B"
 * ══════════════════════════════════════════════════════════════════════
 * On-device LLM using Google Gemma 3n E2B (LiteRT int4).
 *
 * Model file : gemma-3n-E2B-it-int4.task  (or .litertlm)
 * Source     : huggingface.co/google/gemma-3n-E2B-it-litert-lm
 * Runtime    : MediaPipe Tasks GenAI (com.google.mediapipe:tasks-genai:0.10.14)
 *
 * build.gradle (app):
 *   dependencies {
 *     implementation 'com.google.mediapipe:tasks-genai:0.10.14'
 *   }
 *
 * HuggingFace token required (gated model). Save once via:
 *   getDownloadManager().saveHuggingFaceToken("hf_xxx")
 */
class OfflineGemmaClient(private val context: Context) {

    companion object {
        private const val TAG = "DevilAI2B"
        const val MODEL_DISPLAY_NAME = "Devil AI 2B"
        const val MODEL_DESCRIPTION  = "On-device AI writer • Gemma 3n E2B • No internet needed"
        private const val MAX_TOKENS  = 1024
        private const val TEMPERATURE = 0.7f
        private const val TOP_K       = 40
        private const val TOP_P       = 0.95f

        // MediaPipe Tasks GenAI class names (com.google.mediapipe:tasks-genai:0.10.14)
        private const val LLM_INFERENCE_CLASS   = "com.google.mediapipe.tasks.genai.llminference.LlmInference"
        private const val LLM_OPTIONS_CLASS     = "com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions"
        private const val LLM_OPTIONS_BUILDER   = "com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions\$Builder"
        private const val RESULT_LISTENER_CLASS = "com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceResultListener"
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
     * Uses MediaPipe Tasks GenAI API via reflection so the app
     * compiles cleanly even before the dependency resolves.
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

        Log.i(TAG, "Inference start | ${getModelFile().name} | maxTokens=$maxTokens")

        return@withContext try {
            runMediaPipeInference(prompt, maxTokens, onToken)
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "MediaPipe Tasks GenAI not in classpath: ${e.message}")
            GenerateResult(
                success = false,
                error   = "MediaPipe Tasks GenAI not found. Add to build.gradle:\n" +
                          "implementation 'com.google.mediapipe:tasks-genai:0.10.14'"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}", e)
            GenerateResult(success = false, error = "Inference error: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // MediaPipe Tasks GenAI inference (via reflection)
    //
    // Java API:
    //   LlmInference.LlmInferenceOptions (Builder pattern)
    //     .setModelPath(String)
    //     .setMaxTokens(Int)
    //     .build()
    //
    //   LlmInference.createFromOptions(context, options) : LlmInference
    //     .generateResponse(prompt)                       : String
    //     .generateResponseAsync(prompt, listener)
    //     .close()
    // ──────────────────────────────────────────────────────────────────

    private fun runMediaPipeInference(
        prompt: String,
        maxTokens: Int,
        onToken: ((String) -> Unit)?
    ): GenerateResult {

        // --- Resolve classes ---
        val llmCls  = Class.forName(LLM_INFERENCE_CLASS)
        val optsCls = Class.forName(LLM_OPTIONS_CLASS)
        val bldrCls = Class.forName(LLM_OPTIONS_BUILDER)

        // --- Build options via Builder ---
        val bldr = bldrCls
            .getConstructor()
            .newInstance()
        bldrCls.getMethod("setModelPath", String::class.java)
            .invoke(bldr, getModelFile().absolutePath)
        bldrCls.getMethod("setMaxTokens", Int::class.java)
            .invoke(bldr, maxTokens)
        // temperature, topK, topP — set if methods exist (graceful for older versions)
        runCatching {
            bldrCls.getMethod("setTemperature", Float::class.java).invoke(bldr, TEMPERATURE)
        }
        runCatching {
            bldrCls.getMethod("setTopK", Int::class.java).invoke(bldr, TOP_K)
        }
        runCatching {
            bldrCls.getMethod("setTopP", Float::class.java).invoke(bldr, TOP_P)
        }
        val opts = bldrCls.getMethod("build").invoke(bldr)

        // --- Create inference engine ---
        val llm = llmCls
            .getMethod("createFromOptions", Context::class.java, optsCls)
            .invoke(null, context, opts)

        val sb = StringBuilder()
        try {
            if (onToken != null) {
                // Streaming via LlmInferenceResultListener
                try {
                    val listenerCls = Class.forName(RESULT_LISTENER_CLASS)
                    val proxy = java.lang.reflect.Proxy.newProxyInstance(
                        listenerCls.classLoader,
                        arrayOf(listenerCls)
                    ) { _, method, args ->
                        when (method.name) {
                            "onPartialResult",
                            "onResult" -> {
                                val token = args?.getOrNull(0)?.toString() ?: ""
                                if (token.isNotBlank()) {
                                    sb.append(token)
                                    onToken(token)
                                }
                            }
                            "onError" -> Log.e(TAG, "Streaming error: ${args?.getOrNull(0)}")
                        }
                        null
                    }
                    llmCls.getMethod("generateResponseAsync", String::class.java, listenerCls)
                        .invoke(llm, prompt, proxy)
                } catch (_: ClassNotFoundException) {
                    // Fallback: synchronous if listener class not found
                    val out = llmCls.getMethod("generateResponse", String::class.java)
                        .invoke(llm, prompt) as? String ?: ""
                    sb.append(out)
                    onToken(out)
                }
            } else {
                // Synchronous call
                val out = llmCls
                    .getMethod("generateResponse", String::class.java)
                    .invoke(llm, prompt) as? String ?: ""
                sb.append(out)
            }
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
