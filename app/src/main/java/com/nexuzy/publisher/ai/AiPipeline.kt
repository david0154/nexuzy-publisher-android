package com.nexuzy.publisher.ai

import android.content.Context
import android.util.Log
import com.nexuzy.publisher.data.model.Article
import com.nexuzy.publisher.data.model.RssItem
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import com.nexuzy.publisher.network.DuckDuckGoSearchClient
import com.nexuzy.publisher.network.GeminiApiClient
import com.nexuzy.publisher.network.ImageDownloader
import com.nexuzy.publisher.network.OpenAiApiClient
import com.nexuzy.publisher.network.SarvamApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║    NEXUZY AI WRITER — 8-STAGE PIPELINE (v10 • Offline Model)            ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  STAGE 0 │ WEB CONTEXT   │ DuckDuckGo → live summary + source links     ║
 * ║  STAGE 1 │ WRITE         │ 🤖 Offline LiteRT model (100% on-device)     ║
 * ║  STAGE 2 │ FACT-CHECK    │ OpenAI → Gemini → Sarvam (any available)     ║
 * ║  STAGE 3 │ HUMANIZE      │ Gemini → OpenAI → Sarvam fallback            ║
 * ║  STAGE 4 │ GRAMMAR       │ Sarvam → OpenAI fallback                     ║
 * ║  STAGE 5 │ SEO           │ Gemini → Sarvam → OpenAI fallback            ║
 * ║  STAGE 6 │ TITLE + IMAGE │ Gemini → Sarvam → OpenAI (SEO image check)  ║
 * ║  STAGE 7 │ IMAGE         │ Watermark check + fallback search            ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 *
 * DESIGN CONTRACT:
 *   Stage 0  = DuckDuckGo ONLY → fetches live web context + article source links
 *              (free, no API key, always runs)
 *   Stage 1  = Offline LiteRT model ONLY → writes the full article (zero API cost)
 *              The model uses DDG context + source links as its knowledge base.
 *   Stage 2  = OpenAI (primary fact-check) → Gemini → Sarvam (fallbacks)
 *   Stage 3  = Gemini (humanize) → OpenAI → Sarvam (fallbacks)
 *   Stage 4  = Sarvam (grammar/spelling) → OpenAI fallback
 *   Stage 5  = Gemini (SEO meta) → Sarvam → OpenAI fallbacks
 *              Also checks/suggests SEO-friendly image via Gemini
 *   Stage 6  = Gemini → Sarvam → OpenAI (title rewrite, SEO image alt text)
 *   Stage 7  = Download/verify article image
 *
 *   If ALL API keys are absent, stages 2–6 are skipped gracefully.
 *   The pipeline ALWAYS produces an article as long as the offline model is ready.
 */
class AiPipeline(private val context: Context) {

    private val keyManager      = ApiKeyManager(context)
    private val gemini          = GeminiApiClient(keyManager)
    private val openAi          = OpenAiApiClient(keyManager)
    private val sarvam          = SarvamApiClient(keyManager)
    private val imageDownloader = ImageDownloader(context)
    private val ddgSearch       = DuckDuckGoSearchClient()
    private val offlineGemma    = OfflineGemmaClient(context)
    private val offlineWriter   = OfflineArticleWriter(offlineGemma)

    // ──────────────────────────────────────────────────────────────────────
    // Data classes
    // ──────────────────────────────────────────────────────────────────────

    data class PipelineResult(
        val success: Boolean,
        val article: Article? = null,
        val finalContent: String = "",
        val title: String = "",
        val writerProvider: String = "offline_model",
        val openAiDone: Boolean = false,
        val sarvamDone: Boolean = false,
        val humanized: Boolean = false,
        val humanizeProvider: String = "",
        val seoDone: Boolean = false,
        val sarvamUsedForSeo: Boolean = false,
        val titleProvider: String = "",
        val factCheckPassed: Boolean = false,
        val factCheckFeedback: String = "",
        val grammarIssues: List<String> = emptyList(),
        val confidenceScore: Float = 0f,
        val sourceLinks: List<String> = emptyList(),  // DDG article links used as context
        val error: String = "",
        val stepErrors: List<String> = emptyList()
    )

