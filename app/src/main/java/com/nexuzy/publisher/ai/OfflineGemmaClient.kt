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
 * Model file : gemma-3n-E2B-it-int4.litertlm
 * Source     : huggingface.co/google/gemma-3n-E2B-it-litert-lm
 * Runtime    : LiteRT-LM (google-ai-edge)
 *
 * build.gradle (app):
 *   repositories { maven { url 'https://jitpack.io' } }
 *   dependencies {
 *     implementation 'com.github.google-ai-edge:LiteRT-LM:latest'
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
     * Uses LiteRT-LM (google-ai-edge) API via reflection so the app
     * compiles cleanly even before the dependency is resolved.
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
            runLiteRtLmInference(prompt, maxTokens, onToken)
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "LiteRT-LM not in classpath: ${e.message}")
            GenerateResult(
                success = false,
                error   = "LiteRT-LM not found. Add to build.gradle:\n" +
                          "implementation 'com.github.google-ai-edge:LiteRT-LM:latest'"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}", e)
            GenerateResult(success = false, error = "Inference error: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // LiteRT-LM inference (google-ai-edge)
    //
    // Java API (reflection-safe):
    //   com.google.ai.edge.litert.lm.LlmInference
    //     .createFromPath(context, modelPath, options)
    //     .generateResponse(prompt)  : String
    //     .generateResponseAsync(prompt, listener)
    //     .close()
    //
    //   com.google.ai.edge.litert.lm.LlmInferenceOptions (Builder pattern)
    //     .maxTokens(Int)
    //     .temperature(Float)
    //     .topK(Int)
    //     .topP(Float)
    //     .build()
    // ──────────────────────────────────────────────────────────────────

    private fun runLiteRtLmInference(
        prompt: String,
        maxTokens: Int,
        onToken: ((String) -> Unit)?
    ): GenerateResult {

        // --- Resolve classes ---
        val llmCls  = Class.forName("com.google.ai.edge.litert.lm.LlmInference")
        val optsCls = Class.forName("com.google.ai.edge.litert.lm.LlmInferenceOptions")
        val bldrCls = Class.forName("com.google.ai.edge.litert.lm.LlmInferenceOptions\$Builder")

        // --- Build options ---
        val bldr = bldrCls.newInstance()
        bldrCls.getMethod("maxTokens",   Int::class.java  ).invoke(bldr, maxTokens)
        bldrCls.getMethod("temperature", Float::class.java).invoke(bldr, TEMPERATURE)
        bldrCls.getMethod("topK",        Int::class.java  ).invoke(bldr, TOP_K)
        bldrCls.getMethod("topP",        Float::class.java).invoke(bldr, TOP_P)
        val opts = bldrCls.getMethod("build").invoke(bldr)

        // --- Create inference engine ---
        val llm = llmCls
            .getMethod("createFromPath",
                Context::class.java,
                String::class.java,
                optsCls)
            .invoke(null, context, getModelFile().absolutePath, opts)

        val sb = StringBuilder()
        try {
            if (onToken != null) {
                // Streaming via LlmInferenceListener interface
                val listenerCls = Class.forName(
                    "com.google.ai.edge.litert.lm.LlmInference\$LlmInferenceListener")
                val proxy = java.lang.reflect.Proxy.newProxyInstance(
                    listenerCls.classLoader,
                    arrayOf(listenerCls)
                ) { _, method, args ->
                    when (method.name) {
                        "onPartialResult" -> {
                            val token = args?.getOrNull(0)?.toString() ?: ""
                            sb.append(token)
                            onToken(token)
                        }
                        "onResult" -> { /* final callback, ignore — already accumulated */ }
                        "onError"  -> Log.e(TAG, "Streaming error: ${args?.getOrNull(0)}")
                    }
                    null
                }
                llmCls.getMethod(
                    "generateResponseAsync",
                    String::class.java,
                    listenerCls
                ).invoke(llm, prompt, proxy)

                // Block until streaming complete via synchronous call on same prompt
                // (LiteRT-LM queues calls — this will return after the async one)
                val sync = llmCls
                    .getMethod("generateResponse", String::class.java)
                    .invoke(llm, prompt) as? String ?: ""
                if (sb.isEmpty()) sb.append(sync)

            } else {
                // Synchronous
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
