package com.example.resonant.ui.components

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.resonant.R

/**
 * Tarjeta de dato de las fichas: relleno sin borde. Todas las tarjetas de una ficha llevan el
 * mismo color, el acento de la portada (`DetailHero.applyPalette`), en un degradado diagonal
 * suave; hasta que llega la paleta (o si no hay portada) van en neutro `surfaceCardOverlay`.
 */
object InsightTile {

    /**
     * @param color acento de la portada, o `null` para el relleno neutro.
     * @param label etiqueta que se tiñe con el acento (en neutro vuelve a `textTheme`).
     */
    fun paint(tile: View, @ColorInt color: Int?, label: TextView? = null) {
        val context = tile.context
        val radius = 20 * tile.resources.displayMetrics.density
        tile.background = if (color == null) {
            GradientDrawable().apply {
                cornerRadius = radius
                setColor(ContextCompat.getColor(context, R.color.surfaceCardOverlay))
            }
        } else {
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(ColorUtils.setAlphaComponent(color, 0x2E), ColorUtils.setAlphaComponent(color, 0x0D))
            ).apply { cornerRadius = radius }
        }
        label?.setTextColor(
            if (color == null) ContextCompat.getColor(context, R.color.textTheme) else labelColor(context, color)
        )
    }

    /**
     * El acento de la paleta viene aclarado para leerse sobre negro (`DetailHero.readable`);
     * en tema claro se oscurece para que la etiqueta no se pierda sobre blanco.
     */
    @ColorInt
    fun labelColor(context: Context, @ColorInt color: Int): Int {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (night) color else ColorUtils.blendARGB(color, Color.BLACK, 0.35f)
    }
}
