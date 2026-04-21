package com.nexuzy.publisher.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nexuzy.publisher.R
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import com.nexuzy.publisher.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var keyManager: ApiKeyManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        keyManager = ApiKeyManager(this)
        loadExistingKeys()
        setupSaveButton()
    }

    private fun loadExistingKeys() {
        binding.apply {
            // Gemini keys
            etGeminiKey1.setText(keyManager.getGeminiKey(1))
            etGeminiKey2.setText(keyManager.getGeminiKey(2))
            etGeminiKey3.setText(keyManager.getGeminiKey(3))

            // OpenAI keys
            etOpenaiKey1.setText(keyManager.getOpenAiKey(1))
            etOpenaiKey2.setText(keyManager.getOpenAiKey(2))
            etOpenaiKey3.setText(keyManager.getOpenAiKey(3))

            // Sarvam key
            etSarvamKey.setText(keyManager.getSarvamKey())

            // WordPress
            etWpSiteUrl.setText(keyManager.getWordPressSiteUrl())
            etWpUsername.setText(keyManager.getWordPressUsername())
            etWpPassword.setText(keyManager.getWordPressPassword())
        }
    }

    private fun setupSaveButton() {
        binding.btnSaveSettings.setOnClickListener {
            binding.apply {
                // Save Gemini keys (up to 3)
                keyManager.setGeminiKey(1, etGeminiKey1.text.toString().trim())
                keyManager.setGeminiKey(2, etGeminiKey2.text.toString().trim())
                keyManager.setGeminiKey(3, etGeminiKey3.text.toString().trim())

                // Save OpenAI keys (up to 3)
                keyManager.setOpenAiKey(1, etOpenaiKey1.text.toString().trim())
                keyManager.setOpenAiKey(2, etOpenaiKey2.text.toString().trim())
                keyManager.setOpenAiKey(3, etOpenaiKey3.text.toString().trim())

                // Save Sarvam key
                keyManager.setSarvamKey(etSarvamKey.text.toString().trim())

                // Save WordPress credentials
                keyManager.setWordPressSiteUrl(etWpSiteUrl.text.toString().trim())
                keyManager.setWordPressUsername(etWpUsername.text.toString().trim())
                keyManager.setWordPressPassword(etWpPassword.text.toString().trim())
            }

            // Reset rotation indexes when keys are updated
            keyManager.resetRotation()

            Toast.makeText(this, "✅ Settings saved!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
