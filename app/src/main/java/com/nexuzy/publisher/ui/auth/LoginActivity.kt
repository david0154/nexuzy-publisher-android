package com.nexuzy.publisher.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.nexuzy.publisher.data.firebase.FirebaseUserRepository
import com.nexuzy.publisher.data.model.firebase.FirebaseUserProfile
import com.nexuzy.publisher.data.prefs.AppPreferences
import com.nexuzy.publisher.ui.main.MainActivity
import kotlinx.coroutines.launch
import com.nexuzy.publisher.auth.GoogleSignInManager

class LoginActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences
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
                    Toast.makeText(this@LoginActivity, "✅ Google login successful", Toast.LENGTH_SHORT).show()
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
        prefs = AppPreferences(this)

        val current = FirebaseAuth.getInstance().currentUser
        if (current != null) {
            openMain()
            return
        }

        val clientId = prefs.googleWebClientId
        if (clientId.isBlank()) {
            Toast.makeText(this, "Add Google Web Client ID in settings to enable Google login", Toast.LENGTH_LONG).show()
            openMain()
            return
        }

        signInManager = GoogleSignInManager(this, clientId)
        setContentView(createContentView())
    }

    private fun createContentView(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val title = TextView(this).apply {
            text = "Sign in to sync RSS"
            textSize = 22f
            gravity = Gravity.CENTER
        }

        val sub = TextView(this).apply {
            text = "Use Google login to save user profile and RSS links in Firebase."
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 24)
        }

        val signIn = Button(this).apply {
            text = "Continue with Google"
            setOnClickListener {
                googleLauncher.launch(signInManager.signInIntent())
            }
        }

        val skip = Button(this).apply {
            text = "Skip for now"
            setOnClickListener { openMain() }
        }

        root.addView(title)
        root.addView(sub)
        root.addView(signIn)
        root.addView(skip)
        return root
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
