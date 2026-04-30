package com.nexuzy.publisher.ui.settings

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import com.nexuzy.publisher.data.prefs.AppPreferences
import com.nexuzy.publisher.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var keyManager: ApiKeyManager
    private lateinit var appPreferences: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        keyManager = ApiKeyManager(this)
        appPreferences = AppPreferences(this)
        loadExistingKeys()
        setupSaveButton()
    }

    private fun loadExistingKeys() {
        binding.apply {
            etGeminiKey1.setText(keyManager.getGeminiKey(1))
            etGeminiKey2.setText(keyManager.getGeminiKey(2))
            etGeminiKey3.setText(keyManager.getGeminiKey(3))

            etOpenaiKey1.setText(keyManager.getOpenAiKey(1))
            etOpenaiKey2.setText(keyManager.getOpenAiKey(2))
            etOpenaiKey3.setText(keyManager.getOpenAiKey(3))

            etSarvamKey.setText(keyManager.getSarvamKey())

            etWpSiteUrl.setText(keyManager.getWordPressSiteUrl())
            etWpUsername.setText(keyManager.getWordPressUsername())
            etWpPassword.setText(keyManager.getWordPressPassword())
            etGoogleWebClientId.setText(appPreferences.googleWebClientId)
            etSupportEmail.setText(appPreferences.supportEmail)
            etPrivacyPolicyUrl.setText(appPreferences.privacyPolicyUrl)
        }

        // Optional fields (only if XML includes them)
        setOptionalText("etWpAdsCode", keyManager.getWordPressAdsCode())
    }

    private fun setupSaveButton() {
        binding.btnSaveSettings.setOnClickListener {
            binding.apply {
                keyManager.setGeminiKey(1, etGeminiKey1.text.toString().trim())
                keyManager.setGeminiKey(2, etGeminiKey2.text.toString().trim())
                keyManager.setGeminiKey(3, etGeminiKey3.text.toString().trim())

                keyManager.setOpenAiKey(1, etOpenaiKey1.text.toString().trim())
                keyManager.setOpenAiKey(2, etOpenaiKey2.text.toString().trim())
                keyManager.setOpenAiKey(3, etOpenaiKey3.text.toString().trim())

                keyManager.setSarvamKey(etSarvamKey.text.toString().trim())

                keyManager.setWordPressSiteUrl(etWpSiteUrl.text.toString().trim())
                keyManager.setWordPressUsername(etWpUsername.text.toString().trim())
                keyManager.setWordPressPassword(etWpPassword.text.toString().trim())

                appPreferences.googleWebClientId = etGoogleWebClientId.text.toString().trim()
                appPreferences.supportEmail = etSupportEmail.text.toString().trim()
                appPreferences.privacyPolicyUrl = etPrivacyPolicyUrl.text.toString().trim()
            }

            // Optional fields (only if XML includes them)
            keyManager.setWordPressAdsCode(getOptionalText("etWpAdsCode"))

            keyManager.resetRotation()

            Toast.makeText(this, "✅ Settings saved!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getOptionalText(viewIdName: String): String {
        val id = resources.getIdentifier(viewIdName, "id", packageName)
        if (id == 0) return ""
        val field = findViewById<EditText>(id) ?: return ""
        return field.text?.toString()?.trim().orEmpty()
    }

    private fun setOptionalText(viewIdName: String, value: String) {
        val id = resources.getIdentifier(viewIdName, "id", packageName)
        if (id == 0) return
        findViewById<EditText>(id)?.setText(value)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
