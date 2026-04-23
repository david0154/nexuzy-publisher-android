package com.nexuzy.publisher.ui.main.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import com.nexuzy.publisher.R
import com.nexuzy.publisher.ui.settings.SettingsActivity

class WordPressFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_wordpress, container, false)
        root.findViewById<Button>(R.id.btnOpenWpSettings).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        return root
    }
}
