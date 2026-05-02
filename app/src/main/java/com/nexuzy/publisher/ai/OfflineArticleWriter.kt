package com.nexuzy.publisher.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OfflineArticleWriter — builds structured prompts and uses OfflineGemmaClient
 * to generate a full, publish-ready news article from an RSS item.
 *
 * Called by AiPipeline Stage 1 Tier C (final fallback when both Gemini and
 * Sarvam are unavailable / quota-exceeded).
 *
 * Writing strategy:
 *   1. Headline
 *   2. Lead paragraph (who/what/when/where/why in ≤ 3 sentences)
 *   3. Body paragraphs (context, quotes if available, impact)
 *   4. Closing paragraph (what to watch next)
 */
class OfflineArticleWriter(private val gemmaClient: OfflineGemmaClient) {

    companion object {
        private const val TAG = "OfflineArticleWriter"
    }

    data class WriteResult(
        val success: Boolean,
        val content: String = "",
        val error: String = ""
    )

    /**
     * Generate a full news article on-device.
     *
     * @param title        RSS item headline (used as article basis)
     * @param description  RSS snippet / summary
     * @param category     Feed category (Technology, Sports, etc.)
     * @param targetWords  Approximate word count to aim for
     * @param onProgress   Optional callback for UI progress messages
     */
    suspend fun write(
        title: String,
        description: String,
        category: String,
        targetWords: Int = 600,
        onProgress: ((step: String, message: String) -> Unit)? = null
    ): WriteResult = withContext(Dispatchers.IO) {

        if (!gemmaClient.isModelReady()) {
            return@withContext WriteResult(
                success = false,
                error   = "Offline model not ready. Download it in Settings \u2192 AI Model."
            )
        }

        onProgress?.invoke("BUILD_PROMPT", "\uD83D\uDCF1 Building offline prompt\u2026")
        val prompt = buildArticlePrompt(title, description, category, targetWords)
        Log.d(TAG, "Prompt length: ${prompt.length} chars")

        val sb = StringBuilder()
        onProgress?.invoke("INFERRING", "\uD83E\uDD16 Gemma generating article (offline)\u2026")

        val result = gemmaClient.generate(
            prompt    = prompt,
            maxTokens = estimateTokens(targetWords),
            onToken   = { token ->
                sb.append(token)
                if (sb.length % 200 == 0) {
                    onProgress?.invoke("INFERRING",
                        "\uD83D\uDCDD ${sb.length / 5} words written\u2026")
                }
            }
        )

        if (!result.success) {
            return@withContext WriteResult(success = false, error = result.error)
        }

        val raw = if (sb.isNotEmpty()) sb.toString() else result.text
        val cleaned = postProcess(raw, title)

        Log.i(TAG, "Offline article: ${cleaned.length} chars, ~${cleaned.split(" ").size} words")
        onProgress?.invoke("DONE", "\u2705 Offline article written (${cleaned.split(" ").size} words)")

        WriteResult(success = cleaned.isNotBlank(), content = cleaned,
            error = if (cleaned.isBlank()) "Model produced empty output" else "")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Prompt builder
    // ──────────────────────────────────────────────────────────────────────────

    private fun buildArticlePrompt(
        title: String,
        description: String,
        category: String,
        targetWords: Int
    ): String {
        val desc = description.trim().take(600).ifBlank { "No additional description available." }
        return """
<|system|>
You are a professional news journalist. Write factual, neutral, human-readable news articles.
Never add disclaimers. Never say you are an AI. Output only the article.
<|end|>
<|user|>
Write a $targetWords-word news article for the $category section.

Source headline : $title
Source summary  : $desc

STRICT RULES:
- Start with the headline on the first line (no "Headline:" prefix)
- Then a blank line
- Lead paragraph: answer Who, What, When, Where, Why in \u2264 3 sentences
- 3-4 body paragraphs with facts, context, and impact
- Short closing paragraph: what happens next
- No markdown, no bullet points, no headers inside the article
- No AI phrases: "notably", "in conclusion", "it is worth noting", "game-changer"
- Natural contractions: it's, don't, hasn't, they've
- Active voice where possible
- Vary sentence length
- Total article \u2248 $targetWords words

Write the complete article now:
<|end|>
<|assistant|>
        """.trimIndent()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Post-processing: strip artifacts from model output
    // ──────────────────────────────────────────────────────────────────────────

    private fun postProcess(raw: String, originalTitle: String): String {
        var text = raw.trim()

        // Remove common model padding tokens
        val stripPatterns = listOf(
            "<|end|>", "<|assistant|>", "<|user|>", "<|system|>",
            "<end_of_turn>", "[INST]", "[/INST]", "</s>",
            "<s>", "<bos>", "<eos>"
        )
        stripPatterns.forEach { text = text.replace(it, "") }

        // If model echoed the prompt header, strip everything before the article
        val articleStart = text.indexOfFirst { it.isLetter() }
        if (articleStart > 0) text = text.substring(articleStart)

        // Ensure article starts with a headline (use original if model omitted it)
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""

        val hasHeadline = lines.first().length in 20..120
        val body = if (hasHeadline) text else "$originalTitle\n\n$text"

        return body.trim()
    }

    // Rough heuristic: ~0.75 words per token for English news text
    private fun estimateTokens(targetWords: Int): Int = (targetWords / 0.75).toInt().coerceIn(256, 2048)
}
