package com.nexuzy.publisher.ui.main.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import com.nexuzy.publisher.R
import com.nexuzy.publisher.ui.editor.ArticleEditorActivity

class AiWriterFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_ai_writer, container, false)
        root.findViewById<Button>(R.id.btnOpenAiEditor).setOnClickListener {
            startActivity(Intent(requireContext(), ArticleEditorActivity::class.java))
        }
        return root
    }
}
