package com.example.resonant

import com.google.gson.annotations.SerializedName

// Contenedor genérico para la respuesta de búsqueda
data class SearchResponse<T>(
    // Propiedad 'Results'
    @SerializedName("results")
    val results: List<T>,

    // Propiedad 'Suggestions' (una lista de strings, ej. ["Future", "The Fugees"])
    @SerializedName("suggestions")
    val suggestions: List<String>
)
