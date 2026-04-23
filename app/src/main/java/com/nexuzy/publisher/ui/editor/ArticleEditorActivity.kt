package com.nexuzy.publisher.ui.editor

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nexuzy.publisher.ai.AiPipeline
import com.nexuzy.publisher.data.db.AppDatabase
import com.nexuzy.publisher.data.model.Article
import com.nexuzy.publisher.data.model.WordPressSite
import com.nexuzy.publisher.data.model.RssItem
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import com.nexuzy.publisher.databinding.ActivityArticleEditorBinding
import com.nexuzy.publisher.network.WordPressApiClient
import kotlinx.coroutines.launch

class ArticleEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityArticleEditorBinding
    private lateinit var pipeline: AiPipeline
    private lateinit var apiKeyManager: ApiKeyManager
    private val wpClient = WordPressApiClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArticleEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "AI Article Editor"

        pipeline = AiPipeline(this)
        apiKeyManager = ApiKeyManager(this)

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
            saveDraft()
        }

        binding.btnPublishDraft.setOnClickListener {
            publishDraftToWordPress()
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

    private fun buildArticleForSave(status: String = "draft"): Article {
        return Article(
            title = binding.etArticleTitle.text.toString().trim(),
            summary = binding.etArticleSummary.text.toString().trim(),
            content = binding.etArticleContent.text.toString().trim(),
            status = status,
            geminiChecked = binding.chipGemini.isChecked,
            openaiChecked = binding.chipOpenai.isChecked,
            sarvamChecked = binding.chipSarvam.isChecked
        )
    }

    private fun saveDraft() {
        val title = binding.etArticleTitle.text.toString().trim()
        val content = binding.etArticleContent.text.toString().trim()
        if (title.isBlank()) {
            binding.etArticleTitle.error = "Please enter a title"
            return
        }
        if (content.isBlank()) {
            binding.etArticleContent.error = "Please generate or enter article content"
            return
        }

        lifecycleScope.launch {
            val article = buildArticleForSave(status = "draft")
            AppDatabase.getDatabase(this@ArticleEditorActivity).articleDao().insert(article)
            runOnUiThread {
                Toast.makeText(this@ArticleEditorActivity, "Saved as draft", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun publishDraftToWordPress() {
        val title = binding.etArticleTitle.text.toString().trim()
        val content = binding.etArticleContent.text.toString().trim()
        if (title.isBlank() || content.isBlank()) {
            Toast.makeText(this, "Title and content are required before publish", Toast.LENGTH_SHORT).show()
            return
        }

        val siteUrl = apiKeyManager.getWordPressSiteUrl().trim()
        val username = apiKeyManager.getWordPressUsername().trim()
        val appPassword = apiKeyManager.getWordPressPassword().trim()
        if (siteUrl.isBlank() || username.isBlank() || appPassword.isBlank()) {
            Toast.makeText(this, "Configure WordPress credentials in Settings first", Toast.LENGTH_LONG).show()
            return
        }

        val site = WordPressSite(
            name = "Default",
            siteUrl = siteUrl,
            username = username,
            appPassword = appPassword
        )
        val article = buildArticleForSave(status = "draft")
        val adsCode = apiKeyManager.getWordPressAdsCode()

        binding.progressGroup.visibility = View.VISIBLE
        binding.tvPipelineStatus.text = "Publishing draft to WordPress…"
        binding.btnPublishDraft.isEnabled = false

        lifecycleScope.launch {
            val result = wpClient.pushDraft(site, article, adsCode)
            runOnUiThread {
                binding.progressGroup.visibility = View.GONE
                binding.btnPublishDraft.isEnabled = true
                if (result.success) {
                    binding.tvPipelineStatus.text = "✅ Draft published (Post ID: ${result.postId})"
                    Toast.makeText(this@ArticleEditorActivity, "Draft pushed to WordPress", Toast.LENGTH_LONG).show()
                } else {
                    binding.tvPipelineStatus.text = "❌ Publish failed"
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
