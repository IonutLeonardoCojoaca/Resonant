package com.example.resonant

data class Album(
    val id: String = "",
    val title: String? = "",
    val coverImageUrl: String = "",
    val releaseYear: Int,
    val numberOfTracks: Int = 0,
    val duration: Int = 0
)
