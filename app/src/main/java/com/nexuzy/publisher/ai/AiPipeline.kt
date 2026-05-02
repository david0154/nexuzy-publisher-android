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
 * ║          NEXUZY AI WRITER — 7-STAGE PIPELINE (v7)                   ║
 * ╠══════════════════════════════════════════════════════════════════════╣
 * ║  STAGE 0 │ WEB CONTEXT   │ DuckDuckGo live search for fresh context ║
 * ║  STAGE 1 │ WRITE         │ Gemini → Sarvam backup                   ║
 * ║  STAGE 2 │ FACT-CHECK    │ OpenAI verifies facts + corrects errors   ║
 * ║  STAGE 3 │ HUMANIZE      │ Gemini → OpenAI fallback                 ║
 * ║  STAGE 4 │ GRAMMAR       │ Sarvam grammar + spelling                ║
 * ║  STAGE 5 │ SEO           │ Gemini → Sarvam fallback                 ║
 * ║  STAGE 6 │ TITLE REWRITE │ Gemini → Sarvam → OpenAI fallback        ║
 * ║  STAGE 7 │ IMAGE         │ Watermark check + Google search fallback  ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 *
 * Output: publish-ready Article with clean human-like content,
 *         SEO-optimised title, meta description, tags, and image.
 */
class AiPipeline(private val context: Context) {

    private val keyManager      = ApiKeyManager(context)
    private val gemini          = GeminiApiClient(keyManager)
    private val openAi          = OpenAiApiClient(keyManager)
    private val sarvam          = SarvamApiClient(keyManager)
    private val imageDownloader = ImageDownloader(context)
    private val ddgSearch       = DuckDuckGoSearchClient()

    // ─────────────────────────────────────────────────────────────────────────
    // Data classes
    // ─────────────────────────────────────────────────────────────────────────

    data class PipelineResult(
        val success: Boolean,
        val article: Article? = null,
        val finalContent: String = "",
        val title: String = "",
        val geminiDone: Boolean = false,
        val sarvamUsedAsWriter: Boolean = false,
        val openAiDone: Boolean = false,
        val sarvamDone: Boolean = false,
        val humanized: Boolean = false,
        val humanizeProvider: String = "",   // "gemini" | "openai" | "none"
        val seoDone: Boolean = false,
        val sarvamUsedForSeo: Boolean = false,
        val titleProvider: String = "",      // "gemini" | "sarvam" | "openai" | "original"
        val factCheckPassed: Boolean = false,
        val factCheckFeedback: String = "",
        val grammarIssues: List<String> = emptyList(),
        val confidenceScore: Float = 0f,
        val error: String = "",
        val stepErrors: List<String> = emptyList()
    )

