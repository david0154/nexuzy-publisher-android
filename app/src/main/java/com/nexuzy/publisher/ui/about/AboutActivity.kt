package com.nexuzy.publisher.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.nexuzy.publisher.BuildConfig
import com.nexuzy.publisher.R
import com.nexuzy.publisher.databinding.ActivityAboutBinding

/**
 * About screen for Nexuzy Publisher.
 *
 * Developer   : David
 * Maintainer  : David
 * Organisation: Nexuzy Lab
 * Support     : nexuzylab@gmail.com
 * Open Source : https://github.com/david0154/nexuzy-publisher-android
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.about_title)

        binding.apply {

            // ── App info ────────────────────────────────────────────
            tvAppVersion.text = "${getString(R.string.app_name)} v${BuildConfig.VERSION_NAME}"

            // ── Developer + Maintainer ───────────────────────────────
            tvDeveloperName.text = buildString {
                append(getString(R.string.developer_name))
                append(" • ")
                append(getString(R.string.developer_company))
                append(" • ")
                append(getString(R.string.developer_location))
            }

            // ── Organisation tagline ──────────────────────────────
            tvDeveloperCompany.text = buildString {
                appendLine("🛠️  Developed & Maintained by David")
                appendLine("🏢  Organisation: Nexuzy Lab")
                appendLine("📍  ${getString(R.string.developer_location)}")
                appendLine("📧  ${getString(R.string.support_email)}")
                append("${getString(R.string.app_type)}")
            }

            // ── Tools & Tech shown as location line ────────────────
            tvDeveloperLocation.text = buildString {
                appendLine("🤖  ${getString(R.string.tools_tech_title)}")
                append(getString(R.string.tools_tech))
            }
            tvDeveloperLocation.setOnClickListener(null) // no-op for tech stack text

            // ── Buttons ────────────────────────────────────────────

            // Button 1: Open Source GitHub repo
            btnGithubRepo.text = getString(R.string.opensource_label)
            btnGithubRepo.setOnClickListener {
                openUrl(getString(R.string.open_source_url))
            }

            // Button 2: Support Email
            btnDesktopVersion.text = getString(R.string.support_label)
            btnDesktopVersion.setOnClickListener {
                openMail(getString(R.string.support_email))
            }

            // Button 3: Privacy Policy
            btnWebsite.text = getString(R.string.privacy_policy_label)
            btnWebsite.setOnClickListener {
                openUrl(getString(R.string.privacy_policy_url))
            }
        }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun openMail(email: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$email")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            putExtra(Intent.EXTRA_SUBJECT, "Nexuzy Publisher Android — Support")
            putExtra(Intent.EXTRA_TEXT,
                "App Version: ${BuildConfig.VERSION_NAME}\nDevice: ${android.os.Build.MODEL}\nAndroid: ${android.os.Build.VERSION.RELEASE}\n\nDescribe your issue:\n")
        }
        startActivity(intent)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
