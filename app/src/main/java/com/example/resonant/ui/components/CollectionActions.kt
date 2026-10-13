package com.example.resonant.ui.components

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import androidx.core.graphics.ColorUtils
import com.example.resonant.R
import com.google.android.material.button.MaterialButton

/** Binds `view_collection_actions`: "Reproducir" relleno con el acento de la sección y "Aleatorio". */
object CollectionActions {

    fun bind(root: View, accent: ResonantAccent, onPlayAll: () -> Unit, onShuffle: () -> Unit) {
        root.findViewById<MaterialButton>(R.id.btnPlayAll).apply {
            backgroundTintList = ColorStateList.valueOf(ColorUtils.blendARGB(accent.color, Color.BLACK, 0.3f))
            setOnClickListener { onPlayAll() }
        }
        root.findViewById<MaterialButton>(R.id.btnShuffle).setOnClickListener { onShuffle() }
    }

    fun setEnabled(root: View, enabled: Boolean) {
        listOf(R.id.btnPlayAll, R.id.btnShuffle).forEach { id ->
            root.findViewById<View>(id).apply {
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.4f
            }
        }
    }
}
