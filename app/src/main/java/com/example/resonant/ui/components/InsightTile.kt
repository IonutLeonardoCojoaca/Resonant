package com.example.resonant.ui.components

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils

/**
 * Tarjeta de dato de las fichas: degradado diagonal suave de su color con borde del mismo tono
 * y la etiqueta en el color. Cada tarjeta lleva un color distinto para leerse de un vistazo.
 */
object InsightTile {

    fun paint(tile: View, @ColorInt color: Int, label: TextView? = null) {
        val density = tile.resources.displayMetrics.density
        tile.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(ColorUtils.setAlphaComponent(color, 0x3D), ColorUtils.setAlphaComponent(color, 0x0F))
        ).apply {
            cornerRadius = 20 * density
            setStroke((1 * density).toInt(), ColorUtils.setAlphaComponent(color, 0x47))
        }
        label?.setTextColor(color)
    }
}
