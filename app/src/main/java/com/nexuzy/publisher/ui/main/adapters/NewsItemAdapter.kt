package com.nexuzy.publisher.ui.main.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nexuzy.publisher.data.model.RssItem
import com.nexuzy.publisher.databinding.ItemNewsBinding

class NewsItemAdapter(
    private val onItemClick: (RssItem) -> Unit
) : ListAdapter<RssItem, NewsItemAdapter.NewsViewHolder>(DIFF) {

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
                "sports" -> 0xFFE3F2FD.toInt()
                "finance" -> 0xFFE8F5E9.toInt()
                "politics" -> 0xFFFFF9C4.toInt()
                "entertainment" -> 0xFFFCE4EC.toInt()
                "ai" -> 0xFFEDE7F6.toInt()
                else -> 0xFFF5F5F5.toInt()
            }
            binding.chipCategory.setBackgroundColor(chipBg)
            binding.chipCategory.text =
                item.feedCategory.ifBlank { "General" }

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
