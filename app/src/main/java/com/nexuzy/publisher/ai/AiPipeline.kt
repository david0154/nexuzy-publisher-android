package com.nexuzy.publisher.ai

import android.content.Context
import android.util.Log
import com.nexuzy.publisher.data.model.Article
import com.nexuzy.publisher.data.model.RssItem
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import com.nexuzy.publisher.network.GeminiApiClient
import com.nexuzy.publisher.network.ImageDownloader
import com.nexuzy.publisher.network.OpenAiApiClient
import com.nexuzy.publisher.network.SarvamApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI Pipeline Orchestrator.
 *
 * TWO MODES:
 *
 * MODE A — verifyOnlyWithOpenAi(rssItem)
 *   Called during bulk RSS fetch / news list display.
 *   Quick credibility check via OpenAI only. Gemini is NEVER called here.
 *
 * MODE B — processRssItem(rssItem, ...)
 *   Called ONLY when user taps "Write Article" on a specific item.
 *
 *   STEP 1: Gemini writes the article (tries all keys × 4 models).
 *           └─ If ALL Gemini quota exhausted → Sarvam AI backup writer (sarvam-m).
 *   STEP 2: OpenAI fact-checks the written article.
 *   STEP 3: Sarvam grammar & spelling correction.
 *   STEP 4: Gemini generates SEO metadata.
 *   STEP 5: Article image downloaded locally.
 *
 * KEY: rssItem.fullContent (scraped at RSS fetch time) is passed to BOTH
 * Gemini and Sarvam backup so they rewrite using the FULL original article.
 */
class AiPipeline(private val context: Context) {

    private val keyManager      = ApiKeyManager(context)
    private val gemini          = GeminiApiClient(keyManager)
    private val openAi          = OpenAiApiClient(keyManager)
    private val sarvam          = SarvamApiClient(keyManager)
    private val imageDownloader = ImageDownloader(context)

    // ─────────────────────────────────────────────────────────────────────────
    // Data classes
    // ─────────────────────────────────────────────────────────────────────────

    data class PipelineResult(
        val success: Boolean,
        val article: Article? = null,
        val finalContent: String = "",
        val title: String = "",
        val geminiDone: Boolean = false,
        val sarvamUsedAsWriter: Boolean = false,   // true when Sarvam wrote instead of Gemini
        val openAiDone: Boolean = false,
        val sarvamDone: Boolean = false,
        val seoDone: Boolean = false,
        val factCheckPassed: Boolean = false,
        val factCheckFeedback: String = "",
        val grammarIssues: List<String> = emptyList(),
        val confidenceScore: Float = 0f,
        val error: String = "",
        val stepErrors: List<String> = emptyList()
    )

    /** Lightweight result used during bulk RSS display. No Gemini. */
    data class QuickVerifyResult(
        val rssItem: RssItem,
        val credible: Boolean,
        val confidenceScore: Float,
        val reason: String,
        val error: String = ""
    )

    data class PipelineProgress(val step: Step, val message: String)

    enum class Step {
        GEMINI_WRITING,
        SARVAM_WRITING,       // backup writer step
        OPENAI_CHECKING,
        SARVAM_CHECKING,
        SEO_GENERATING,
        IMAGE_DOWNLOADING,
        COMPLETE,
        ERROR
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MODE A: Quick OpenAI verify only (bulk list) — ZERO Gemini
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Quick credibility check via OpenAI.
     * Gemini is NEVER called here.
     * Uses rssItem.fullContent (scraped from article URL) when available.
     */
    suspend fun verifyOnlyWithOpenAi(rssItem: RssItem): QuickVerifyResult =
        withContext(Dispatchers.IO) {
            val contentToCheck = when {
                rssItem.fullContent.isNotBlank() -> rssItem.fullContent.take(2000)
                rssItem.description.isNotBlank() -> rssItem.description
                else                             -> rssItem.title
            }
            val result = openAi.factCheckArticle(
                title   = rssItem.title,
                content = contentToCheck
            )
            if (!result.success) {
                return@withContext QuickVerifyResult(
                    rssItem         = rssItem,
                    credible        = true,
                    confidenceScore = 0.5f,
                    reason          = "OpenAI check skipped: ${result.error}",
                    error           = result.error
                )
            }
            QuickVerifyResult(
                rssItem         = rssItem,
                credible        = result.isAccurate,
                confidenceScore = result.confidenceScore,
                reason          = result.feedback
            )
        }

