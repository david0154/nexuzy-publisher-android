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
 *   Uses OpenAI ONLY for a quick credibility check.
 *   Gemini is NEVER called here — saves all Gemini quota.
 *
 * MODE B — processRssItem(rssItem, ...)
 *   Called ONLY when user taps "Write Article" on a specific item.
 *   Step 1: Gemini  → writes full article
 *   Step 2: OpenAI  → fact-checks the written article
 *   Step 3: Sarvam  → grammar & spelling correction
 *   Step 4: Gemini  → generates SEO metadata
 *   Step 5: Image   → downloads article image
 */
class AiPipeline(private val context: Context) {

    private val keyManager      = ApiKeyManager(context)
    private val gemini          = GeminiApiClient(keyManager)
    private val openAi          = OpenAiApiClient(keyManager)
    private val sarvam          = SarvamApiClient(keyManager)
    private val imageDownloader = ImageDownloader(context)

    // ─── Data classes ─────────────────────────────────────────────────────

    data class PipelineResult(
        val success: Boolean,
        val article: Article? = null,
        val finalContent: String = "",
        val title: String = "",
        val geminiDone: Boolean = false,
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
        GEMINI_WRITING, OPENAI_CHECKING, SARVAM_CHECKING,
        SEO_GENERATING, IMAGE_DOWNLOADING, COMPLETE, ERROR
    }

    // ─── MODE A: Quick OpenAI verify only (bulk list) — ZERO Gemini ─────────

    /**
     * Quick credibility check via OpenAI.
     * Gemini is NEVER called here. Safe to call for every headline.
     */
    suspend fun verifyOnlyWithOpenAi(rssItem: RssItem): QuickVerifyResult =
        withContext(Dispatchers.IO) {
            // FIX: use 'title' not 'headline' — matches OpenAiApiClient.factCheckArticle(title, content)
            val result = openAi.factCheckArticle(
                title   = rssItem.title,
                content = rssItem.description.ifBlank { rssItem.title }
            )
            if (!result.success) {
                return@withContext QuickVerifyResult(
                    rssItem          = rssItem,
                    credible         = true,
                    confidenceScore  = 0.5f,
                    reason           = "OpenAI check skipped: ${result.error}",
                    error            = result.error
                )
            }
            QuickVerifyResult(
                rssItem         = rssItem,
                credible        = result.isAccurate,
                confidenceScore = result.confidenceScore,
                reason          = result.feedback
            )
        }

    // ─── MODE B: Full pipeline (Gemini + OpenAI + Sarvam + SEO) ────────────

    /**
     * Full AI pipeline. Only called when user taps "Write Article".
     * This is the ONLY function that calls Gemini.
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

        // ─── STEP 1: Gemini writes the article ───────────────────────────────
        onProgress?.invoke(PipelineProgress(Step.GEMINI_WRITING, "📝 Gemini is writing the article…"))
        val geminiResult = gemini.writeNewsArticle(
            rssTitle       = rssItem.title,
            rssDescription = rssItem.description,
            category       = rssItem.feedCategory,
            model          = model,
            maxWords       = maxWords
        )
        if (!geminiResult.success) {
            Log.e("AiPipeline", "Gemini failed: ${geminiResult.error}")
            return@withContext PipelineResult(
                success = false,
                title   = rewrittenTitle,
                error   = geminiResult.error
            )
        }
        var currentContent = geminiResult.content
        Log.d("AiPipeline", "Gemini wrote ${currentContent.length} chars (key #${geminiResult.keyUsed}, model=${geminiResult.modelUsed})")

        // ─── STEP 2: OpenAI fact-checks the written article ─────────────────
        onProgress?.invoke(PipelineProgress(Step.OPENAI_CHECKING, "🔍 OpenAI verifying facts…"))
        // FIX: 'title' and 'content' match OpenAiApiClient.factCheckArticle(title, content)
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
                Log.d("AiPipeline", "OpenAI corrected content (key #${openAiResult.keyUsed})")
            }
        } else {
            stepErrors.add("OpenAI: ${openAiResult.error}")
            Log.w("AiPipeline", "OpenAI fact-check skipped: ${openAiResult.error}")
        }

        // ─── STEP 3: Sarvam grammar & spelling ───────────────────────────────
        onProgress?.invoke(PipelineProgress(Step.SARVAM_CHECKING, "✏️ Sarvam AI checking grammar…"))
        val sarvamResult = sarvam.checkGrammarAndSpelling(currentContent)
        if (sarvamResult.success && sarvamResult.correctedText.isNotBlank()) {
            currentContent = sarvamResult.correctedText
        } else if (!sarvamResult.success) {
            stepErrors.add("Sarvam: ${sarvamResult.error}")
        }

        // ─── STEP 4: Gemini generates SEO metadata ───────────────────────────
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
            Log.d("AiPipeline", "SEO: keyphrase='$focusKeyphrase' tags=$tags")
        } else {
            stepErrors.add("SEO: ${seoResult.error}")
        }

        // ─── STEP 5: Download article image ─────────────────────────────────
        var localImagePath = ""
        if (rssItem.imageUrl.isNotBlank()) {
            onProgress?.invoke(PipelineProgress(Step.IMAGE_DOWNLOADING, "🖼️ Downloading article image…"))
            localImagePath = imageDownloader.downloadImage(rssItem.imageUrl, rssItem.title)
            if (localImagePath.isBlank()) stepErrors.add("Image download failed: ${rssItem.imageUrl}")
        }

        onProgress?.invoke(PipelineProgress(Step.COMPLETE, "✅ All steps complete!"))

        rewrittenTitle = if (focusKeyphrase.isNotBlank())
            "${focusKeyphrase.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }}: ${rssItem.title}"
        else rssItem.title

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
            geminiChecked     = true,
            openaiChecked     = openAiResult.success,
            sarvamChecked     = sarvamResult.success,
            factCheckPassed   = factCheckPassed,
            factCheckFeedback = factCheckFeedback,
            confidenceScore   = confidenceScore,
            aiProvider        = "gemini"
        )

        PipelineResult(
            success           = true,
            article           = article,
            finalContent      = currentContent,
            title             = rewrittenTitle,
            geminiDone        = true,
            openAiDone        = openAiResult.success,
            sarvamDone        = sarvamResult.success,
            seoDone           = seoDone,
            factCheckPassed   = factCheckPassed,
            factCheckFeedback = factCheckFeedback,
            grammarIssues     = sarvamResult.issuesFound,
            confidenceScore   = confidenceScore,
            stepErrors        = stepErrors
        )
    }
}
