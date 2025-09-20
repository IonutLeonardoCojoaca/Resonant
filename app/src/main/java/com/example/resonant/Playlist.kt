package com.example.resonant

data class Playlist(
    val id: String? = null,
    val userId: String? = null,
    val name: String,
    val description: String,
    val numberOfTracks: Int,
    val duration: Int,
    val fileName: String? = null,
    val isPublic: Boolean,
    val imageUrl: String? = null
)
