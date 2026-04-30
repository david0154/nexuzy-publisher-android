package com.nexuzy.publisher.ui.auth

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.nexuzy.publisher.R
import com.nexuzy.publisher.auth.GoogleSignInManager
import com.nexuzy.publisher.data.db.AppDatabase
import com.nexuzy.publisher.data.db.entity.UserProfile
import com.nexuzy.publisher.databinding.ActivityLoginBinding
import com.nexuzy.publisher.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInManager: GoogleSignInManager

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleGoogleSignInResult(result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        // If already signed in, skip login.
        if (auth.currentUser != null) {
            goToMain()
            return
        }

        googleSignInManager = GoogleSignInManager(this)

        setupGoogleSignInButton()
        setupSkipButton()
    }

    private fun setupGoogleSignInButton() {
        binding.btnGoogleSignIn.setOnClickListener {
            if (!googleSignInManager.isConfigured) {
                // Client ID not configured yet — show a clear message instead of crashing.
                Toast.makeText(
                    this,
                    "\u26a0\ufe0f Google Sign-In is not set up yet.\n" +
                            "Add your OAuth Web Client ID to strings.xml (google_web_client_id).",
                    Toast.LENGTH_LONG
                ).show()
                Log.w("LoginActivity", "Google Sign-In attempted but client ID not configured.")
                return@setOnClickListener
            }

            val signInIntent = googleSignInManager.getSignInIntent()
            if (signInIntent != null) {
                binding.progressBar.visibility = View.VISIBLE
                binding.btnGoogleSignIn.isEnabled = false
                googleSignInLauncher.launch(signInIntent)
            } else {
                Toast.makeText(this, "Failed to start Google Sign-In", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupSkipButton() {
        binding.btnSkipLogin?.setOnClickListener {
            goToMain()
        }
    }

    private fun handleGoogleSignInResult(data: Intent?) {
        binding.progressBar.visibility = View.GONE
        binding.btnGoogleSignIn.isEnabled = true

        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            val idToken = account.idToken

            if (idToken == null) {
                Toast.makeText(this, "Google Sign-In failed: no ID token", Toast.LENGTH_SHORT).show()
                return
            }

            firebaseAuthWithGoogle(idToken)
        } catch (e: ApiException) {
            val message = when (e.statusCode) {
                GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> "Sign-In cancelled"
                GoogleSignInStatusCodes.NETWORK_ERROR -> "Network error — check your connection"
                else -> "Google Sign-In failed (code ${e.statusCode})"
            }
            Log.e("LoginActivity", "Google sign-in ApiException: ${e.statusCode}", e)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        binding.progressBar.visibility = View.VISIBLE
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnSuccessListener { result ->
                val firebaseUser = result.user ?: return@addOnSuccessListener
                // Persist user profile to local Room DB.
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        AppDatabase.getDatabase(this@LoginActivity)
                            .userProfileDao()
                            .upsertUserProfile(
                                UserProfile(
                                    uid = firebaseUser.uid,
                                    email = firebaseUser.email ?: "",
                                    displayName = firebaseUser.displayName ?: "",
                                    photoUrl = firebaseUser.photoUrl?.toString() ?: ""
                                )
                            )
                    } catch (e: Exception) {
                        Log.e("LoginActivity", "Failed to save user profile: ${e.message}", e)
                    }
                }
                goToMain()
            }
            .addOnFailureListener { e ->
                binding.progressBar.visibility = View.GONE
                Log.e("LoginActivity", "Firebase auth failed: ${e.message}", e)
                Toast.makeText(this, "Authentication failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}
