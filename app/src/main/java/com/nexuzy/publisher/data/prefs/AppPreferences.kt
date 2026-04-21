package com.nexuzy.publisher.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("nexuzy_prefs", Context.MODE_PRIVATE)

    var autoFetchRss: Boolean
        get() = prefs.getBoolean("auto_fetch_rss", true)
        set(value) = prefs.edit { putBoolean("auto_fetch_rss", value) }

    var fetchIntervalMinutes: Int
        get() = prefs.getInt("fetch_interval", 30)
        set(value) = prefs.edit { putInt("fetch_interval", value) }

    var defaultCategory: String
        get() = prefs.getString("default_category", "General") ?: "General"
        set(value) = prefs.edit { putString("default_category", value) }

    var autoPublish: Boolean
        get() = prefs.getBoolean("auto_publish", false)
        set(value) = prefs.edit { putBoolean("auto_publish", value) }

    var darkMode: Boolean
        get() = prefs.getBoolean("dark_mode", false)
        set(value) = prefs.edit { putBoolean("dark_mode", value) }

    var aiModel: String
        get() = prefs.getString("ai_model", "gemini-1.5-flash") ?: "gemini-1.5-flash"
        set(value) = prefs.edit { putString("ai_model", value) }

    var maxArticleWords: Int
        get() = prefs.getInt("max_words", 800)
        set(value) = prefs.edit { putInt("max_words", value) }

    var language: String
        get() = prefs.getString("language", "en") ?: "en"
        set(value) = prefs.edit { putString("language", value) }

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications", true)
        set(value) = prefs.edit { putBoolean("notifications", value) }

    var googleWebClientId: String
        get() = prefs.getString("google_web_client_id", "") ?: ""
        set(value) = prefs.edit { putString("google_web_client_id", value) }

    var supportEmail: String
        get() = prefs.getString("support_email", "nexuzylab@gmail.com") ?: "nexuzylab@gmail.com"
        set(value) = prefs.edit { putString("support_email", value) }

    var privacyPolicyUrl: String
        get() = prefs.getString("privacy_policy_url", DEFAULT_PRIVACY_URL) ?: DEFAULT_PRIVACY_URL
        set(value) = prefs.edit { putString("privacy_policy_url", value) }

    companion object {
        private const val DEFAULT_PRIVACY_URL =
            "https://github.com/david0154/nexuzy-publisher-android/blob/main/PRIVACY.md"
    }
}
