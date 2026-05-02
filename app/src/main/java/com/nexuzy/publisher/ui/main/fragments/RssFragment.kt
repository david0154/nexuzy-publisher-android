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
import com.nexuzy.publisher.data.firebase.FirestoreUserRepository
import com.nexuzy.publisher.data.model.RssFeed
import com.nexuzy.publisher.data.model.WpCategory
import com.nexuzy.publisher.data.model.WordPressSite
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import com.nexuzy.publisher.data.prefs.AppPreferences
import com.nexuzy.publisher.data.seed.DefaultFeedsSeeder
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

    private lateinit var firestoreRepo: FirestoreUserRepository

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

        val keyManager = ApiKeyManager(requireContext())
        firestoreRepo  = FirestoreUserRepository(keyManager, AppPreferences(requireContext()))

        adapter = RssFeedAdapter { feed -> deleteFeed(feed) }
        binding.rvRssFeeds.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRssFeeds.adapter = adapter

        val db = AppDatabase.getDatabase(requireContext())

        // ── STEP 1: Seed / sync 80 default feeds ─────────────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                DefaultFeedsSeeder.seedIfEmpty(requireContext())
            }
            val ctx = context ?: return@launch          // guard: fragment may have detached
            val activeCount = withContext(Dispatchers.IO) {
                db.rssFeedDao().getActiveCount()
            }
            if (!isAdded) return@launch
            Toast.makeText(
                ctx,
                "\u2705 $activeCount active feeds ready to fetch",
                Toast.LENGTH_SHORT
            ).show()
        }

        // ── STEP 2: Observe live feed list ────────────────────────────────────
        db.rssFeedDao().getAllFeeds().observe(viewLifecycleOwner) { feeds ->
            adapter.submitList(feeds)
            if (feeds.isEmpty()) {
                restoreFeedsFromFirebase(db)
            }
        }

        // ── WP Categories ─────────────────────────────────────────────────────
        newsViewModel.wpCategories.observe(viewLifecycleOwner) { cats ->
            if (cats.isNotEmpty()) {
                val names = cats.map { it.name }
                val autoAdapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_dropdown_item_1line,
                    names
                )
                binding.actvFeedCategory.setAdapter(autoAdapter)
                binding.tvCategoryStatus.text = "\u2705 ${cats.size} WP categories loaded"
                binding.tvCategoryStatus.isVisible = true
            }
        }

        binding.btnLoadWpCategories.setOnClickListener {
            val siteUrl  = keyManager.getWordPressSiteUrl().trim()
            val username = keyManager.getWordPressUsername().trim()
            val password = keyManager.getWordPressPassword().trim()

            if (siteUrl.isBlank() || username.isBlank() || password.isBlank()) {
                Toast.makeText(
                    requireContext(),
                    "\u26a0\ufe0f Configure WordPress credentials in Settings first",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            binding.btnLoadWpCategories.isEnabled = false
            binding.btnLoadWpCategories.text = "Loading\u2026"
            binding.tvCategoryStatus.text = "Fetching categories from WordPress\u2026"
            binding.tvCategoryStatus.isVisible = true

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val site = WordPressSite(
                        name        = "Default",
                        siteUrl     = siteUrl,
                        username    = username,
                        appPassword = password
                    )
                    val cats: List<WpCategory> = withContext(Dispatchers.IO) {
                        wpClient.fetchCategories(site).map { wc ->
                            WpCategory(
                                id    = wc.id.toInt(),
                                name  = wc.name,
                                slug  = wc.slug,
                                count = wc.count
                            )
                        }
                    }
                    if (!isAdded) return@launch
                    newsViewModel.setWpCategories(cats)
                    binding.btnLoadWpCategories.isEnabled = true
                    binding.btnLoadWpCategories.text = "\uD83D\uDCC2 Load"
                    if (cats.isEmpty()) {
                        binding.tvCategoryStatus.text = "\u26a0\ufe0f No categories found on this WP site"
                    }
                    Toast.makeText(
                        requireContext(),
                        "\u2705 ${cats.size} WordPress categories loaded",
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.actvFeedCategory.showDropDown()
                } catch (e: Exception) {
                    if (!isAdded) return@launch
                    binding.btnLoadWpCategories.isEnabled = true
                    binding.btnLoadWpCategories.text = "\uD83D\uDCC2 Load"
                    binding.tvCategoryStatus.text = "\u274C Failed: ${e.message}"
                    Toast.makeText(
                        requireContext(),
                        "Failed to load categories: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        // ── Fetching state ────────────────────────────────────────────────────
        newsViewModel.isFetching.observe(viewLifecycleOwner) { fetching ->
            binding.btnFetchLatestNews.isEnabled = !fetching
            binding.btnFetchLatestNews.text =
                if (fetching) "Fetching\u2026" else "Fetch Latest News"
        }

        // ── Add RSS Feed ──────────────────────────────────────────────────────
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
                val insertedId = withContext(Dispatchers.IO) {
                    db.rssFeedDao().insert(
                        RssFeed(
                            name      = name,
                            url       = url,
                            category  = category,
                            isActive  = true,
                            isDefault = false
                        )
                    )
                }
                launch(Dispatchers.IO) {
                    firestoreRepo.addRssFeedToFirestore(
                        rssId    = insertedId.toString(),
                        url      = url,
                        name     = name,
                        category = category
                    )
                }
                if (!isAdded) return@launch
                binding.etFeedName.setText("")
                binding.etFeedUrl.setText("")
                binding.actvFeedCategory.setText("")
                Toast.makeText(
                    requireContext(),
                    "\u2705 RSS feed added & synced to your account",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // ── Fetch Latest News ─────────────────────────────────────────────────
        binding.btnFetchLatestNews.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val activeCount = withContext(Dispatchers.IO) {
                    db.rssFeedDao().getActiveCount()
                }
                if (!isAdded) return@launch
                if (activeCount == 0) {
                    Toast.makeText(
                        requireContext(),
                        "\u26a0\ufe0f No active feeds found. Seeding defaults\u2026",
                        Toast.LENGTH_SHORT
                    ).show()
                    withContext(Dispatchers.IO) {
                        DefaultFeedsSeeder.seedIfEmpty(requireContext())
                    }
                    return@launch
                }

                newsViewModel.setFetching(true)
                try {
                    val snapshot = withContext(Dispatchers.IO) {
                        NewsWorkflowManager(requireContext()).fetchTodayHotNews(limitPerFeed = 20)
                    }
                    if (!isAdded) {
                        newsViewModel.setFetching(false)
                        return@launch
                    }
                    newsViewModel.setSnapshot(snapshot)
                    newsViewModel.setFetching(false)

                    val total = snapshot.allToday.size
                    val hot   = snapshot.hotNews.size
                    val viral = snapshot.potentialViral.size

                    if (total == 0) {
                        Toast.makeText(
                            requireContext(),
                            "No news found. Check your RSS feeds or internet connection.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "\u2705 Fetched $total news | Hot: $hot | Viral: $viral \u2014 Opening Dashboard\u2026",
                            Toast.LENGTH_LONG
                        ).show()
                        findNavController().navigate(R.id.nav_dashboard)
                    }
                } catch (e: Exception) {
                    if (!isAdded) return@launch
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

    // ── Delete RSS feed ───────────────────────────────────────────────────────
    private fun deleteFeed(feed: RssFeed) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(requireContext()).rssFeedDao().delete(feed)
            }
            launch(Dispatchers.IO) {
                firestoreRepo.deleteRssFeedFromFirestore(feed.id.toString())
            }
            if (!isAdded) return@launch
            Toast.makeText(requireContext(), "RSS feed deleted", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Restore from Firebase (only when DB is empty after seeding) ───────────
    private fun restoreFeedsFromFirebase(db: AppDatabase) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val cloudFeeds = withContext(Dispatchers.IO) {
                    firestoreRepo.loadRssFeedsFromFirestore()
                }
                if (!isAdded) return@launch
                if (cloudFeeds.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        cloudFeeds.forEach { entry ->
                            db.rssFeedDao().insert(
                                RssFeed(
                                    name     = entry.name,
                                    url      = entry.url,
                                    category = entry.category,
                                    isActive = true
                                )
                            )
                        }
                    }
                    if (!isAdded) return@launch
                    Toast.makeText(
                        requireContext(),
                        "\uD83D\uDD04 ${cloudFeeds.size} RSS feeds restored from your account",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    withContext(Dispatchers.IO) {
                        DefaultFeedsSeeder.seedIfEmpty(requireContext())
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.IO) {
                    DefaultFeedsSeeder.seedIfEmpty(requireContext())
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
