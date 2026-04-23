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
    val factFeedback: String = "",
    val geminiDone: Boolean = false,
    val openAiDone: Boolean = false,
    val sarvamDone: Boolean = false,
    val error: String = ""
)

class ArticleEditorViewModel(application: Application) : AndroidViewModel(application) {

    private val pipeline = AiPipeline(application.applicationContext)

    private val _pipelineState = MutableLiveData(EditorPipelineState(statusText = "Status"))
    val pipelineState: LiveData<EditorPipelineState> = _pipelineState

    fun runPipeline(rssItem: RssItem) {
        _pipelineState.value = EditorPipelineState(loading = true, statusText = "📝 Gemini is writing…")

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
                _pipelineState.postValue(
                    EditorPipelineState(
                        loading = false,
                        statusText = "✅ Done! Fact score: ${result.confidenceScore.toInt()}%",
                        finalContent = result.finalContent,
                        factFeedback = result.factCheckFeedback,
                        geminiDone = result.geminiDone,
                        openAiDone = result.openAiDone,
                        sarvamDone = result.sarvamDone,
                        error = ""
                    )
                )
            } else {
                _pipelineState.postValue(
                    EditorPipelineState(
                        loading = false,
                        statusText = "❌ ${result.error}",
                        error = result.error
                    )
                )
            }
        }
    }
}
