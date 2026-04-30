package com.nexuzy.publisher.ui.main.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.nexuzy.publisher.R
import com.nexuzy.publisher.data.db.AppDatabase
import com.nexuzy.publisher.data.model.RssFeed
import com.nexuzy.publisher.databinding.FragmentRssBinding
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

        // Observe fetching state to disable button
        newsViewModel.isFetching.observe(viewLifecycleOwner) { fetching ->
            binding.btnFetchLatestNews.isEnabled = !fetching
            binding.btnFetchLatestNews.text =
                if (fetching) "Fetching…" else "Fetch Latest News"
        }

        binding.btnAddFeed.setOnClickListener {
            val name = binding.etFeedName.text?.toString()?.trim().orEmpty()
            val url = binding.etFeedUrl.text?.toString()?.trim().orEmpty()
            val category =
                binding.etFeedCategory.text?.toString()?.trim().orEmpty().ifBlank { "General" }

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
            newsViewModel.setFetching(true)
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val snapshot = withContext(Dispatchers.IO) {
                        NewsWorkflowManager(requireContext()).fetchTodayHotNews(limitPerFeed = 20)
                    }
                    // Post snapshot to shared ViewModel so Dashboard can display it
                    newsViewModel.setSnapshot(snapshot)
                    newsViewModel.setFetching(false)

                    val total = snapshot.allToday.size
                    val hot = snapshot.hotNews.size
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
                        // Navigate to Dashboard to show the news
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
