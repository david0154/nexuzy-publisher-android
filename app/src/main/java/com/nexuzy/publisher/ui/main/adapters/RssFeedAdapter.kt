package com.nexuzy.publisher.ui.main.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.nexuzy.publisher.data.model.RssFeed
import com.nexuzy.publisher.databinding.ItemRssFeedBinding

class RssFeedAdapter(
    private val onDelete: (RssFeed) -> Unit
) : RecyclerView.Adapter<RssFeedAdapter.RssFeedViewHolder>() {

    private val items = mutableListOf<RssFeed>()

    fun submitList(newItems: List<RssFeed>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RssFeedViewHolder {
        val binding = ItemRssFeedBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RssFeedViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RssFeedViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class RssFeedViewHolder(private val binding: ItemRssFeedBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(feed: RssFeed) {
            binding.tvFeedName.text = feed.name
            binding.tvFeedMeta.text = "${feed.category} • ${feed.url}"
            binding.btnDeleteFeed.setOnClickListener { onDelete(feed) }
        }
    }
}
