package com.nexuzy.publisher.ui.davidai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.nexuzy.publisher.R
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import com.nexuzy.publisher.databinding.ActivityDavidAiChatBinding
import com.nexuzy.publisher.network.ArticleGeneratorClient
import com.nexuzy.publisher.network.SarvamChatClient
import com.nexuzy.publisher.network.WeatherClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * David AI Chat Screen
 *
 * A ChatGPT-style in-app AI assistant branded as "David AI".
 * Powered by Sarvam AI for conversation and Gemini for article generation.
 *
 * FIX: SarvamChatClient.chat() now receives the Sarvam key from ApiKeyManager
 *      so it uses the key the user saved in Settings, not a hardcoded placeholder.
 */
class DavidAiChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDavidAiChatBinding
    private lateinit var adapter: ChatMessageAdapter
    private lateinit var keyManager: ApiKeyManager

    private val conversationHistory = mutableListOf<Pair<String, String>>()

    /** Live weather context string — injected as system context to Sarvam */
    private var weatherContext: String = ""

    /** Permission launcher for location */
    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) fetchWeatherSilently()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDavidAiChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        keyManager = ApiKeyManager(this)

        setSupportActionBar(binding.toolbarDavidAi)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = ""
        }

        setupRecyclerView()
        setupInput()
        showWelcomeMessage()
        requestLocationAndFetchWeather()
    }

    // ── Weather ────────────────────────────────────────────────────────────────

    private fun requestLocationAndFetchWeather() {
        val fine   = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
        val hasFine   = ContextCompat.checkSelfPermission(this, fine)   == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, coarse) == PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            fetchWeatherSilently()
        } else {
            locationPermLauncher.launch(arrayOf(fine, coarse))
        }
    }

    private fun fetchWeatherSilently() {
        lifecycleScope.launch(Dispatchers.IO) {
            val weather = WeatherClient.getWeather(this@DavidAiChatActivity)
            if (weather != null) {
                weatherContext = weather.summary()
            }
        }
    }

    // ── RecyclerView & Input ─────────────────────────────────────────────────

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
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
        }
    }

    private fun showWelcomeMessage() {
        adapter.addMessage(
            ChatMessage(
                content = "Hello! I'm David AI, your intelligent news assistant created by David and " +
                    "powered by Nexuzy Lab.\n\nI can help you with:\n" +
                    "• News article writing & ideas\n" +
                    "• 🌦️ Real-time weather for your location\n" +
                    "• 📝 Article generation — try: \"generate article about [topic]\"\n" +
                    "• SEO optimization tips\n" +
                    "• WordPress publishing help\n" +
                    "• Fact-checking & research\n" +
                    "• General knowledge questions\n\n" +
                    "What would you like today?",
                isUser = false
            )
        )
    }

    // ── Message sending ────────────────────────────────────────────────────────

    private fun sendMessage() {
        val text = binding.etMessage.text?.toString()?.trim()
        if (text.isNullOrEmpty()) return

        adapter.addMessage(ChatMessage(content = text, isUser = true))
        binding.etMessage.setText("")
        scrollToBottom()

        setInputEnabled(false)

        val articleTopic = ArticleGeneratorClient.extractTopic(text)
        if (articleTopic != null) {
            handleArticleGeneration(text, articleTopic)
        } else {
            handleNormalChat(text)
        }
    }

    // ── Article Generation Handler ───────────────────────────────────────────

    private fun handleArticleGeneration(originalText: String, topic: String) {
        val finalTopic = topic.ifBlank {
            setInputEnabled(true)
            adapter.addMessage(ChatMessage(
                content = "📝 What topic should I write about?\n" +
                    "Example: \"generate article about climate change in India\"",
                isUser = false
            ))
            scrollToBottom()
            return
        }

        adapter.addMessage(ChatMessage(
            content = "⏳ Generating article about \"$finalTopic\"... This may take 10-20 seconds.",
            isUser = false
        ))
        scrollToBottom()

        lifecycleScope.launch {
            val draft = withContext(Dispatchers.IO) {
                ArticleGeneratorClient.generate(
                    topic      = finalTopic,
                    weatherCtx = weatherContext,
                    keyManager = keyManager
                )
            }
            setInputEnabled(true)
            adapter.addMessage(ChatMessage(content = draft.toChatReply(), isUser = false))
            scrollToBottom()
        }
    }

    // ── Normal Sarvam Chat Handler ────────────────────────────────────────────

    private fun handleNormalChat(text: String) {
        val weatherTriggers = listOf("weather", "temperature", "rain", "sunny", "cold", "hot", "humid", "wind", "forecast", "climate")
        val isWeatherQuery = weatherTriggers.any { text.lowercase().contains(it) }

        val contextualText = if (isWeatherQuery && weatherContext.isNotBlank()) {
            "[System context — real-time data: $weatherContext]\n\nUser question: $text"
        } else text

        conversationHistory.add(Pair("user", contextualText))
        binding.tvTyping.visibility = View.VISIBLE

        // Read Sarvam key from ApiKeyManager (Settings) — NOT hardcoded
        val sarvamKey = keyManager.getSarvamKey()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                SarvamChatClient.chat(
                    history    = conversationHistory.toList(),
                    sarvamKey  = sarvamKey
                )
            }

            binding.tvTyping.visibility = View.GONE
            setInputEnabled(true)

            if (result.success) {
                adapter.addMessage(ChatMessage(content = result.reply, isUser = false))
                conversationHistory.add(Pair("assistant", result.reply))
                scrollToBottom()
            } else {
                Toast.makeText(this@DavidAiChatActivity, "David AI: ${result.error}", Toast.LENGTH_LONG).show()
                if (conversationHistory.isNotEmpty())
                    conversationHistory.removeAt(conversationHistory.size - 1)
            }
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun newChat() {
        conversationHistory.clear()
        adapter.clearAll()
        showWelcomeMessage()
        fetchWeatherSilently()
    }

    private fun setInputEnabled(enabled: Boolean) {
        binding.btnSend.isEnabled    = enabled
        binding.etMessage.isEnabled  = enabled
        binding.tvTyping.visibility  = if (enabled) View.GONE else View.VISIBLE
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
            android.R.id.home    -> { onBackPressedDispatcher.onBackPressed(); true }
            R.id.action_new_chat -> { newChat(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
