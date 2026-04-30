package com.nexuzy.publisher.ui.editor

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.nexuzy.publisher.data.db.AppDatabase
import com.nexuzy.publisher.data.model.Article
import com.nexuzy.publisher.data.model.WordPressSite
import com.nexuzy.publisher.data.model.RssItem
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import com.nexuzy.publisher.databinding.ActivityArticleEditorBinding
import com.nexuzy.publisher.network.WordPressApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArticleEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityArticleEditorBinding
    private lateinit var apiKeyManager: ApiKeyManager
    private lateinit var wpClient: WordPressApiClient
    private val viewModel: ArticleEditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArticleEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "AI Article Editor"

        apiKeyManager = ApiKeyManager(this)
        wpClient = WordPressApiClient()
        observePipelineState()
        setupBackPressConfirmation()

        // ── Accept full RssItem Parcelable OR individual string extras (backwards compat) ──
        @Suppress("DEPRECATION")
        val rssItem: RssItem? = intent.getParcelableExtra("rss_item")

        val rssTitle = rssItem?.title ?: intent.getStringExtra("rss_title") ?: ""
        val rssDescription = rssItem?.description ?: intent.getStringExtra("rss_description") ?: ""
        val rssLink = rssItem?.link ?: intent.getStringExtra("rss_link") ?: ""
        val rssCategory = rssItem?.feedCategory ?: intent.getStringExtra("rss_category") ?: "General"

        if (rssTitle.isNotBlank()) {
            binding.etArticleTitle.setText(rssTitle)
        }
        if (rssDescription.isNotBlank()) {
            binding.etArticleSummary.setText(rssDescription)
        }

        binding.btnRunAiPipeline.setOnClickListener {
            val title = binding.etArticleTitle.text.toString().trim()
            val description = binding.etArticleSummary.text.toString().trim()
            if (title.isBlank()) {
                binding.etArticleTitle.error = "Please enter a title"
                return@setOnClickListener
            }

            val item = RssItem(
                title = title,
                description = description.ifBlank { rssDescription },
                link = rssLink,
                pubDate = "",
                feedCategory = rssCategory
            )
            viewModel.runPipeline(item)
        }

        binding.btnSaveDraft.setOnClickListener { saveDraft() }
        binding.btnPublishDraft.setOnClickListener { publishDraftToWordPress() }
    }

    private fun setupBackPressConfirmation() {
        onBackPressedDispatcher.addCallback(this) {
            if (!hasUnsavedChanges()) {
                finish()
                return@addCallback
            }
            AlertDialog.Builder(this@ArticleEditorActivity)
                .setTitle("Discard changes?")
                .setMessage("You have unsaved edits. Save as draft before leaving?")
                .setPositiveButton("Save Draft") { _, _ -> saveDraft { finish() } }
                .setNegativeButton("Discard") { _, _ -> finish() }
                .setNeutralButton("Cancel", null)
                .show()
        }
    }

    private fun hasUnsavedChanges(): Boolean =
        binding.etArticleTitle.text?.isNotBlank() == true ||
            binding.etArticleSummary.text?.isNotBlank() == true ||
            binding.etArticleContent.text?.isNotBlank() == true

    private fun observePipelineState() {
        viewModel.pipelineState.observe(this, Observer { state ->
            binding.progressGroup.visibility = if (state.loading) View.VISIBLE else View.GONE
            binding.btnRunAiPipeline.isEnabled = !state.loading
            binding.tvPipelineStatus.text = state.statusText

            if (state.finalContent.isNotBlank()) {
                binding.etArticleContent.setText(state.finalContent)
            }

            binding.chipGemini.isChecked = state.geminiDone
            binding.chipOpenai.isChecked = state.openAiDone
            binding.chipSarvam.isChecked = state.sarvamDone

            if (state.factFeedback.isNotBlank()) {
                binding.tvFactFeedback.text = state.factFeedback
                binding.tvFactFeedback.visibility = View.VISIBLE
            } else {
                binding.tvFactFeedback.visibility = View.GONE
            }

            if (state.error.isNotBlank()) {
                Toast.makeText(this, state.error, Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun buildArticleForSave(status: String = "draft"): Article = Article(
        title = binding.etArticleTitle.text.toString().trim(),
        summary = binding.etArticleSummary.text.toString().trim(),
        content = binding.etArticleContent.text.toString().trim(),
        status = status,
        geminiChecked = binding.chipGemini.isChecked,
        openaiChecked = binding.chipOpenai.isChecked,
        sarvamChecked = binding.chipSarvam.isChecked
    )

    private fun saveDraft(onComplete: (() -> Unit)? = null) {
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
            val article = buildArticleForSave("draft")
            withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(this@ArticleEditorActivity).articleDao().insert(article)
            }
            Toast.makeText(this@ArticleEditorActivity, "\u2705 Saved as draft", Toast.LENGTH_SHORT).show()
            onComplete?.invoke()
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
        val site = WordPressSite(name = "Default", siteUrl = siteUrl, username = username, appPassword = appPassword)
        val article = buildArticleForSave("draft")
        val adsCode = apiKeyManager.getWordPressAdsCode()

        binding.progressGroup.visibility = View.VISIBLE
        binding.tvPipelineStatus.text = "Publishing draft to WordPress…"
        binding.btnPublishDraft.isEnabled = false

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { wpClient.pushDraft(site, article, adsCode) }
            binding.progressGroup.visibility = View.GONE
            binding.btnPublishDraft.isEnabled = true
            if (result.success) {
                binding.tvPipelineStatus.text = "\u2705 Draft published (Post ID: ${result.postId})"
                Toast.makeText(this@ArticleEditorActivity, "Draft pushed to WordPress", Toast.LENGTH_LONG).show()
            } else {
                binding.tvPipelineStatus.text = "\u274C Publish failed"
                Toast.makeText(this@ArticleEditorActivity, result.error, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
