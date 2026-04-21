package com.nexuzy.publisher.ui.editor

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nexuzy.publisher.R
import com.nexuzy.publisher.ai.AiPipeline
import com.nexuzy.publisher.data.model.RssItem
import com.nexuzy.publisher.databinding.ActivityArticleEditorBinding
import kotlinx.coroutines.launch

class ArticleEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityArticleEditorBinding
    private lateinit var pipeline: AiPipeline

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArticleEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "AI Article Editor"

        pipeline = AiPipeline(this)

        // Pre-fill from intent if launched from RSS item
        val rssTitle = intent.getStringExtra("rss_title") ?: ""
        val rssDescription = intent.getStringExtra("rss_description") ?: ""
        val rssLink = intent.getStringExtra("rss_link") ?: ""
        val rssCategory = intent.getStringExtra("rss_category") ?: "General"

        if (rssTitle.isNotBlank()) {
            binding.etArticleTitle.setText(rssTitle)
        }

        binding.btnRunAiPipeline.setOnClickListener {
            val title = binding.etArticleTitle.text.toString().trim()
            val description = binding.etArticleSummary.text.toString().trim()
            if (title.isBlank()) {
                binding.etArticleTitle.error = "Please enter a title"
                return@setOnClickListener
            }

            val rssItem = RssItem(
                title = title,
                description = description.ifBlank { rssDescription },
                link = rssLink,
                pubDate = "",
                feedCategory = rssCategory
            )
            runAiPipeline(rssItem)
        }

        binding.btnSaveDraft.setOnClickListener {
            // TODO: Save to Room DB
            Toast.makeText(this, "Saved as draft", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runAiPipeline(rssItem: RssItem) {
        binding.progressGroup.visibility = View.VISIBLE
        binding.btnRunAiPipeline.isEnabled = false
        binding.tvPipelineStatus.text = "📝 Gemini is writing…"

        lifecycleScope.launch {
            val result = pipeline.processRssItem(rssItem) { progress ->
                runOnUiThread {
                    binding.tvPipelineStatus.text = progress.message
                }
            }

            runOnUiThread {
                binding.progressGroup.visibility = View.GONE
                binding.btnRunAiPipeline.isEnabled = true

                if (result.success) {
                    binding.etArticleContent.setText(result.finalContent)
                    binding.tvPipelineStatus.text = "✅ Done! Fact score: ${result.confidenceScore.toInt()}%"

                    // Show AI check badges
                    binding.chipGemini.isChecked = result.geminiDone
                    binding.chipOpenai.isChecked = result.openAiDone
                    binding.chipSarvam.isChecked = result.sarvamDone

                    if (result.factCheckFeedback.isNotBlank()) {
                        binding.tvFactFeedback.text = result.factCheckFeedback
                        binding.tvFactFeedback.visibility = View.VISIBLE
                    }
                } else {
                    binding.tvPipelineStatus.text = "❌ ${result.error}"
                    Toast.makeText(this@ArticleEditorActivity, result.error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
