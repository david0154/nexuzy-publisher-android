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
            // App version
            tvAppVersion.text = getString(R.string.app_version)

            // Developer info
            tvDeveloperName.text = getString(R.string.developer_name)
            tvDeveloperCompany.text = getString(R.string.developer_company)
            tvDeveloperLocation.text = getString(R.string.developer_location)

            // GitHub repo button
            btnGithubRepo.setOnClickListener {
                openUrl(getString(R.string.github_url))
            }

            // Desktop version link
            btnDesktopVersion.setOnClickListener {
                openUrl(getString(R.string.desktop_url))
            }

            // Website link
            btnWebsite.setOnClickListener {
                openUrl(getString(R.string.website_url))
            }
        }
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