    // ─────────────────────────────────────────────────────────────────────────
    // MODE B: Full pipeline (Gemini → Sarvam backup → OpenAI → Sarvam → SEO → Image)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Full AI pipeline. Only called when user taps "Write Article".
     *
     * Writing priority:
     *   1. Gemini (tries all API keys × 4 models)
     *   2. Sarvam AI sarvam-m (fallback if ALL Gemini exhausted)
     *   3. Abort with error (if both fail)
     */
    suspend fun processRssItem(
        rssItem: RssItem,
        model: String = GeminiApiClient.DEFAULT_MODEL,
        maxWords: Int = 800,
        wordpressSiteId: Long = 0,
        onProgress: ((PipelineProgress) -> Unit)? = null
    ): PipelineResult = withContext(Dispatchers.IO) {

        val stepErrors = mutableListOf<String>()
        var rewrittenTitle = rssItem.title
        var sarvamUsedAsWriter = false

        val hasFullContent = rssItem.fullContent.isNotBlank()
        Log.d("AiPipeline", "processRssItem: fullContent=${if (hasFullContent) "${rssItem.fullContent.length} chars" else "EMPTY — RSS summary only"}")

        // ── STEP 1A: Gemini writes the article ────────────────────────────────
        onProgress?.invoke(PipelineProgress(
            Step.GEMINI_WRITING,
            if (hasFullContent)
                "✍️ Gemini rewriting from full article (${rssItem.fullContent.length} chars)…"
            else
                "✍️ Gemini writing from RSS summary (full content unavailable)…"
        ))

        val geminiResult = gemini.writeNewsArticle(
            rssTitle       = rssItem.title,
            rssDescription = rssItem.description,
            rssFullContent = rssItem.fullContent,
            category       = rssItem.feedCategory,
            model          = model,
            maxWords       = maxWords
        )

        var currentContent: String

        if (geminiResult.success) {
            // ✔ Gemini succeeded
            currentContent = geminiResult.content
            Log.i("AiPipeline", "Gemini wrote ${currentContent.length} chars (key #${geminiResult.keyUsed}, model=${geminiResult.modelUsed})")
        } else {
            // ✖ Gemini failed — try Sarvam AI as backup writer
            Log.w("AiPipeline", "Gemini failed: ${geminiResult.error}")
            Log.i("AiPipeline", "Falling back to Sarvam AI (sarvam-m) as backup writer…")
            stepErrors.add("Gemini: ${geminiResult.error}")

            onProgress?.invoke(PipelineProgress(
                Step.SARVAM_WRITING,
                "⚠️ Gemini unavailable. Sarvam AI writing article as backup…"
            ))

            val sarvamWriteResult = sarvam.writeArticle(
                rssTitle       = rssItem.title,
                rssDescription = rssItem.description,
                rssFullContent = rssItem.fullContent,   // same full content Gemini would have used
                category       = rssItem.feedCategory,
                maxWords       = maxWords
            )

            if (!sarvamWriteResult.success || sarvamWriteResult.content.isBlank()) {
                // Both Gemini and Sarvam failed — abort pipeline
                Log.e("AiPipeline", "Sarvam backup writer also failed: ${sarvamWriteResult.error}")
                stepErrors.add("Sarvam backup: ${sarvamWriteResult.error}")
                return@withContext PipelineResult(
                    success    = false,
                    title      = rewrittenTitle,
                    error      = "Both Gemini and Sarvam AI failed to write the article. " +
                                 "Gemini: ${geminiResult.error}. " +
                                 "Sarvam: ${sarvamWriteResult.error}.",
                    stepErrors = stepErrors
                )
            }

            // ✔ Sarvam backup succeeded
            currentContent     = sarvamWriteResult.content
            sarvamUsedAsWriter = true
            Log.i("AiPipeline", "Sarvam backup writer success: ${currentContent.length} chars")
        }

        // ── STEP 2: OpenAI fact-checks the written article ─────────────────
        onProgress?.invoke(PipelineProgress(Step.OPENAI_CHECKING, "🔍 OpenAI verifying facts…"))
        val openAiResult = openAi.factCheckArticle(
            title   = rssItem.title,
            content = currentContent
        )
        var factCheckPassed   = false
        var factCheckFeedback = ""
        var confidenceScore   = 0f
        if (openAiResult.success) {
            factCheckPassed   = openAiResult.isAccurate
            factCheckFeedback = openAiResult.feedback
            confidenceScore   = openAiResult.confidenceScore
            if (!openAiResult.isAccurate && openAiResult.correctedContent.isNotBlank()) {
                currentContent = openAiResult.correctedContent
                Log.d("AiPipeline", "OpenAI corrected content")
            }
        } else {
            stepErrors.add("OpenAI: ${openAiResult.error}")
            Log.w("AiPipeline", "OpenAI fact-check skipped: ${openAiResult.error}")
        }

        // ── STEP 3: Sarvam grammar & spelling ──────────────────────────────
        onProgress?.invoke(PipelineProgress(Step.SARVAM_CHECKING, "✏️ Sarvam AI checking grammar…"))
        val sarvamResult = sarvam.checkGrammarAndSpelling(currentContent)
        if (sarvamResult.success && sarvamResult.correctedText.isNotBlank()) {
            currentContent = sarvamResult.correctedText
        } else if (!sarvamResult.success) {
            stepErrors.add("Sarvam grammar: ${sarvamResult.error}")
        }

        // ── STEP 4: Gemini generates SEO metadata ──────────────────────────
        onProgress?.invoke(PipelineProgress(Step.SEO_GENERATING, "🔎 Generating SEO tags…"))
        val seoResult = gemini.generateSeoData(
            title          = rewrittenTitle,
            articleContent = currentContent,
            category       = rssItem.feedCategory,
            model          = model
        )
        var tags            = ""
        var metaKeywords    = ""
        var focusKeyphrase  = ""
        var metaDescription = ""
        var seoDone         = false
        if (seoResult.success) {
            tags            = seoResult.tags.joinToString(", ")
            metaKeywords    = seoResult.metaKeywords
            focusKeyphrase  = seoResult.focusKeyphrase
            metaDescription = seoResult.metaDescription
            seoDone         = true
        } else {
            stepErrors.add("SEO: ${seoResult.error}")
        }

        // ── STEP 5: Download article image locally ──────────────────────────
        var localImagePath = rssItem.localImagePath
        if (localImagePath.isBlank() && rssItem.imageUrl.isNotBlank()) {
            onProgress?.invoke(PipelineProgress(Step.IMAGE_DOWNLOADING, "🖼️ Downloading article image…"))
            localImagePath = imageDownloader.downloadImage(rssItem.imageUrl, rssItem.title)
            if (localImagePath.isBlank()) stepErrors.add("Image download failed: ${rssItem.imageUrl}")
        }

        onProgress?.invoke(PipelineProgress(Step.COMPLETE, "✅ All steps complete!"))

        rewrittenTitle = if (focusKeyphrase.isNotBlank())
            "${focusKeyphrase.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }}: ${rssItem.title}"
        else rssItem.title

        // ── Build final Article entity ───────────────────────────────────────
        val article = Article(
            title             = rewrittenTitle,
            content           = currentContent,
            summary           = metaDescription.ifBlank { rssItem.description.ifBlank { currentContent.take(160) } },
            category          = rssItem.feedCategory,
            tags              = tags,
            metaKeywords      = metaKeywords,
            focusKeyphrase    = focusKeyphrase,
            metaDescription   = metaDescription,
            sourceUrl         = rssItem.link,
            sourceName        = rssItem.feedName,
            imageUrl          = rssItem.imageUrl,
            imagePath         = localImagePath,
            status            = "draft",
            wordpressSiteId   = wordpressSiteId,
            geminiChecked     = !sarvamUsedAsWriter,   // false if Sarvam wrote instead
            openaiChecked     = openAiResult.success,
            sarvamChecked     = sarvamResult.success,
            factCheckPassed   = factCheckPassed,
            factCheckFeedback = factCheckFeedback,
            confidenceScore   = confidenceScore,
            aiProvider        = if (sarvamUsedAsWriter) "sarvam" else "gemini"
        )

        PipelineResult(
            success            = true,
            article            = article,
            finalContent       = currentContent,
            title              = rewrittenTitle,
            geminiDone         = !sarvamUsedAsWriter,
            sarvamUsedAsWriter = sarvamUsedAsWriter,
            openAiDone         = openAiResult.success,
            sarvamDone         = sarvamResult.success,
            seoDone            = seoDone,
            factCheckPassed    = factCheckPassed,
            factCheckFeedback  = factCheckFeedback,
            grammarIssues      = sarvamResult.issuesFound,
            confidenceScore    = confidenceScore,
            stepErrors         = stepErrors
        )
    }
}
