package com.nexuzy.publisher.ui.main.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.nexuzy.publisher.data.db.AppDatabase
import com.nexuzy.publisher.data.model.RssFeed
import com.nexuzy.publisher.databinding.FragmentRssBinding
import com.nexuzy.publisher.ui.main.adapters.RssFeedAdapter
import com.nexuzy.publisher.workflow.NewsWorkflowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RssFragment : Fragment() {

    private var _binding: FragmentRssBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: RssFeedAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
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

        binding.btnAddFeed.setOnClickListener {
            val name = binding.etFeedName.text?.toString()?.trim().orEmpty()
            val url = binding.etFeedUrl.text?.toString()?.trim().orEmpty()
            val category = binding.etFeedCategory.text?.toString()?.trim().orEmpty().ifBlank { "General" }

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
                binding.etFeedCategory.setText("")
                Toast.makeText(requireContext(), "RSS feed added", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnFetchLatestNews.setOnClickListener {
            binding.btnFetchLatestNews.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val snapshot = withContext(Dispatchers.IO) {
                    NewsWorkflowManager(requireContext()).fetchTodayHotNews(limitPerFeed = 20)
                }
                binding.btnFetchLatestNews.isEnabled = true
                Toast.makeText(
                    requireContext(),
                    "Fetched ${snapshot.allToday.size} items from last 24h. Hot: ${snapshot.hotNews.size}",
                    Toast.LENGTH_LONG
                ).show()
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
