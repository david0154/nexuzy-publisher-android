package com.nexuzy.publisher.ui.main.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.nexuzy.publisher.data.db.AppDatabase
import com.nexuzy.publisher.databinding.FragmentDashboardBinding

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
