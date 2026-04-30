package com.nexuzy.publisher.ui.main.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nexuzy.publisher.data.model.RssItem
import com.nexuzy.publisher.databinding.ItemNewsBinding

class NewsItemAdapter(
    private val onItemClick: (RssItem) -> Unit
) : ListAdapter<RssItem, NewsItemAdapter.NewsViewHolder>(DIFF) {

    /**
     * Optional score map: link -> score (0..100).
     * Set via [setScores] after submitting the list so confidence badges show.
     */
    private var scores: Map<String, Int> = emptyMap()
    private var viralLinks: Set<String> = emptySet()
    private var hotLinks: Set<String> = emptySet()

    fun setScores(
        scoreMap: Map<String, Int>,
        hotLinkSet: Set<String> = emptySet(),
        viralLinkSet: Set<String> = emptySet()
    ) {
        scores = scoreMap
        hotLinks = hotLinkSet
        viralLinks = viralLinkSet
        notifyDataSetChanged()
    }

    inner class NewsViewHolder(private val binding: ItemNewsBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RssItem) {
            binding.tvNewsTitle.text = item.title
            binding.tvNewsSource.text =
                item.feedName.ifBlank { item.feedCategory.ifBlank { "News" } }
            binding.tvNewsDate.text = item.pubDate.take(16).ifBlank { "" }
            binding.tvNewsDesc.text = item.description.take(120).ifBlank { "" }

            // Category chip color
            val chipBg = when (item.feedCategory.lowercase()) {
                "sports"        -> 0xFFE3F2FD.toInt()
                "finance"       -> 0xFFE8F5E9.toInt()
                "politics"      -> 0xFFFFF9C4.toInt()
                "entertainment" -> 0xFFFCE4EC.toInt()
                "ai"            -> 0xFFEDE7F6.toInt()
                else            -> 0xFFF5F5F5.toInt()
            }
            binding.chipCategory.setBackgroundColor(chipBg)
            binding.chipCategory.text = item.feedCategory.ifBlank { "General" }

            // ── Confidence / Viral score badge ──────────────────────────────────
            val score = scores[item.link]
            val isViral = item.link in viralLinks
            val isHot   = item.link in hotLinks

            when {
                isViral -> {
                    binding.tvConfidenceScore.isVisible = true
                    binding.tvConfidenceScore.text = "⚡ Viral"
                    binding.tvConfidenceScore.setBackgroundColor(Color.parseColor("#E91E63"))
                }
                isHot && score != null && score > 0 -> {
                    binding.tvConfidenceScore.isVisible = true
                    binding.tvConfidenceScore.text = "🔥 ${score}%"
                    val color = when {
                        score >= 70 -> Color.parseColor("#FF5722")
                        score >= 40 -> Color.parseColor("#FF9800")
                        else        -> Color.parseColor("#9E9E9E")
                    }
                    binding.tvConfidenceScore.setBackgroundColor(color)
                }
                score != null && score > 0 -> {
                    binding.tvConfidenceScore.isVisible = true
                    binding.tvConfidenceScore.text = "${score}%"
                    val color = when {
                        score >= 70 -> Color.parseColor("#4CAF50")
                        score >= 40 -> Color.parseColor("#FF9800")
                        else        -> Color.parseColor("#9E9E9E")
                    }
                    binding.tvConfidenceScore.setBackgroundColor(color)
                }
                else -> {
                    binding.tvConfidenceScore.isVisible = false
                }
            }

            binding.root.setOnClickListener { onItemClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NewsViewHolder {
        val binding =
            ItemNewsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return NewsViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NewsViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RssItem>() {
            override fun areItemsTheSame(oldItem: RssItem, newItem: RssItem) =
                oldItem.link == newItem.link

            override fun areContentsTheSame(oldItem: RssItem, newItem: RssItem) =
                oldItem == newItem
        }
    }
}
