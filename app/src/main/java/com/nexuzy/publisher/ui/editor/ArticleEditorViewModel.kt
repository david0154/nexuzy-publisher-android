package com.nexuzy.publisher.ui.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.nexuzy.publisher.ai.AiPipeline
import com.nexuzy.publisher.data.model.RssItem
import kotlinx.coroutines.launch

data class EditorPipelineState(
    val loading: Boolean = false,
    val statusText: String = "",
    val finalContent: String = "",
    val rewrittenTitle: String = "",
    val factFeedback: String = "",
    val geminiDone: Boolean = false,
    val openAiDone: Boolean = false,
    val sarvamDone: Boolean = false,
    val seoDone: Boolean = false,
    val tags: String = "",
    val metaKeywords: String = "",
    val focusKeyphrase: String = "",
    val metaDescription: String = "",
    val imageUrl: String = "",
    val imagePath: String = "",
    val factCheckPassed: Boolean = false,
    val confidenceScore: Float = 0f,
    val error: String = ""
)

class ArticleEditorViewModel(application: Application) : AndroidViewModel(application) {

    private val pipeline = AiPipeline(application.applicationContext)

    private val _pipelineState = MutableLiveData(EditorPipelineState(statusText = "Ready — tap Run AI Pipeline"))
    val pipelineState: LiveData<EditorPipelineState> = _pipelineState

    fun runPipeline(rssItem: RssItem) {
        _pipelineState.value = EditorPipelineState(loading = true, statusText = "📝 AI pipeline starting...")

        viewModelScope.launch {
            val result = pipeline.processRssItem(rssItem) { progress ->
                _pipelineState.postValue(
                    (_pipelineState.value ?: EditorPipelineState()).copy(
                        loading = true,
                        statusText = progress.message,
                        error = ""
                    )
                )
            }

            if (result.success) {
                val article = result.article
                _pipelineState.postValue(
                    EditorPipelineState(
                        loading        = false,
                        statusText     = buildStatusText(result),
                        finalContent   = result.finalContent,
                        rewrittenTitle = result.title,
                        factFeedback   = result.factCheckFeedback,
                        geminiDone     = false,
                        openAiDone     = result.openAiDone,
                        sarvamDone     = result.sarvamDone,
                        seoDone        = result.seoDone,
                        tags           = article?.tags ?: "",
                        metaKeywords   = article?.metaKeywords ?: "",
                        focusKeyphrase = article?.focusKeyphrase ?: "",
                        metaDescription = article?.metaDescription ?: "",
                        imageUrl       = article?.imageUrl ?: rssItem.imageUrl,
                        imagePath      = article?.imagePath ?: "",
                        factCheckPassed = result.factCheckPassed,
                        confidenceScore = result.confidenceScore,
                        error          = ""
                    )
                )
            } else {
                _pipelineState.postValue(
                    EditorPipelineState(
                        loading    = false,
                        statusText = "❌ Pipeline failed",
                        error      = result.error
                    )
                )
            }
        }
    }

    private fun buildStatusText(result: AiPipeline.PipelineResult): String {
        val badges = buildList {
            if (result.openAiDone)  add("✅ OpenAI")
            if (result.sarvamDone)  add("✅ Sarvam")
            if (result.seoDone)     add("✅ SEO")
            if (result.humanized)   add("✅ Humanized (${result.humanizeProvider})")
            if (result.factCheckPassed) add("✅ Fact-check passed")
        }
        val confidence = if (result.confidenceScore > 0f)
            " · Confidence: ${(result.confidenceScore * 100).toInt()}%" else ""
        return if (badges.isEmpty()) "✅ Pipeline complete$confidence"
        else "✅ ${badges.joinToString(" · ")}$confidence"
    }
}
