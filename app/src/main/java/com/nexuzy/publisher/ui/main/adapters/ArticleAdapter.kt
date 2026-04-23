package com.nexuzy.publisher.ui.main.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.nexuzy.publisher.data.model.Article
import com.nexuzy.publisher.databinding.ItemArticleBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ArticleAdapter : RecyclerView.Adapter<ArticleAdapter.ArticleViewHolder>() {

    private val items = mutableListOf<Article>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun submitList(newItems: List<Article>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArticleViewHolder {
        val binding = ItemArticleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ArticleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ArticleViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ArticleViewHolder(private val binding: ItemArticleBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(article: Article) {
            binding.tvTitle.text = article.title.ifBlank { "(Untitled article)" }
            val created = dateFormat.format(Date(article.createdAt))
            binding.tvMeta.text = "Status: ${article.status} • Confidence: ${article.confidenceScore.toInt()}% • $created"
        }
    }
}
