package com.example.resonant.utils

/**
 * Tamaños máximos de decodificación para las cargas con CustomTarget<Bitmap>, que sin tamaño decodifican la
 * imagen original completa (varios MB por portada). Un héroe de pantalla completa no necesita más de 1024 px
 * y una tarjeta de lista no más de 512 px; Palette trabaja igual de bien con mucho menos.
 */
object ImageSizes {
    const val HERO_PX = 1024
    const val CARD_PX = 512
}
