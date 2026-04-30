package com.nexuzy.publisher.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.nexuzy.publisher.auth.GoogleSignInManager
import com.nexuzy.publisher.data.firebase.FirebaseUserRepository
import com.nexuzy.publisher.data.model.firebase.FirebaseUserProfile
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import com.nexuzy.publisher.R
import com.nexuzy.publisher.ui.main.MainActivity
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var signInManager: GoogleSignInManager
    private val userRepo = FirebaseUserRepository()

    private val googleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        lifecycleScope.launch {
            try {
                val user = signInManager.handleSignInResult(result.data)
                if (user != null) {
                    userRepo.upsertUserProfile(
                        FirebaseUserProfile(
                            uid = user.uid,
                            email = user.email.orEmpty(),
                            displayName = user.displayName.orEmpty(),
                            photoUrl = user.photoUrl?.toString().orEmpty(),
                            lastLoginAt = System.currentTimeMillis()
                        )
                    )
                    Toast.makeText(
                        this@LoginActivity,
                        "\u2705 Google login successful",
                        Toast.LENGTH_SHORT
                    ).show()
                    openMain()
                } else {
                    Toast.makeText(
                        this@LoginActivity,
                        "Google login failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@LoginActivity,
                    "Login error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If already signed in, go straight to main
        if (FirebaseAuth.getInstance().currentUser != null) {
            openMain()
            return
        }

        // Read Web Client ID from ApiKeyManager — no R.string or google-services.json needed.
        // To set it: go to Settings -> Google Web Client ID
        // or take it from google-services.json -> client_type:3 -> client_id
        val webClientId = ApiKeyManager(this).getGoogleWebClientId()

        signInManager = GoogleSignInManager(this, webClientId)
        setContentView(R.layout.activity_login)

        findViewById<Button>(R.id.btnGoogleSignIn).setOnClickListener {
            if (webClientId.isBlank()) {
                Toast.makeText(
                    this,
                    "\u26a0\ufe0f Google Web Client ID not set. Add it in Settings.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            googleLauncher.launch(signInManager.signInIntent())
        }

        findViewById<Button>(R.id.btnSkipLogin).setOnClickListener {
            openMain()
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
