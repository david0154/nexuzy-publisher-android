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
 * TWO MODES — chosen based on WHERE in the app we are:
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * MODE A: verifyOnlyWithOpenAi(rssItem)   ← called from NewsWorkflowManager
 *         during bulk fetch / news list display.
 *
 *   Uses:   OpenAI only  (quick fact credibility score, no writing)
 *   Cost:   ~1 OpenAI call per headline checked
 *   Gemini: NEVER called here   ← SAVES ALL GEMINI QUOTA
 *
 * MODE B: processRssItem(rssItem, ...)   ← called ONLY when user taps
 *         "Write Article" on a specific news item in the editor.
 *
 *   Step 1: Gemini  → writes full article
 *   Step 2: OpenAI  → fact-checks the written article
 *   Step 3: Sarvam  → grammar & spelling correction
 *   Step 4: Gemini  → generates SEO metadata
 *   Step 5: Image   → downloads article image
 *
 *   Gemini is called ONLY when the user explicitly requests a write.
 * ─────────────────────────────────────────────────────────────────────────────
 */
class AiPipeline(private val context: Context) {

    private val keyManager = ApiKeyManager(context)
    private val gemini = GeminiApiClient(keyManager)
    private val openAi = OpenAiApiClient(keyManager)
    private val sarvam = SarvamApiClient(keyManager)
    private val imageDownloader = ImageDownloader(context)

    // ─── Result types ─────────────────────────────────────────────────────────

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

    /**
     * Lightweight credibility score returned during bulk RSS display.
     * NO Gemini calls. NO article writing.
     */
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
        OPENAI_CHECKING,
        SARVAM_CHECKING,
        SEO_GENERATING,
        IMAGE_DOWNLOADING,
        COMPLETE,
        ERROR
    }

    // ─── MODE A: Quick OpenAI verify only (bulk list — NO Gemini) ────────────

    /**
     * Quickly checks headline credibility via OpenAI.
     * Called during bulk RSS news list population.
     * Gemini is NEVER touched here.
     *
     * @return QuickVerifyResult with credibility score and reason.
     */
    suspend fun verifyOnlyWithOpenAi(rssItem: RssItem): QuickVerifyResult =
        withContext(Dispatchers.IO) {
            val result = openAi.factCheckArticle(
                headline = rssItem.title,
                content = rssItem.description.ifBlank { rssItem.title }
            )
            if (!result.success) {
                // OpenAI failed — treat as neutral (don't block display)
                return@withContext QuickVerifyResult(
                    rssItem = rssItem,
                    credible = true,
                    confidenceScore = 0.5f,
                    reason = "OpenAI check skipped: ${result.error}",
                    error = result.error
                )
            }
            QuickVerifyResult(
                rssItem = rssItem,
                credible = result.isAccurate,
                confidenceScore = result.confidenceScore,
                reason = result.feedback
            )
        }

    // ─── MODE B: Full pipeline — Gemini write + OpenAI + Sarvam + SEO ────────

    /**
     * Full AI pipeline. Called ONLY when user taps "Write Article" on a
     * specific news item. This is the only place Gemini API is ever called.
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

        // ─── STEP 1: Gemini writes article ───────────────────────────────────
        onProgress?.invoke(PipelineProgress(Step.GEMINI_WRITING, "\uD83D\uDCDD Gemini is writing the article\u2026"))
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

        // ─── STEP 2: OpenAI fact-checks the written article ──────────────────
        onProgress?.invoke(PipelineProgress(Step.OPENAI_CHECKING, "\uD83D\uDD0D OpenAI verifying facts\u2026"))
        val openAiResult = openAi.factCheckArticle(rssItem.title, currentContent)
        var factCheckPassed  = false
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

        // ─── STEP 3: Sarvam grammar & spelling correction ────────────────────
        onProgress?.invoke(PipelineProgress(Step.SARVAM_CHECKING, "\u270F\uFE0F Sarvam AI checking grammar\u2026"))
        val sarvamResult = sarvam.checkGrammarAndSpelling(currentContent)
        if (sarvamResult.success && sarvamResult.correctedText.isNotBlank()) {
            currentContent = sarvamResult.correctedText
        } else if (!sarvamResult.success) {
            stepErrors.add("Sarvam: ${sarvamResult.error}")
        }

        // ─── STEP 4: Gemini generates SEO metadata ───────────────────────────
        onProgress?.invoke(PipelineProgress(Step.SEO_GENERATING, "\uD83D\uDD0E Generating SEO tags\u2026"))
        val seoResult = gemini.generateSeoData(
            title          = rewrittenTitle,
            articleContent = currentContent,
            category       = rssItem.feedCategory,
            model          = model
        )
        var tags             = ""
        var metaKeywords     = ""
        var focusKeyphrase   = ""
        var metaDescription  = ""
        var seoDone          = false
        if (seoResult.success) {
            tags            = seoResult.tags.joinToString(", ")
            metaKeywords    = seoResult.metaKeywords
            focusKeyphrase  = seoResult.focusKeyphrase
            metaDescription = seoResult.metaDescription
            seoDone = true
            Log.d("AiPipeline", "SEO: keyphrase='$focusKeyphrase' tags=$tags")
        } else {
            stepErrors.add("SEO: ${seoResult.error}")
        }

        // ─── STEP 5: Download RSS article image ──────────────────────────────
        var localImagePath = ""
        if (rssItem.imageUrl.isNotBlank()) {
            onProgress?.invoke(PipelineProgress(Step.IMAGE_DOWNLOADING, "\uD83D\uDDBC\uFE0F Downloading article image\u2026"))
            localImagePath = imageDownloader.downloadImage(rssItem.imageUrl, rssItem.title)
            if (localImagePath.isBlank()) stepErrors.add("Image download failed: ${rssItem.imageUrl}")
        }

        onProgress?.invoke(PipelineProgress(Step.COMPLETE, "\u2705 All steps complete!"))

        rewrittenTitle = if (focusKeyphrase.isNotBlank())
            "${focusKeyphrase.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }}: ${rssItem.title}"
        else rssItem.title

        val article = Article(
            title            = rewrittenTitle,
            content          = currentContent,
            summary          = metaDescription.ifBlank { rssItem.description.ifBlank { currentContent.take(160) } },
            category         = rssItem.feedCategory,
            tags             = tags,
            metaKeywords     = metaKeywords,
            focusKeyphrase   = focusKeyphrase,
            metaDescription  = metaDescription,
            sourceUrl        = rssItem.link,
            sourceName       = rssItem.feedName,
            imageUrl         = rssItem.imageUrl,
            imagePath        = localImagePath,
            status           = "draft",
            wordpressSiteId  = wordpressSiteId,
            geminiChecked    = true,
            openaiChecked    = openAiResult.success,
            sarvamChecked    = sarvamResult.success,
            factCheckPassed  = factCheckPassed,
            factCheckFeedback = factCheckFeedback,
            confidenceScore  = confidenceScore,
            aiProvider       = "gemini"
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
