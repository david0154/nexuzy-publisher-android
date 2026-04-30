package com.nexuzy.publisher.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nexuzy.publisher.data.firebase.FirestoreUserRepository
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import com.nexuzy.publisher.data.prefs.AppPreferences
import com.nexuzy.publisher.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var keyManager: ApiKeyManager
    private lateinit var appPreferences: AppPreferences
    private lateinit var firestoreRepo: FirestoreUserRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        keyManager    = ApiKeyManager(this)
        appPreferences = AppPreferences(this)
        firestoreRepo = FirestoreUserRepository(keyManager, appPreferences)

        // First: load local (instant, no network wait)
        loadExistingKeys()

        // Then: silently restore from Firestore in background.
        // If Firestore has newer/missing keys (e.g. new device), they get applied
        // and the UI fields are refreshed automatically.
        restoreFromFirestoreIfNeeded()

        setupSaveButton()
    }

    /**
     * Load from Firestore in the background.
     * Only called if any key is locally empty — avoids unnecessary Firestore reads.
     * If Firestore has data, it is written to local prefs and UI is refreshed.
     */
    private fun restoreFromFirestoreIfNeeded() {
        val anyEmpty = keyManager.getGeminiKeys().isEmpty() ||
                       keyManager.getOpenAiKeys().isEmpty() ||
                       keyManager.getSarvamKey().isBlank()  ||
                       keyManager.getWordPressSiteUrl().isBlank()

        if (!anyEmpty) return  // All keys already in local prefs — skip Firestore read

        lifecycleScope.launch {
            val loaded = firestoreRepo.loadApiKeysFromFirestore()
            if (loaded) {
                // Firestore had data — refresh UI fields to show restored keys
                runOnUiThread { loadExistingKeys() }
                Toast.makeText(
                    this@SettingsActivity,
                    "\uD83D\uDD04 Settings restored from your account",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
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

            etSupportEmail.setText(appPreferences.supportEmail)
            etPrivacyPolicyUrl.setText(appPreferences.privacyPolicyUrl)
        }
        setOptionalText("etWpAdsCode", keyManager.getWordPressAdsCode())
    }

    private fun setupSaveButton() {
        binding.btnSaveSettings.setOnClickListener {

            // 1. Save to local EncryptedSharedPreferences (instant)
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

                appPreferences.supportEmail      = etSupportEmail.text.toString().trim()
                appPreferences.privacyPolicyUrl  = etPrivacyPolicyUrl.text.toString().trim()
            }
            keyManager.setWordPressAdsCode(getOptionalText("etWpAdsCode"))
            keyManager.resetRotation()

            // Instant local feedback
            Toast.makeText(this, "\u2705 Settings saved!", Toast.LENGTH_SHORT).show()

            // 2. Sync to Firestore in background (non-blocking)
            lifecycleScope.launch {
                val synced = firestoreRepo.saveApiKeysToFirestore()
                if (!synced) {
                    // Firestore sync failed — local save still worked, warn user
                    runOnUiThread {
                        Toast.makeText(
                            this@SettingsActivity,
                            "\u26A0\uFE0F Saved locally. Cloud sync failed — check internet.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(
                            this@SettingsActivity,
                            "\u2601\uFE0F Settings synced to your account!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun getOptionalText(viewIdName: String): String {
        val id = resources.getIdentifier(viewIdName, "id", packageName)
        if (id == 0) return ""
        return findViewById<EditText>(id)?.text?.toString()?.trim().orEmpty()
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
