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
 * ╔══════════════════════════════════════════════════════════════════════╗
 * ║      NEXUZY AI WRITER — 7-STAGE PIPELINE (v9 • Devil AI 2B)         ║
 * ╠══════════════════════════════════════════════════════════════════════╣
 * ║  STAGE 0 │ WEB CONTEXT   │ DuckDuckGo live search (no API key)      ║
 * ║  STAGE 1 │ WRITE         │ 😈 Devil AI 2B (on-device, NO API)       ║
 * ║  STAGE 2 │ FACT-CHECK    │ OpenAI (optional, verifies facts)         ║
 * ║  STAGE 3 │ HUMANIZE      │ Gemini → OpenAI fallback (optional)       ║
 * ║  STAGE 4 │ GRAMMAR       │ Sarvam grammar + spelling (optional)      ║
 * ║  STAGE 5 │ SEO           │ Gemini → Sarvam fallback (optional)       ║
 * ║  STAGE 6 │ TITLE REWRITE │ Gemini → Sarvam → OpenAI (optional)      ║
 * ║  STAGE 7 │ IMAGE         │ Watermark check + Google search fallback  ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 *
 * KEY DESIGN:
 *   Stage 1 (WRITING) = Devil AI 2B ONLY — 100% offline, zero API cost.
 *   Stages 2–6 = online APIs used ONLY for fact-check / grammar / SEO.
 *   If ALL APIs are absent, stages 2–6 are skipped gracefully.
 *   The pipeline ALWAYS produces an article as long as Devil AI 2B is ready.
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
        val writerProvider: String = "devil_ai_2b",  // always devil_ai_2b for Stage 1
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
        DEVIL_AI_WRITING,           // Stage 1 — Devil AI 2B (primary, always)
        MODEL_DOWNLOADING,          // Stage 1 — auto-downloading model if needed
        OPENAI_CHECKING,            // Stage 2 — optional
        HUMANIZING,                 // Stage 3 — optional
        HUMANIZING_OPENAI_FALLBACK, // Stage 3 — optional fallback
        SARVAM_CHECKING,            // Stage 4 — optional
        SEO_GENERATING,             // Stage 5 — optional
        SEO_SARVAM_FALLBACK,        // Stage 5 — optional fallback
        TITLE_REWRITING,            // Stage 6 — optional
        TITLE_SARVAM_FALLBACK,      // Stage 6 — optional fallback
        TITLE_OPENAI_FALLBACK,      // Stage 6 — optional fallback
        IMAGE_DOWNLOADING,          // Stage 7
        IMAGE_WATERMARK_SEARCH,     // Stage 7
        COMPLETE, ERROR
    }

    // ──────────────────────────────────────────────────────────────────────
    // MODE A: Quick OpenAI verify (bulk news list)
    // ──────────────────────────────────────────────────────────────────────

    suspend fun verifyOnlyWithOpenAi(rssItem: RssItem): QuickVerifyResult =
        withContext(Dispatchers.IO) {
            val content = when {
                rssItem.fullContent.isNotBlank() -> rssItem.fullContent.take(2000)
                rssItem.description.isNotBlank() -> rssItem.description
                else                             -> rssItem.title
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

            val displayScore = when {
                result.confidenceScore > 0f -> result.confidenceScore
                result.isAccurate           -> 0.65f
                else                        -> 0.25f
            }

            QuickVerifyResult(
                rssItem         = rssItem,
                credible        = result.isAccurate,
                confidenceScore = displayScore,
                reason          = result.feedback
            )
        }

    // ──────────────────────────────────────────────────────────────────────
    // MODE B: Full 7-stage pipeline
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

        // ════════════════════════════════════════════════════════════════
        // STAGE 0 — DuckDuckGo web context (free, no API key)
        // ════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.WEB_SEARCHING,
            "🌐 Stage 0/7 — Fetching live web context…"))
        var liveWebContext = ""
        try {
            val ddg = ddgSearch.search(
                "${rssItem.title} ${rssItem.feedCategory} latest news",
                maxChars = 1500
            )
            if (ddg.success && ddg.summary.isNotBlank()) {
                liveWebContext = ddg.summary
            } else {
                stepErrors.add("[S0-DDG] ${ddg.error}")
            }
        } catch (e: Exception) {
            stepErrors.add("[S0-DDG-ex] ${e.message}")
        }

        // ════════════════════════════════════════════════════════════════
        // STAGE 1 — 😈 Devil AI 2B writes the article (PRIMARY, offline)
        // ════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.DEVIL_AI_WRITING,
            "😈 Stage 1/7 — Devil AI 2B writing article offline…"))

        // Auto-download model if not present
        if (!offlineGemma.isModelReady()) {
            onProgress?.invoke(PipelineProgress(Step.MODEL_DOWNLOADING,
                "📥 Downloading Devil AI 2B model… this happens only once (~1.5 GB)"))
            val downloaded = offlineGemma.getDownloadManager().downloadModel { progress ->
                val pct  = progress.percent
                val mbDone = progress.bytesDownloaded / 1_048_576
                val mbTotal = progress.totalBytes / 1_048_576
                onProgress?.invoke(PipelineProgress(Step.MODEL_DOWNLOADING,
                    "📥 Devil AI 2B: $pct% ($mbDone MB / $mbTotal MB)"))
            }
            if (!downloaded || !offlineGemma.isModelReady()) {
                return@withContext PipelineResult(
                    success = false,
                    title   = rssItem.title,
                    error   = "Failed to download Devil AI 2B model. Check internet connection.",
                    stepErrors = stepErrors
                )
            }
        }

        val writeResult = offlineWriter.write(
            title       = rssItem.title,
            description = if (liveWebContext.isNotBlank())
                "$liveWebContext\n\n${rssItem.description}"
            else
                rssItem.description,
            category    = rssItem.feedCategory,
            targetWords = maxWords,
            onProgress  = { _, msg ->
                onProgress?.invoke(PipelineProgress(Step.DEVIL_AI_WRITING, msg))
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
        // STAGE 2 — Fact-check: OpenAI (optional — skipped if no API key)
        // ════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.OPENAI_CHECKING,
            "🔍 Stage 2/7 — OpenAI fact-checking…"))

        var factCheckPassed   = false
        var factCheckFeedback = ""
        var rawConfidence     = 0f
        var openAiDone        = false

        try {
            val factCheck = openAi.factCheckArticle(
                title   = rssItem.title,
                content = currentContent
            )
            if (factCheck.success) {
                openAiDone        = true
                factCheckPassed   = factCheck.isAccurate
                factCheckFeedback = factCheck.feedback
                rawConfidence     = factCheck.confidenceScore
                if (factCheck.correctedContent.isNotBlank() &&
                    factCheck.correctedContent.length >= currentContent.length / 2) {
                    currentContent = factCheck.correctedContent
                    Log.i("AiPipeline", "[S2] OpenAI corrected content")
                }
            } else {
                stepErrors.add("[S2-OpenAI] ${factCheck.error} (skipped)")
            }
        } catch (e: Exception) {
            stepErrors.add("[S2-OpenAI-ex] ${e.message} (skipped)")
        }

        val displayConfidence = when {
            rawConfidence > 0f -> rawConfidence
            openAiDone         -> if (factCheckPassed) 0.65f else 0.30f
            else               -> 0.50f  // no fact-check available
        }

        // ════════════════════════════════════════════════════════════════
        // STAGE 3 — Humanize: Gemini → OpenAI fallback (optional)
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
                stepErrors.add("[S3-Gemini] ${r.error.ifBlank { "Empty" }} (skipped)")
            }
        } catch (e: Exception) {
            stepErrors.add("[S3-Gemini-ex] ${e.message}")
        }

        if (!humanized) {
            onProgress?.invoke(PipelineProgress(Step.HUMANIZING_OPENAI_FALLBACK,
                "⚠️ Stage 3/7 — OpenAI humanizing fallback…"))
            try {
                val r = openAi.humanizeArticle(
                    title   = rssItem.title,
                    content = currentContent
                )
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
        // STAGE 4 — Grammar: Sarvam (optional)
        // ════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.SARVAM_CHECKING,
            "✏️ Stage 4/7 — Sarvam grammar check…"))
        var grammarIssues = emptyList<String>()
        var sarvamDone    = false
        try {
            val grammar = sarvam.checkGrammarAndSpelling(currentContent)
            if (grammar.success && grammar.correctedText.isNotBlank()) {
                currentContent = grammar.correctedText
                grammarIssues  = grammar.issuesFound
                sarvamDone     = true
            } else {
                stepErrors.add("[S4-Sarvam] ${grammar.error} (skipped)")
            }
        } catch (e: Exception) {
            stepErrors.add("[S4-Sarvam-ex] ${e.message}")
        }

        // ════════════════════════════════════════════════════════════════
        // STAGE 5 — SEO: Gemini → Sarvam fallback (optional)
        // ════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.SEO_GENERATING,
            "🔎 Stage 5/7 — Generating SEO metadata…"))
        var seoResult = gemini.generateSeoData(
            title          = rssItem.title,
            articleContent = currentContent,
            category       = rssItem.feedCategory,
            model          = model
        )
        if (!seoResult.success) {
            stepErrors.add("[S5-Gemini-SEO] ${seoResult.error}")
            onProgress?.invoke(PipelineProgress(Step.SEO_SARVAM_FALLBACK,
                "⚠️ Stage 5/7 — Sarvam SEO fallback…"))
            try {
                seoResult = sarvam.generateSeoData(
                    title          = rssItem.title,
                    articleContent = currentContent,
                    category       = rssItem.feedCategory
                )
                if (seoResult.success) sarvamSeo = true
                else stepErrors.add("[S5-Sarvam-SEO] ${seoResult.error} (skipped)")
            } catch (e: Exception) {
                stepErrors.add("[S5-Sarvam-SEO-ex] ${e.message}")
            }
        }
        val tags            = if (seoResult.success) seoResult.tags.joinToString(", ") else ""
        val metaKeywords    = if (seoResult.success) seoResult.metaKeywords else ""
        val focusKeyphrase  = if (seoResult.success) seoResult.focusKeyphrase else ""
        val metaDescription = if (seoResult.success) seoResult.metaDescription else ""

        // ════════════════════════════════════════════════════════════════
        // STAGE 6 — Title Rewrite: Gemini → Sarvam → OpenAI (optional)
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
        // STAGE 7 — Image download
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
            "✅ Nexuzy AI Writer complete! (😈 Devil AI 2B)"))

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
            sourceUrl         = rssItem.link,
            sourceName        = rssItem.feedName,
            imageUrl          = rssItem.imageUrl,
            imagePath         = localImagePath,
            status            = "draft",
            wordpressSiteId   = wordpressSiteId,
            geminiChecked     = false,           // Gemini NOT used for writing
            openaiChecked     = openAiDone,
            sarvamChecked     = sarvamDone,
            factCheckPassed   = factCheckPassed,
            factCheckFeedback = factCheckFeedback,
            confidenceScore   = displayConfidence,
            aiProvider        = "devil_ai_2b"     // branding
        )

        PipelineResult(
            success           = true,
            article           = article,
            finalContent      = currentContent,
            title             = finalTitle,
            writerProvider    = "devil_ai_2b",
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
            stepErrors        = stepErrors
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun buildHumanizePrompt(): String = """
        HUMANIZATION TASK — STRICT RULES:
        • Do NOT change any facts, numbers, names, dates, or direct quotes.
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
                "[S6-Gemini-Title] ${r.error.ifBlank{"Empty"}} — trying Sarvam…")
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
                "[S6-OpenAI-Title] ${r.error.ifBlank{"Empty"}} — using original")
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
