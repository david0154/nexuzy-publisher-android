package com.nexuzy.publisher.ai

import android.content.Context
import android.util.Log
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import com.nexuzy.publisher.data.model.RssItem
import com.nexuzy.publisher.network.GeminiApiClient
import com.nexuzy.publisher.network.OpenAiApiClient
import com.nexuzy.publisher.network.SarvamApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI Pipeline Orchestrator
 *
 * Flow:
 *   1. Gemini writes the news article (uses up to 3 Gemini keys with rotation)
 *   2. OpenAI fact-checks the article (uses up to 3 OpenAI keys with rotation)
 *   3. Sarvam AI checks grammar & spelling
 *
 * Gemini and OpenAI work INDEPENDENTLY — they never replace each other.
 * Gemini = Writer. OpenAI = Fact Checker. Sarvam = Grammar Checker.
 */
class AiPipeline(context: Context) {

    private val keyManager = ApiKeyManager(context)
    private val gemini = GeminiApiClient(keyManager)
    private val openAi = OpenAiApiClient(keyManager)
    private val sarvam = SarvamApiClient(keyManager)

    data class PipelineResult(
        val success: Boolean,
        val finalContent: String,
        val title: String,
        val geminiDone: Boolean = false,
        val openAiDone: Boolean = false,
        val sarvamDone: Boolean = false,
        val factCheckPassed: Boolean = false,
        val factCheckFeedback: String = "",
        val grammarIssues: List<String> = emptyList(),
        val confidenceScore: Float = 0f,
        val error: String = "",
        val stepErrors: List<String> = emptyList()
    )

    data class PipelineProgress(
        val step: Step,
        val message: String
    )

    enum class Step { GEMINI_WRITING, OPENAI_CHECKING, SARVAM_CHECKING, COMPLETE, ERROR }

    /**
     * Full pipeline: write → fact-check → grammar check
     */
    suspend fun processRssItem(
        rssItem: RssItem,
        model: String = "gemini-1.5-flash",
        maxWords: Int = 800,
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
                finalContent = "",
                title = rssItem.title,
                error = "Gemini writing failed: ${geminiResult.error}"
            )
        }

        var currentContent = geminiResult.content
        Log.d("AiPipeline", "Gemini wrote ${currentContent.length} chars (key #${geminiResult.keyUsed})")

        // ─── STEP 2: OpenAI Fact Checks ───
        onProgress?.invoke(PipelineProgress(Step.OPENAI_CHECKING, "🔍 OpenAI is verifying facts…"))

        val openAiResult = openAi.factCheckArticle(rssItem.title, currentContent)
        var factCheckPassed = false
        var factCheckFeedback = ""
        var confidenceScore = 0f

        if (openAiResult.success) {
            factCheckPassed = openAiResult.isAccurate
            factCheckFeedback = openAiResult.feedback
            confidenceScore = openAiResult.confidenceScore
            // If OpenAI found issues and provided corrections, use them
            if (!openAiResult.isAccurate && openAiResult.correctedContent.isNotBlank()) {
                currentContent = openAiResult.correctedContent
                Log.d("AiPipeline", "OpenAI corrected content (key #${openAiResult.keyUsed})")
            }
        } else {
            stepErrors.add("OpenAI: ${openAiResult.error}")
            Log.w("AiPipeline", "OpenAI fact check failed: ${openAiResult.error}")
        }

        // ─── STEP 3: Sarvam Grammar Check ───
        onProgress?.invoke(PipelineProgress(Step.SARVAM_CHECKING, "✏️ Sarvam AI checking grammar & spelling…"))

        val sarvamResult = sarvam.checkGrammarAndSpelling(currentContent)
        if (sarvamResult.success && sarvamResult.correctedText.isNotBlank()) {
            currentContent = sarvamResult.correctedText
        } else if (!sarvamResult.success) {
            stepErrors.add("Sarvam: ${sarvamResult.error}")
        }

        onProgress?.invoke(PipelineProgress(Step.COMPLETE, "✅ Processing complete!"))

        PipelineResult(
            success = true,
            finalContent = currentContent,
            title = rssItem.title,
            geminiDone = true,
            openAiDone = openAiResult.success,
            sarvamDone = sarvamResult.success,
            factCheckPassed = factCheckPassed,
            factCheckFeedback = factCheckFeedback,
            grammarIssues = sarvamResult.issuesFound,
            confidenceScore = confidenceScore,
            stepErrors = stepErrors
        )
    }
}
