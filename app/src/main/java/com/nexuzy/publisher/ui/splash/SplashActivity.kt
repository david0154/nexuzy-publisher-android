package com.nexuzy.publisher.ui.splash

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.nexuzy.publisher.R
import com.google.firebase.auth.FirebaseAuth
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import com.nexuzy.publisher.ui.auth.LoginActivity
import com.nexuzy.publisher.ui.main.MainActivity
import com.nexuzy.publisher.ui.settings.SettingsActivity

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        Handler(Looper.getMainLooper()).postDelayed({
            val keyManager = ApiKeyManager(this)

            // If no Gemini key yet, send user to Settings first
            if (keyManager.getGeminiKeys().isEmpty()) {
                Toast.makeText(
                    this,
                    "\u26a0\ufe0f Please add your Gemini API key in Settings",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(this, SettingsActivity::class.java))
                finish()
                return@postDelayed
            }

            // Show Login screen if user is not signed in to Firebase;
            // otherwise go straight to MainActivity.
            val user = FirebaseAuth.getInstance().currentUser
            if (user == null) {
                startActivity(Intent(this, LoginActivity::class.java))
            } else {
                startActivity(Intent(this, MainActivity::class.java))
            }
            finish()
        }, 1200)
    }
}
