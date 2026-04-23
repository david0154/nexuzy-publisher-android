package com.nexuzy.publisher.auth

import android.app.Activity
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

class GoogleSignInManager(
    activity: Activity,
    webClientId: String,
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private val googleSignInClient: GoogleSignInClient

    init {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(activity, options)
    }

    fun signInIntent(): Intent = googleSignInClient.signInIntent

    suspend fun handleSignInResult(data: Intent?) = run {
        val account = GoogleSignIn.getSignedInAccountFromIntent(data).result
        val credential = GoogleAuthProvider.getCredential(account.idToken, null)
        firebaseAuth.signInWithCredential(credential).await().user
    }

    suspend fun signOut() {
        firebaseAuth.signOut()
        googleSignInClient.signOut().await()
    }
}