    data class QuickVerifyResult(
        val rssItem: RssItem,
        val credible: Boolean,
        val confidenceScore: Float,
        val reason: String,
        val error: String = ""
    )

    data class PipelineProgress(val step: Step, val message: String)

    enum class Step {
        WEB_SEARCHING,
        OFFLINE_WRITING,            // Stage 1 — offline model (primary, always)
        MODEL_DOWNLOADING,          // Stage 1 — auto-download if needed
        FACT_CHECKING,              // Stage 2 — OpenAI primary
        FACT_CHECK_GEMINI_FALLBACK, // Stage 2 — Gemini fallback
        FACT_CHECK_SARVAM_FALLBACK, // Stage 2 — Sarvam fallback
        HUMANIZING,                 // Stage 3 — Gemini primary
        HUMANIZING_OPENAI_FALLBACK, // Stage 3 — OpenAI fallback
        HUMANIZING_SARVAM_FALLBACK, // Stage 3 — Sarvam fallback
        SARVAM_CHECKING,            // Stage 4 — grammar
        GRAMMAR_OPENAI_FALLBACK,    // Stage 4 — OpenAI grammar fallback
        SEO_GENERATING,             // Stage 5 — Gemini primary
        SEO_SARVAM_FALLBACK,        // Stage 5 — Sarvam fallback
        SEO_OPENAI_FALLBACK,        // Stage 5 — OpenAI fallback
        TITLE_REWRITING,            // Stage 6 — Gemini primary
        TITLE_SARVAM_FALLBACK,      // Stage 6 — Sarvam fallback
        TITLE_OPENAI_FALLBACK,      // Stage 6 — OpenAI fallback
        IMAGE_DOWNLOADING,          // Stage 7
        IMAGE_WATERMARK_SEARCH,     // Stage 7
        COMPLETE, ERROR
    }

    // ──────────────────────────────────────────────────────────────────────
    // MODE A: Quick multi-provider verify (bulk news list)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Quick credibility check for a single RSS item.
     * Tries OpenAI → Gemini → Sarvam in order, uses first that succeeds.
     */
    suspend fun verifyOnlyWithOpenAi(rssItem: RssItem): QuickVerifyResult =
        withContext(Dispatchers.IO) {
            val content = when {
                rssItem.fullContent.isNotBlank() -> rssItem.fullContent.take(2000)
                rssItem.description.isNotBlank() -> rssItem.description
                else -> rssItem.title
            }

            // Try OpenAI first
            val openAiResult = openAi.factCheckArticle(title = rssItem.title, content = content)
            if (openAiResult.success) {
                val score = when {
                    openAiResult.confidenceScore > 0f -> openAiResult.confidenceScore
                    openAiResult.isAccurate -> 0.65f
                    else -> 0.25f
                }
                return@withContext QuickVerifyResult(
                    rssItem         = rssItem,
                    credible        = openAiResult.isAccurate,
                    confidenceScore = score,
                    reason          = openAiResult.feedback
                )
            }

            // Gemini fallback
            try {
                val r = gemini.factCheckArticle(
                    title   = rssItem.title,
                    content = content
                )
                if (r.success) {
                    val score = when {
                        r.confidenceScore > 0f -> r.confidenceScore
                        r.isAccurate -> 0.60f
                        else -> 0.20f
                    }
                    return@withContext QuickVerifyResult(
                        rssItem         = rssItem,
                        credible        = r.isAccurate,
                        confidenceScore = score,
                        reason          = r.feedback
                    )
                }
            } catch (_: Exception) {}

            // Sarvam fallback
            try {
                val r = sarvam.factCheckArticle(
                    title   = rssItem.title,
                    content = content
                )
                if (r.success) {
                    val score = when {
                        r.confidenceScore > 0f -> r.confidenceScore
                        r.isAccurate -> 0.55f
                        else -> 0.20f
                    }
                    return@withContext QuickVerifyResult(
                        rssItem         = rssItem,
                        credible        = r.isAccurate,
                        confidenceScore = score,
                        reason          = r.feedback
                    )
                }
            } catch (_: Exception) {}

            // All failed — auto-score
            QuickVerifyResult(
                rssItem         = rssItem,
                credible        = true,
                confidenceScore = 0.50f,
                reason          = "Auto-scored (all fact-check APIs unavailable: ${openAiResult.error})",
                error           = openAiResult.error
            )
        }

