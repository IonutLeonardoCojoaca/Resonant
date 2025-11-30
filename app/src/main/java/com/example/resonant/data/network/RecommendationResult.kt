package com.example.resonant.data.network

import com.example.resonant.data.models.Song

data class RecommendationResult<T>(
    val items: List<T>,
    val title: String? // Aquí vendrá el texto "Porque escuchaste..."
)
