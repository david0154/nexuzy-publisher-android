package com.nexuzy.publisher.ui.main.adapters

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nexuzy.publisher.data.model.RssFeed
import com.nexuzy.publisher.databinding.ItemRssFeedBinding

class RssFeedAdapter(
    private val onDelete: (RssFeed) -> Unit
) : ListAdapter<RssFeed, RssFeedAdapter.RssFeedViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<RssFeed>() {
            override fun areItemsTheSame(old: RssFeed, new: RssFeed) = old.id == new.id
            override fun areContentsTheSame(old: RssFeed, new: RssFeed) = old == new
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RssFeedViewHolder {
        val binding = ItemRssFeedBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return RssFeedViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RssFeedViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RssFeedViewHolder(
        private val binding: ItemRssFeedBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(feed: RssFeed) {
            binding.tvFeedName.text = feed.name
            binding.tvFeedCategory.text = feed.category
            binding.tvFeedUrl.text = feed.url

            // Badge: blue = Default (pre-loaded), green = Custom (user-added)
            if (feed.isDefault) {
                binding.tvFeedBadge.text = "Default"
                binding.tvFeedBadge.setBackgroundResource(
                    com.nexuzy.publisher.R.drawable.bg_badge_default
                )
            } else {
                binding.tvFeedBadge.text = "Custom"
                binding.tvFeedBadge.setBackgroundResource(
                    com.nexuzy.publisher.R.drawable.bg_badge_custom
                )
            }

            // ALL feeds (Default + Custom) can be deleted
            // Show confirmation dialog before deleting any feed
            binding.btnDeleteFeed.setOnClickListener {
                AlertDialog.Builder(binding.root.context)
                    .setTitle("Delete Feed")
                    .setMessage("Delete \"${feed.name}\"?\n\nThis will remove it from your feed list and stop fetching news from this source.")
                    .setPositiveButton("Delete") { _, _ -> onDelete(feed) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
}
