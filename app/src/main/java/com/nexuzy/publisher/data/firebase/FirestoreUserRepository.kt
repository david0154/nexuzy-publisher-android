package com.nexuzy.publisher.data.firebase

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import com.nexuzy.publisher.data.prefs.AppPreferences
import kotlinx.coroutines.tasks.await

/**
 * FirestoreUserRepository
 *
 * Syncs TWO categories of user data to Firestore:
 *
 * 1) API Keys & Settings  →  users/{userId}   (main document)
 *    - Gemini keys (1-3), OpenAI keys (1-3), Sarvam key
 *    - WordPress URL, username, password, ads code
 *    - Support email, privacy policy URL
 *
 * 2) RSS Feed URLs  →  users/{userId}/rss_links/{rssId}  (subcollection)
 *    - url, name, category, isActive, addedAt
 *
 * Firestore rules required (already configured by user):
 *   match /users/{userId} { allow read, write: if request.auth.uid == userId }
 *   match /users/{userId}/rss_links/{rssId} { allow read, write: if request.auth.uid == userId }
 *
 * USAGE:
 *   - Call saveApiKeysToFirestore() after user saves Settings
 *   - Call loadApiKeysFromFirestore() when Settings screen opens (if local is empty)
 *   - Call addRssFeedToFirestore() when user adds an RSS feed
 *   - Call deleteRssFeedFromFirestore() when user deletes a feed
 *   - Call loadRssFeedsFromFirestore() to restore feeds on new device/reinstall
 */
