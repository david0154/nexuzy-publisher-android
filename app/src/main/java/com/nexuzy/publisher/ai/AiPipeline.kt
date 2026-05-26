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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ╔══════════════════════════════════════════════════════════════════════╗
 * ║    NEXUZY AI WRITER — 8-STAGE PIPELINE (v3 • Sarvam removed)           ║
 * ╠══════════════════════════════════════════════════════════════════════╣
 * ║  STAGE 0 │ WEB CONTEXT   │ DuckDuckGo → live summary + source links     ║
 * ║  STAGE 1 │ WRITE         │ 😈 Devil AI 2B (Gemma 2B, auto-download)       ║
 * ║  STAGE 2 │ FACT-CHECK    │ OpenAI → Gemini fallback                     ║
 * ║  STAGE 3 │ HUMANIZE      │ Gemini → OpenAI fallback                     ║
 * ║  STAGE 4 │ GRAMMAR       │ Gemini flash-lite → OpenAI fallback           ║
 * ║  STAGE 5 │ SEO           │ Gemini → OpenAI fallback                     ║
 * ║  STAGE 6 │ TITLE         │ Gemini → OpenAI fallback                     ║
 * ║  STAGE 7 │ IMAGE         │ Download + watermark check                   ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 *
 * Sarvam AI has been removed from all stages (was causing silent failures).
 * If ALL API keys are absent, stages 2–6 are skipped gracefully.
 * The pipeline ALWAYS produces an article as long as the offline model is ready.
 */
class AiPipeline(private val context: Context) {

    private val keyManager      = ApiKeyManager(context)
    private val gemini          = GeminiApiClient(keyManager)
    private val openAi          = OpenAiApiClient(keyManager)
    private val imageDownloader = ImageDownloader(context)
    private val ddgSearch       = DuckDuckGoSearchClient()
    private val offlineGemma    = OfflineGemmaClient(context)
    private val offlineWriter   = OfflineArticleWriter(offlineGemma)

    // ────────────────────────────────────────────────────────────────────────
    // Data classes
    // ────────────────────────────────────────────────────────────────────────

    data class PipelineResult(
        val success: Boolean,
        val article: Article? = null,
        val finalContent: String = "",
        val title: String = "",
        val writerProvider: String = "devil-ai-2b",
        val openAiDone: Boolean = false,
        val humanized: Boolean = false,
        val humanizeProvider: String = "",
        val seoDone: Boolean = false,
        val titleProvider: String = "",
        val factCheckPassed: Boolean = false,
        val factCheckFeedback: String = "",
        val grammarIssues: List<String> = emptyList(),
        val confidenceScore: Float = 0f,
        val sourceLinks: List<String> = emptyList(),
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
        OFFLINE_WRITING,
        MODEL_DOWNLOADING,
        FACT_CHECKING,
        FACT_CHECK_GEMINI_FALLBACK,
        HUMANIZING,
        HUMANIZING_OPENAI_FALLBACK,
        GRAMMAR_CHECKING,
        GRAMMAR_OPENAI_FALLBACK,
        SEO_GENERATING,
        SEO_OPENAI_FALLBACK,
        TITLE_REWRITING,
        TITLE_OPENAI_FALLBACK,
        IMAGE_DOWNLOADING,
        IMAGE_WATERMARK_SEARCH,
        COMPLETE, ERROR
    }

    // ────────────────────────────────────────────────────────────────────────
    // Quick verify (bulk news list)
    // ────────────────────────────────────────────────────────────────────────

    suspend fun verifyOnlyWithOpenAi(rssItem: RssItem): QuickVerifyResult =
        withContext(Dispatchers.IO) {
            val content = when {
                rssItem.fullContent.isNotBlank()  -> rssItem.fullContent.take(2000)
                rssItem.description.isNotBlank()  -> rssItem.description
                else                              -> rssItem.title
            }

            // Try OpenAI first
            val openAiResult = openAi.factCheckArticle(title = rssItem.title, content = content)
            if (openAiResult.success) {
                val score = when {
                    openAiResult.confidenceScore > 0f -> openAiResult.confidenceScore
                    openAiResult.isAccurate           -> 0.65f
                    else                              -> 0.25f
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
                val r = gemini.factCheckArticle(title = rssItem.title, content = content)
                if (r.success) {
                    return@withContext QuickVerifyResult(
                        rssItem         = rssItem,
                        credible        = r.isAccurate,
                        confidenceScore = if (r.confidenceScore > 0f) r.confidenceScore else if (r.isAccurate) 0.60f else 0.20f,
                        reason          = r.feedback
                    )
                }
            } catch (_: Exception) {}

            // All failed — auto-score
            QuickVerifyResult(
                rssItem         = rssItem,
                credible        = true,
                confidenceScore = 0.50f,
                reason          = "Auto-scored (all fact-check APIs unavailable)",
                error           = openAiResult.error
            )
        }

    // ────────────────────────────────────────────────────────────────────────
    // Full 8-stage pipeline
    // ────────────────────────────────────────────────────────────────────────

    suspend fun processRssItem(
        rssItem: RssItem,
        model: String = GeminiApiClient.DEFAULT_MODEL,
        maxWords: Int = 800,
        wordpressSiteId: Long = 0,
        onProgress: ((PipelineProgress) -> Unit)? = null
    ): PipelineResult = withContext(Dispatchers.IO) {

        val stepErrors       = mutableListOf<String>()
        var humanized        = false
        var humanizeProvider = "none"
        var titleProvider    = "original"
        val sourceLinks      = mutableListOf<String>()

        // ════════════════════════════════════════════════════════════════
        // STAGE 0 — DuckDuckGo live web context + source links
        // ════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.WEB_SEARCHING,
            "🌐 Stage 0/7 — Fetching live context via DuckDuckGo…"))
        var liveWebContext = ""
        try {
            val ddg = ddgSearch.searchWithLinks(
                query    = "${rssItem.title} ${rssItem.feedCategory} latest news",
                maxChars = 2000
            )
            if (ddg.success) {
                liveWebContext = ddg.summary
                sourceLinks.addAll(ddg.links.take(5))
            } else {
                stepErrors.add("[S0-DDG] ${ddg.error}")
            }
        } catch (e: Exception) {
            stepErrors.add("[S0-DDG-ex] ${e.message}")
        }

