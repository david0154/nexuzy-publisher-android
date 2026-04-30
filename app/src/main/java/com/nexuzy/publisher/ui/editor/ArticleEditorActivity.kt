package com.nexuzy.publisher.ui.editor

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.nexuzy.publisher.data.db.AppDatabase
import com.nexuzy.publisher.data.model.Article
import com.nexuzy.publisher.data.model.RssItem
import com.nexuzy.publisher.data.model.WordPressSite
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

    // Class-level RSS source fields
    private var rssLink: String = ""
    private var rssCategory: String = "General"
    private var rssImageUrl: String = ""
    private var rssFeedName: String = ""

    // Locally picked gallery image URI
    private var pickedImageUri: Uri? = null

    // Gallery picker launcher
    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = result.data?.data
                if (uri != null) {
                    pickedImageUri = uri
                    showImagePreview(uri.toString())
                    binding.tvImageStatus.text = "\uD83D\uDDBC\uFE0F Gallery image selected"
                    binding.tvImageStatus.isVisible = true
                }
            }
        }

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

        // Accept full RssItem Parcelable OR individual string extras
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

        // Show RSS image preview if available
        if (rssImageUrl.isNotBlank()) {
            showImagePreview(rssImageUrl)
            binding.tvImageStatus.text = "\uD83D\uDDBC\uFE0F RSS image found — will be uploaded with article"
            binding.tvImageStatus.isVisible = true
        }

        // ── Image action buttons ──────────────────────────────────────────────
        binding.btnUseRssImage.isVisible = rssImageUrl.isNotBlank()
        binding.btnUseRssImage.setOnClickListener {
            pickedImageUri = null
            showImagePreview(rssImageUrl)
            binding.tvImageStatus.text = "\uD83D\uDDBC\uFE0F Using RSS image"
            binding.tvImageStatus.isVisible = true
        }

        binding.btnPickGalleryImage.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            galleryLauncher.launch(intent)
        }

        // ── AI Pipeline button ────────────────────────────────────────────────
        binding.btnRunAiPipeline.setOnClickListener {
            val title       = binding.etArticleTitle.text.toString().trim()
            val description = binding.etArticleSummary.text.toString().trim()
            if (title.isBlank()) {
                binding.etArticleTitle.error = "Please enter a title"
                return@setOnClickListener
            }
            val item = RssItem(
                title        = title,
                description  = description.ifBlank { rssDescription },
                link         = rssLink,
                pubDate      = "",
                feedCategory = rssCategory,
                feedName     = rssFeedName,
                imageUrl     = rssImageUrl
            )
            viewModel.runPipeline(item)
        }

        binding.btnSaveDraft.setOnClickListener    { saveDraft() }
        binding.btnPublishDraft.setOnClickListener { publishDraftToWordPress() }
    }

    private fun showImagePreview(url: String) {
        if (url.isBlank()) return
        binding.ivArticleImage.isVisible = true
        try {
            Glide.with(this)
                .load(url)
                .centerCrop()
                .into(binding.ivArticleImage)
        } catch (_: Exception) {
            binding.ivArticleImage.isVisible = false
        }
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

            if (state.finalContent.isNotBlank()) binding.etArticleContent.setText(state.finalContent)
            if (state.rewrittenTitle.isNotBlank()) binding.etArticleTitle.setText(state.rewrittenTitle)

            binding.chipGemini.isChecked = state.geminiDone
            binding.chipOpenai.isChecked = state.openAiDone
            binding.chipSarvam.isChecked = state.sarvamDone
            try { binding.chipSeo.isChecked = state.seoDone } catch (_: Exception) {}

            // Update image preview from pipeline result
            when {
                state.imagePath.isNotBlank() -> {
                    showImagePreview(state.imagePath)
                    binding.tvImageStatus.text = "\u2705 Article image downloaded: ${state.imagePath.substringAfterLast('/')}"
                    binding.tvImageStatus.isVisible = true
                }
                state.imageUrl.isNotBlank() -> {
                    showImagePreview(state.imageUrl)
                    binding.tvImageStatus.text = "\uD83D\uDDBC\uFE0F Image URL: ${state.imageUrl.take(60)}\u2026"
                    binding.tvImageStatus.isVisible = true
                }
            }

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
     * Auto-generates SEO fields if the AI pipeline has not run yet.
     */
    private fun buildArticleForSave(status: String = "draft"): Article {
        val state          = viewModel.pipelineState.value
        val currentTitle   = binding.etArticleTitle.text.toString().trim()
        val currentSummary = binding.etArticleSummary.text.toString().trim()
        val currentContent = binding.etArticleContent.text.toString().trim()

        // ── Auto-generate SEO fields when pipeline hasn't run or returned blanks ──
        val autoKeywords = currentTitle.lowercase()
            .split(" ")
            .filter { it.length > 4 }
            .take(8)
            .joinToString(", ")

        val finalKeywords    = state?.metaKeywords?.ifBlank { autoKeywords }    ?: autoKeywords
        val finalKeyphrase   = state?.focusKeyphrase?.ifBlank { currentTitle.take(60) } ?: currentTitle.take(60)
        val finalMetaDesc    = state?.metaDescription?.ifBlank {
            currentSummary.ifBlank { currentContent.take(155) }
        } ?: currentSummary.ifBlank { currentContent.take(155) }
        val finalTags        = state?.tags?.ifBlank { autoKeywords } ?: autoKeywords

        // Resolve article image: gallery pick > pipeline download > pipeline url > rss url
        val finalImageUrl  = pickedImageUri?.toString()
            ?: state?.imageUrl?.ifBlank { rssImageUrl }
            ?: rssImageUrl
        val finalImagePath = state?.imagePath ?: ""

        return Article(
            title              = currentTitle,
            summary            = currentSummary.ifBlank { finalMetaDesc },
            content            = currentContent,
            status             = status,
            category           = rssCategory,
            sourceUrl          = rssLink,
            sourceName         = rssFeedName,
            tags               = finalTags,
            metaKeywords       = finalKeywords,
            focusKeyphrase     = finalKeyphrase,
            metaDescription    = finalMetaDesc,
            imageUrl           = finalImageUrl,
            imagePath          = finalImagePath,
            geminiChecked      = binding.chipGemini.isChecked,
            openaiChecked      = binding.chipOpenai.isChecked,
            sarvamChecked      = binding.chipSarvam.isChecked,
            factCheckPassed    = state?.factCheckPassed    ?: false,
            factCheckFeedback  = state?.factFeedback       ?: "",
            confidenceScore    = state?.confidenceScore    ?: 0f,
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
            Toast.makeText(this@ArticleEditorActivity, "\u2705 Saved as draft", Toast.LENGTH_SHORT).show()
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
        binding.tvPipelineStatus.text = "Publishing draft to WordPress\u2026"
        binding.btnPublishDraft.isEnabled = false

        lifecycleScope.launch {
            // allowCategoryCreate = false → only push to EXISTING WordPress categories
            val result = withContext(Dispatchers.IO) {
                wpClient.pushDraft(site, article, adsCode)
            }
            binding.progressGroup.visibility = View.GONE
            binding.btnPublishDraft.isEnabled = true

            if (result.success) {
                withContext(Dispatchers.IO) {
                    AppDatabase.getDatabase(this@ArticleEditorActivity).articleDao()
                        .insert(article.copy(wordpressPostId = result.postId, status = "draft"))
                }
                binding.tvPipelineStatus.text =
                    "\u2705 Draft pushed to WordPress (Post ID: ${result.postId})"
                Toast.makeText(
                    this@ArticleEditorActivity,
                    "\uD83C\uDF89 Draft pushed to WordPress!",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                // Handle category-not-found gracefully
                val errMsg = result.error ?: "Unknown error"
                binding.tvPipelineStatus.text = when {
                    errMsg.contains("CATEGORY_NOT_FOUND", ignoreCase = true) ->
                        "\u26A0\uFE0F Category '${article.category}' not found on WordPress. " +
                        "Add it on your WP site or pick a different category."
                    else -> "\u274C Publish failed: $errMsg"
                }
                Toast.makeText(this@ArticleEditorActivity, errMsg, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
