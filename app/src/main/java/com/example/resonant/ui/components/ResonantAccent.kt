package com.example.resonant.ui.components

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import com.example.resonant.R

/**
 * Identidad de color de cada sección de rankings: el mismo acento y el mismo logo
 * con degradado en la tarjeta de Explorar y en la cabecera de su pantalla.
 */
enum class ResonantAccent(@ColorInt val color: Int, @DrawableRes val icon: Int) {
    ARTISTS(0xFFF29A2E.toInt(), R.drawable.ic_resonant_gradient_orange),
    ALBUMS(0xFF9277FF.toInt(), R.drawable.ic_resonant_gradient_purple),
    SONGS(0xFF2EE6A8.toInt(), R.drawable.ic_resonant_gradient_green),
    TRENDING(0xFF4FC8FF.toInt(), R.drawable.ic_resonant_gradient_blue),
    PLAYLISTS(0xFFFF4FB8.toInt(), R.drawable.ic_resonant_gradient_pink)
}