    /** Lightweight result for bulk RSS credibility display. No Gemini. */
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
        GEMINI_WRITING, SARVAM_WRITING,
        OPENAI_CHECKING,
        HUMANIZING, HUMANIZING_OPENAI_FALLBACK,
        SARVAM_CHECKING,
        SEO_GENERATING, SEO_SARVAM_FALLBACK,
        TITLE_REWRITING, TITLE_SARVAM_FALLBACK, TITLE_OPENAI_FALLBACK,
        IMAGE_DOWNLOADING, IMAGE_WATERMARK_SEARCH,
        COMPLETE, ERROR
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MODE A: Quick OpenAI verify (bulk news list, zero Gemini calls)
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // MODE B: Full 7-stage Nexuzy AI Writer pipeline
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun processRssItem(
        rssItem: RssItem,
        model: String = GeminiApiClient.DEFAULT_MODEL,
        maxWords: Int = 800,
        wordpressSiteId: Long = 0,
        onProgress: ((PipelineProgress) -> Unit)? = null
    ): PipelineResult = withContext(Dispatchers.IO) {

        val stepErrors       = mutableListOf<String>()
        var sarvamWriter     = false
        var sarvamSeo        = false
        var humanized        = false
        var humanizeProvider = "none"
        var titleProvider    = "original"

        // ════════════════════════════════════════════════════════════════════
        // STAGE 0 — DuckDuckGo web context (free, no API key needed)
        // ════════════════════════════════════════════════════════════════════
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
                Log.i("AiPipeline", "[S0] DDG context: ${liveWebContext.length} chars")
            } else {
                stepErrors.add("[S0-DDG] ${ddg.error}")
            }
        } catch (e: Exception) {
            stepErrors.add("[S0-DDG-ex] ${e.message}")
        }

        // ════════════════════════════════════════════════════════════════════
        // STAGE 1 — Write: Gemini primary → Sarvam backup
        // ════════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.GEMINI_WRITING,
            "✍️ Stage 1/7 — Gemini writing article…"))

        val geminiWrite = gemini.writeNewsArticle(
            rssTitle       = rssItem.title,
            rssDescription = rssItem.description,
            rssFullContent = rssItem.fullContent,
            rssPubDate     = rssItem.pubDate,
            rssSourceUrl   = rssItem.link,
            liveWebContext = liveWebContext,
            category       = rssItem.feedCategory,
            model          = model,
            maxWords       = maxWords
        )

        var currentContent: String

        if (geminiWrite.success && geminiWrite.content.isNotBlank()) {
            currentContent = geminiWrite.content
            Log.i("AiPipeline", "[S1] Gemini wrote ${currentContent.length} chars (${geminiWrite.modelUsed})")
        } else {
            stepErrors.add("[S1-Gemini] ${geminiWrite.error}")
            onProgress?.invoke(PipelineProgress(Step.SARVAM_WRITING,
                "⚠️ Stage 1/7 — Gemini quota hit. Sarvam AI writing…"))

            val sarvamWrite = sarvam.writeArticle(
                rssTitle       = rssItem.title,
                rssDescription = rssItem.description,
                rssFullContent = rssItem.fullContent,
                category       = rssItem.feedCategory,
                maxWords       = maxWords
            )

            if (!sarvamWrite.success || sarvamWrite.content.isBlank()) {
                stepErrors.add("[S1-Sarvam] ${sarvamWrite.error}")
                return@withContext PipelineResult(
                    success    = false,
                    title      = rssItem.title,
                    error      = "Stage 1 FAIL — Gemini: ${geminiWrite.error} | Sarvam: ${sarvamWrite.error}",
                    stepErrors = stepErrors
                )
            }
            currentContent = sarvamWrite.content
            sarvamWriter   = true
            Log.i("AiPipeline", "[S1] Sarvam backup wrote ${currentContent.length} chars")
        }

        // ════════════════════════════════════════════════════════════════════
        // STAGE 2 — Fact-check: OpenAI verifies & corrects
        // ════════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.OPENAI_CHECKING,
            "🔍 Stage 2/7 — OpenAI fact-checking…"))

        val factCheck = openAi.factCheckArticle(title = rssItem.title, content = currentContent)
        var factCheckPassed   = false
        var factCheckFeedback = ""
        var rawConfidence     = 0f

        if (factCheck.success) {
            factCheckPassed   = factCheck.isAccurate
            factCheckFeedback = factCheck.feedback
            rawConfidence     = factCheck.confidenceScore
            if (factCheck.correctedContent.isNotBlank() &&
                factCheck.correctedContent.length >= currentContent.length / 2) {
                currentContent = factCheck.correctedContent
                Log.i("AiPipeline", "[S2] OpenAI corrected content")
            }
        } else {
            stepErrors.add("[S2-OpenAI] ${factCheck.error}")
            Log.w("AiPipeline", "[S2] OpenAI unavailable: ${factCheck.error}")
        }

        val displayConfidence = when {
            rawConfidence > 0f   -> rawConfidence
            factCheck.isAccurate -> 0.65f
            else                 -> 0.30f
        }

        // ════════════════════════════════════════════════════════════════════
        // STAGE 3 — Humanize: Gemini primary → OpenAI fallback
        //
        // Goal: strip AI phrasing, vary rhythm, add contractions.
        // Facts are NEVER changed in this stage.
        // ════════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.HUMANIZING,
            "🧑 Stage 3/7 — Humanizing (Gemini)…"))

        val humanizePrompt = buildHumanizePrompt()
        var humanizeAttempted = false

        // — Attempt A: Gemini humanizer —
        try {
            val geminiHumanize = gemini.writeNewsArticle(
                rssTitle       = rssItem.title,
                rssDescription = humanizePrompt,
                rssFullContent = currentContent,
                category       = rssItem.feedCategory,
                model          = model,
                maxWords       = maxWords + 50
            )
            if (geminiHumanize.success && geminiHumanize.content.isNotBlank() &&
                geminiHumanize.content.length >= currentContent.length / 2) {
                currentContent   = geminiHumanize.content
                humanized        = true
                humanizeProvider = "gemini"
                humanizeAttempted = true
                Log.i("AiPipeline", "[S3] Gemini humanized: ${currentContent.length} chars")
            } else {
                stepErrors.add("[S3-Gemini-Humanize] ${geminiHumanize.error.ifBlank { "Empty" }}")
                Log.w("AiPipeline", "[S3] Gemini humanize failed: ${geminiHumanize.error}")
            }
        } catch (e: Exception) {
            stepErrors.add("[S3-Gemini-Humanize-ex] ${e.message}")
            Log.w("AiPipeline", "[S3] Gemini humanize exception: ${e.message}")
        }

        // — Attempt B: OpenAI fallback humanizer (if Gemini failed) —
        if (!humanizeAttempted) {
            onProgress?.invoke(PipelineProgress(Step.HUMANIZING_OPENAI_FALLBACK,
                "⚠️ Stage 3/7 — Gemini unavailable. OpenAI humanizing…"))
            try {
                val openAiHumanize = openAi.humanizeArticle(
                    title   = rssItem.title,
                    content = currentContent
                )
                if (openAiHumanize.success && openAiHumanize.humanizedContent.isNotBlank() &&
                    openAiHumanize.humanizedContent.length >= currentContent.length / 2) {
                    currentContent   = openAiHumanize.humanizedContent
                    humanized        = true
                    humanizeProvider = "openai"
                    Log.i("AiPipeline", "[S3] OpenAI fallback humanized: ${currentContent.length} chars")
                } else {
                    stepErrors.add("[S3-OpenAI-Humanize] ${openAiHumanize.error.ifBlank { "Empty" }}")
                    Log.w("AiPipeline", "[S3] OpenAI humanize also failed: ${openAiHumanize.error}")
                }
            } catch (e: Exception) {
                stepErrors.add("[S3-OpenAI-Humanize-ex] ${e.message}")
                Log.w("AiPipeline", "[S3] OpenAI humanize exception: ${e.message}")
            }
        }

        // ════════════════════════════════════════════════════════════════════
        // STAGE 4 — Grammar: Sarvam spelling + grammar correction
        // ════════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.SARVAM_CHECKING,
            "✏️ Stage 4/7 — Sarvam grammar check…"))

        val grammar = sarvam.checkGrammarAndSpelling(currentContent)
        if (grammar.success && grammar.correctedText.isNotBlank()) {
            currentContent = grammar.correctedText
            Log.i("AiPipeline", "[S4] Grammar: ${grammar.issuesFound.size} issues fixed")
        } else if (!grammar.success) {
            stepErrors.add("[S4-Sarvam] ${grammar.error}")
        }

        // ════════════════════════════════════════════════════════════════════
        // STAGE 5 — SEO: Gemini → Sarvam fallback
        // ════════════════════════════════════════════════════════════════════
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
            seoResult = sarvam.generateSeoData(
                title          = rssItem.title,
                articleContent = currentContent,
                category       = rssItem.feedCategory
            )
            if (seoResult.success) {
                sarvamSeo = true
                Log.i("AiPipeline", "[S5] Sarvam SEO fallback OK")
            } else {
                stepErrors.add("[S5-Sarvam-SEO] ${seoResult.error}")
            }
        }

        val tags            = if (seoResult.success) seoResult.tags.joinToString(", ") else ""
        val metaKeywords    = if (seoResult.success) seoResult.metaKeywords else ""
        val focusKeyphrase  = if (seoResult.success) seoResult.focusKeyphrase else ""
        val metaDescription = if (seoResult.success) seoResult.metaDescription else ""

        // ════════════════════════════════════════════════════════════════════
        // STAGE 6 — Title Rewrite: Gemini → Sarvam → OpenAI → original
        //
        // Each provider tries to produce a clean ≤70-char SEO headline.
        // Falls through to the next provider on failure.
        // ════════════════════════════════════════════════════════════════════
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
        Log.i("AiPipeline", "[S6] Final title ($titleProvider): $finalTitle")

        // ════════════════════════════════════════════════════════════════════
        // STAGE 7 — Image: download + watermark check + search fallback
        // ════════════════════════════════════════════════════════════════════
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

        onProgress?.invoke(PipelineProgress(Step.COMPLETE, "✅ Nexuzy AI Writer complete!"))

        // ════════════════════════════════════════════════════════════════════
        // BUILD FINAL ARTICLE
        // ════════════════════════════════════════════════════════════════════
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
            geminiChecked     = !sarvamWriter,
            openaiChecked     = factCheck.success,
            sarvamChecked     = grammar.success,
            factCheckPassed   = factCheckPassed,
            factCheckFeedback = factCheckFeedback,
            confidenceScore   = displayConfidence,
            aiProvider        = if (sarvamWriter) "sarvam" else "gemini"
        )

        PipelineResult(
            success            = true,
            article            = article,
            finalContent       = currentContent,
            title              = finalTitle,
            geminiDone         = !sarvamWriter,
            sarvamUsedAsWriter = sarvamWriter,
            sarvamUsedForSeo   = sarvamSeo,
            openAiDone         = factCheck.success,
            sarvamDone         = grammar.success,
            humanized          = humanized,
            humanizeProvider   = humanizeProvider,
            seoDone            = seoResult.success,
            titleProvider      = titleProvider,
            factCheckPassed    = factCheckPassed,
            factCheckFeedback  = factCheckFeedback,
            grammarIssues      = grammar.issuesFound,
            confidenceScore    = displayConfidence,
            stepErrors         = stepErrors
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Stage 3 — Humanizer system prompt.
     * Passed as rssDescription to writeNewsArticle(); the actual article
     * body goes into rssFullContent so the AI sees the full text to rewrite.
     *
     * CRITICAL: facts, dates, numbers, names, quotes are NEVER changed.
     */
    private fun buildHumanizePrompt(): String = """
        HUMANIZATION TASK — STRICT RULES:
        • Do NOT change any facts, numbers, names, dates, or direct quotes.
        • Do NOT add new information or remove key details.
        • Rewrite ONLY the style and tone to sound like a human journalist.

        Remove ALL of the following AI filler phrases wherever they appear:
        "notably", "in conclusion", "this underscores", "pivotal", "landscape",
        "delve", "shed light on", "it's worth noting", "it is important to note",
        "this highlights", "in summary", "furthermore", "moreover", "nevertheless",
        "in today's fast-paced world", "game-changer", "paradigm shift",
        "To be honest", "Arguably", "If you think about it", "For most people",
        "As an AI", "I cannot", "Certainly!", "Absolutely!", "Of course!"

        Style rules:
        - Vary sentence length: short punchy sentences mixed with longer ones
        - Use natural contractions: "it's", "don't", "isn't", "they're", "we've"
        - Active voice preferred over passive
        - Keep the same paragraph structure and order
        - Keep the same headline

        Output: ONLY the rewritten article. No preamble. No explanation.
        Start directly with the headline on the first line.
    """.trimIndent()

    /**
     * Stage 6 — Title Rewrite with 3-provider fallback chain:
     * Gemini → Sarvam → OpenAI → original title
     *
     * Each provider uses the same prompt and clean-up logic.
     * The first successful clean headline wins.
     */
    private suspend fun rewriteTitle(
        originalTitle: String,
        articleContent: String,
        focusKeyphrase: String,
        category: String,
        model: String,
        onFallback: (Step, String) -> Unit,
        onProviderUsed: (String) -> Unit
    ): String {

        val titlePrompt = buildTitlePrompt(
            originalTitle  = originalTitle,
            articleContent = articleContent,
            focusKeyphrase = focusKeyphrase,
            category       = category
        )

        // ── Provider 1: Gemini ────────────────────────────────────────────
        try {
            val r = gemini.writeNewsArticle(
                rssTitle       = originalTitle,
                rssDescription = titlePrompt,
                rssFullContent = articleContent.take(500),
                category       = category,
                model          = model,
                maxWords       = 20
            )
            val headline = extractHeadline(r.content)
            if (r.success && headline != null) {
                onProviderUsed("gemini")
                return headline
            }
            onFallback(Step.TITLE_SARVAM_FALLBACK,
                "[S6-Gemini-Title] ${r.error.ifBlank { "Empty" }} — trying Sarvam…")
        } catch (e: Exception) {
            onFallback(Step.TITLE_SARVAM_FALLBACK,
                "[S6-Gemini-Title-ex] ${e.message} — trying Sarvam…")
        }

        // ── Provider 2: Sarvam ────────────────────────────────────────────
        try {
            val r = sarvam.generateSeoData(
                title          = originalTitle,
                articleContent = articleContent.take(800),
                category       = category
            )
            // Sarvam SEO result carries a focusKeyphrase we can use as a fallback title
            val headline = if (r.success && r.focusKeyphrase.isNotBlank())
                buildSarvamTitleFallback(r.focusKeyphrase, category)
            else null

            if (headline != null) {
                onProviderUsed("sarvam")
                Log.i("AiPipeline", "[S6] Sarvam title fallback: $headline")
                return headline
            }
            onFallback(Step.TITLE_OPENAI_FALLBACK,
                "[S6-Sarvam-Title] No usable title — trying OpenAI…")
        } catch (e: Exception) {
            onFallback(Step.TITLE_OPENAI_FALLBACK,
                "[S6-Sarvam-Title-ex] ${e.message} — trying OpenAI…")
        }

        // ── Provider 3: OpenAI ────────────────────────────────────────────
        try {
            val r = openAi.rewriteTitle(
                originalTitle  = originalTitle,
                articleContent = articleContent.take(600),
                focusKeyphrase = focusKeyphrase,
                category       = category
            )
            val headline = if (r.success) extractHeadline(r.title) else null
            if (headline != null) {
                onProviderUsed("openai")
                Log.i("AiPipeline", "[S6] OpenAI title fallback: $headline")
                return headline
            }
            onFallback(Step.TITLE_REWRITING,
                "[S6-OpenAI-Title] ${r.error.ifBlank { "Empty" }} — using original")
        } catch (e: Exception) {
            onFallback(Step.TITLE_REWRITING,
                "[S6-OpenAI-Title-ex] ${e.message} — using original")
        }

        // ── Final fallback: original title ────────────────────────────────
        onProviderUsed("original")
        Log.w("AiPipeline", "[S6] All title providers failed — keeping original: $originalTitle")
        return originalTitle
    }

    /** Shared title prompt used by Gemini and OpenAI providers. */
    private fun buildTitlePrompt(
        originalTitle: String,
        articleContent: String,
        focusKeyphrase: String,
        category: String
    ): String = """
        Write ONE publish-ready news headline.
        Focus keyphrase : $focusKeyphrase
        Category        : $category
        Original title  : $originalTitle
        Article preview : ${articleContent.take(300)}

        Headline rules:
        - Under 70 characters
        - Factual, specific, direct — no clickbait
        - No "How", "Why", "Everything you need to know", "Ultimate guide"
        - No AI filler words
        - Include the focus keyphrase or a close variant if it fits naturally
        - No colons unless truly necessary

        Output ONLY the headline text. No quotes. No punctuation at end.
    """.trimIndent()

    /**
     * Sarvam doesn't have a dedicated title endpoint, so we build a
     * clean title from its focusKeyphrase result.
     * Only returns non-null if the phrase is a usable length.
     */
    private fun buildSarvamTitleFallback(focusKeyphrase: String, category: String): String? {
        val cleaned = focusKeyphrase.trim()
            .replaceFirstChar { it.uppercaseChar() }
        return if (cleaned.length in 15..80) cleaned else null
    }

    /** Extracts and cleans the first non-blank line from an AI response. */
    private fun extractHeadline(raw: String): String? {
        if (raw.isBlank()) return null
        val line = raw.lines()
            .firstOrNull { it.trim().isNotBlank() }
            ?.trim()
            ?.removeSurrounding("\"")
            ?.removeSurrounding("*")
            ?.take(120)
            ?: return null
        return if (line.length >= 10) line else null   // reject suspiciously short responses
    }
}
