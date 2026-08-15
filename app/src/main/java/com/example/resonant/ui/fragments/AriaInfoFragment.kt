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

        val glowBg = view.findViewById<View>(R.id.ariaInfoGlowBg)
        glowBg.setRenderEffect(RenderEffect.createBlurEffect(180f, 180f, Shader.TileMode.DECAL))

        val recyclerView = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        
        val flatList = com.example.resonant.aria.AriaCapabilities.getFlatList()
        recyclerView.adapter = com.example.resonant.ui.adapters.AriaInfoAdapter(flatList)

        // Parallax and fade effect on scroll
        recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                val offset = recyclerView.computeVerticalScrollOffset()
                glowBg.translationY = -(offset * 0.3f)
                glowBg.alpha = (0.75f - (offset * 0.0005f)).coerceIn(0.1f, 0.75f)
            }
        })

        return view
    }
}
