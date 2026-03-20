package com.example.resonant.ui.fragments

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.resonant.R

class AriaInfoFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_aria_info, container, false)

        view.findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            findNavController().navigateUp()
        }

        view.findViewById<View>(R.id.ariaInfoGlowBg)
            .setRenderEffect(RenderEffect.createBlurEffect(180f, 180f, Shader.TileMode.DECAL))

        return view
    }
}
