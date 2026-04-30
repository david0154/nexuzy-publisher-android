package com.nexuzy.publisher.ui.davidai

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nexuzy.publisher.R

/**
 * RecyclerView adapter for the David AI chat screen.
 * Uses two distinct ViewTypes:
 *   - VIEW_TYPE_USER (0) -> right-aligned teal bubble
 *   - VIEW_TYPE_AI   (1) -> left-aligned white card with 'David AI' label
 */
class ChatMessageAdapter(
    private val messages: MutableList<ChatMessage> = mutableListOf()
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 0
        private const val VIEW_TYPE_AI   = 1
    }

    inner class UserViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val tvMessage: TextView = itemView.findViewById(R.id.tvUserMessage)
    }

    inner class AiViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val tvMessage: TextView = itemView.findViewById(R.id.tvAiMessage)
    }

    override fun getItemViewType(position: Int): Int =
        if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_AI

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER) {
            UserViewHolder(inflater.inflate(R.layout.item_chat_user, parent, false))
        } else {
            AiViewHolder(inflater.inflate(R.layout.item_chat_ai, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        when (holder) {
            is UserViewHolder -> holder.tvMessage.text = msg.content
            is AiViewHolder  -> holder.tvMessage.text = msg.content
        }
    }

    override fun getItemCount(): Int = messages.size

    /** Append a new message and scroll hint. */
    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    /** Replace the last message content (used for streaming / correction). */
    fun updateLastMessage(content: String) {
        if (messages.isNotEmpty()) {
            val idx = messages.size - 1
            messages[idx] = messages[idx].copy(content = content)
            notifyItemChanged(idx)
        }
    }

    /** Clear all messages (e.g., new chat). */
    fun clearAll() {
        messages.clear()
        notifyDataSetChanged()
    }
}
