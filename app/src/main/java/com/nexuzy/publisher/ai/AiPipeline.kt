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
 * ║          NEXUZY AI WRITER — 7-STAGE PIPELINE (v6)                   ║
 * ╠══════════════════════════════════════════════════════════════════════╣
 * ║  STAGE 0 │ WEB CONTEXT   │ DuckDuckGo live search for fresh context ║
 * ║  STAGE 1 │ WRITE         │ Gemini journalist prompt → Sarvam backup  ║
 * ║  STAGE 2 │ FACT-CHECK    │ OpenAI verifies facts + corrects errors   ║
 * ║  STAGE 3 │ HUMANIZE      │ Gemini humanizer pass → strip AI phrases  ║
 * ║  STAGE 4 │ GRAMMAR       │ Sarvam grammar + spelling correction      ║
 * ║  STAGE 5 │ SEO           │ Gemini SEO → Sarvam fallback              ║
 * ║  STAGE 6 │ TITLE REWRITE │ Gemini rewrites headline from SEO data    ║
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
        val seoDone: Boolean = false,
        val sarvamUsedForSeo: Boolean = false,
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
        HUMANIZING,
        SARVAM_CHECKING,
        SEO_GENERATING, SEO_SARVAM_FALLBACK,
        TITLE_REWRITING,
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

        val stepErrors = mutableListOf<String>()
        var sarvamWriter = false
        var sarvamSeo    = false
        var humanized    = false

        // ════════════════════════════════════════════════════════════════════
        // STAGE 0 — DuckDuckGo web context (free, no API key needed)
        // ════════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.WEB_SEARCHING,
            "🌐 Stage 0/7 — Fetching live web context…"))
        var liveWebContext = ""
        try {
            val searchQuery = "${rssItem.title} ${rssItem.feedCategory} latest news"
            val ddg = ddgSearch.search(searchQuery, maxChars = 1500)
            if (ddg.success && ddg.summary.isNotBlank()) {
                liveWebContext = ddg.summary
                Log.i("AiPipeline", "[S0] DDG context: ${liveWebContext.length} chars")
            } else {
                stepErrors.add("[S0-DDG] ${ddg.error}")
                Log.w("AiPipeline", "[S0] DDG returned nothing: ${ddg.error}")
            }
        } catch (e: Exception) {
            stepErrors.add("[S0-DDG-ex] ${e.message}")
            Log.w("AiPipeline", "[S0] DDG exception: ${e.message}")
        }

        // ════════════════════════════════════════════════════════════════════
        // STAGE 1 — Write full article: Gemini primary → Sarvam backup
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
            // Sarvam backup writer
            Log.w("AiPipeline", "[S1] Gemini failed: ${geminiWrite.error}. Trying Sarvam backup…")
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
        // STAGE 2 — OpenAI fact-check & correction
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
            // Apply OpenAI corrections if content improved
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
            rawConfidence > 0f    -> rawConfidence
            factCheck.isAccurate  -> 0.65f
            else                  -> 0.30f
        }

        // ════════════════════════════════════════════════════════════════════
        // STAGE 3 — Humanize: Gemini second-pass to strip AI phrasing
        // Prompt is specifically for humanization, not rewriting facts.
        // ════════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.HUMANIZING,
            "🧑 Stage 3/7 — Humanizing article…"))
        try {
            val humanizePrompt = buildHumanizePrompt(currentContent)
            val humanizeResult = gemini.writeNewsArticle(
                rssTitle       = rssItem.title,
                rssDescription = humanizePrompt,  // humanizer content goes here as context
                rssFullContent = currentContent,  // full article to humanize
                category       = rssItem.feedCategory,
                model          = model,
                maxWords       = maxWords + 50    // allow slight expansion for natural flow
            )
            if (humanizeResult.success && humanizeResult.content.isNotBlank() &&
                humanizeResult.content.length >= currentContent.length / 2) {
                currentContent = humanizeResult.content
                humanized = true
                Log.i("AiPipeline", "[S3] Humanized: ${currentContent.length} chars")
            } else {
                // Non-fatal: keep previous content, just log it
                stepErrors.add("[S3-Humanize] ${humanizeResult.error.ifBlank { "Empty response" }}")
                Log.w("AiPipeline", "[S3] Humanize pass skipped: ${humanizeResult.error}")
            }
        } catch (e: Exception) {
            stepErrors.add("[S3-Humanize-ex] ${e.message}")
            Log.w("AiPipeline", "[S3] Humanize exception: ${e.message}")
        }

        // ════════════════════════════════════════════════════════════════════
        // STAGE 4 — Sarvam grammar & spelling correction
        // ════════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.SARVAM_CHECKING,
            "✏️ Stage 4/7 — Sarvam grammar check…"))

        val grammar = sarvam.checkGrammarAndSpelling(currentContent)
        if (grammar.success && grammar.correctedText.isNotBlank()) {
            currentContent = grammar.correctedText
            Log.i("AiPipeline", "[S4] Sarvam grammar corrected: ${grammar.issuesFound.size} issues fixed")
        } else if (!grammar.success) {
            stepErrors.add("[S4-Sarvam] ${grammar.error}")
            Log.w("AiPipeline", "[S4] Sarvam grammar unavailable: ${grammar.error}")
        }

        // ════════════════════════════════════════════════════════════════════
        // STAGE 5 — SEO: Gemini generates tags/keywords → Sarvam fallback
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
            Log.w("AiPipeline", "[S5] Gemini SEO failed: ${seoResult.error}. Trying Sarvam…")
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
                Log.i("AiPipeline", "[S5] Sarvam SEO fallback succeeded")
            } else {
                stepErrors.add("[S5-Sarvam-SEO] ${seoResult.error}")
                Log.w("AiPipeline", "[S5] Sarvam SEO also failed: ${seoResult.error}")
            }
        }

        val tags            = if (seoResult.success) seoResult.tags.joinToString(", ") else ""
        val metaKeywords    = if (seoResult.success) seoResult.metaKeywords else ""
        val focusKeyphrase  = if (seoResult.success) seoResult.focusKeyphrase else ""
        val metaDescription = if (seoResult.success) seoResult.metaDescription else ""

        // ════════════════════════════════════════════════════════════════════
        // STAGE 6 — Title rewrite: clean SEO headline from Gemini
        // The old code was concatenating focusKeyphrase + original title.
        // Now we ask Gemini to write a proper headline using the article + SEO data.
        // ════════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.TITLE_REWRITING,
            "📝 Stage 6/7 — Rewriting headline…"))
        val finalTitle = rewriteTitle(
            originalTitle  = rssItem.title,
            articleContent = currentContent,
            focusKeyphrase = focusKeyphrase,
            category       = rssItem.feedCategory,
            model          = model
        )
        Log.i("AiPipeline", "[S6] Final title: $finalTitle")

        // ════════════════════════════════════════════════════════════════════
        // STAGE 7 — Image: watermark check + Google search fallback
        // ════════════════════════════════════════════════════════════════════
        onProgress?.invoke(PipelineProgress(Step.IMAGE_DOWNLOADING,
            "🖼️ Stage 7/7 — Downloading article image…"))
        var localImagePath = rssItem.localImagePath

        if (localImagePath.isBlank() && rssItem.imageUrl.isNotBlank()) {
            val hasSearchKeys = keyManager.getGoogleSearchApiKey().isNotBlank() &&
                                keyManager.getGoogleSearchCseId().isNotBlank()
            if (hasSearchKeys) {
                onProgress?.invoke(PipelineProgress(Step.IMAGE_WATERMARK_SEARCH,
                    "🔍 Stage 7/7 — Checking image watermark…"))
            }
            localImagePath = imageDownloader.downloadImage(
                url           = rssItem.imageUrl,
                titleHint     = finalTitle,
                searchQuery   = finalTitle,
                apiKeyManager = keyManager
            )
            if (localImagePath.isBlank()) {
                Log.w("AiPipeline", "[S7] Image download failed — WP will upload from URL at push time")
            }
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
            seoDone            = seoResult.success,
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
     * Stage 3: Humanizer prompt.
     * Tells Gemini to rewrite only the style/tone of the article,
     * not the facts. No structural changes, no new information.
     */
    private fun buildHumanizePrompt(articleContent: String): String {
        // This is passed as rssDescription to the writeNewsArticle() call.
        // The actual article content goes into rssFullContent.
        return """
            HUMANIZATION TASK — Do NOT change any facts, quotes, numbers, names, or dates.
            Rewrite the style and tone of the article below to sound like a real human journalist:
            - Remove any remaining AI filler phrases ("notably", "in conclusion", "this underscores", "pivotal", "landscape", "delve", "shed light on")
            - Remove phrases like "To be honest", "Arguably", "If you think about it", "For most people"
            - Vary sentence lengths naturally — mix short punchy sentences with longer explanatory ones
            - Make contractions where natural ("it's", "don't", "isn't")
            - Keep the same paragraph structure
            - Output ONLY the rewritten article. No explanation. No preamble. Start directly with the headline.
        """.trimIndent()
    }

    /**
     * Stage 6: Title rewrite.
     * Generates a clean, SEO-optimised headline. Does NOT concatenate
     * focusKeyphrase + original title (which was the old broken approach).
     */
    private suspend fun rewriteTitle(
        originalTitle: String,
        articleContent: String,
        focusKeyphrase: String,
        category: String,
        model: String
    ): String {
        return try {
            val titlePrompt = """
                Write a single, clean, publish-ready news headline for the article below.
                Focus keyphrase: $focusKeyphrase
                Category: $category
                Original title: $originalTitle
                Article (first 300 chars): ${articleContent.take(300)}

                Rules for the headline:
                - Under 70 characters
                - No colons unless necessary
                - No "How", "Why", "Everything you need to know"
                - No AI filler words
                - Factual, specific, direct
                - Must include the focus keyphrase or its close variant if it fits naturally
                - Output ONLY the headline text. Nothing else. No quotes around it.
            """.trimIndent()

            val result = gemini.writeNewsArticle(
                rssTitle       = originalTitle,
                rssDescription = titlePrompt,
                rssFullContent = articleContent.take(500),
                category       = category,
                model          = model,
                maxWords       = 20  // headline only
            )

            if (result.success && result.content.isNotBlank()) {
                // Clean the returned text — take only the first line
                val headline = result.content
                    .lines()
                    .firstOrNull { it.trim().isNotBlank() }
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?.take(120)  // hard cap
                    ?: originalTitle
                Log.i("AiPipeline", "[S6] Rewrote title: $headline")
                headline
            } else {
                Log.w("AiPipeline", "[S6] Title rewrite failed: ${result.error}. Using original.")
                originalTitle
            }
        } catch (e: Exception) {
            Log.w("AiPipeline", "[S6] Title rewrite exception: ${e.message}. Using original.")
            originalTitle
        }
    }
}
