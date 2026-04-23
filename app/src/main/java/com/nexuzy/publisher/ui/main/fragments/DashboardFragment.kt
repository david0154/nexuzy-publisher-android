package com.nexuzy.publisher.ui.main.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.nexuzy.publisher.data.db.AppDatabase
import com.nexuzy.publisher.databinding.FragmentDashboardBinding
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
    }

    private fun refreshStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.getDatabase(requireContext())
            val articleDao = db.articleDao()
            val total = articleDao.getTotalCount()
            val drafts = articleDao.getDraftCount()
            val published = articleDao.getPublishedCount()
            val feeds = db.rssFeedDao().getActiveFeeds().size

            binding.tvTotalArticles.text = "Total articles: $total"
            binding.tvDraftArticles.text = "Drafts: $drafts"
            binding.tvPublishedArticles.text = "Published: $published"
            binding.tvTotalFeeds.text = "Configured RSS feeds: $feeds"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
