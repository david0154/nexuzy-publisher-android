package com.nexuzy.publisher.ui.main.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.nexuzy.publisher.data.db.AppDatabase
import com.nexuzy.publisher.data.model.RssItem
import com.nexuzy.publisher.databinding.FragmentDashboardBinding
import com.nexuzy.publisher.ui.editor.ArticleEditorActivity
import com.nexuzy.publisher.ui.main.NewsViewModel
import com.nexuzy.publisher.ui.main.adapters.NewsItemAdapter

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val newsViewModel: NewsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // --- Stats from Room DB ---
        val db = AppDatabase.getDatabase(requireContext())
        db.articleDao().getAllArticles().observe(viewLifecycleOwner) { articles ->
            val total = articles.size
            val drafts = articles.count { it.status.equals("draft", ignoreCase = true) }
            val published = articles.count { it.status.equals("published", ignoreCase = true) }
            binding.tvTotalArticles.text = "Total articles: $total"
            binding.tvDraftArticles.text = "Drafts: $drafts"
            binding.tvPublishedArticles.text = "Published: $published"
        }
        db.rssFeedDao().getAllFeeds().observe(viewLifecycleOwner) { feeds ->
            binding.tvTotalFeeds.text = "Configured RSS feeds: ${feeds.count { it.isActive }}"
        }

        // --- News lists from shared ViewModel ---
        val latestAdapter = NewsItemAdapter { item -> openEditor(item) }
        val hotAdapter = NewsItemAdapter { item -> openEditor(item) }
        val viralAdapter = NewsItemAdapter { item -> openEditor(item) }

        binding.rvLatestNews.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
        binding.rvLatestNews.adapter = latestAdapter
        binding.rvLatestNews.isNestedScrollingEnabled = false

        binding.rvHotNews.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvHotNews.adapter = hotAdapter

        binding.rvViralNews.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvViralNews.adapter = viralAdapter

        newsViewModel.snapshot.observe(viewLifecycleOwner) { snapshot ->
            val latestItems = snapshot.allToday.take(30)
            val hotItems = snapshot.hotNews.map { it.item }
            val viralItems = snapshot.potentialViral.map { it.item }

            // Empty state
            val hasNews = latestItems.isNotEmpty()
            binding.tvNoNewsHint.isVisible = !hasNews
            binding.sectionLatest.isVisible = hasNews
            binding.sectionHot.isVisible = hotItems.isNotEmpty()
            binding.sectionViral.isVisible = viralItems.isNotEmpty()

            latestAdapter.submitList(latestItems)
            hotAdapter.submitList(hotItems)
            viralAdapter.submitList(viralItems)
        }

        // Fetching spinner
        newsViewModel.isFetching.observe(viewLifecycleOwner) { fetching ->
            binding.fetchProgressBar.isVisible = fetching
        }
    }

    private fun openEditor(item: RssItem) {
        val intent = Intent(requireContext(), ArticleEditorActivity::class.java).apply {
            putExtra("rss_item", item)
            putExtra("rss_title", item.title)
            putExtra("rss_description", item.description)
            putExtra("rss_link", item.link)
            putExtra("rss_category", item.feedCategory)
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
