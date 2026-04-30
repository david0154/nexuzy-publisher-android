package com.nexuzy.publisher.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.nexuzy.publisher.R
import com.nexuzy.publisher.auth.GoogleSignInManager
import com.nexuzy.publisher.data.firebase.FirebaseUserRepository
import com.nexuzy.publisher.data.model.firebase.FirebaseUserProfile
import com.nexuzy.publisher.ui.main.MainActivity
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var signInManager: GoogleSignInManager
    private val userRepo = FirebaseUserRepository()

    private val googleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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
                    Toast.makeText(this@LoginActivity, "\u2705 Google login successful", Toast.LENGTH_SHORT).show()
                    openMain()
                } else {
                    Toast.makeText(this@LoginActivity, "Google login failed", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@LoginActivity, "Login error: ${e.message}", Toast.LENGTH_LONG).show()
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

        // Web Client ID is baked into the app via google-services.json (R.string.default_web_client_id)
        // No user input required
        signInManager = GoogleSignInManager(this, getString(R.string.default_web_client_id))
        setContentView(R.layout.activity_login)

        findViewById<Button>(R.id.btnGoogleSignIn).setOnClickListener {
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
