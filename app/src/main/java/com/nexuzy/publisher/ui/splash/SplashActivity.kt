package com.nexuzy.publisher.ui.splash

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.nexuzy.publisher.R
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import com.nexuzy.publisher.ui.main.MainActivity
import com.nexuzy.publisher.ui.settings.SettingsActivity

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        Handler(Looper.getMainLooper()).postDelayed({
            val keyManager = ApiKeyManager(this)
            if (keyManager.getGeminiKeys().isEmpty()) {
                Toast.makeText(
                    this,
                    "⚠️ Please add your Gemini API key in Settings",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(this, SettingsActivity::class.java))
                finish()
                return@postDelayed
            }

            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 1200)
    }
}