class FirestoreUserRepository(
    private val keyManager: ApiKeyManager,
    private val appPrefs: AppPreferences
) {
    private val TAG = "FirestoreUserRepo"
    private val db   = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // Returns null and logs a warning if no user is signed in
    private fun currentUid(): String? {
        val uid = auth.currentUser?.uid
        if (uid == null) Log.w(TAG, "No authenticated user — Firestore sync skipped")
        return uid
    }

    private fun userDoc(uid: String) = db.collection("users").document(uid)
    private fun rssCol(uid: String)  = userDoc(uid).collection("rss_links")

    // ──────────────────────────────────────────────────────────────────────────
    // API KEYS: Save to Firestore
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Saves all API keys and settings to Firestore.
     * Call this every time the user taps "Save" in SettingsActivity.
     *
     * Uses SetOptions.merge() so only provided fields are overwritten.
     */
    suspend fun saveApiKeysToFirestore(): Boolean {
        val uid = currentUid() ?: return false
        return try {
            val data = mapOf(
                // Gemini
                "gemini_key_1"       to keyManager.getGeminiKey(1),
                "gemini_key_2"       to keyManager.getGeminiKey(2),
                "gemini_key_3"       to keyManager.getGeminiKey(3),
                // OpenAI
                "openai_key_1"       to keyManager.getOpenAiKey(1),
                "openai_key_2"       to keyManager.getOpenAiKey(2),
                "openai_key_3"       to keyManager.getOpenAiKey(3),
                // Sarvam
                "sarvam_key"         to keyManager.getSarvamKey(),
                // WordPress
                "wp_site_url"        to keyManager.getWordPressSiteUrl(),
                "wp_username"        to keyManager.getWordPressUsername(),
                "wp_password"        to keyManager.getWordPressPassword(),
                "wp_ads_code"        to keyManager.getWordPressAdsCode(),
                // App preferences
                "support_email"      to appPrefs.supportEmail,
                "privacy_policy_url" to appPrefs.privacyPolicyUrl,
                // Metadata
                "updated_at"         to FieldValue.serverTimestamp()
            )
            userDoc(uid).set(data, SetOptions.merge()).await()
            Log.i(TAG, "✅ API keys saved to Firestore (uid=$uid)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveApiKeysToFirestore failed: ${e.message}")
            false
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // API KEYS: Load from Firestore → apply to local EncryptedSharedPreferences
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Loads API keys from Firestore and writes them into local EncryptedSharedPreferences.
     * Call this in SettingsActivity.onCreate() so keys restore on new device / reinstall.
     *
     * Only overwrites local value if the Firestore value is NON-EMPTY.
     * This means local manually-entered keys are NEVER accidentally cleared.
     */
    suspend fun loadApiKeysFromFirestore(): Boolean {
        val uid = currentUid() ?: return false
        return try {
            val snap = userDoc(uid).get().await()
            if (!snap.exists()) {
                Log.d(TAG, "No settings doc in Firestore yet for uid=$uid")
                return false
            }

            fun str(field: String) = snap.getString(field)?.trim() ?: ""

            // Gemini keys
            str("gemini_key_1").ifNotEmpty { keyManager.setGeminiKey(1, it) }
            str("gemini_key_2").ifNotEmpty { keyManager.setGeminiKey(2, it) }
            str("gemini_key_3").ifNotEmpty { keyManager.setGeminiKey(3, it) }

            // OpenAI keys
            str("openai_key_1").ifNotEmpty { keyManager.setOpenAiKey(1, it) }
            str("openai_key_2").ifNotEmpty { keyManager.setOpenAiKey(2, it) }
            str("openai_key_3").ifNotEmpty { keyManager.setOpenAiKey(3, it) }

            // Sarvam
            str("sarvam_key").ifNotEmpty { keyManager.setSarvamKey(it) }

            // WordPress
            str("wp_site_url").ifNotEmpty  { keyManager.setWordPressSiteUrl(it) }
            str("wp_username").ifNotEmpty  { keyManager.setWordPressUsername(it) }
            str("wp_password").ifNotEmpty  { keyManager.setWordPressPassword(it) }
            str("wp_ads_code").ifNotEmpty  { keyManager.setWordPressAdsCode(it) }

            // App prefs
            str("support_email").ifNotEmpty      { appPrefs.supportEmail = it }
            str("privacy_policy_url").ifNotEmpty { appPrefs.privacyPolicyUrl = it }

            Log.i(TAG, "✅ API keys loaded from Firestore (uid=$uid)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "loadApiKeysFromFirestore failed: ${e.message}")
            false
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // RSS FEEDS: Add to Firestore
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Saves a single RSS feed URL to Firestore.
     * Call after adding a feed to local Room DB.
     *
     * @param rssId    Unique ID (use Room's auto-generated ID or UUID)
     * @param url      Feed URL
     * @param name     Feed display name
     * @param category Feed category (Technology, Business, etc.)
     * @return The Firestore document ID on success, empty string on failure
     */
    suspend fun addRssFeedToFirestore(
        rssId: String,
        url: String,
        name: String,
        category: String
    ): String {
        val uid = currentUid() ?: return ""
        return try {
            val data = mapOf(
                "url"        to url,
                "name"       to name,
                "category"   to category,
                "is_active"  to true,
                "added_at"   to FieldValue.serverTimestamp()
            )
            // Use rssId as document ID so local + Firestore IDs stay in sync
            rssCol(uid).document(rssId).set(data).await()
            Log.i(TAG, "✅ RSS feed saved to Firestore: $url")
            rssId
        } catch (e: Exception) {
            Log.e(TAG, "addRssFeedToFirestore failed: ${e.message}")
            ""
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // RSS FEEDS: Delete from Firestore
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Deletes a single RSS feed from Firestore.
     * Call AFTER deleting from local Room DB.
     */
    suspend fun deleteRssFeedFromFirestore(rssId: String): Boolean {
        val uid = currentUid() ?: return false
        return try {
            rssCol(uid).document(rssId).delete().await()
            Log.i(TAG, "✅ RSS feed deleted from Firestore: rssId=$rssId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "deleteRssFeedFromFirestore failed: ${e.message}")
            false
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // RSS FEEDS: Load all from Firestore → restore on new device
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Loads all RSS feeds from Firestore.
     * Returns a list of [RssFeedEntry] to be inserted into local Room DB.
     * Call this on first launch / after reinstall when local DB is empty.
     */
    suspend fun loadRssFeedsFromFirestore(): List<RssFeedEntry> {
        val uid = currentUid() ?: return emptyList()
        return try {
            val snapshot = rssCol(uid).get().await()
            val feeds = snapshot.documents.mapNotNull { doc ->
                val url  = doc.getString("url")?.trim() ?: return@mapNotNull null
                val name = doc.getString("name")?.trim() ?: url
                val cat  = doc.getString("category")?.trim() ?: ""
                val active = doc.getBoolean("is_active") ?: true
                if (url.isBlank()) null
                else RssFeedEntry(
                    firestoreId = doc.id,
                    url         = url,
                    name        = name,
                    category    = cat,
                    isActive    = active
                )
            }
            Log.i(TAG, "✅ Loaded ${feeds.size} RSS feeds from Firestore")
            feeds
        } catch (e: Exception) {
            Log.e(TAG, "loadRssFeedsFromFirestore failed: ${e.message}")
            emptyList()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Data model for RSS feeds returned from Firestore
    // ──────────────────────────────────────────────────────────────────────────

    data class RssFeedEntry(
        val firestoreId: String,
        val url: String,
        val name: String,
        val category: String,
        val isActive: Boolean = true
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Utility
    // ──────────────────────────────────────────────────────────────────────────

    /** Helper: only run block if string is non-empty */
    private inline fun String.ifNotEmpty(block: (String) -> Unit) {
        if (this.isNotBlank()) block(this)
    }
}
