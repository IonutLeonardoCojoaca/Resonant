package com.example.resonant.feature.collabfinder.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.example.resonant.R
import com.example.resonant.ui.components.ResonantAccent
import com.google.android.material.button.MaterialButton

/** Identidad visual de Collab Finder (acento índigo) compartida por sus pantallas. */
object CollabStyle {

    val accent: Int get() = ResonantAccent.COLLAB.color

    val accentDark: Int get() = ColorUtils.blendARGB(accent, Color.BLACK, 0.32f)

    fun primaryButton(button: MaterialButton) {
        button.backgroundTintList = ColorStateList.valueOf(accentDark)
    }

    /** Glow radial de acento para la capa de fondo de una pantalla. */
    fun backdropGlow(view: View, centerY: Float = 0.35f) {
        view.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(ColorUtils.setAlphaComponent(accent, 0x40), 0x00000000)
            setGradientCenter(0.5f, centerY)
            gradientRadius = 300 * view.resources.displayMetrics.density
        }
    }

    /** Píldora de búsqueda: borde de acento cuando tiene el foco. */
    fun searchField(view: View, focused: Boolean) {
        val density = view.resources.displayMetrics.density
        view.background = GradientDrawable().apply {
            cornerRadius = 27 * density
            setColor(ContextCompat.getColor(view.context, R.color.cardsTheme))
            setStroke(
                ((if (focused) 1.5f else 1f) * density).toInt(),
                if (focused) accent else ContextCompat.getColor(view.context, R.color.discTheme)
            )
        }
    }

    /** Chip de sugerencia de artista (texto + borde fino, toque con ripple). */
    fun suggestionChip(chip: TextView) {
        val density = chip.resources.displayMetrics.density
        chip.background = GradientDrawable().apply {
            cornerRadius = 18 * density
            setColor(ContextCompat.getColor(chip.context, R.color.cardsTheme))
            setStroke((1 * density).toInt(), ColorUtils.setAlphaComponent(accent, 0x55))
        }
        chip.typeface = ResourcesCompat.getFont(chip.context, R.font.unageo_medium)
        chip.setTextColor(ContextCompat.getColor(chip.context, R.color.white))
        chip.textSize = 13f
        chip.setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
        chip.isClickable = true
        chip.isFocusable = true
    }
}
