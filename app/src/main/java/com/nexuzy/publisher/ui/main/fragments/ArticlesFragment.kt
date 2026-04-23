package com.nexuzy.publisher.ui.main.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.nexuzy.publisher.data.db.AppDatabase
import com.nexuzy.publisher.databinding.FragmentArticlesBinding
import com.nexuzy.publisher.ui.main.adapters.ArticleAdapter
import com.nexuzy.publisher.ui.editor.ArticleEditorActivity

class ArticlesFragment : Fragment() {

    private var _binding: FragmentArticlesBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: ArticleAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentArticlesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ArticleAdapter { article ->
            startActivity(Intent(requireContext(), ArticleEditorActivity::class.java).apply {
                putExtra("rss_title", article.title)
                putExtra("rss_description", article.summary)
                putExtra("rss_link", article.sourceUrl)
                putExtra("rss_category", article.category)
            })
        }
        binding.rvArticles.layoutManager = LinearLayoutManager(requireContext())
        binding.rvArticles.adapter = adapter

        AppDatabase.getDatabase(requireContext()).articleDao().getAllArticles()
            .observe(viewLifecycleOwner) { articles ->
                adapter.submitList(articles.toList())
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