        val enrichedContext = buildEnrichedContext(
            rssTitle       = rssItem.title,
            rssDescription = rssItem.description,
            liveContext    = liveWebContext,
            sourceLinks    = sourceLinks
        )

        // ════════════════════════════════════════════════════════════════
        // STAGE 1 — Devil AI 2B writes the article (on-device, primary)
        // ════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.OFFLINE_WRITING,
            "😈 Stage 1/7 — Devil AI 2B writing article offline…"))

        if (!offlineGemma.isModelReady()) {
            onProgress?.invoke(PipelineProgress(Step.MODEL_DOWNLOADING,
                "📥 Downloading Devil AI 2B model… (~500 MB, one-time)"))
            // Fixed: downloadModelSync no longer takes hfToken parameter
            val downloaded = offlineGemma.getDownloadManager().downloadModelSync(
                onProgress = { progress ->
                    val pct     = progress.percent
                    val mbDone  = progress.bytesDownloaded / 1_048_576
                    val mbTotal = progress.totalBytes / 1_048_576
                    onProgress?.invoke(PipelineProgress(Step.MODEL_DOWNLOADING,
                        "📥 Devil AI 2B: $pct% ($mbDone MB / $mbTotal MB)"))
                }
            )
            if (!downloaded || !offlineGemma.isModelReady()) {
                return@withContext PipelineResult(
                    success    = false,
                    title      = rssItem.title,
                    error      = "Failed to download Devil AI 2B model. Check internet connection.",
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
                error      = "Stage 1 (Devil AI 2B) failed: ${writeResult.error}",
                stepErrors = stepErrors
            )
        }

        var currentContent = writeResult.content
        Log.i("AiPipeline", "[S1] Devil AI 2B wrote ${currentContent.length} chars")

        // ════════════════════════════════════════════════════════════════
        // STAGE 2 — Fact-check: OpenAI → Gemini fallback
        // ════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.FACT_CHECKING,
            "🔍 Stage 2/7 — Fact-checking (OpenAI)…"))

        var factCheckPassed   = false
        var factCheckFeedback = ""
        var rawConfidence     = 0f
        var openAiDone        = false

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
                    stepErrors.add("[S2-Gemini] ${gr.error} (skipped)")
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
        // STAGE 3 — Humanize: Gemini primary → OpenAI fallback
        // (Sarvam removed — was causing silent failures)
        // ════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.HUMANIZING,
            "🧑 Stage 3/7 — Humanizing (Gemini)…"))

        try {
            val r = gemini.writeNewsArticle(
                rssTitle       = rssItem.title,
                rssDescription = buildHumanizePrompt(),
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
                stepErrors.add("[S3-Gemini] ${r.error.ifBlank { "Empty" }} — trying OpenAI…")
            }
        } catch (e: Exception) {
            stepErrors.add("[S3-Gemini-ex] ${e.message} — trying OpenAI…")
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
                    stepErrors.add("[S3-OpenAI] ${r.error.ifBlank { "Empty" }} (skipped)")
                }
            } catch (e: Exception) {
                stepErrors.add("[S3-OpenAI-ex] ${e.message}")
            }
        }

        // ════════════════════════════════════════════════════════════════
        // STAGE 4 — Grammar: Gemini flash-lite primary → OpenAI fallback
        // (Sarvam removed — was causing silent failures)
        // ════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.GRAMMAR_CHECKING,
            "✏️ Stage 4/7 — Grammar check (Gemini)…"))
        var grammarIssues = emptyList<String>()
        try {
            val gr = gemini.checkGrammarAndSpelling(currentContent)
            if (gr.success && gr.correctedText.isNotBlank()) {
                currentContent = gr.correctedText
                grammarIssues  = gr.issuesFound
            } else {
                stepErrors.add("[S4-Gemini] ${gr.error} — trying OpenAI grammar…")
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
        // STAGE 5 — SEO: Gemini primary → OpenAI fallback
        // (Sarvam removed — was causing silent failures)
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
            stepErrors.add("[S5-Gemini-SEO] ${seoResult.error} — trying OpenAI…")
            onProgress?.invoke(PipelineProgress(Step.SEO_OPENAI_FALLBACK,
                "⚠️ Stage 5/7 — SEO via OpenAI…"))
            try {
                val or3 = openAi.generateSeoData(
                    title          = rssItem.title,
                    articleContent = currentContent,
                    category       = rssItem.feedCategory
                )
                if (or3.success) seoResult = or3
                else stepErrors.add("[S5-OpenAI-SEO] ${or3.error} (skipped)")
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
        // STAGE 6 — Title rewrite: Gemini primary → OpenAI fallback
        // (Sarvam removed — was causing silent failures)
        // ════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.TITLE_REWRITING,
            "📝 Stage 6/7 — Rewriting headline (Gemini)…"))
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
            "✅ Nexuzy AI Writer complete! (😈 Devil AI 2B + 🌐 DDG context)"))

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
            geminiChecked     = false,
            openaiChecked     = openAiDone,
            sarvamChecked     = false,
            factCheckPassed   = factCheckPassed,
            factCheckFeedback = factCheckFeedback,
            confidenceScore   = displayConfidence,
            aiProvider        = "devil-ai-2b",
            sourceLinks       = sourceLinks.joinToString("\n")
        )

        PipelineResult(
            success           = true,
            article           = article,
            finalContent      = currentContent,
            title             = finalTitle,
            writerProvider    = "devil-ai-2b",
            openAiDone        = openAiDone,
            humanized         = humanized,
            humanizeProvider  = humanizeProvider,
            seoDone           = seoResult.success,
            titleProvider     = titleProvider,
            factCheckPassed   = factCheckPassed,
            factCheckFeedback = factCheckFeedback,
            grammarIssues     = grammarIssues,
            confidenceScore   = displayConfidence,
            sourceLinks       = sourceLinks,
            stepErrors        = stepErrors
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun buildEnrichedContext(
        rssTitle: String,
        rssDescription: String,
        liveContext: String,
        sourceLinks: List<String>
    ): String {
        val linksBlock = if (sourceLinks.isNotEmpty())
            "\n\nSOURCE LINKS:\n" + sourceLinks.joinToString("\n") { "• $it" }
        else ""
        return if (liveContext.isNotBlank()) {
            """
            LIVE WEB CONTEXT:
            $liveContext
            $linksBlock

            ORIGINAL RSS DESCRIPTION:
            $rssDescription

            TASK: Write a complete, factual news article about: $rssTitle
            Use the live web context above as your primary knowledge source.
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
        • Do NOT change any facts, numbers, names, dates, or source links.
        • Do NOT add new information or remove key details.
        • Rewrite ONLY the style and tone to sound like a human journalist.
        Remove ALL AI filler: "notably", "in conclusion", "this underscores",
        "pivotal", "landscape", "delve", "shed light on", "it's worth noting",
        "furthermore", "moreover", "game-changer", "paradigm shift".
        Style: vary sentence length, natural contractions, active voice.
        Output: ONLY the rewritten article. Start with headline.
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
        // Gemini primary
        try {
            val r = gemini.writeNewsArticle(
                rssTitle       = originalTitle,
                rssDescription = buildTitlePrompt(originalTitle, articleContent, focusKeyphrase, category),
                rssFullContent = articleContent.take(500),
                category       = category,
                model          = model,
                maxWords       = 20
            )
            val h = extractHeadline(r.content)
            if (r.success && h != null) { onProviderUsed("gemini"); return h }
            onFallback(Step.TITLE_OPENAI_FALLBACK,
                "[S6-Gemini-Title] ${r.error.ifBlank { "Empty" }} — trying OpenAI…")
        } catch (e: Exception) {
            onFallback(Step.TITLE_OPENAI_FALLBACK,
                "[S6-Gemini-Title-ex] ${e.message} — trying OpenAI…")
        }

        // OpenAI fallback
        try {
            val r = openAi.rewriteTitle(
                originalTitle  = originalTitle,
                articleContent = articleContent.take(600),
                focusKeyphrase = focusKeyphrase,
                category       = category
            )
            val h = if (r.success) extractHeadline(r.title) else null
            if (h != null) { onProviderUsed("openai"); return h }
        } catch (e: Exception) {
            onFallback(Step.TITLE_REWRITING, "[S6-OpenAI-Title-ex] ${e.message} — using original")
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
