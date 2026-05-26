package com.nexuzy.publisher.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OfflineArticleWriter — powered by Devil AI 2B
 * ══════════════════════════════════════════════════════════════════════
 * Writes full, publish-ready news articles 100% on-device.
 * Uses Gemma 3n E2B LiteRT (.litertlm) via Google LiteRT-LM runtime
 * (com.google.ai.edge.litert:litert-lm:1.0.0 from maven.google.com).
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

        onProgress?.invoke("WRITING", "😈 Devil AI 2B writing article offline…")

        val prompt = buildPrompt(title, description, category, targetWords)
        val sb     = StringBuilder()

        val result = gemmaClient.generate(
            prompt    = prompt,
            maxTokens = estimateTokens(targetWords),
            onToken   = { token ->
                sb.append(token)
                if (sb.length % 300 == 0) {
                    val words = sb.split(" ").size
                    onProgress?.invoke("WRITING", "😈 Devil AI 2B writing… ~$words words")
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
        onProgress?.invoke("DONE", "✅ Devil AI 2B wrote $words words offline")

        WriteResult(
            success     = cleaned.isNotBlank(),
            content     = cleaned,
            error       = if (cleaned.isBlank()) "Empty output from model" else "",
            usedDevilAi = true
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // Prompt — Gemma 3n ChatML format
    //
    // Produces a properly structured published news article:
    //   HEADLINE
    //   (blank line)
    //   By [Author] | [Category] | [Date]
    //   (blank line)
    //   LEAD PARAGRAPH  — answers Who/What/When/Where/Why in 2-3 sentences
    //   (blank line)
    //   BODY PARAGRAPH 1 — key facts and details
    //   (blank line)
    //   BODY PARAGRAPH 2 — background / context
    //   (blank line)
    //   BODY PARAGRAPH 3 — impact / reactions / quotes if available
    //   (blank line)
    //   CLOSING PARAGRAPH — what happens next / outlook
    // ─────────────────────────────────────────────────────────────────

    private fun buildPrompt(
        title: String,
        description: String,
        category: String,
        targetWords: Int
    ): String {
        val ctx = description.trim().take(600).ifBlank { "No additional context provided." }
        val minWords = (targetWords * 0.85).toInt()
        val maxWords = (targetWords * 1.15).toInt()

        return """<start_of_turn>user
You are a senior news journalist at a major publication. Write a complete, professionally structured news article ready for immediate publication.

TOPIC: $title
CATEGORY: $category
CONTEXT: $ctx

ARTICLE STRUCTURE — follow this exactly, in this order:

1. HEADLINE
   Write a strong, factual headline (max 12 words). No label or prefix. Just the headline text.

2. BLANK LINE

3. BYLINE
   Format: By Nexuzy Desk | $category

4. BLANK LINE

5. LEAD PARAGRAPH (2-3 sentences)
   Answer all of: Who, What, When, Where, Why. This is the most important paragraph.
   Use active voice. Be specific.

6. BLANK LINE

7. BODY PARAGRAPH 1 — Key Facts (3-4 sentences)
   The most important specific facts, numbers, names, statements.

8. BLANK LINE

9. BODY PARAGRAPH 2 — Background / Context (3-4 sentences)
   Why this matters. Historical context, related events, or broader implications.

10. BLANK LINE

11. BODY PARAGRAPH 3 — Impact / Expert View (2-3 sentences)
    What does this mean for readers? Any reactions, expert opinions, or what is at stake.

12. BLANK LINE

13. CLOSING PARAGRAPH (2 sentences)
    What happens next. Expected timeline, next steps, or outlook.

WRITING RULES:
- Target length: $minWords to $maxWords words total
- Plain prose ONLY — no bullet points, no asterisks, no markdown, no sub-headers
- NO AI filler words: notably, furthermore, moreover, it is worth noting, in conclusion,
  this underscores, pivotal, landscape, delve, shed light on, game-changer, paradigm shift
- Use natural contractions: it's, don't, hasn't, they've, won't
- Vary sentence length — short punchy sentences mixed with longer ones
- Active voice preferred throughout
- Every paragraph must be separated by a blank line
- Do NOT add any labels like "LEAD:", "BODY:", "CLOSING:" — just the text
- Start writing the article immediately. Output ONLY the article.
<end_of_turn>
<start_of_turn>model
"""
    }

    // ─────────────────────────────────────────────────────────────────
    // Post-processing — clean Gemma control tokens and fix structure
    // ─────────────────────────────────────────────────────────────────

    private fun postProcess(raw: String, originalTitle: String): String {
        var text = raw.trim()

        // Strip all Gemma / ChatML control tokens
        listOf(
            "<end_of_turn>", "<start_of_turn>model", "<start_of_turn>user",
            "<start_of_turn>", "<bos>", "<eos>",
            "<|end|>", "<|assistant|>", "<|user|>", "<|system|>",
            "<|im_end|>", "<|im_start|>"
        ).forEach { token ->
            text = text.replace(token, "")
        }

        // Strip markdown artifacts that Gemma sometimes outputs
        text = text
            .replace(Regex("^#{1,3}\\s+", RegexOption.MULTILINE), "") // ## headers
            .replace(Regex("\\*{1,3}([^*]+)\\*{1,3}"), "$1")           // **bold** / *italic*
            .replace(Regex("^[-*]\\s+", RegexOption.MULTILINE), "")    // bullet points
            .replace(Regex("^\\d+\\.\\s+", RegexOption.MULTILINE), "") // numbered lists

        // Collapse 3+ consecutive blank lines to 2
        text = text.replace(Regex("\n{3,}"), "\n\n").trim()

        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""

        // Ensure headline exists — if first line is too short or missing, prepend original title
        val firstLine = lines.first().trim()
        return if (firstLine.length >= 15) text else "$originalTitle\n\n${text}"
    }

    // Estimate token count: ~0.75 words per token for English
    private fun estimateTokens(targetWords: Int): Int =
        (targetWords / 0.75).toInt().coerceIn(512, 2048)
}
