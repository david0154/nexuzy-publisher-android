package com.nexuzy.publisher.data.firebase

import android.util.Base64
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.nexuzy.publisher.data.model.firebase.FirebaseRssLink
import com.nexuzy.publisher.data.model.firebase.FirebaseUserProfile
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import kotlinx.coroutines.tasks.await

class FirebaseUserRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {

    // ──────────────────────────────────────────────────────────────
    // User profile (auth info)
    // ──────────────────────────────────────────────────────────────

    suspend fun upsertUserProfile(profile: FirebaseUserProfile) {
        firestore.collection(USERS)
            .document(profile.uid)
            .set(profile, SetOptions.merge())
            .await()
    }

    suspend fun getUserProfile(uid: String): FirebaseUserProfile? {
        val doc = firestore.collection(USERS).document(uid).get().await()
        return if (doc.exists()) doc.toObject(FirebaseUserProfile::class.java) else null
    }

    // ──────────────────────────────────────────────────────────────
    // Settings backup: WordPress + API keys → Firestore
    // ──────────────────────────────────────────────────────────────

    /**
     * Saves WordPress credentials and API key hints to Firestore.
     * Call this from SettingsActivity after the user taps Save.
     *
     * Full API keys are NEVER sent to Firestore — only masked hints
     * (first 8 chars + "..." + last 4 chars) are stored so the user
     * can see which keys were configured on a new device.
     *
     * WordPress app password is Base64-encoded for backup convenience.
     * Re-encode on restore.
     */
    suspend fun saveUserSettings(uid: String, keyManager: ApiKeyManager, appVersion: String = "") {
        val geminiKeys = keyManager.getGeminiKeys()
        val openAiKeys = keyManager.getOpenAiKeys()
        val sarvamKey  = keyManager.getSarvamKey()
        val wpUrl      = keyManager.getWordPressSiteUrl()
        val wpUser     = keyManager.getWordPressUsername()
        val wpPass     = keyManager.getWordPressPassword()
        val adsCode    = keyManager.getWordPressAdsCode()

        val update = mapOf(
            // WordPress
            "wpSiteUrl"      to wpUrl,
            "wpUsername"     to wpUser,
            "wpAppPassword"  to if (wpPass.isNotBlank())
                Base64.encodeToString(wpPass.toByteArray(), Base64.NO_WRAP)
            else "",
            "wpLoginEnabled" to keyManager.isWordPressLoginEnabled(),
            "adsCode"        to adsCode,

            // Gemini hints
            "geminiKeyCount" to geminiKeys.size,
            "geminiKey1Hint" to geminiKeys.getOrNull(0).maskKey(),
            "geminiKey2Hint" to geminiKeys.getOrNull(1).maskKey(),
            "geminiKey3Hint" to geminiKeys.getOrNull(2).maskKey(),

            // OpenAI hints
            "openAiKeyCount" to openAiKeys.size,
            "openAiKey1Hint" to openAiKeys.getOrNull(0).maskKey(),
            "openAiKey2Hint" to openAiKeys.getOrNull(1).maskKey(),
            "openAiKey3Hint" to openAiKeys.getOrNull(2).maskKey(),

            // Sarvam hint
            "hasSarvamKey"  to sarvamKey.isNotBlank(),
            "sarvamKeyHint" to sarvamKey.maskKey(),

            // Metadata
            "updatedAt"  to System.currentTimeMillis(),
            "appVersion" to appVersion
        )

        firestore.collection(USERS)
            .document(uid)
            .set(update, SetOptions.merge())
            .await()
    }

    /**
     * Restores WordPress credentials from Firestore into local ApiKeyManager.
     * Call this after Google Sign-In on a fresh device install.
     * The user will still need to re-enter their full API keys
     * (we only store hints, not the real keys, for security).
     */
    suspend fun restoreUserSettings(uid: String, keyManager: ApiKeyManager) {
        val profile = getUserProfile(uid) ?: return

        // Restore WordPress credentials
        if (profile.wpSiteUrl.isNotBlank()) {
            keyManager.setWordPressSiteUrl(profile.wpSiteUrl)
        }
        if (profile.wpUsername.isNotBlank()) {
            keyManager.setWordPressUsername(profile.wpUsername)
        }
        if (profile.wpAppPassword.isNotBlank()) {
            try {
                val decoded = String(Base64.decode(profile.wpAppPassword, Base64.NO_WRAP))
                keyManager.setWordPressPassword(decoded)
            } catch (_: Exception) {
                // Corrupt backup — skip password restore
            }
        }
        if (profile.adsCode.isNotBlank()) {
            keyManager.setWordPressAdsCode(profile.adsCode)
        }
        keyManager.setWordPressLoginEnabled(profile.wpLoginEnabled)
        // API keys are NOT restored — user must re-enter them.
        // The hints (geminiKey1Hint etc.) are shown in SettingsActivity
        // as placeholder text so the user knows which keys were previously set.
    }

    // ──────────────────────────────────────────────────────────────
    // RSS links
    // ──────────────────────────────────────────────────────────────

    suspend fun addRssLink(uid: String, link: FirebaseRssLink) {
        val id = link.id.ifBlank {
            firestore.collection(USERS).document(uid).collection(RSS_LINKS).document().id
        }
        firestore.collection(USERS)
            .document(uid)
            .collection(RSS_LINKS)
            .document(id)
            .set(link.copy(id = id), SetOptions.merge())
            .await()
    }

    suspend fun deleteRssLink(uid: String, rssId: String) {
        firestore.collection(USERS)
            .document(uid)
            .collection(RSS_LINKS)
            .document(rssId)
            .delete()
            .await()
    }

    suspend fun getRssLinks(uid: String): List<FirebaseRssLink> {
        val snapshot = firestore.collection(USERS)
            .document(uid)
            .collection(RSS_LINKS)
            .get()
            .await()
        return snapshot.documents.mapNotNull { doc ->
            doc.toObject(FirebaseRssLink::class.java)?.copy(id = doc.id)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    /** Returns "AIza...WXYZ" style hint — safe to store in Firestore */
    private fun String?.maskKey(): String {
        if (this == null || this.length < 12) return ""
        return "${this.take(8)}...${this.takeLast(4)}"
    }

    companion object {
        private const val USERS     = "users"
        private const val RSS_LINKS = "rss_links"
    }
}
