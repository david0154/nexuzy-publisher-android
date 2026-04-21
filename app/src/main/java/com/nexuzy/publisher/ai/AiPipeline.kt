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
 * AI Pipeline Orchestrator — matches Python desktop app flow.
 *
 * Flow:
 *   1. Gemini  → writes news article  (3-key rotation)
 *   2. OpenAI  → fact-checks against current knowledge  (3-key rotation)
 *   3. Sarvam  → grammar & spelling correction  (1 key, non-fatal)
 *   4. Gemini  → generates SEO data (tags, keywords, focus keyphrase, meta description)
 *   5. Image   → downloads RSS image to local cache
 *
 * Rules:
 *   - Gemini and OpenAI NEVER replace each other. Gemini = Writer. OpenAI = Fact Checker.
 *   - Steps 3, 4, 5 are non-fatal — pipeline succeeds even if they fail.
 *   - Only Step 1 failure aborts the pipeline.
 */
class AiPipeline(private val context: Context) {

    private val keyManager = ApiKeyManager(context)
    private val gemini = GeminiApiClient(keyManager)
    private val openAi = OpenAiApiClient(keyManager)
    private val sarvam = SarvamApiClient(keyManager)
    private val imageDownloader = ImageDownloader(context)

    data class PipelineResult(
        val success: Boolean,
        val article: Article? = null,           // fully populated Article ready to save / publish
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

    suspend fun processRssItem(
        rssItem: RssItem,
        model: String = "gemini-1.5-flash",
        maxWords: Int = 800,
        wordpressSiteId: Long = 0,
        onProgress: ((PipelineProgress) -> Unit)? = null
    ): PipelineResult = withContext(Dispatchers.IO) {

        val stepErrors = mutableListOf<String>()

        // ─── STEP 1: Gemini Writes Article ───
        onProgress?.invoke(PipelineProgress(Step.GEMINI_WRITING, "📝 Gemini is writing the article…"))
        val geminiResult = gemini.writeNewsArticle(
            rssTitle = rssItem.title,
            rssDescription = rssItem.description,
            category = rssItem.feedCategory,
            model = model,
            maxWords = maxWords
        )
        if (!geminiResult.success) {
            Log.e("AiPipeline", "Gemini failed: ${geminiResult.error}")
            return@withContext PipelineResult(
                success = false,
                title = rssItem.title,
                error = "Gemini writing failed: ${geminiResult.error}"
            )
        }
        var currentContent = geminiResult.content
        Log.d("AiPipeline", "Gemini wrote ${currentContent.length} chars (key #${geminiResult.keyUsed})")

        // ─── STEP 2: OpenAI Fact Checks ───
        onProgress?.invoke(PipelineProgress(Step.OPENAI_CHECKING, "🔍 OpenAI is verifying facts against current news…"))
        val openAiResult = openAi.factCheckArticle(rssItem.title, currentContent)
        var factCheckPassed = false
        var factCheckFeedback = ""
        var confidenceScore = 0f
        if (openAiResult.success) {
            factCheckPassed = openAiResult.isAccurate
            factCheckFeedback = openAiResult.feedback
            confidenceScore = openAiResult.confidenceScore
            if (!openAiResult.isAccurate && openAiResult.correctedContent.isNotBlank()) {
                currentContent = openAiResult.correctedContent
                Log.d("AiPipeline", "OpenAI corrected content (key #${openAiResult.keyUsed})")
            }
        } else {
            stepErrors.add("OpenAI: ${openAiResult.error}")
            Log.w("AiPipeline", "OpenAI fact check skipped: ${openAiResult.error}")
        }

        // ─── STEP 3: Sarvam Grammar & Spelling ───
        onProgress?.invoke(PipelineProgress(Step.SARVAM_CHECKING, "✏️ Sarvam AI checking grammar & spelling…"))
        val sarvamResult = sarvam.checkGrammarAndSpelling(currentContent)
        if (sarvamResult.success && sarvamResult.correctedText.isNotBlank()) {
            currentContent = sarvamResult.correctedText
        } else if (!sarvamResult.success) {
            stepErrors.add("Sarvam: ${sarvamResult.error}")
        }

        // ─── STEP 4: Gemini SEO Generation ───
        onProgress?.invoke(PipelineProgress(Step.SEO_GENERATING, "🔎 Generating SEO tags, keywords & meta…"))
        val seoResult = gemini.generateSeoData(
            title = rssItem.title,
            articleContent = currentContent,
            category = rssItem.feedCategory,
            model = model
        )
        var tags = ""
        var metaKeywords = ""
        var focusKeyphrase = ""
        var metaDescription = ""
        var seoDone = false
        if (seoResult.success) {
            tags = seoResult.tags.joinToString(", ")
            metaKeywords = seoResult.metaKeywords
            focusKeyphrase = seoResult.focusKeyphrase
            metaDescription = seoResult.metaDescription
            seoDone = true
            Log.d("AiPipeline", "SEO generated: keyphrase='$focusKeyphrase' tags=$tags")
        } else {
            stepErrors.add("SEO: ${seoResult.error}")
            Log.w("AiPipeline", "SEO generation failed: ${seoResult.error}")
        }

        // ─── STEP 5: Download RSS Image ───
        var localImagePath = ""
        if (rssItem.imageUrl.isNotBlank()) {
            onProgress?.invoke(PipelineProgress(Step.IMAGE_DOWNLOADING, "🖼️ Downloading article image…"))
            localImagePath = imageDownloader.downloadImage(rssItem.imageUrl, rssItem.title)
            if (localImagePath.isBlank()) {
                stepErrors.add("Image download failed for ${rssItem.imageUrl}")
            } else {
                Log.d("AiPipeline", "Image downloaded to $localImagePath")
            }
        }

        onProgress?.invoke(PipelineProgress(Step.COMPLETE, "✅ All steps complete!"))

        // Build fully-populated Article
        val article = Article(
            title = rssItem.title,
            content = currentContent,
            summary = metaDescription.ifBlank { currentContent.take(160) },
            category = rssItem.feedCategory,
            tags = tags,
            metaKeywords = metaKeywords,
            focusKeyphrase = focusKeyphrase,
            metaDescription = metaDescription,
            sourceUrl = rssItem.link,
            sourceName = rssItem.feedName,
            imageUrl = rssItem.imageUrl,
            imagePath = localImagePath,
            status = "ready",
            wordpressSiteId = wordpressSiteId,
            geminiChecked = true,
            openaiChecked = openAiResult.success,
            sarvamChecked = sarvamResult.success,
            factCheckPassed = factCheckPassed,
            factCheckFeedback = factCheckFeedback,
            confidenceScore = confidenceScore,
            aiProvider = "gemini"
        )

        PipelineResult(
            success = true,
            article = article,
            finalContent = currentContent,
            title = rssItem.title,
            geminiDone = true,
            openAiDone = openAiResult.success,
            sarvamDone = sarvamResult.success,
            seoDone = seoDone,
            factCheckPassed = factCheckPassed,
            factCheckFeedback = factCheckFeedback,
            grammarIssues = sarvamResult.issuesFound,
            confidenceScore = confidenceScore,
            stepErrors = stepErrors
        )
    }
}
