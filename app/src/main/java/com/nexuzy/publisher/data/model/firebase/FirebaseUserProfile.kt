package com.nexuzy.publisher.data.model.firebase

/**
 * Firestore document stored at: users/{uid}
 *
 * Includes cross-device backup for:
 *   - Google account info (auth fields)
 *   - WordPress site URL + credentials
 *   - API key counts/hints (never store full keys in Firestore — security risk)
 *   - Optional ads code
 *
 * NOTE: Full Gemini/OpenAI/Sarvam keys are stored only in local EncryptedSharedPreferences.
 *       We store only the COUNT and a masked HINT (e.g. "AIza...xxxx") so the user
 *       can see on a new device which keys were set, and can re-enter them.
 *       WordPress password is stored encrypted via Base64 for convenience —
 *       do NOT use this in a high-security app without proper server-side encryption.
 */
data class FirebaseUserProfile(
    // ── Auth fields ──────────────────────────────────────────────
    val uid: String = "",
    val email: String = "",
    val displayName: String = "",
    val photoUrl: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastLoginAt: Long = System.currentTimeMillis(),

    // ── WordPress backup ─────────────────────────────────────────
    val wpSiteUrl: String = "",           // e.g. https://mynews.com
    val wpUsername: String = "",          // WordPress application username
    val wpAppPassword: String = "",       // WordPress application password (Base64 encoded for backup)
    val wpLoginEnabled: Boolean = true,
    val adsCode: String = "",             // Optional ad injection code

    // ── API key hints (masked — NOT the real keys) ────────────────
    // Hints look like: "AIza...WXYZ" so user knows which key was set
    val geminiKeyCount: Int = 0,
    val geminiKey1Hint: String = "",
    val geminiKey2Hint: String = "",
    val geminiKey3Hint: String = "",

    val openAiKeyCount: Int = 0,
    val openAiKey1Hint: String = "",
    val openAiKey2Hint: String = "",
    val openAiKey3Hint: String = "",

    val hasSarvamKey: Boolean = false,
    val sarvamKeyHint: String = "",

    // ── Metadata ─────────────────────────────────────────────────
    val updatedAt: Long = System.currentTimeMillis(),
    val appVersion: String = ""
)
