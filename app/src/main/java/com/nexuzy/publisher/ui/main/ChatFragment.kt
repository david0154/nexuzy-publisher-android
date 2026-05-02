package com.nexuzy.publisher.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nexuzy.publisher.R
import com.nexuzy.publisher.databinding.FragmentChatBinding
import com.nexuzy.publisher.network.SarvamChatClient
import kotlinx.coroutines.launch

/**
 * ChatFragment — AI assistant powered by Sarvam AI (sarvam-m).
 *
 * Features:
 *   - Full chat message history in RecyclerView
 *   - User messages (right-aligned) + AI replies (left-aligned)
 *   - Typing indicator while waiting for AI
 *   - Suggested quick prompts for news publishing tasks
 */
class ChatFragment : Fragment() {

    // ─── View Binding ──────────────────────────────────────────────────────────────────

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    // ─── Message model ─────────────────────────────────────────────────────────────

    data class ChatMessage(
        val id: Long = System.currentTimeMillis(),
        val text: String,
        val isUser: Boolean,
        val isError: Boolean = false,
        val isTyping: Boolean = false
    )

    // ─── Adapter ───────────────────────────────────────────────────────────────────

    inner class ChatAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(
        object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(a: ChatMessage, b: ChatMessage) = a.id == b.id
            override fun areContentsTheSame(a: ChatMessage, b: ChatMessage) = a == b
        }
    ) {
        private val USER_TYPE   = 1
        private val AI_TYPE     = 2
        private val TYPING_TYPE = 3

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
                is UserVH -> holder.bind(msg)
                is AiVH   -> holder.bind(msg)
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

    // ─── Fragment state ───────────────────────────────────────────────────────────

    private val chatAdapter = ChatAdapter()
    private val messages    = mutableListOf<ChatMessage>()
    private val conversationHistory = mutableListOf<Pair<String, String>>()
    private var isWaiting = false

    // ─── Lifecycle ─────────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvChatMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvChatMessages.adapter = chatAdapter

        // Welcome message
        addAiMessage(
            "\uD83D\uDC4B Hello! I'm your AI news assistant powered by Sarvam AI.\n\n" +
            "I can help you with:\n" +
            "\u2022 Rewriting news article headlines\n" +
            "\u2022 Improving article quality\n" +
            "\u2022 Generating SEO tips\n" +
            "\u2022 Answering publishing questions\n\n" +
            "What can I help you with today?"
        )

        binding.btnSendMessage.setOnClickListener { sendMessage() }

        binding.etChatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }

        setupQuickPrompts(view)
    }

    // ─── Quick prompt chips ─────────────────────────────────────────────────────

    private val quickPrompts = listOf(
        "\uD83D\uDCF0 How do I write a viral headline?",
        "\uD83D\uDD0D What makes news SEO-friendly?",
        "\uD83D\uDCCB Help me rewrite a boring title",
        "\u2705 How to fact-check a news article?"
    )

    private fun setupQuickPrompts(root: View) {
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

    // ─── Send message ────────────────────────────────────────────────────────────

    private fun sendMessage() {
        val text = binding.etChatInput.text.toString().trim()
        if (text.isBlank() || isWaiting) return
        binding.etChatInput.setText("")
        sendMessageText(text)
    }

    private fun sendMessageText(text: String) {
        if (isWaiting) return
        isWaiting = true

        addUserMessage(text)
        conversationHistory.add(Pair("user", text))
        binding.tvChatEmpty.isVisible = false

        val typingMsg = ChatMessage(text = "", isUser = false, isTyping = true)
        messages.add(typingMsg)
        chatAdapter.submitList(messages.toList())
        scrollToBottom()

        lifecycleScope.launch {
            try {
                val result = SarvamChatClient.chat(conversationHistory.toList())
                messages.removeAll { it.isTyping }

                if (result.success) {
                    conversationHistory.add(Pair("assistant", result.reply))
                    addAiMessage(result.reply)
                } else {
                    addAiMessage(
                        "\u274C ${result.error.ifBlank { "Sorry, I couldn't get a response. Check your internet and Sarvam API key in Settings." }}",
                        isError = true
                    )
                }
            } catch (e: Exception) {
                messages.removeAll { it.isTyping }
                addAiMessage(
                    "\u274C Sorry, I couldn't get a response. Please check your internet connection and Sarvam API key in Settings.",
                    isError = true
                )
            } finally {
                isWaiting = false
            }
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────────

    private fun addUserMessage(text: String) {
        messages.add(ChatMessage(text = text, isUser = true))
        chatAdapter.submitList(messages.toList())
        scrollToBottom()
    }

    private fun addAiMessage(text: String, isError: Boolean = false) {
        messages.add(ChatMessage(text = text, isUser = false, isError = isError))
        chatAdapter.submitList(messages.toList())
        scrollToBottom()
    }

    private fun scrollToBottom() {
        if (messages.isNotEmpty())
            binding.rvChatMessages.smoothScrollToPosition(messages.size - 1)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
