package com.nexuzy.publisher.ui.davidai

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.nexuzy.publisher.R
import com.nexuzy.publisher.databinding.ActivityDavidAiChatBinding
import com.nexuzy.publisher.network.SarvamChatClient
import kotlinx.coroutines.launch

/**
 * David AI Chat Screen
 *
 * A ChatGPT / Gemini-style in-app AI assistant branded as "David AI".
 * Powered by Sarvam AI via a developer pre-embedded API key.
 * End users do NOT need to configure any API key to use this feature.
 *
 * Branding  : David AI
 * Powered by: Nexuzy Lab (developer: David)
 * Engine    : Sarvam AI (sarvam-m model)
 */
class DavidAiChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDavidAiChatBinding
    private lateinit var adapter: ChatMessageAdapter

    // Full conversation history sent to Sarvam AI on each turn
    // List of (role, content) — role is "user" or "assistant"
    private val conversationHistory = mutableListOf<Pair<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDavidAiChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarDavidAi)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = ""
        }

        setupRecyclerView()
        setupInput()
        showWelcomeMessage()
    }

    private fun setupRecyclerView() {
        adapter = ChatMessageAdapter()
        binding.rvChat.apply {
            layoutManager = LinearLayoutManager(this@DavidAiChatActivity).also {
                it.stackFromEnd = true
            }
            adapter = this@DavidAiChatActivity.adapter
        }
    }

    private fun setupInput() {
        binding.btnSend.setOnClickListener { sendMessage() }

        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }
    }

    private fun showWelcomeMessage() {
        adapter.addMessage(
            ChatMessage(
                content = "Hello! I'm David AI, your intelligent assistant created by David and" +
                    " powered by Nexuzy Lab.\n\nI can help you with:\n" +
                    "\u2022 News article writing & ideas\n" +
                    "\u2022 SEO optimization tips\n" +
                    "\u2022 WordPress publishing help\n" +
                    "\u2022 Fact-checking & research\n" +
                    "\u2022 General knowledge questions\n\n" +
                    "What would you like to know today?",
                isUser = false
            )
        )
    }

    private fun sendMessage() {
        val text = binding.etMessage.text?.toString()?.trim()
        if (text.isNullOrEmpty()) return

        // 1. Add user message to chat
        adapter.addMessage(ChatMessage(content = text, isUser = true))
        conversationHistory.add(Pair("user", text))
        binding.etMessage.setText("")
        scrollToBottom()

        // 2. Show typing indicator, disable send button
        binding.tvTyping.visibility = View.VISIBLE
        binding.btnSend.isEnabled = false
        binding.etMessage.isEnabled = false

        lifecycleScope.launch {
            val result = SarvamChatClient.chat(conversationHistory.toList())

            // 3. Hide typing indicator, re-enable input
            binding.tvTyping.visibility = View.GONE
            binding.btnSend.isEnabled = true
            binding.etMessage.isEnabled = true

            if (result.success) {
                // 4. Add AI reply to chat
                adapter.addMessage(ChatMessage(content = result.reply, isUser = false))
                conversationHistory.add(Pair("assistant", result.reply))
                scrollToBottom()
            } else {
                Toast.makeText(
                    this@DavidAiChatActivity,
                    "David AI: ${result.error}",
                    Toast.LENGTH_LONG
                ).show()
                // Roll back the user message from history since we got no response
                if (conversationHistory.isNotEmpty())
                    conversationHistory.removeAt(conversationHistory.size - 1)
            }
        }
    }

    /** New Chat: clear messages and history, show welcome again. */
    private fun newChat() {
        conversationHistory.clear()
        adapter.clearAll()
        showWelcomeMessage()
    }

    private fun scrollToBottom() {
        val count = adapter.itemCount
        if (count > 0) binding.rvChat.smoothScrollToPosition(count - 1)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_david_ai, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { onBackPressedDispatcher.onBackPressed(); true }
            R.id.action_new_chat -> { newChat(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
