package com.nexuzy.publisher.ui.main.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.nexuzy.publisher.data.db.AppDatabase
import com.nexuzy.publisher.data.model.WordPressSite
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import com.nexuzy.publisher.databinding.FragmentWordpressBinding
import com.nexuzy.publisher.network.WordPressApiClient
import com.nexuzy.publisher.ui.editor.ArticleEditorActivity
import com.nexuzy.publisher.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WordPressFragment : Fragment() {

    private var _binding: FragmentWordpressBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWordpressBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val db = AppDatabase.getDatabase(requireContext())
        val keyManager = ApiKeyManager(requireContext())
        val wpClient = WordPressApiClient()

        // Live draft count
        db.articleDao().getAllArticles().observe(viewLifecycleOwner) { articles ->
            val drafts = articles.count { it.status.equals("draft", ignoreCase = true) }
            val published = articles.count { it.status.equals("published", ignoreCase = true) }
            val failed = articles.count { it.status.equals("failed", ignoreCase = true) }
            binding.tvDraftCount.text = "\uD83D\uDCDD Drafts: $drafts"
            binding.tvPublishedCount.text = "\u2705 Published: $published"
            binding.tvFailedCount.text = "\u274C Failed: $failed"

            // Show site URL from saved settings
            val siteUrl = keyManager.getWordPressSiteUrl().trim()
            if (siteUrl.isNotBlank()) {
                binding.tvWpSiteUrl.text = "Site: $siteUrl"
                binding.tvWpSiteUrl.isVisible = true
            } else {
                binding.tvWpSiteUrl.text = "⚠️ No WordPress site configured"
                binding.tvWpSiteUrl.isVisible = true
            }
        }

        // Test connection button
        binding.btnTestConnection.setOnClickListener {
            val siteUrl = keyManager.getWordPressSiteUrl().trim()
            val username = keyManager.getWordPressUsername().trim()
            val password = keyManager.getWordPressPassword().trim()

            if (siteUrl.isBlank() || username.isBlank() || password.isBlank()) {
                Toast.makeText(
                    requireContext(),
                    "\u26A0\uFE0F Configure WordPress credentials in Settings first",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            binding.btnTestConnection.isEnabled = false
            binding.tvConnectionStatus.text = "Testing connection…"
            binding.tvConnectionStatus.isVisible = true

            viewLifecycleOwner.lifecycleScope.launch {
                val site = WordPressSite(
                    name = "Default",
                    siteUrl = siteUrl,
                    username = username,
                    appPassword = password
                )
                val ok = withContext(Dispatchers.IO) {
                    try {
                        wpClient.testConnection(site)
                    } catch (e: Exception) {
                        false
                    }
                }
                binding.btnTestConnection.isEnabled = true
                if (ok) {
                    binding.tvConnectionStatus.text = "\u2705 Connected to $siteUrl"
                    Toast.makeText(
                        requireContext(),
                        "WordPress connected!",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    binding.tvConnectionStatus.text =
                        "\u274C Connection failed. Check URL and App Password."
                    Toast.makeText(
                        requireContext(),
                        "Connection failed. Go to Settings and verify credentials.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        binding.btnOpenWpSettings.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        binding.btnOpenEditorFromWp.setOnClickListener {
            startActivity(Intent(requireContext(), ArticleEditorActivity::class.java))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
