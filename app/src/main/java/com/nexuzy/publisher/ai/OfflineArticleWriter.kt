package com.nexuzy.publisher.ai

import android.util.Log
import kotlinx.coroutines.delay

/**
 * OfflineArticleWriter
 *
 * Generates a full news article using an offline Gemma model via a
 * multi-step pipeline. Designed for when ALL online API keys are exhausted
 * (Gemini quota + Sarvam key missing).
 *
 * ─── PIPELINE ───────────────────────────────────────────────────────────────
 *
 *   Step 1 │ OUTLINE   │ Generate 4-point article outline
 *   Step 2 │ EXPAND    │ Write ~100 words per outline section
 *   Step 3 │ MERGE     │ Combine sections into flowing article
 *   Step 4 │ CLEAN     │ Fix grammar, remove AI filler, ensure clean output
 *
 * ─── WHY MULTI-STEP? ────────────────────────────────────────────────────────
 *
 *   Gemma 3B on-device has a limited context window (~8k tokens) and tends to
 *   lose structure when asked to write 400+ words in one shot.
 *   Breaking generation into small focused steps (outline → 100-word sections
 *   → merge → clean) produces far better output quality.
 *
 * ─── USAGE ──────────────────────────────────────────────────────────────────
 *
 *   val writer = OfflineArticleWriter(offlineGemmaClient)
 *   val result = writer.write(
 *       title       = rssItem.title,
 *       description = rssItem.description,
 *       category    = rssItem.feedCategory,
 *       targetWords = 400,
 *       onProgress  = { step, msg -> updateUi(msg) }
 *   )
 *   if (result.success) showArticle(result.content)
 */
class OfflineArticleWriter(private val gemma: OfflineGemmaClient) {

    companion object {
        private const val TAG = "OfflineWriter"
        private const val WORDS_PER_SECTION = 100
        private const val OUTLINE_SECTIONS  = 4
    }

    data class WriteResult(
        val success: Boolean,
        val content: String = "",
        val error: String   = ""
    )

    // ─── Main entry point ─────────────────────────────────────────────────────

    suspend fun write(
        title: String,
        description: String,
        category: String = "General",
        targetWords: Int  = 400,
        onProgress: ((step: Int, message: String) -> Unit)? = null
    ): WriteResult {

        if (!gemma.isModelReady()) {
            return WriteResult(false, error = "Offline model not downloaded. Go to Settings → Download AI Model.")
        }

        Log.i(TAG, "Starting offline write: '$title'")

        // ── Step 1: Generate outline ──────────────────────────────────────
        onProgress?.invoke(1, "🧠 Generating outline...")
        val outlineResult = gemma.generate(
            prompt = buildOutlinePrompt(title, description, category),
            maxTokens = 200,
            temperature = 0.7f
        )

        if (!outlineResult.success || outlineResult.text.isBlank()) {
            Log.e(TAG, "Outline failed: ${outlineResult.error}")
            return WriteResult(false, error = "Outline step failed: ${outlineResult.error}")
        }

        val sections = parseOutline(outlineResult.text)
        Log.i(TAG, "Outline: ${sections.size} sections: $sections")

        if (sections.isEmpty()) {
            return WriteResult(false, error = "Could not parse outline from model output")
        }

        // ── Step 2: Expand each section ───────────────────────────────────
        val contentParts = mutableListOf<String>()
        for ((i, section) in sections.withIndex()) {
            onProgress?.invoke(2, "✍️ Writing section ${i + 1}/${sections.size}: $section")
            val sectionResult = gemma.generate(
                prompt    = buildSectionPrompt(section, title, category, WORDS_PER_SECTION),
                maxTokens = 300,
                temperature = 0.85f
            )
            val text = if (sectionResult.success && sectionResult.text.isNotBlank())
                sectionResult.text.trim()
            else {
                Log.w(TAG, "Section '${section}' failed — skipping")
                continue
            }
            contentParts.add(text)
        }

        if (contentParts.isEmpty()) {
            return WriteResult(false, error = "All section expansions failed")
        }

        // ── Step 3: Merge sections into flowing article ───────────────────
        onProgress?.invoke(3, "📄 Merging sections...")
        val merged = contentParts.joinToString("\n\n")

        // If merged content is already good length, skip the merge pass to save tokens
        val mergedWords = merged.split("\s+".toRegex()).size
        val currentContent: String

        if (mergedWords >= targetWords * 0.7) {
            // Content is long enough — just clean it instead of a full merge pass
            Log.i(TAG, "Merged $mergedWords words — skipping merge rewrite, going to clean")
            currentContent = merged
        } else {
            val mergeResult = gemma.generate(
                prompt    = buildMergePrompt(title, merged, targetWords),
                maxTokens = 700,
                temperature = 0.75f
            )
            currentContent = if (mergeResult.success && mergeResult.text.isNotBlank())
                mergeResult.text
            else {
                Log.w(TAG, "Merge pass failed — using raw merged sections")
                merged
            }
        }

        // ── Step 4: Clean + improve grammar ──────────────────────────────
        onProgress?.invoke(4, "✨ Finalising article...")
        val cleanResult = gemma.generate(
            prompt    = buildCleanPrompt(currentContent),
            maxTokens = 800,
            temperature = 0.6f
        )

        val finalContent = if (cleanResult.success && cleanResult.text.isNotBlank() &&
            cleanResult.text.length >= currentContent.length / 2)
            cleanResult.text
        else {
            Log.w(TAG, "Clean pass failed or too short — using merged content")
            gemma.cleanOutput(currentContent) // Apply at least the regex cleaner
        }

        val finalWords = finalContent.split("\\s+".toRegex()).size
        Log.i(TAG, "Offline write complete: $finalWords words")

        return WriteResult(true, finalContent)
    }

