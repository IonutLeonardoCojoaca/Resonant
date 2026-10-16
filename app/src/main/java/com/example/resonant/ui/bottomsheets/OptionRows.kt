package com.example.resonant.ui.bottomsheets

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.example.resonant.R

/**
 * Filas de los bottom sheets de opciones (estilo `OptionRow`): todas con el icono a 24dp y el
 * mismo tinte. Los cambios de estado (favorito, descarga, lista) pasan por aquí para que el
 * icono no cambie de tamaño ni se quede con el tinte del estado anterior.
 */
object OptionRows {

    private const val ICON_DP = 24

    enum class Tone { NORMAL, ACTIVE, DESTRUCTIVE, MUTED }

    /** Fija a 24dp el icono de todas las filas con icono inicial bajo [root]. */
    fun normalize(root: View) {
        when (root) {
            is TextView -> root.compoundDrawablesRelative[0]?.let { resize(root, it) }
            is ViewGroup -> for (i in 0 until root.childCount) normalize(root.getChildAt(i))
        }
    }

    /** Cambia el icono de una fila manteniendo tamaño y tinte. */
    fun setIcon(row: TextView, @DrawableRes icon: Int) {
        val drawable = ContextCompat.getDrawable(row.context, icon)?.mutate() ?: return
        resize(row, drawable)
    }

    fun setTone(row: TextView, tone: Tone) {
        val color = ContextCompat.getColor(row.context, when (tone) {
            Tone.NORMAL -> R.color.white
            Tone.ACTIVE, Tone.DESTRUCTIVE -> R.color.errorColor
            Tone.MUTED -> R.color.textTheme
        })
        row.setTextColor(color)
        row.compoundDrawableTintList = ColorStateList.valueOf(color)
    }

    /** Fila de favorito: corazón vacío y color normal, o corazón lleno en rojo si ya lo es. */
    fun setFavorite(row: TextView, isFavorite: Boolean) {
        setIcon(row, if (isFavorite) R.drawable.ic_favorite_delete else R.drawable.ic_add_favorite)
        setTone(row, if (isFavorite) Tone.ACTIVE else Tone.NORMAL)
        row.text = if (isFavorite) "Eliminar de favoritos" else "Añadir a favoritos"
    }

    /** Fila no disponible (sin conexión): atenuada y sin pulsar. */
    fun disable(row: TextView) {
        row.isEnabled = false
        row.alpha = 0.4f
        setTone(row, Tone.MUTED)
    }

    private fun resize(row: TextView, drawable: android.graphics.drawable.Drawable) {
        val px = (ICON_DP * row.resources.displayMetrics.density).toInt()
        drawable.setBounds(0, 0, px, px)
        val current = row.compoundDrawablesRelative
        row.setCompoundDrawablesRelative(drawable, current[1], current[2], current[3])
    }
}
