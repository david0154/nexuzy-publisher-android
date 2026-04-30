package com.nexuzy.publisher.ui.main.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.nexuzy.publisher.R
import com.nexuzy.publisher.data.db.AppDatabase
import com.nexuzy.publisher.data.model.RssFeed
import com.nexuzy.publisher.data.model.WordPressSite
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import com.nexuzy.publisher.databinding.FragmentRssBinding
import com.nexuzy.publisher.network.WordPressApiClient
import com.nexuzy.publisher.ui.main.NewsViewModel
import com.nexuzy.publisher.ui.main.adapters.RssFeedAdapter
import com.nexuzy.publisher.workflow.NewsWorkflowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RssFragment : Fragment() {

    private var _binding: FragmentRssBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: RssFeedAdapter
    private val newsViewModel: NewsViewModel by activityViewModels()
    private val wpClient = WordPressApiClient()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRssBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = RssFeedAdapter { feed -> deleteFeed(feed) }
        binding.rvRssFeeds.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRssFeeds.adapter = adapter

        val db = AppDatabase.getDatabase(requireContext())
        db.rssFeedDao().getAllFeeds().observe(viewLifecycleOwner) { feeds ->
            adapter.submitList(feeds)
        }

        // Populate category dropdown if ViewModel already has cached WP categories
        newsViewModel.wpCategories.observe(viewLifecycleOwner) { cats ->
            if (cats.isNotEmpty()) {
                val names = cats.map { it.name }
                val autoAdapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_dropdown_item_1line,
                    names
                )
                binding.actvFeedCategory.setAdapter(autoAdapter)
                binding.tvCategoryStatus.text = "✅ ${cats.size} WP categories loaded"
                binding.tvCategoryStatus.isVisible = true
            }
        }

        // Load WP Categories button
        binding.btnLoadWpCategories.setOnClickListener {
            val keyManager = ApiKeyManager(requireContext())
            val siteUrl  = keyManager.getWordPressSiteUrl().trim()
            val username = keyManager.getWordPressUsername().trim()
            val password = keyManager.getWordPressPassword().trim()

            if (siteUrl.isBlank() || username.isBlank() || password.isBlank()) {
                Toast.makeText(
                    requireContext(),
                    "⚠️ Configure WordPress credentials in Settings first",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            binding.btnLoadWpCategories.isEnabled = false
            binding.btnLoadWpCategories.text = "Loading…"
            binding.tvCategoryStatus.text = "Fetching categories from WordPress…"
            binding.tvCategoryStatus.isVisible = true

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val site = WordPressSite(
                        name = "Default",
                        siteUrl = siteUrl,
                        username = username,
                        appPassword = password
                    )
                    val cats = withContext(Dispatchers.IO) {
                        wpClient.fetchCategories(site)
                    }
                    newsViewModel.setWpCategories(cats)
                    binding.btnLoadWpCategories.isEnabled = true
                    binding.btnLoadWpCategories.text = "📂 Load"
                    if (cats.isEmpty()) {
                        binding.tvCategoryStatus.text = "⚠️ No categories found on this WP site"
                    }
                    Toast.makeText(
                        requireContext(),
                        "✅ ${cats.size} WordPress categories loaded",
                        Toast.LENGTH_SHORT
                    ).show()
                    // Show dropdown immediately
                    binding.actvFeedCategory.showDropDown()
                } catch (e: Exception) {
                    binding.btnLoadWpCategories.isEnabled = true
                    binding.btnLoadWpCategories.text = "📂 Load"
                    binding.tvCategoryStatus.text = "❌ Failed: ${e.message}"
                    Toast.makeText(
                        requireContext(),
                        "Failed to load categories: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        // Observe fetching state to disable button
        newsViewModel.isFetching.observe(viewLifecycleOwner) { fetching ->
            binding.btnFetchLatestNews.isEnabled = !fetching
            binding.btnFetchLatestNews.text =
                if (fetching) "Fetching…" else "Fetch Latest News"
        }

        binding.btnAddFeed.setOnClickListener {
            val name = binding.etFeedName.text?.toString()?.trim().orEmpty()
            val url  = binding.etFeedUrl.text?.toString()?.trim().orEmpty()
            val category = binding.actvFeedCategory.text?.toString()?.trim()
                .orEmpty().ifBlank { "General" }

            if (name.isBlank()) {
                binding.etFeedName.error = "Feed name required"
                return@setOnClickListener
            }
            if (url.isBlank()) {
                binding.etFeedUrl.error = "Feed URL required"
                return@setOnClickListener
            }

            viewLifecycleOwner.lifecycleScope.launch {
                db.rssFeedDao().insert(RssFeed(name = name, url = url, category = category))
                binding.etFeedName.setText("")
                binding.etFeedUrl.setText("")
                binding.actvFeedCategory.setText("")
                Toast.makeText(
                    requireContext(),
                    "✅ RSS feed added (category: $category)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.btnFetchLatestNews.setOnClickListener {
            newsViewModel.setFetching(true)
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val snapshot = withContext(Dispatchers.IO) {
                        NewsWorkflowManager(requireContext()).fetchTodayHotNews(limitPerFeed = 20)
                    }
                    newsViewModel.setSnapshot(snapshot)
                    newsViewModel.setFetching(false)

                    val total = snapshot.allToday.size
                    val hot   = snapshot.hotNews.size
                    val viral = snapshot.potentialViral.size

                    if (total == 0) {
                        Toast.makeText(
                            requireContext(),
                            "No news found. Check your RSS feeds in the list above.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "\u2705 Fetched $total news | Hot: $hot | Viral: $viral — Opening Dashboard…",
                            Toast.LENGTH_LONG
                        ).show()
                        findNavController().navigate(R.id.nav_dashboard)
                    }
                } catch (e: Exception) {
                    newsViewModel.setFetching(false)
                    Toast.makeText(
                        requireContext(),
                        "Fetch error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun deleteFeed(feed: RssFeed) {
        viewLifecycleOwner.lifecycleScope.launch {
            AppDatabase.getDatabase(requireContext()).rssFeedDao().delete(feed)
            Toast.makeText(requireContext(), "RSS feed deleted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
