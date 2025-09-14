package com.example.resonant

data class Playlist(
    val id: String,
    val userId: String,
    val name: String,
    val description: String,
    val numberOfTracks: Int,
    val duration: Int,
    val fileName: String,
    val isPublic: Boolean
)
