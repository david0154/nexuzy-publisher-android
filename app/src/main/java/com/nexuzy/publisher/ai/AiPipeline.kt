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
 * STEP 1 — WRITE:    Gemini (all keys ×4 models) → Sarvam backup writer
 * STEP 2 — VERIFY:   OpenAI fact-check (confidence score fixed: min 0.3)
 * STEP 3 — GRAMMAR:  Sarvam grammar & spelling
 * STEP 4 — SEO:      Gemini generateSeoData → Sarvam generateSeoData backup
 * STEP 5 — IMAGE:    Watermark check → Google image search fallback → local file
 */
class AiPipeline(private val context: Context) {

    private val keyManager      = ApiKeyManager(context)
    private val gemini          = GeminiApiClient(keyManager)
    private val openAi          = OpenAiApiClient(keyManager)
    private val sarvam          = SarvamApiClient(keyManager)
    private val imageDownloader = ImageDownloader(context)

    // ─── Data classes ──────────────────────────────────────────────────────────

    data class PipelineResult(
        val success: Boolean,
        val article: Article? = null,
        val finalContent: String = "",
        val title: String = "",
        val geminiDone: Boolean = false,
        val sarvamUsedAsWriter: Boolean = false,
        val openAiDone: Boolean = false,
        val sarvamDone: Boolean = false,
        val seoDone: Boolean = false,
        val sarvamUsedForSeo: Boolean = false,
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
        GEMINI_WRITING, SARVAM_WRITING,
        OPENAI_CHECKING,
        SARVAM_CHECKING,
        SEO_GENERATING, SEO_SARVAM_FALLBACK,
        IMAGE_DOWNLOADING, IMAGE_WATERMARK_SEARCH,
        COMPLETE, ERROR
    }

    // ─── MODE A: Quick OpenAI verify (bulk news list) ──────────────────────────

    suspend fun verifyOnlyWithOpenAi(rssItem: RssItem): QuickVerifyResult =
        withContext(Dispatchers.IO) {
            val content = when {
                rssItem.fullContent.isNotBlank()  -> rssItem.fullContent.take(2000)
                rssItem.description.isNotBlank()  -> rssItem.description
                else                              -> rssItem.title
            }
            val result = openAi.factCheckArticle(title = rssItem.title, content = content)

            if (!result.success) {
                return@withContext QuickVerifyResult(
                    rssItem         = rssItem,
                    credible        = true,
                    confidenceScore = 0.50f,
                    reason          = "Auto-scored (OpenAI unavailable: ${result.error})",
                    error           = result.error
                )
            }

            val rawScore = result.confidenceScore
            val displayScore = when {
                rawScore > 0f     -> rawScore
                result.isAccurate -> 0.65f
                else              -> 0.25f
            }

            QuickVerifyResult(
                rssItem         = rssItem,
                credible        = result.isAccurate,
                confidenceScore = displayScore,
                reason          = result.feedback
            )
        }

    // ─── MODE B: Full pipeline (Write → Verify → Grammar → SEO → Image) ────────

    suspend fun processRssItem(
        rssItem: RssItem,
        model: String = GeminiApiClient.DEFAULT_MODEL,
        maxWords: Int = 800,
        wordpressSiteId: Long = 0,
        onProgress: ((PipelineProgress) -> Unit)? = null
    ): PipelineResult = withContext(Dispatchers.IO) {

        val stepErrors = mutableListOf<String>()
        var rewrittenTitle   = rssItem.title
        var sarvamWriter     = false
        var sarvamSeo        = false

        // ── STEP 1A: Gemini writes ─────────────────────────────────────────────
        onProgress?.invoke(PipelineProgress(Step.GEMINI_WRITING, "✍️ Gemini rewriting article…"))
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
            currentContent = geminiResult.content
            Log.i("AiPipeline", "Gemini wrote ${currentContent.length} chars (model=${geminiResult.modelUsed})")
        } else {
            // ── STEP 1B: Sarvam backup writer ─────────────────────────────────
            Log.w("AiPipeline", "Gemini failed: ${geminiResult.error}. Trying Sarvam backup…")
            stepErrors.add("Gemini write: ${geminiResult.error}")
            onProgress?.invoke(PipelineProgress(Step.SARVAM_WRITING, "⚠️ Gemini quota hit. Sarvam AI writing backup…"))

            val sarvamWrite = sarvam.writeArticle(
                rssTitle       = rssItem.title,
                rssDescription = rssItem.description,
                rssFullContent = rssItem.fullContent,
                category       = rssItem.feedCategory,
                maxWords       = maxWords
            )

            if (!sarvamWrite.success || sarvamWrite.content.isBlank()) {
                stepErrors.add("Sarvam backup: ${sarvamWrite.error}")
                return@withContext PipelineResult(
                    success    = false,
                    title      = rewrittenTitle,
                    error      = "Both Gemini and Sarvam failed. Gemini: ${geminiResult.error}. Sarvam: ${sarvamWrite.error}.",
                    stepErrors = stepErrors
                )
            }
            currentContent = sarvamWrite.content
            sarvamWriter   = true
            Log.i("AiPipeline", "Sarvam backup writer success: ${currentContent.length} chars")
        }

        // ── STEP 2: OpenAI fact-check ──────────────────────────────────────────
        onProgress?.invoke(PipelineProgress(Step.OPENAI_CHECKING, "🔍 OpenAI verifying facts…"))
        val openAiResult = openAi.factCheckArticle(title = rssItem.title, content = currentContent)
        var factCheckPassed   = false
        var factCheckFeedback = ""
        var rawConfidence     = 0f

