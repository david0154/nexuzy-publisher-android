package com.nexuzy.publisher.ui.main.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.nexuzy.publisher.data.model.RssItem
import com.nexuzy.publisher.databinding.FragmentAiWriterBinding
import com.nexuzy.publisher.ui.editor.ArticleEditorActivity
import com.nexuzy.publisher.ui.main.NewsViewModel
import com.nexuzy.publisher.ui.settings.SettingsActivity

class AiWriterFragment : Fragment() {

    private var _binding: FragmentAiWriterBinding? = null
    private val binding get() = _binding!!
    private val newsViewModel: NewsViewModel by activityViewModels()
    private var selectedItem: RssItem? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAiWriterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Check if launched with a selected RssItem from Dashboard (via arguments)
        @Suppress("DEPRECATION")
        val argItem = arguments?.getParcelable<RssItem>("rss_item")
        if (argItem != null) {
            selectedItem = argItem
            showSelectedItem(argItem)
        }

        // Observe latest snapshot — show most recent hot news as suggestion
        newsViewModel.snapshot.observe(viewLifecycleOwner) { snapshot ->
            if (selectedItem == null) {
                val suggestion = snapshot.hotNews.firstOrNull()?.item
                    ?: snapshot.allToday.firstOrNull()
                if (suggestion != null) {
                    binding.cardSuggestedNews.isVisible = true
                    binding.tvSuggestedTitle.text = suggestion.title
                    binding.tvSuggestedSource.text =
                        suggestion.feedName.ifBlank { suggestion.feedCategory }
                    binding.btnWriteSuggested.setOnClickListener {
                        launchEditor(suggestion)
                    }
                } else {
                    binding.cardSuggestedNews.isVisible = false
                }
            }
        }

        binding.btnOpenAiEditor.setOnClickListener {
            val item = selectedItem
            if (item != null) {
                launchEditor(item)
            } else {
                // Launch blank editor
                startActivity(Intent(requireContext(), ArticleEditorActivity::class.java))
            }
        }

        binding.btnOpenSettingsFromAi.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
    }

    private fun showSelectedItem(item: RssItem) {
        binding.cardSelectedNews.isVisible = true
        binding.cardSuggestedNews.isVisible = false
        binding.tvSelectedTitle.text = item.title
        binding.tvSelectedSource.text =
            item.feedName.ifBlank { item.feedCategory.ifBlank { "News" } }
        binding.tvSelectedDesc.text = item.description.take(200)
        binding.btnOpenAiEditor.text = "\u270D\uFE0F Write This Article"
    }

    private fun launchEditor(item: RssItem) {
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
