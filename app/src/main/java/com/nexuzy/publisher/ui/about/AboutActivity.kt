package com.nexuzy.publisher.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.nexuzy.publisher.R
import com.nexuzy.publisher.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "About"

        binding.apply {
            tvAppVersion.text = getString(R.string.app_version)
            tvDeveloperName.text = getString(R.string.developer_name)
            tvDeveloperCompany.text = getString(R.string.developer_company)
            tvDeveloperLocation.text = "${getString(R.string.developer_location)} • ${getString(R.string.github_url)}"
            tvDeveloperLocation.setOnClickListener { openUrl(getString(R.string.github_url)) }

            // Existing button slots reused for important project links
            btnGithubRepo.text = getString(R.string.opensource_label)
            btnDesktopVersion.text = getString(R.string.support_label)
            btnWebsite.text = getString(R.string.privacy_policy_label)

            btnGithubRepo.setOnClickListener {
                openUrl(getString(R.string.github_url))
            }

            btnDesktopVersion.setOnClickListener {
                openMail(getString(R.string.support_email))
            }

            btnWebsite.setOnClickListener {
                openUrl(getString(R.string.privacy_policy_url))
            }
        }
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun openMail(email: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$email")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            putExtra(Intent.EXTRA_SUBJECT, "Nexuzy Publisher Android Support")
        }
        startActivity(intent)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
