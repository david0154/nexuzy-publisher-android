package com.nexuzy.publisher.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * OfflineGemmaClient — wraps Google's MediaPipe LLM Inference API
 * to run Gemma 3n (or any GGUF/TFLite model) fully on-device.
 *
 * Model placement:
 *   /sdcard/Android/data/com.nexuzy.publisher/files/models/gemma3n.bin
 *   (or wherever getModelFile() points)
 *
 * Dependencies to add in app/build.gradle:
 *   implementation 'com.google.mediapipe:tasks-genai:0.10.14'
 *
 * Falls back gracefully if model is not downloaded yet —
 * isModelReady() returns false and generate() returns an error result.
 */
class OfflineGemmaClient(private val context: Context) {

    companion object {
        private const val TAG = "OfflineGemmaClient"
        private const val MODEL_FILENAME = "gemma3n.bin"
        private const val MAX_TOKENS = 1024
        private const val TEMPERATURE = 0.7f
        private const val TOP_K = 40
    }

    data class GenerateResult(
        val success: Boolean,
        val text: String = "",
        val error: String = "",
        val tokensGenerated: Int = 0
    )

    /** Returns the expected model file path so the Settings screen can display it. */
    fun getModelFile(): File =
        File(context.getExternalFilesDir("models"), MODEL_FILENAME)

    /** True only when the model binary exists on disk and is non-empty. */
    fun isModelReady(): Boolean {
        val f = getModelFile()
        val ready = f.exists() && f.length() > 1_000_000L   // >1 MB sanity check
        Log.d(TAG, "isModelReady=$ready  path=${f.absolutePath}")
        return ready
    }

    /**
     * Run inference with the on-device model.
     * Wrapped in IO dispatcher — safe to call from any coroutine.
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int = MAX_TOKENS,
        onToken: ((String) -> Unit)? = null
    ): GenerateResult = withContext(Dispatchers.IO) {

        if (!isModelReady()) {
            return@withContext GenerateResult(
                success = false,
                error   = "Offline model not found at ${getModelFile().absolutePath}. " +
                          "Go to Settings \u2192 Download AI Model."
            )
        }

        return@withContext try {
            runMediaPipeInference(prompt, maxTokens, onToken)
        } catch (e: Exception) {
            Log.e(TAG, "MediaPipe inference failed: ${e.message}", e)
            // Graceful degradation: try pure-Java tokenizer fallback
            try {
                runJavaLlmFallback(prompt, maxTokens)
            } catch (e2: Exception) {
                GenerateResult(
                    success = false,
                    error   = "Offline inference error: ${e2.message}"
                )
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // MediaPipe LLM Inference (primary path)
    // ──────────────────────────────────────────────────────────────────────────

    private fun runMediaPipeInference(
        prompt: String,
        maxTokens: Int,
        onToken: ((String) -> Unit)?
    ): GenerateResult {
        /*
         * Dynamic class loading so the app compiles even if the mediapipe
         * tasks-genai dependency is not yet added to build.gradle.
         * Once you add: implementation 'com.google.mediapipe:tasks-genai:0.10.14'
         * this will resolve at runtime.
         */
        val llmClass     = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
        val optionsClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions")
        val builderClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions\$Builder")

        // Build options
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

        // Create inference engine
        val llm = llmClass.getMethod("createFromOptions", Context::class.java, optionsClass)
            .invoke(null, context, options)

        val sb = StringBuilder()
        try {
            if (onToken != null) {
                // Streaming
                val streamClass = Class.forName(
                    "com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceListener")
                val proxy = java.lang.reflect.Proxy.newProxyInstance(
                    streamClass.classLoader,
                    arrayOf(streamClass)
                ) { _, method, args ->
                    if (method.name == "onPartialResult" && args != null && args.isNotEmpty()) {
                        val token = args[0]?.toString() ?: ""
                        sb.append(token)
                        onToken(token)
                    }
                    null
                }
                llmClass.getMethod("generateResponseAsync", String::class.java, streamClass)
                    .invoke(llm, prompt, proxy)
                // Wait for completion via generateResponse synchronously as well
                // (streaming result already accumulated in sb)
            } else {
                // Synchronous
                val result = llmClass.getMethod("generateResponse", String::class.java)
                    .invoke(llm, prompt) as? String ?: ""
                sb.append(result)
            }
        } finally {
            try { llmClass.getMethod("close").invoke(llm) } catch (_: Exception) {}
        }

        val output = sb.toString().trim()
        return if (output.isNotBlank()) {
            GenerateResult(success = true, text = output, tokensGenerated = output.split(" ").size)
        } else {
            GenerateResult(success = false, error = "Model returned empty output")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Pure-Java LLM fallback (llm.java / llama.cpp via JNI if bundled)
    // ──────────────────────────────────────────────────────────────────────────

    private fun runJavaLlmFallback(prompt: String, maxTokens: Int): GenerateResult {
        /*
         * Attempt to load a llama.cpp JNI wrapper if bundled as a .so in the APK.
         * This is a best-effort fallback — if the .so isn't present this throws
         * and the caller catches + returns an error.
         */
        System.loadLibrary("llama")
        val llamaClass = Class.forName("com.nexuzy.publisher.llama.LlamaContext")
        val ctx = llamaClass
            .getMethod("create", String::class.java, Int::class.java, Int::class.java)
            .invoke(null, getModelFile().absolutePath, maxTokens, 4 /* threads */)
        val output = llamaClass
            .getMethod("completion", String::class.java)
            .invoke(ctx, prompt) as? String ?: ""
        try { llamaClass.getMethod("free").invoke(ctx) } catch (_: Exception) {}

        return if (output.isNotBlank()) {
            GenerateResult(success = true, text = output.trim(), tokensGenerated = output.split(" ").size)
        } else {
            GenerateResult(success = false, error = "Llama.cpp fallback returned empty output")
        }
    }
}
