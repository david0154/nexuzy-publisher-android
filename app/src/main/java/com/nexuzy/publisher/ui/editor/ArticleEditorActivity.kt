package com.nexuzy.publisher.ui.editor

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
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

    // ── Class-level RSS source fields so buildArticleForSave() can always access them ──
    private var rssLink: String = ""
    private var rssCategory: String = "General"
    private var rssImageUrl: String = ""   // Remote image URL from RSS feed
    private var rssFeedName: String = ""

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

        val rssTitle       = rssItem?.title        ?: intent.getStringExtra("rss_title")       ?: ""
        val rssDescription = rssItem?.description  ?: intent.getStringExtra("rss_description") ?: ""
        rssLink            = rssItem?.link         ?: intent.getStringExtra("rss_link")        ?: ""
        rssCategory        = rssItem?.feedCategory ?: intent.getStringExtra("rss_category")   ?: "General"
        rssImageUrl        = rssItem?.imageUrl     ?: ""
        rssFeedName        = rssItem?.feedName     ?: ""

        if (rssTitle.isNotBlank())       binding.etArticleTitle.setText(rssTitle)
        if (rssDescription.isNotBlank()) binding.etArticleSummary.setText(rssDescription)

        // Show RSS image URL hint if available
        if (rssImageUrl.isNotBlank()) {
            binding.tvImageStatus.text = "🖼️ RSS image found — will be downloaded during pipeline"
            binding.tvImageStatus.isVisible = true
        }

        binding.btnRunAiPipeline.setOnClickListener {
            val title = binding.etArticleTitle.text.toString().trim()
            val description = binding.etArticleSummary.text.toString().trim()
            if (title.isBlank()) {
                binding.etArticleTitle.error = "Please enter a title"
                return@setOnClickListener
            }

            // Pass imageUrl so AiPipeline Step-5 can download the RSS image
            val item = RssItem(
                title       = title,
                description = description.ifBlank { rssDescription },
                link        = rssLink,
                pubDate     = "",
                feedCategory = rssCategory,
                feedName    = rssFeedName,
                imageUrl    = rssImageUrl    // ← KEY FIX: pass RSS image URL to pipeline
            )
            viewModel.runPipeline(item)
        }

        binding.btnSaveDraft.setOnClickListener   { saveDraft() }
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
                .setNegativeButton("Discard")    { _, _ -> finish() }
                .setNeutralButton("Cancel", null)
                .show()
        }
    }

    private fun hasUnsavedChanges(): Boolean =
        binding.etArticleTitle.text?.isNotBlank()   == true ||
        binding.etArticleSummary.text?.isNotBlank() == true ||
        binding.etArticleContent.text?.isNotBlank() == true

    private fun observePipelineState() {
        viewModel.pipelineState.observe(this, Observer { state ->
            binding.progressGroup.visibility = if (state.loading) View.VISIBLE else View.GONE
            binding.btnRunAiPipeline.isEnabled = !state.loading
            binding.tvPipelineStatus.text = state.statusText

            // Apply Gemini rewritten article body
            if (state.finalContent.isNotBlank()) {
                binding.etArticleContent.setText(state.finalContent)
            }

            // Apply Gemini rewritten SEO title
            if (state.rewrittenTitle.isNotBlank()) {
                binding.etArticleTitle.setText(state.rewrittenTitle)
            }

            // AI pipeline chips
            binding.chipGemini.isChecked = state.geminiDone
            binding.chipOpenai.isChecked = state.openAiDone
            binding.chipSarvam.isChecked = state.sarvamDone
            // SEO chip — only shown when seoDone
            if (::binding.isInitialized) {
                try { binding.chipSeo.isChecked = state.seoDone } catch (_: Exception) {}
            }

            // Image status
            when {
                state.imagePath.isNotBlank() -> {
                    binding.tvImageStatus.text = "✅ Article image downloaded: ${state.imagePath.substringAfterLast('/')}"
                    binding.tvImageStatus.isVisible = true
                }
                state.imageUrl.isNotBlank() -> {
                    binding.tvImageStatus.text = "🖼️ Image URL: ${state.imageUrl.take(60)}…"
                    binding.tvImageStatus.isVisible = true
                }
                else -> {
                    binding.tvImageStatus.isVisible = false
                }
            }

            // Fact feedback
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

    /**
     * Build a fully-populated Article from UI + pipeline state.
     * Includes SEO meta, tags, image, source URL, category — all required for WordPress push.
     */
    private fun buildArticleForSave(status: String = "draft"): Article {
        val state = viewModel.pipelineState.value
        val currentTitle   = binding.etArticleTitle.text.toString().trim()
        val currentSummary = binding.etArticleSummary.text.toString().trim()
        val currentContent = binding.etArticleContent.text.toString().trim()

        return Article(
            title           = currentTitle,
            summary         = currentSummary.ifBlank { state?.metaDescription ?: "" },
            content         = currentContent,
            status          = status,
            // Source
            category        = rssCategory,
            sourceUrl       = rssLink,
            sourceName      = rssFeedName,
            // SEO from pipeline
            tags            = state?.tags            ?: "",
            metaKeywords    = state?.metaKeywords    ?: "",
            focusKeyphrase  = state?.focusKeyphrase  ?: "",
            metaDescription = state?.metaDescription ?: currentSummary.take(160),
            // Image: prefer local downloaded path, fallback to remote RSS URL
            imageUrl        = state?.imageUrl  ?: rssImageUrl,
            imagePath       = state?.imagePath ?: "",
            // AI pipeline flags
            geminiChecked   = binding.chipGemini.isChecked,
            openaiChecked   = binding.chipOpenai.isChecked,
            sarvamChecked   = binding.chipSarvam.isChecked,
            // Fact check
            factCheckPassed    = state?.factCheckPassed ?: false,
            factCheckFeedback  = state?.factFeedback    ?: "",
            confidenceScore    = state?.confidenceScore ?: 0f,
            aiProvider         = "gemini"
        )
    }

    private fun saveDraft(onComplete: (() -> Unit)? = null) {
        val title   = binding.etArticleTitle.text.toString().trim()
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
            Toast.makeText(this@ArticleEditorActivity, "✅ Saved as draft", Toast.LENGTH_SHORT).show()
            onComplete?.invoke()
        }
    }

    private fun publishDraftToWordPress() {
        val title   = binding.etArticleTitle.text.toString().trim()
        val content = binding.etArticleContent.text.toString().trim()
        if (title.isBlank() || content.isBlank()) {
            Toast.makeText(this, "Title and content are required before publish", Toast.LENGTH_SHORT).show()
            return
        }
        val siteUrl     = apiKeyManager.getWordPressSiteUrl().trim()
        val username    = apiKeyManager.getWordPressUsername().trim()
        val appPassword = apiKeyManager.getWordPressPassword().trim()
        if (siteUrl.isBlank() || username.isBlank() || appPassword.isBlank()) {
            Toast.makeText(this, "Configure WordPress credentials in Settings first", Toast.LENGTH_LONG).show()
            return
        }

        val site    = WordPressSite(name = "Default", siteUrl = siteUrl, username = username, appPassword = appPassword)
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
                // Save locally with WP post ID
                withContext(Dispatchers.IO) {
                    AppDatabase.getDatabase(this@ArticleEditorActivity).articleDao()
                        .insert(article.copy(wordpressPostId = result.postId, status = "draft"))
                }
                binding.tvPipelineStatus.text = "✅ Draft pushed to WordPress (Post ID: ${result.postId})"
                Toast.makeText(this@ArticleEditorActivity, "Draft pushed to WordPress!", Toast.LENGTH_LONG).show()
            } else {
                binding.tvPipelineStatus.text = "❌ Publish failed"
                Toast.makeText(this@ArticleEditorActivity, result.error, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