        if (openAiResult.success) {
            factCheckPassed   = openAiResult.isAccurate
            factCheckFeedback = openAiResult.feedback
            rawConfidence     = openAiResult.confidenceScore
            if (!openAiResult.isAccurate && openAiResult.correctedContent.isNotBlank())
                currentContent = openAiResult.correctedContent
        } else {
            stepErrors.add("OpenAI: ${openAiResult.error}")
        }

        val displayConfidence = when {
            rawConfidence > 0f      -> rawConfidence
            openAiResult.isAccurate -> 0.65f
            else                    -> 0.30f
        }

        // ── STEP 3: Sarvam grammar check ──────────────────────────────────────
        onProgress?.invoke(PipelineProgress(Step.SARVAM_CHECKING, "✏️ Sarvam checking grammar…"))
        val sarvamGrammar = sarvam.checkGrammarAndSpelling(currentContent)
        if (sarvamGrammar.success && sarvamGrammar.correctedText.isNotBlank())
            currentContent = sarvamGrammar.correctedText
        else if (!sarvamGrammar.success)
            stepErrors.add("Sarvam grammar: ${sarvamGrammar.error}")

        // ── STEP 4: SEO (Gemini → Sarvam fallback) ────────────────────────────
        onProgress?.invoke(PipelineProgress(Step.SEO_GENERATING, "🔎 Generating SEO tags…"))
        var seoResult = gemini.generateSeoData(
            title          = rewrittenTitle,
            articleContent = currentContent,
            category       = rssItem.feedCategory,
            model          = model
        )

        if (!seoResult.success) {
            Log.w("AiPipeline", "Gemini SEO failed: ${seoResult.error}. Trying Sarvam SEO backup…")
            stepErrors.add("Gemini SEO: ${seoResult.error}")
            onProgress?.invoke(PipelineProgress(Step.SEO_SARVAM_FALLBACK, "⚠️ Sarvam generating SEO backup…"))
            seoResult = sarvam.generateSeoData(
                title          = rewrittenTitle,
                articleContent = currentContent,
                category       = rssItem.feedCategory
            )
            if (seoResult.success) sarvamSeo = true
            else stepErrors.add("Sarvam SEO: ${seoResult.error}")
        }

        val tags            = if (seoResult.success) seoResult.tags.joinToString(", ") else ""
        val metaKeywords    = if (seoResult.success) seoResult.metaKeywords else ""
        val focusKeyphrase  = if (seoResult.success) seoResult.focusKeyphrase else ""
        val metaDescription = if (seoResult.success) seoResult.metaDescription else ""

        // ── STEP 5: Article image — watermark check + search fallback ──────────
        onProgress?.invoke(PipelineProgress(Step.IMAGE_DOWNLOADING, "🖼️ Downloading and checking article image…"))
        var localImagePath = rssItem.localImagePath

        if (localImagePath.isBlank() && rssItem.imageUrl.isNotBlank()) {
            // downloadImage now handles: download → watermark check → Google fallback
            val hasSearchKeys = keyManager.getGoogleSearchApiKey().isNotBlank() &&
                                keyManager.getGoogleSearchCseId().isNotBlank()

            if (hasSearchKeys) {
                // Pass the article title as search query for the watermark fallback
                onProgress?.invoke(
                    PipelineProgress(Step.IMAGE_WATERMARK_SEARCH,
                    "🔍 Checking image for watermarks…")
                )
            }

            localImagePath = imageDownloader.downloadImage(
                url          = rssItem.imageUrl,
                titleHint    = rssItem.title,
                searchQuery  = rssItem.title,
                apiKeyManager = keyManager
            )

            if (localImagePath.isBlank()) {
                Log.w("AiPipeline", "Image download failed — WP will upload from URL at push time")
            }
        }

        onProgress?.invoke(PipelineProgress(Step.COMPLETE, "✅ Pipeline complete!"))

        rewrittenTitle = if (focusKeyphrase.isNotBlank())
            "${focusKeyphrase.replaceFirstChar { it.titlecase() }}: ${rssItem.title}"
        else rssItem.title

        val article = Article(
            title             = rewrittenTitle,
            content           = currentContent,
            summary           = metaDescription.ifBlank { rssItem.description.take(160) },
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
            geminiChecked     = !sarvamWriter,
            openaiChecked     = openAiResult.success,
            sarvamChecked     = sarvamGrammar.success,
            factCheckPassed   = factCheckPassed,
            factCheckFeedback = factCheckFeedback,
            confidenceScore   = displayConfidence,
            aiProvider        = if (sarvamWriter) "sarvam" else "gemini"
        )

        PipelineResult(
            success            = true,
            article            = article,
            finalContent       = currentContent,
            title              = rewrittenTitle,
            geminiDone         = !sarvamWriter,
            sarvamUsedAsWriter = sarvamWriter,
            sarvamUsedForSeo   = sarvamSeo,
            openAiDone         = openAiResult.success,
            sarvamDone         = sarvamGrammar.success,
            seoDone            = seoResult.success,
            factCheckPassed    = factCheckPassed,
            factCheckFeedback  = factCheckFeedback,
            grammarIssues      = sarvamGrammar.issuesFound,
            confidenceScore    = displayConfidence,
            stepErrors         = stepErrors
        )
    }
}
