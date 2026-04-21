package com.nexuzy.publisher.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Manages API keys and integration settings.
 *
 * Keep provider groups stable/append-only to stay merge-friendly across branches.
 */
class ApiKeyManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("nexuzy_api_keys", Context.MODE_PRIVATE)

    fun setGeminiKey(index: Int, key: String) = prefs.edit { putString("gemini_key_$index", key) }
    fun getGeminiKey(index: Int): String = prefs.getString("gemini_key_$index", "") ?: ""
    fun getGeminiKeys(): List<String> = (1..3).map { getGeminiKey(it) }.filter { it.isNotBlank() }

    fun setOpenAiKey(index: Int, key: String) = prefs.edit { putString("openai_key_$index", key) }
    fun getOpenAiKey(index: Int): String = prefs.getString("openai_key_$index", "") ?: ""
    fun getOpenAiKeys(): List<String> = (1..3).map { getOpenAiKey(it) }.filter { it.isNotBlank() }

    fun setSarvamKey(key: String) = prefs.edit { putString("sarvam_key", key) }
    fun getSarvamKey(): String = prefs.getString("sarvam_key", "") ?: ""

    fun setPerplexityKey(index: Int, key: String) = prefs.edit { putString("perplexity_key_$index", key) }
    fun getPerplexityKey(index: Int): String = prefs.getString("perplexity_key_$index", "") ?: ""
    fun getPerplexityKeys(): List<String> = (1..3).map { getPerplexityKey(it) }.filter { it.isNotBlank() }

    fun setReplitKey(index: Int, key: String) = prefs.edit { putString("replit_key_$index", key) }
    fun getReplitKey(index: Int): String = prefs.getString("replit_key_$index", "") ?: ""
    fun getReplitKeys(): List<String> = (1..3).map { getReplitKey(it) }.filter { it.isNotBlank() }

    fun setMapsApiKey(key: String) = prefs.edit { putString("maps_api_key", key) }
    fun getMapsApiKey(): String = prefs.getString("maps_api_key", "") ?: ""

    fun setWeatherApiKey(key: String) = prefs.edit { putString("weather_api_key", key) }
    fun getWeatherApiKey(): String = prefs.getString("weather_api_key", "") ?: ""

    /** Generic helpers to avoid merge conflicts when adding future providers. */
    fun setProviderKey(provider: String, index: Int, key: String) =
        prefs.edit { putString("${provider.lowercase()}_key_$index", key) }

    fun getProviderKey(provider: String, index: Int): String =
        prefs.getString("${provider.lowercase()}_key_$index", "") ?: ""

    fun getProviderKeys(provider: String, maxKeys: Int = 3): List<String> =
        (1..maxKeys).map { getProviderKey(provider, it) }.filter { it.isNotBlank() }

    private fun getCurrentGeminiIndex(): Int = prefs.getInt("gemini_current_index", 0)
    private fun getCurrentOpenAiIndex(): Int = prefs.getInt("openai_current_index", 0)

    fun getActiveGeminiKey(): String? {
        val keys = getGeminiKeys()
        if (keys.isEmpty()) return null
        return keys[getCurrentGeminiIndex() % keys.size]
    }

    fun rotateGeminiKey(): String? {
        val keys = getGeminiKeys()
        if (keys.isEmpty()) return null
        val current = getCurrentGeminiIndex()
        val next = (current + 1) % keys.size
        prefs.edit { putInt("gemini_current_index", next) }
        return if (next == 0 && current != 0) null else keys[next]
    }

    fun getActiveOpenAiKey(): String? {
        val keys = getOpenAiKeys()
        if (keys.isEmpty()) return null
        return keys[getCurrentOpenAiIndex() % keys.size]
    }

    fun rotateOpenAiKey(): String? {
        val keys = getOpenAiKeys()
        if (keys.isEmpty()) return null
        val current = getCurrentOpenAiIndex()
        val next = (current + 1) % keys.size
        prefs.edit { putInt("openai_current_index", next) }
        return if (next == 0 && current != 0) null else keys[next]
    }

    fun resetRotation() {
        prefs.edit {
            putInt("gemini_current_index", 0)
            putInt("openai_current_index", 0)
        }
    }

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
