package com.nexuzy.publisher.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Manages API keys and integration settings.
 *
 * Key rotation logic:
 * - getActiveGeminiKey()       → returns current key in rotation
 * - rotateGeminiKeyOnQuota()   → call on 429/quota error; moves to next key
 * - withGeminiKeyRotation {}   → auto-retries all keys on quota errors (USE THIS in AiPipeline)
 */
class ApiKeyManager(context: Context) {

    private val prefs: SharedPreferences = createPrefs(context)

    private fun createPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "nexuzy_api_keys_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            context.getSharedPreferences("nexuzy_api_keys", Context.MODE_PRIVATE)
        }
    }

    // ── Gemini ────────────────────────────────────────────────────────────────
    fun setGeminiKey(index: Int, key: String) = prefs.edit { putString("gemini_key_$index", key) }
    fun getGeminiKey(index: Int): String = prefs.getString("gemini_key_$index", "") ?: ""
    fun getGeminiKeys(): List<String> = (1..3).map { getGeminiKey(it) }.filter { it.isNotBlank() }

    // ── OpenAI ────────────────────────────────────────────────────────────────
    fun setOpenAiKey(index: Int, key: String) = prefs.edit { putString("openai_key_$index", key) }
    fun getOpenAiKey(index: Int): String = prefs.getString("openai_key_$index", "") ?: ""
    fun getOpenAiKeys(): List<String> = (1..3).map { getOpenAiKey(it) }.filter { it.isNotBlank() }

    // ── Sarvam ────────────────────────────────────────────────────────────────
    fun setSarvamKey(key: String) = prefs.edit { putString("sarvam_key", key) }
    fun getSarvamKey(): String = prefs.getString("sarvam_key", "") ?: ""

    // ── Perplexity ────────────────────────────────────────────────────────────
    fun setPerplexityKey(index: Int, key: String) = prefs.edit { putString("perplexity_key_$index", key) }
    fun getPerplexityKey(index: Int): String = prefs.getString("perplexity_key_$index", "") ?: ""
    fun getPerplexityKeys(): List<String> = (1..3).map { getPerplexityKey(it) }.filter { it.isNotBlank() }

    // ── Replit ────────────────────────────────────────────────────────────────
    fun setReplitKey(index: Int, key: String) = prefs.edit { putString("replit_key_$index", key) }
    fun getReplitKey(index: Int): String = prefs.getString("replit_key_$index", "") ?: ""
    fun getReplitKeys(): List<String> = (1..3).map { getReplitKey(it) }.filter { it.isNotBlank() }

    // ── Google Custom Search ──────────────────────────────────────────────────
    fun setGoogleSearchApiKey(key: String) = prefs.edit { putString("google_search_api_key", key) }
    fun getGoogleSearchApiKey(): String = prefs.getString("google_search_api_key", "") ?: ""

    fun setGoogleSearchCseId(id: String) = prefs.edit { putString("google_search_cse_id", id) }
    fun getGoogleSearchCseId(): String = prefs.getString("google_search_cse_id", "") ?: ""

    // ── Other services ────────────────────────────────────────────────────────
    fun setMapsApiKey(key: String) = prefs.edit { putString("maps_api_key", key) }
    fun getMapsApiKey(): String = prefs.getString("maps_api_key", "") ?: ""

    fun setWeatherApiKey(key: String) = prefs.edit { putString("weather_api_key", key) }
    fun getWeatherApiKey(): String = prefs.getString("weather_api_key", "") ?: ""

    // ── Google Sign-In Web Client ID ──────────────────────────────────────────
    fun setGoogleWebClientId(id: String) = prefs.edit { putString("google_web_client_id", id) }
    fun getGoogleWebClientId(): String = prefs.getString("google_web_client_id", "") ?: ""

    // ── Generic helpers ───────────────────────────────────────────────────────
    fun setProviderKey(provider: String, index: Int, key: String) =
        prefs.edit { putString("${provider.lowercase()}_key_$index", key) }

    fun getProviderKey(provider: String, index: Int): String =
        prefs.getString("${provider.lowercase()}_key_$index", "") ?: ""

    fun getProviderKeys(provider: String, maxKeys: Int = 3): List<String> =
        (1..maxKeys).map { getProviderKey(provider, it) }.filter { it.isNotBlank() }

    // ── Key rotation ──────────────────────────────────────────────────────────

    private fun getCurrentGeminiIndex(): Int = prefs.getInt("gemini_current_index", 0)
    private fun getCurrentOpenAiIndex(): Int  = prefs.getInt("openai_current_index", 0)

    fun getActiveGeminiKey(): String? {
        val keys = getGeminiKeys()
        if (keys.isEmpty()) return null
        return keys[getCurrentGeminiIndex() % keys.size]
    }

    fun getActiveOpenAiKey(): String? {
        val keys = getOpenAiKeys()
        if (keys.isEmpty()) return null
        return keys[getCurrentOpenAiIndex() % keys.size]
    }

    /**
     * Call when you get a 429/quota error on the current Gemini key.
     * Advances to the next key.
     * Returns the next key string, or null if all keys have been cycled through.
     */
    fun rotateGeminiKeyOnQuota(): String? {
        val keys = getGeminiKeys()
        if (keys.isEmpty()) return null
        val current = getCurrentGeminiIndex()
        val next    = (current + 1) % keys.size
        prefs.edit { putInt("gemini_current_index", next) }
        return if (next == 0) null else keys[next]
    }

    // Keep old callers working
    fun rotateGeminiKey(): String? = rotateGeminiKeyOnQuota()

    fun rotateOpenAiKey(): String? {
        val keys = getOpenAiKeys()
        if (keys.isEmpty()) return null
        val current = getCurrentOpenAiIndex()
        val next    = (current + 1) % keys.size
        prefs.edit { putInt("openai_current_index", next) }
        return if (next == 0) null else keys[next]
    }

    /**
     * USE THIS in AiPipeline instead of getActiveGeminiKey().
     *
     * Automatically tries every saved Gemini key in order.
     * If a key returns a 429/quota error it silently rotates to the next key.
     * Throws only when ALL keys are exhausted.
     */
    suspend fun <T> withGeminiKeyRotation(block: suspend (key: String) -> T): T {
        val keys = getGeminiKeys()
        require(keys.isNotEmpty()) { "No Gemini API keys configured. Please add keys in Settings." }
        var lastException: Exception? = null
        for (i in keys.indices) {
            val key = keys[(getCurrentGeminiIndex() + i) % keys.size]
            try {
                return block(key)
            } catch (e: Exception) {
                val msg = e.message?.lowercase() ?: ""
                val isQuota = msg.contains("429") || msg.contains("quota") ||
                              msg.contains("exhausted") || msg.contains("resource_exhausted") ||
                              msg.contains("rate_limit") || msg.contains("too many requests")
                if (isQuota) {
                    val nextIdx = (getCurrentGeminiIndex() + i + 1) % keys.size
                    prefs.edit { putInt("gemini_current_index", nextIdx) }
                    lastException = e
                    continue
                }
                throw e
            }
        }
        throw lastException
            ?: Exception("All ${keys.size} Gemini API key(s) exhausted (quota exceeded). Add more keys in Settings.")
    }

    /**
     * Same auto-rotation for OpenAI keys.
     */
    suspend fun <T> withOpenAiKeyRotation(block: suspend (key: String) -> T): T {
        val keys = getOpenAiKeys()
        require(keys.isNotEmpty()) { "No OpenAI API keys configured. Please add keys in Settings." }
        var lastException: Exception? = null
        for (i in keys.indices) {
            val key = keys[(getCurrentOpenAiIndex() + i) % keys.size]
            try {
                return block(key)
            } catch (e: Exception) {
                val msg = e.message?.lowercase() ?: ""
                val isQuota = msg.contains("429") || msg.contains("quota") ||
                              msg.contains("insufficient_quota") || msg.contains("rate_limit") ||
                              msg.contains("too many requests")
                if (isQuota) {
                    val nextIdx = (getCurrentOpenAiIndex() + i + 1) % keys.size
                    prefs.edit { putInt("openai_current_index", nextIdx) }
                    lastException = e
                    continue
                }
                throw e
            }
        }
        throw lastException
            ?: Exception("All ${keys.size} OpenAI API key(s) exhausted (quota exceeded). Add more keys in Settings.")
    }

    fun resetRotation() {
        prefs.edit {
            putInt("gemini_current_index", 0)
            putInt("openai_current_index", 0)
        }
    }

    // ── WordPress ─────────────────────────────────────────────────────────────
    fun setWordPressSiteUrl(url: String) = prefs.edit { putString("wp_site_url", url) }
    fun getWordPressSiteUrl(): String = prefs.getString("wp_site_url", "") ?: ""
    fun setWordPressUsername(u: String) = prefs.edit { putString("wp_username", u) }
    fun getWordPressUsername(): String = prefs.getString("wp_username", "") ?: ""
    fun setWordPressPassword(p: String) = prefs.edit { putString("wp_password", p) }
    fun getWordPressPassword(): String = prefs.getString("wp_password", "") ?: ""

    fun setWordPressLoginEnabled(enabled: Boolean) = prefs.edit { putBoolean("wp_login_enabled", enabled) }
    fun isWordPressLoginEnabled(): Boolean = prefs.getBoolean("wp_login_enabled", true)

    fun setWordPressAdsCode(code: String) = prefs.edit { putString("wp_ads_code", code) }
    fun getWordPressAdsCode(): String = prefs.getString("wp_ads_code", "") ?: ""
}