    // ─── Prompt builders ──────────────────────────────────────────────────────

    private fun buildOutlinePrompt(title: String, description: String, category: String) =
        """Create a $OUTLINE_SECTIONS-point outline for a news article.
Headline: $title
Summary: $description
Category: $category

Output only $OUTLINE_SECTIONS section titles, one per line. No numbering. No explanation."""

    private fun buildSectionPrompt(
        section: String, title: String, category: String, words: Int
    ) = """Write exactly $words words about: $section
News topic: $title
Category: $category
Tone: Professional journalist
Audience: General readers

Rules:
- Start directly with the paragraph. No heading.
- No AI openers like "Certainly" or "Sure".
- No bullet points. Pure flowing prose.
- End with a complete sentence."""

    private fun buildMergePrompt(title: String, content: String, targetWords: Int) =
        """You are a news editor. Combine these article sections into one smooth $targetWords-word article.
Headline: $title

$content

Rules:
- Keep all facts, names, numbers exactly as written.
- Fix transitions between sections so it reads as one article.
- Remove duplicate sentences.
- Output only the article text. No preamble. No tags."""

    private fun buildCleanPrompt(content: String) =
        """Fix grammar, spelling, and flow of this news article.
Remove any AI thinking text or meta-commentary.
Do NOT change facts, names, numbers, or dates.
Output only the corrected article text. No tags. No hashtags.

Article:
$content

Corrected article:"""

    // ─── Outline parser ───────────────────────────────────────────────────────

    /**
     * Parses the outline into a list of section titles.
     * Strips numbering, bullets, and blank lines.
     * Falls back to splitting by newline if no clean lines found.
     */
    private fun parseOutline(outline: String): List<String> {
        val cleanLineRegex = Regex("""^[\d\-.*•]+[.)\s]+""")
        val lines = outline.lines()
            .map { it.trim().replace(cleanLineRegex, "").trim() }
            .filter { it.isNotBlank() && it.length > 4 }
            .take(OUTLINE_SECTIONS)

        return lines.ifEmpty {
            // Absolute fallback: split on any separator
            outline.split(Regex("[\n;,]"))
                .map { it.trim() }
                .filter { it.isNotBlank() && it.length > 4 }
                .take(OUTLINE_SECTIONS)
        }
    }
}
