package com.nexuzy.publisher.auth

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.nexuzy.publisher.R

/**
 * Manages Google Sign-In client setup.
 *
 * Crash fix: Previously crashed with IllegalArgumentException (Given String is empty or null)
 * because requestIdToken() was called with the placeholder string
 * "YOUR_WEB_CLIENT_ID.apps.googleusercontent.com" before a real client ID was configured.
 *
 * Now:
 *  - Validates the client ID before building GoogleSignInOptions.
 *  - If the client ID is missing or still the placeholder, [isConfigured] returns false
 *    and the caller can show a helpful error instead of crashing.
 */
class GoogleSignInManager(private val context: Context) {

    companion object {
        private const val TAG = "GoogleSignInManager"
        private const val PLACEHOLDER_PREFIX = "YOUR_WEB_CLIENT_ID"
    }

    /** True when a real (non-placeholder) web client ID has been set in strings.xml. */
    val isConfigured: Boolean
        get() {
            val clientId = runCatching {
                context.getString(R.string.google_web_client_id)
            }.getOrElse { "" }
            return clientId.isNotBlank() && !clientId.startsWith(PLACEHOLDER_PREFIX)
        }

    private var _googleSignInClient: GoogleSignInClient? = null

    /** Returns the configured [GoogleSignInClient], or null if not properly configured. */
    val googleSignInClient: GoogleSignInClient?
        get() {
            if (_googleSignInClient == null) init()
            return _googleSignInClient
        }

    private fun init() {
        if (!isConfigured) {
            Log.w(
                TAG,
                "Google Sign-In not configured: google_web_client_id is missing or still set " +
                        "to the placeholder. Add your real OAuth 2.0 Web Client ID in strings.xml."
            )
            _googleSignInClient = null
            return
        }

        try {
            val webClientId = context.getString(R.string.google_web_client_id)
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(webClientId)
                .requestEmail()
                .requestProfile()
                .build()
            _googleSignInClient = GoogleSignIn.getClient(context, gso)
            Log.d(TAG, "GoogleSignInClient initialised successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialise GoogleSignInClient: ${e.message}", e)
            _googleSignInClient = null
        }
    }

    /** Returns a sign-in [Intent] ready to launch, or null if not configured. */
    fun getSignInIntent(): Intent? = googleSignInClient?.signInIntent

    /** Signs the current user out. */
    fun signOut(onComplete: () -> Unit = {}) {
        googleSignInClient?.signOut()?.addOnCompleteListener { onComplete() } ?: onComplete()
    }
}
