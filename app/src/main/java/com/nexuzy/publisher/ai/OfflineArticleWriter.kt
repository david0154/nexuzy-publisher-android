package com.nexuzy.publisher.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OfflineArticleWriter — powered by Devil AI 2B
 * ══════════════════════════════════════════════════════════════════════
 * Writes full, publish-ready news articles 100% on-device.
 * Uses Gemma 3n E2B LiteRT (.litertlm) via MediaPipe.
 *
 * No API key needed. No internet needed at write time
 * (model auto-downloads on first run via ModelDownloadManager).
 */
class OfflineArticleWriter(private val gemmaClient: OfflineGemmaClient) {

    companion object {
        private const val TAG = "DevilAI2B-Writer"
    }

    data class WriteResult(
        val success: Boolean,
        val content: String = "",
        val error: String = "",
        val usedDevilAi: Boolean = true
    )

    suspend fun write(
        title: String,
        description: String,
        category: String,
        targetWords: Int = 700,
        onProgress: ((step: String, message: String) -> Unit)? = null
    ): WriteResult = withContext(Dispatchers.IO) {

        if (!gemmaClient.isModelReady()) {
            return@withContext WriteResult(
                success = false,
                error   = "Devil AI 2B model is not ready. Download in progress."
            )
        }

        onProgress?.invoke("WRITING", "\uD83D\uDE08 Devil AI 2B writing article offline\u2026")

        val prompt = buildPrompt(title, description, category, targetWords)
        val sb     = StringBuilder()

        val result = gemmaClient.generate(
            prompt    = prompt,
            maxTokens = estimateTokens(targetWords),
            onToken   = { token ->
                sb.append(token)
                if (sb.length % 300 == 0) {
                    val words = sb.split(" ").size
                    onProgress?.invoke("WRITING", "\uD83D\uDE08 Devil AI 2B writing\u2026 ~$words words")
                }
            }
        )

        if (!result.success) {
            return@withContext WriteResult(success = false, error = result.error)
        }

        val raw     = if (sb.isNotEmpty()) sb.toString() else result.text
        val cleaned = postProcess(raw, title)
        val words   = cleaned.split(" ").size

        Log.i(TAG, "Devil AI 2B wrote: $words words")
        onProgress?.invoke("DONE", "\u2705 Devil AI 2B wrote $words words offline")

        WriteResult(
            success     = cleaned.isNotBlank(),
            content     = cleaned,
            error       = if (cleaned.isBlank()) "Empty output from model" else "",
            usedDevilAi = true
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // Prompt (ChatML format — matches Gemma 3n IT template)
    // ──────────────────────────────────────────────────────────────────

    private fun buildPrompt(
        title: String,
        description: String,
        category: String,
        targetWords: Int
    ): String {
        val ctx = description.trim().take(500).ifBlank { "No additional context." }
        return """<start_of_turn>user
You are a professional news journalist. Write a factual, neutral, human-readable news article.

Category        : $category
Headline source : $title
Context         : $ctx

RULES:
- First line = article headline (no label prefix)
- Blank line after headline
- Lead paragraph: Who/What/When/Where/Why in ≤3 sentences
- 3–4 body paragraphs: facts, background, impact
- Short closing paragraph: what happens next
- NO markdown, NO bullet points, NO sub-headers
- NO AI filler: "notably", "in conclusion", "it is worth noting", "game-changer"
- Natural contractions: it's, don't, hasn't, they've
- Active voice, vary sentence length
- Approximate total: $targetWords words

Write the complete article now. Output only the article.
<end_of_turn>
<start_of_turn>model
"""
    }

    // ──────────────────────────────────────────────────────────────────
    // Post-processing
    // ──────────────────────────────────────────────────────────────────

    private fun postProcess(raw: String, originalTitle: String): String {
        var text = raw.trim()

        // Strip Gemma control tokens
        listOf(
            "<end_of_turn>", "<start_of_turn>", "<bos>", "<eos>",
            "<|end|>", "<|assistant|>", "<|user|>", "<|system|>"
        ).forEach { text = text.replace(it, "") }

        text = text.trim()
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""

        // Prepend title if model skipped it
        val hasHeadline = lines.first().length in 15..130
        return if (hasHeadline) text else "$originalTitle\n\n$text"
    }

    private fun estimateTokens(targetWords: Int): Int =
        (targetWords / 0.75).toInt().coerceIn(256, 2048)
}
