package com.example.resonant.ui.components

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import com.example.resonant.R

/**
 * Identidad de color de cada sección: el mismo acento y el mismo logo con degradado
 * en su tarjeta de Explorar (si la tiene), en su cabecera y en sus acciones.
 */
enum class ResonantAccent(@ColorInt val color: Int, @DrawableRes val icon: Int) {
    ARTISTS(0xFFF29A2E.toInt(), R.drawable.ic_resonant_gradient_orange),
    ALBUMS(0xFF9277FF.toInt(), R.drawable.ic_resonant_gradient_purple),
    SONGS(0xFF2EE6A8.toInt(), R.drawable.ic_resonant_gradient_green),
    /** Rankings (Top artistas, álbumes y canciones): familia cálida, la misma que Crear Playlist / Crear Playmix. */
    CHART_ARTISTS(0xFFFFB238.toInt(), R.drawable.ic_resonant_gradient_amber),
    CHART_ALBUMS(0xFFE8459C.toInt(), R.drawable.ic_resonant_gradient_fuchsia),
    CHART_SONGS(0xFFFF5A5F.toInt(), R.drawable.ic_resonant_gradient_coral),
    TRENDING(0xFF4FC8FF.toInt(), R.drawable.ic_resonant_gradient_blue),
    PLAYLISTS(0xFFFF4FB8.toInt(), R.drawable.ic_resonant_gradient_pink),
    PLAYMIX(0xFFFF7A59.toInt(), R.drawable.ic_resonant_gradient_sunset),
    HISTORY(0xFFFFC94D.toInt(), R.drawable.ic_resonant_gradient_gold),
    DOWNLOADS(0xFFB8F04A.toInt(), R.drawable.ic_resonant_gradient_lime),
    COLLAB(0xFF8C9DFF.toInt(), R.drawable.ic_resonant_gradient_indigo),
    ESSENTIALS(0xFFE8E8EE.toInt(), R.drawable.ic_resonant_gradient_platinum),
    RADIO(0xFFFFD43B.toInt(), R.drawable.ic_resonant_gradient_yellow)
}
