package com.nexuzy.publisher.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OfflineArticleWriter — powered by Devil AI 2B
 * ═══════════════════════════════════════════════════════════════════════
 * Writes full publish-ready news articles 100% on-device.
 * No API key. No internet needed (model already downloaded).
 *
 * This is the PRIMARY article writer in AiPipeline Stage 1.
 * Online APIs (Gemini, Sarvam) are NOT used for writing —
 * they are only used for fact-check, grammar, and SEO (Stages 2–6).
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

    /**
     * Write a full news article using Devil AI 2B (on-device).
     *
     * @param title        RSS headline
     * @param description  RSS summary / snippet
     * @param category     Feed category (Technology, Sports, etc.)
     * @param targetWords  Approximate article word count
     * @param onProgress   UI progress callback
     */
    suspend fun write(
        title: String,
        description: String,
        category: String,
        targetWords: Int = 700,
        onProgress: ((step: String, message: String) -> Unit)? = null
    ): WriteResult = withContext(Dispatchers.IO) {

        if (!gemmaClient.isModelReady()) {
            // Trigger background download if not ready
            onProgress?.invoke("DOWNLOADING",
                "📥 Devil AI 2B model downloading… please wait")
            return@withContext WriteResult(
                success = false,
                error   = "Devil AI 2B model is not ready yet. Download in progress."
            )
        }

        onProgress?.invoke("WRITING",
            "😈 Devil AI 2B is writing your article offline…")

        val prompt = buildArticlePrompt(title, description, category, targetWords)
        Log.d(TAG, "Prompt: ${prompt.take(120)}…")

        val sb = StringBuilder()
        val result = gemmaClient.generate(
            prompt    = prompt,
            maxTokens = estimateTokens(targetWords),
            onToken   = { token ->
                sb.append(token)
                if (sb.length % 300 == 0) {
                    val wordCount = sb.split(" ").size
                    onProgress?.invoke("WRITING",
                        "😈 Devil AI 2B writing… ~$wordCount words")
                }
            }
        )

        if (!result.success) {
            return@withContext WriteResult(success = false, error = result.error)
        }

        val raw     = if (sb.isNotEmpty()) sb.toString() else result.text
        val cleaned = postProcess(raw, title)

        val words = cleaned.split(" ").size
        Log.i(TAG, "Devil AI 2B wrote: $words words")
        onProgress?.invoke("DONE", "✅ Devil AI 2B wrote $words words offline")

        WriteResult(
            success      = cleaned.isNotBlank(),
            content      = cleaned,
            error        = if (cleaned.isBlank()) "Empty output from model" else "",
            usedDevilAi  = true
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Prompt — journalist style, removes all AI filler
    // ──────────────────────────────────────────────────────────────────────

    private fun buildArticlePrompt(
        title: String,
        description: String,
        category: String,
        targetWords: Int
    ): String {
        val desc = description.trim().take(500).ifBlank { "No additional context." }
        return """
<|system|>
You are a professional news journalist. Write factual, neutral, human-readable news articles.
Never add disclaimers. Never say you are an AI. Output only the article, nothing else.
<|end|>
<|user|>
Write a $targetWords-word news article for the $category section.

Headline source : $title
Context         : $desc

RULES:
- First line = article headline (no label, no colon prefix)
- Blank line after headline
- Lead paragraph: Who/What/When/Where/Why in ≤3 sentences
- 3–4 body paragraphs: facts, background, impact
- Short closing paragraph: what happens next
- NO markdown, NO bullets, NO sub-headers inside the body
- NO AI filler: "notably", "in conclusion", "it is worth noting", "game-changer", "pivotal"
- Natural contractions: it's, don't, hasn't, they've, we're
- Active voice preferred, vary sentence length
- Approximate total: $targetWords words

Write the complete article now:
<|end|>
<|assistant|>
        """.trimIndent()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Post-processing
    // ──────────────────────────────────────────────────────────────────────

    private fun postProcess(raw: String, originalTitle: String): String {
        var text = raw.trim()

        // Strip model padding tokens
        listOf(
            "<|end|>", "<|assistant|>", "<|user|>", "<|system|>",
            "<end_of_turn>", "[INST]", "[/INST]", "</s>", "<s>", "<bos>", "<eos>"
        ).forEach { text = text.replace(it, "") }

        text = text.trim()
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""

        // If model didn't include a headline, prepend original
        val hasHeadline = lines.first().length in 15..130
        return if (hasHeadline) text else "$originalTitle\n\n$text"
    }

    private fun estimateTokens(targetWords: Int): Int =
        (targetWords / 0.75).toInt().coerceIn(256, 2048)
}
