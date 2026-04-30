package com.nexuzy.publisher.ui.splash

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
        // installSplashScreen() MUST be called before super.onCreate() for the
        // Android 12+ Splash Screen API to correctly display windowSplashScreenAnimatedIcon.
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Keep the splash screen visible until our delay logic completes.
        splashScreen.setKeepOnScreenCondition { false }

        Handler(Looper.getMainLooper()).postDelayed({
            navigateFromSplash()
        }, 1500)
    }

    /**
     * Navigation order:
     *   1. If the user is NOT signed in → LoginActivity (login / sign-up via Google)
     *   2. If signed in but no Gemini API key yet → SettingsActivity (API setup)
     *   3. If signed in AND keys configured → MainActivity
     *
     * Previously the check was reversed: API keys were checked BEFORE login,
     * causing the app to skip straight to SettingsActivity on every fresh install.
     */
    private fun navigateFromSplash() {
        val user = FirebaseAuth.getInstance().currentUser

        if (user == null) {
            // User not logged in — show login screen first.
            Log.d("SplashActivity", "User not signed in, navigating to LoginActivity")
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // User is signed in — check if API keys are configured.
        val keyManager = ApiKeyManager(this)
        val hasGeminiKey = keyManager.getGeminiKeys().isNotEmpty()

        if (!hasGeminiKey) {
            Log.d("SplashActivity", "No Gemini API key found, navigating to SettingsActivity")
            Toast.makeText(
                this,
                "\u26a0\ufe0f Welcome! Please add your API keys to get started.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            Log.d("SplashActivity", "All good — navigating to MainActivity")
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