    // ──────────────────────────────────────────────────────────────────────
    // MODE B: Full 8-stage pipeline
    // ──────────────────────────────────────────────────────────────────────

    suspend fun processRssItem(
        rssItem: RssItem,
        model: String = GeminiApiClient.DEFAULT_MODEL,
        maxWords: Int = 800,
        wordpressSiteId: Long = 0,
        onProgress: ((PipelineProgress) -> Unit)? = null
    ): PipelineResult = withContext(Dispatchers.IO) {

        val stepErrors   = mutableListOf<String>()
        var humanized    = false
        var humanizeProvider = "none"
        var titleProvider    = "original"
        var sarvamSeo        = false
        val sourceLinks  = mutableListOf<String>()

        // ════════════════════════════════════════════════════════════════
        // STAGE 0 — DuckDuckGo: live web context + source article links
        // Free, no API key. Gives the offline model current information.
        // ════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.WEB_SEARCHING,
            "🌐 Stage 0/7 — Fetching live context + source links via DuckDuckGo…"))
        var liveWebContext = ""
        try {
            val ddg = ddgSearch.searchWithLinks(
                query    = "${rssItem.title} ${rssItem.feedCategory} latest news",
                maxChars = 2000
            )
            if (ddg.success) {
                liveWebContext = ddg.summary
                sourceLinks.addAll(ddg.links.take(5))
                Log.i("AiPipeline", "[S0] DDG: ${ddg.links.size} links, ${ddg.summary.length} chars")
            } else {
                stepErrors.add("[S0-DDG] ${ddg.error}")
            }
        } catch (e: Exception) {
            stepErrors.add("[S0-DDG-ex] ${e.message}")
        }

        // Build enriched context block for the offline model:
        // includes live DDG summary + source URLs so the model can cite them
        val enrichedContext = buildEnrichedContext(
            rssTitle       = rssItem.title,
            rssDescription = rssItem.description,
            liveContext    = liveWebContext,
            sourceLinks    = sourceLinks
        )

        // ════════════════════════════════════════════════════════════════
        // STAGE 1 — 🤖 Offline LiteRT model writes the article (PRIMARY)
        //   Receives DDG live context + source links as knowledge input.
        //   Zero API cost. 100% on-device.
        // ════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.OFFLINE_WRITING,
            "🤖 Stage 1/7 — Offline model writing article…"))

        if (!offlineGemma.isModelReady()) {
            onProgress?.invoke(PipelineProgress(Step.MODEL_DOWNLOADING,
                "📥 Downloading offline model… this happens only once (~1.5 GB)"))
            val downloaded = offlineGemma.getDownloadManager().downloadModel { progress ->
                val pct     = progress.percent
                val mbDone  = progress.bytesDownloaded / 1_048_576
                val mbTotal = progress.totalBytes / 1_048_576
                onProgress?.invoke(PipelineProgress(Step.MODEL_DOWNLOADING,
                    "📥 Offline model: $pct% ($mbDone MB / $mbTotal MB)"))
            }
            if (!downloaded || !offlineGemma.isModelReady()) {
                return@withContext PipelineResult(
                    success    = false,
                    title      = rssItem.title,
                    error      = "Failed to download offline model. Check internet connection.",
                    stepErrors = stepErrors
                )
            }
        }

        val writeResult = offlineWriter.write(
            title       = rssItem.title,
            description = enrichedContext,
            category    = rssItem.feedCategory,
            targetWords = maxWords,
            onProgress  = { _, msg ->
                onProgress?.invoke(PipelineProgress(Step.OFFLINE_WRITING, msg))
            }
        )

        if (!writeResult.success || writeResult.content.isBlank()) {
            return@withContext PipelineResult(
                success    = false,
                title      = rssItem.title,
                error      = "Stage 1 (offline model) failed: ${writeResult.error}",
                stepErrors = stepErrors
            )
        }

        var currentContent = writeResult.content
        Log.i("AiPipeline", "[S1] Offline model wrote ${currentContent.length} chars")

        // ════════════════════════════════════════════════════════════════
        // STAGE 2 — Fact-check: OpenAI → Gemini → Sarvam
        //   APIs verify facts and may correct the offline-written content.
        // ════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.FACT_CHECKING,
            "🔍 Stage 2/7 — Fact-checking (OpenAI)…"))

        var factCheckPassed   = false
        var factCheckFeedback = ""
        var rawConfidence     = 0f
        var openAiDone        = false

        // Primary: OpenAI
        try {
            val r = openAi.factCheckArticle(title = rssItem.title, content = currentContent)
            if (r.success) {
                openAiDone        = true
                factCheckPassed   = r.isAccurate
                factCheckFeedback = r.feedback
                rawConfidence     = r.confidenceScore
                if (r.correctedContent.isNotBlank() &&
                    r.correctedContent.length >= currentContent.length / 2) {
                    currentContent = r.correctedContent
                }
            } else {
                stepErrors.add("[S2-OpenAI] ${r.error} — trying Gemini…")
                onProgress?.invoke(PipelineProgress(Step.FACT_CHECK_GEMINI_FALLBACK,
                    "⚠️ Stage 2/7 — Fact-check via Gemini…"))
                // Fallback: Gemini
                val gr = gemini.factCheckArticle(title = rssItem.title, content = currentContent)
                if (gr.success) {
                    factCheckPassed   = gr.isAccurate
                    factCheckFeedback = gr.feedback
                    rawConfidence     = gr.confidenceScore
                    if (gr.correctedContent.isNotBlank() &&
                        gr.correctedContent.length >= currentContent.length / 2) {
                        currentContent = gr.correctedContent
                    }
                } else {
                    stepErrors.add("[S2-Gemini] ${gr.error} — trying Sarvam…")
                    onProgress?.invoke(PipelineProgress(Step.FACT_CHECK_SARVAM_FALLBACK,
                        "⚠️ Stage 2/7 — Fact-check via Sarvam…"))
                    // Fallback: Sarvam
                    val sr = sarvam.factCheckArticle(title = rssItem.title, content = currentContent)
                    if (sr.success) {
                        factCheckPassed   = sr.isAccurate
                        factCheckFeedback = sr.feedback
                        rawConfidence     = sr.confidenceScore
                    } else {
                        stepErrors.add("[S2-Sarvam] ${sr.error} (skipped)")
                    }
                }
            }
        } catch (e: Exception) {
            stepErrors.add("[S2-ex] ${e.message} (skipped)")
        }

        val displayConfidence = when {
            rawConfidence > 0f -> rawConfidence
            openAiDone         -> if (factCheckPassed) 0.65f else 0.30f
            else               -> 0.50f
        }

        // ════════════════════════════════════════════════════════════════
        // STAGE 3 — Humanize: Gemini → OpenAI → Sarvam
        //   Makes the offline model's output sound like a human journalist.
        // ════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.HUMANIZING,
            "🧑 Stage 3/7 — Humanizing (Gemini)…"))

        val humanizePrompt = buildHumanizePrompt()
        try {
            val r = gemini.writeNewsArticle(
                rssTitle       = rssItem.title,
                rssDescription = humanizePrompt,
                rssFullContent = currentContent,
                category       = rssItem.feedCategory,
                model          = model,
                maxWords       = maxWords + 50
            )
            if (r.success && r.content.isNotBlank() &&
                r.content.length >= currentContent.length / 2) {
                currentContent   = r.content
                humanized        = true
                humanizeProvider = "gemini"
            } else {
                stepErrors.add("[S3-Gemini] ${r.error.ifBlank { "Empty" }}")
            }
        } catch (e: Exception) {
            stepErrors.add("[S3-Gemini-ex] ${e.message}")
        }

        if (!humanized) {
            onProgress?.invoke(PipelineProgress(Step.HUMANIZING_OPENAI_FALLBACK,
                "⚠️ Stage 3/7 — Humanizing via OpenAI…"))
            try {
                val r = openAi.humanizeArticle(title = rssItem.title, content = currentContent)
                if (r.success && r.humanizedContent.isNotBlank() &&
                    r.humanizedContent.length >= currentContent.length / 2) {
                    currentContent   = r.humanizedContent
                    humanized        = true
                    humanizeProvider = "openai"
                } else {
                    stepErrors.add("[S3-OpenAI] ${r.error.ifBlank { "Empty" }}")
                }
            } catch (e: Exception) {
                stepErrors.add("[S3-OpenAI-ex] ${e.message}")
            }
        }

        if (!humanized) {
            onProgress?.invoke(PipelineProgress(Step.HUMANIZING_SARVAM_FALLBACK,
                "⚠️ Stage 3/7 — Humanizing via Sarvam…"))
            try {
                val r = sarvam.humanizeArticle(title = rssItem.title, content = currentContent)
                if (r.success && r.humanizedContent.isNotBlank() &&
                    r.humanizedContent.length >= currentContent.length / 2) {
                    currentContent   = r.humanizedContent
                    humanized        = true
                    humanizeProvider = "sarvam"
                } else {
                    stepErrors.add("[S3-Sarvam] ${r.error.ifBlank { "Empty" }} (skipped)")
                }
            } catch (e: Exception) {
                stepErrors.add("[S3-Sarvam-ex] ${e.message}")
            }
        }

        // ════════════════════════════════════════════════════════════════
        // STAGE 4 — Grammar: Sarvam → OpenAI fallback
        // ════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.SARVAM_CHECKING,
            "✏️ Stage 4/7 — Grammar check (Sarvam)…"))
        var grammarIssues = emptyList<String>()
        var sarvamDone    = false
        try {
            val r = sarvam.checkGrammarAndSpelling(currentContent)
            if (r.success && r.correctedText.isNotBlank()) {
                currentContent = r.correctedText
                grammarIssues  = r.issuesFound
                sarvamDone     = true
            } else {
                stepErrors.add("[S4-Sarvam] ${r.error} — trying OpenAI grammar…")
                onProgress?.invoke(PipelineProgress(Step.GRAMMAR_OPENAI_FALLBACK,
                    "⚠️ Stage 4/7 — Grammar check via OpenAI…"))
                val or2 = openAi.checkGrammarAndSpelling(currentContent)
                if (or2.success && or2.correctedText.isNotBlank()) {
                    currentContent = or2.correctedText
                    grammarIssues  = or2.issuesFound
                } else {
                    stepErrors.add("[S4-OpenAI] ${or2.error} (skipped)")
                }
            }
        } catch (e: Exception) {
            stepErrors.add("[S4-ex] ${e.message}")
        }

        // ════════════════════════════════════════════════════════════════
        // STAGE 5 — SEO: Gemini → Sarvam → OpenAI
        //   Generates meta, keywords, focus keyphrase, SEO image suggestion.
        // ════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.SEO_GENERATING,
            "🔎 Stage 5/7 — Generating SEO metadata (Gemini)…"))
        var seoResult = gemini.generateSeoData(
            title          = rssItem.title,
            articleContent = currentContent,
            category       = rssItem.feedCategory,
            model          = model
        )
        if (!seoResult.success) {
            stepErrors.add("[S5-Gemini-SEO] ${seoResult.error}")
            onProgress?.invoke(PipelineProgress(Step.SEO_SARVAM_FALLBACK,
                "⚠️ Stage 5/7 — SEO via Sarvam…"))
            try {
                seoResult = sarvam.generateSeoData(
                    title          = rssItem.title,
                    articleContent = currentContent,
                    category       = rssItem.feedCategory
                )
                if (seoResult.success) {
                    sarvamSeo = true
                } else {
                    stepErrors.add("[S5-Sarvam-SEO] ${seoResult.error}")
                    onProgress?.invoke(PipelineProgress(Step.SEO_OPENAI_FALLBACK,
                        "⚠️ Stage 5/7 — SEO via OpenAI…"))
                    val or3 = openAi.generateSeoData(
                        title          = rssItem.title,
                        articleContent = currentContent,
                        category       = rssItem.feedCategory
                    )
                    if (or3.success) seoResult = or3
                    else stepErrors.add("[S5-OpenAI-SEO] ${or3.error} (skipped)")
                }
            } catch (e: Exception) {
                stepErrors.add("[S5-SEO-ex] ${e.message}")
            }
        }
        val tags            = if (seoResult.success) seoResult.tags.joinToString(", ") else ""
        val metaKeywords    = if (seoResult.success) seoResult.metaKeywords else ""
        val focusKeyphrase  = if (seoResult.success) seoResult.focusKeyphrase else ""
        val metaDescription = if (seoResult.success) seoResult.metaDescription else ""
        val seoImageAlt     = if (seoResult.success) seoResult.imageAltText else ""

        // ════════════════════════════════════════════════════════════════
        // STAGE 6 — Title + SEO image alt: Gemini → Sarvam → OpenAI
        // ════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.TITLE_REWRITING,
            "📝 Stage 6/7 — Rewriting headline…"))
        val finalTitle = rewriteTitle(
            originalTitle  = rssItem.title,
            articleContent = currentContent,
            focusKeyphrase = focusKeyphrase,
            category       = rssItem.feedCategory,
            model          = model,
            onFallback     = { step, msg ->
                onProgress?.invoke(PipelineProgress(step, msg))
                stepErrors.add(msg)
            },
            onProviderUsed = { provider -> titleProvider = provider }
        )

        // ════════════════════════════════════════════════════════════════
        // STAGE 7 — Image download + watermark check
        // ════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.IMAGE_DOWNLOADING,
            "🖼️ Stage 7/7 — Downloading article image…"))
        var localImagePath = rssItem.localImagePath
        if (localImagePath.isBlank() && rssItem.imageUrl.isNotBlank()) {
            if (keyManager.getGoogleSearchApiKey().isNotBlank() &&
                keyManager.getGoogleSearchCseId().isNotBlank()) {
                onProgress?.invoke(PipelineProgress(Step.IMAGE_WATERMARK_SEARCH,
                    "🔍 Stage 7/7 — Checking image watermark…"))
            }
            localImagePath = imageDownloader.downloadImage(
                url           = rssItem.imageUrl,
                titleHint     = finalTitle,
                searchQuery   = finalTitle,
                apiKeyManager = keyManager
            )
        }

        onProgress?.invoke(PipelineProgress(Step.COMPLETE,
            "✅ Nexuzy AI Writer complete! (🤖 offline model + 🌐 DDG context)"))

        // ════════════════════════════════════════════════════════════════
        // Build final Article
        // ════════════════════════════════════════════════════════════════
        val article = Article(
            title             = finalTitle,
            content           = currentContent,
            summary           = metaDescription.ifBlank { rssItem.description.take(160) },
            category          = rssItem.feedCategory,
            tags              = tags,
            metaKeywords      = metaKeywords,
            focusKeyphrase    = focusKeyphrase,
            metaDescription   = metaDescription,
            imageAltText      = seoImageAlt.ifBlank { finalTitle },
            sourceUrl         = rssItem.link,
            sourceName        = rssItem.feedName,
            imageUrl          = rssItem.imageUrl,
            imagePath         = localImagePath,
            status            = "draft",
            wordpressSiteId   = wordpressSiteId,
            geminiChecked     = false,   // Gemini NOT used for writing
            openaiChecked     = openAiDone,
            sarvamChecked     = sarvamDone,
            factCheckPassed   = factCheckPassed,
            factCheckFeedback = factCheckFeedback,
            confidenceScore   = displayConfidence,
            aiProvider        = "offline_model",
            sourceLinks       = sourceLinks.joinToString("\n")  // DDG-sourced article links
        )

        PipelineResult(
            success           = true,
            article           = article,
            finalContent      = currentContent,
            title             = finalTitle,
            writerProvider    = "offline_model",
            openAiDone        = openAiDone,
            sarvamDone        = sarvamDone,
            humanized         = humanized,
            humanizeProvider  = humanizeProvider,
            seoDone           = seoResult.success,
            sarvamUsedForSeo  = sarvamSeo,
            titleProvider     = titleProvider,
            factCheckPassed   = factCheckPassed,
            factCheckFeedback = factCheckFeedback,
            grammarIssues     = grammarIssues,
            confidenceScore   = displayConfidence,
            sourceLinks       = sourceLinks,
            stepErrors        = stepErrors
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Builds the enriched context block fed to the offline model.
     * Includes live DDG summary + source article links so the model
     * can produce current, cited content.
     */
    private fun buildEnrichedContext(
        rssTitle: String,
        rssDescription: String,
        liveContext: String,
        sourceLinks: List<String>
    ): String {
        val linksBlock = if (sourceLinks.isNotEmpty()) {
            "\n\nSOURCE LINKS (cite these in the article where relevant):\n" +
                    sourceLinks.joinToString("\n") { "• $it" }
        } else ""

        return if (liveContext.isNotBlank()) {
            """
            LIVE WEB CONTEXT (current information from DuckDuckGo):
            $liveContext
            $linksBlock
            
            ORIGINAL RSS DESCRIPTION:
            $rssDescription
            
            TASK: Write a complete, factual news article about: $rssTitle
            Use the live web context above as your primary knowledge source.
            Include at least one source link from the list above in the article body.
            """.trimIndent()
        } else {
            """
            ORIGINAL RSS DESCRIPTION:
            $rssDescription
            
            TASK: Write a complete, factual news article about: $rssTitle
            """.trimIndent()
        }
    }

    private fun buildHumanizePrompt(): String = """
        HUMANIZATION TASK — STRICT RULES:
        • Do NOT change any facts, numbers, names, dates, direct quotes, or source links.
        • Do NOT add new information or remove key details.
        • Rewrite ONLY the style and tone to sound like a human journalist.

        Remove ALL of the following AI filler phrases:
        "notably", "in conclusion", "this underscores", "pivotal", "landscape",
        "delve", "shed light on", "it's worth noting", "it is important to note",
        "this highlights", "in summary", "furthermore", "moreover", "nevertheless",
        "in today's fast-paced world", "game-changer", "paradigm shift",
        "To be honest", "Arguably", "As an AI", "I cannot", "Certainly!", "Absolutely!"

        Style rules:
        - Vary sentence length: short punchy sentences mixed with longer ones
        - Natural contractions: "it's", "don't", "isn't", "they're", "we've"
        - Active voice preferred
        - Keep the same paragraph structure and order
        - Keep the same headline
        - Keep all source links exactly as they appear

        Output: ONLY the rewritten article. No preamble. Start with headline.
    """.trimIndent()

    private suspend fun rewriteTitle(
        originalTitle: String,
        articleContent: String,
        focusKeyphrase: String,
        category: String,
        model: String,
        onFallback: (Step, String) -> Unit,
        onProviderUsed: (String) -> Unit
    ): String {
        val titlePrompt = buildTitlePrompt(originalTitle, articleContent, focusKeyphrase, category)

        // Provider 1: Gemini
        try {
            val r = gemini.writeNewsArticle(
                rssTitle       = originalTitle,
                rssDescription = titlePrompt,
                rssFullContent = articleContent.take(500),
                category       = category,
                model          = model,
                maxWords       = 20
            )
            val h = extractHeadline(r.content)
            if (r.success && h != null) { onProviderUsed("gemini"); return h }
            onFallback(Step.TITLE_SARVAM_FALLBACK,
                "[S6-Gemini-Title] ${r.error.ifBlank { "Empty" }} — trying Sarvam…")
        } catch (e: Exception) {
            onFallback(Step.TITLE_SARVAM_FALLBACK,
                "[S6-Gemini-Title-ex] ${e.message} — trying Sarvam…")
        }

        // Provider 2: Sarvam
        try {
            val r = sarvam.generateSeoData(
                title          = originalTitle,
                articleContent = articleContent.take(800),
                category       = category
            )
            val h = if (r.success && r.focusKeyphrase.isNotBlank())
                buildSarvamTitleFallback(r.focusKeyphrase) else null
            if (h != null) { onProviderUsed("sarvam"); return h }
            onFallback(Step.TITLE_OPENAI_FALLBACK,
                "[S6-Sarvam-Title] No usable title — trying OpenAI…")
        } catch (e: Exception) {
            onFallback(Step.TITLE_OPENAI_FALLBACK,
                "[S6-Sarvam-Title-ex] ${e.message} — trying OpenAI…")
        }

        // Provider 3: OpenAI
        try {
            val r = openAi.rewriteTitle(
                originalTitle  = originalTitle,
                articleContent = articleContent.take(600),
                focusKeyphrase = focusKeyphrase,
                category       = category
            )
            val h = if (r.success) extractHeadline(r.title) else null
            if (h != null) { onProviderUsed("openai"); return h }
            onFallback(Step.TITLE_REWRITING,
                "[S6-OpenAI-Title] ${r.error.ifBlank { "Empty" }} — using original")
        } catch (e: Exception) {
            onFallback(Step.TITLE_REWRITING,
                "[S6-OpenAI-Title-ex] ${e.message} — using original")
        }

        onProviderUsed("original")
        return originalTitle
    }

    private fun buildTitlePrompt(
        originalTitle: String,
        articleContent: String,
        focusKeyphrase: String,
        category: String
    ) = """
        Write ONE publish-ready news headline.
        Focus keyphrase : $focusKeyphrase
        Category        : $category
        Original title  : $originalTitle
        Article preview : ${articleContent.take(300)}
        Rules: under 70 chars, factual, no clickbait, no AI filler.
        Output ONLY the headline. No quotes. No punctuation at end.
    """.trimIndent()

    private fun buildSarvamTitleFallback(focusKeyphrase: String): String? {
        val c = focusKeyphrase.trim().replaceFirstChar { it.uppercaseChar() }
        return if (c.length in 15..80) c else null
    }

    private fun extractHeadline(raw: String): String? {
        if (raw.isBlank()) return null
        val line = raw.lines()
            .firstOrNull { it.trim().isNotBlank() }
            ?.trim()
            ?.removeSurrounding("\"")
            ?.removeSurrounding("*")
            ?.take(120)
            ?: return null
        return if (line.length >= 10) line else null
    }
}
