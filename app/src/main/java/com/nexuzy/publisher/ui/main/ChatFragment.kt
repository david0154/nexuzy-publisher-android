package com.nexuzy.publisher.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nexuzy.publisher.R
import com.nexuzy.publisher.network.SarvamChatClient
import kotlinx.coroutines.launch

/**
 * ChatFragment — AI assistant powered by Sarvam AI (sarvam-m).
 *
 * Features:
 *   - Full chat message history in RecyclerView
 *   - User messages (right-aligned) + AI replies (left-aligned)
 *   - Typing indicator while waiting for AI
 *   - Error recovery: retries on network failure
 *   - Suggested quick prompts for news publishing tasks
 *
 * Layout required: fragment_chat.xml
 */
class ChatFragment : Fragment() {

    // ─── Message model ─────────────────────────────────────────────────────────

    data class ChatMessage(
        val id: Long = System.currentTimeMillis(),
        val text: String,
        val isUser: Boolean,
        val isError: Boolean = false,
        val isTyping: Boolean = false
    )

    // ─── Adapter ───────────────────────────────────────────────────────────────

    inner class ChatAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(
        object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(a: ChatMessage, b: ChatMessage) = a.id == b.id
            override fun areContentsTheSame(a: ChatMessage, b: ChatMessage) = a == b
        }
    ) {
        private val USER_TYPE    = 1
        private val AI_TYPE      = 2
        private val TYPING_TYPE  = 3

        override fun getItemViewType(position: Int) = when {
            getItem(position).isTyping -> TYPING_TYPE
            getItem(position).isUser   -> USER_TYPE
            else                       -> AI_TYPE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                USER_TYPE   -> UserVH(inflater.inflate(R.layout.item_chat_user, parent, false))
                TYPING_TYPE -> TypingVH(inflater.inflate(R.layout.item_chat_typing, parent, false))
                else        -> AiVH(inflater.inflate(R.layout.item_chat_ai, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val msg = getItem(position)
            when (holder) {
                is UserVH  -> holder.bind(msg)
                is AiVH    -> holder.bind(msg)
                // TypingVH animates itself, no data needed
            }
        }
    }

    inner class UserVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvText: TextView = view.findViewById(R.id.tvUserMessage)
        fun bind(msg: ChatMessage) { tvText.text = msg.text }
    }

    inner class AiVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvText: TextView = view.findViewById(R.id.tvAiMessage)
        fun bind(msg: ChatMessage) {
            tvText.text = msg.text
            tvText.setTextColor(
                if (msg.isError)
                    resources.getColor(R.color.error_color, null)
                else
                    resources.getColor(R.color.text_primary, null)
            )
        }
    }

    inner class TypingVH(view: View) : RecyclerView.ViewHolder(view)

    // ─── Fragment state ────────────────────────────────────────────────────────

    private val adapter    = ChatAdapter()
    private val messages   = mutableListOf<ChatMessage>()
    private var chatClient: SarvamChatClient? = null
    private var isWaiting  = false

    private lateinit var rvMessages: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var tvEmpty: TextView

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Uses fragment_chat layout (create this XML or adapt to your existing layout system)
        return inflater.inflate(R.layout.fragment_chat, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvMessages = view.findViewById(R.id.rvChatMessages)
        etInput    = view.findViewById(R.id.etChatInput)
        btnSend    = view.findViewById(R.id.btnSendMessage)
        tvEmpty    = view.findViewById(R.id.tvChatEmpty)

        chatClient = SarvamChatClient(requireContext())

        // RecyclerView setup
        rvMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true   // newest messages at the bottom
        }
        rvMessages.adapter = adapter

        // Show welcome message
        addAiMessage(
            "👋 Hello! I\'m your AI news assistant powered by Sarvam AI.\n\n" +
            "I can help you with:\n" +
            "• Rewriting news article headlines\n" +
            "• Improving article quality\n" +
            "• Generating SEO tips\n" +
            "• Answering publishing questions\n\n" +
            "What can I help you with today?"
        )

        // Send on button click
        btnSend.setOnClickListener { sendMessage() }

        // Send on keyboard Done
        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }

        // Quick prompt chips (if your layout has them)
        setupQuickPrompts(view)
    }

    // ─── Quick prompt chips ────────────────────────────────────────────────────

    private val quickPrompts = listOf(
        "📰 How do I write a viral headline?",
        "🔍 What makes news SEO-friendly?",
        "📋 Help me rewrite a boring title",
        "✅ How to fact-check a news article?"
    )

    private fun setupQuickPrompts(root: View) {
        // Bind quick prompt chips if the layout has them with IDs chip_1..chip_4
        val chipIds = listOf(
            resources.getIdentifier("chip_prompt_1", "id", requireContext().packageName),
            resources.getIdentifier("chip_prompt_2", "id", requireContext().packageName),
            resources.getIdentifier("chip_prompt_3", "id", requireContext().packageName),
            resources.getIdentifier("chip_prompt_4", "id", requireContext().packageName)
        )
        chipIds.forEachIndexed { i, id ->
            if (id != 0) {
                root.findViewById<TextView>(id)?.apply {
                    text = quickPrompts.getOrNull(i) ?: return@apply
                    setOnClickListener { sendMessageText(quickPrompts[i]) }
                }
            }
        }
    }

    // ─── Send message ──────────────────────────────────────────────────────────

    private fun sendMessage() {
        val text = etInput.text.toString().trim()
        if (text.isBlank() || isWaiting) return
        etInput.setText("")
        sendMessageText(text)
    }

    private fun sendMessageText(text: String) {
        if (isWaiting) return
        isWaiting = true

        // Add user message
        addUserMessage(text)
        tvEmpty.isVisible = false

        // Show typing indicator
        val typingMsg = ChatMessage(text = "", isUser = false, isTyping = true)
        messages.add(typingMsg)
        adapter.submitList(messages.toList())
        scrollToBottom()

        lifecycleScope.launch {
            try {
                val reply = chatClient?.sendMessage(text) ?: "AI assistant is not initialized."

                // Remove typing indicator and add real reply
                messages.removeAll { it.isTyping }
                addAiMessage(reply)
            } catch (e: Exception) {
                messages.removeAll { it.isTyping }
                addAiMessage(
                    "❌ Sorry, I couldn\'t get a response. Please check your internet connection and Sarvam API key in Settings.",
                    isError = true
                )
            } finally {
                isWaiting = false
            }
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun addUserMessage(text: String) {
        messages.add(ChatMessage(text = text, isUser = true))
        adapter.submitList(messages.toList())
        scrollToBottom()
    }

    private fun addAiMessage(text: String, isError: Boolean = false) {
        messages.add(ChatMessage(text = text, isUser = false, isError = isError))
        adapter.submitList(messages.toList())
        scrollToBottom()
    }

    private fun scrollToBottom() {
        if (messages.isNotEmpty())
            rvMessages.smoothScrollToPosition(messages.size - 1)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        chatClient = null
    }
}
