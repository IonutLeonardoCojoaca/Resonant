package com.example.spomusicapp

data class Album(
    val id: String = "",
    val id_artist: String = "",
    val artistName: String? = null,
    val numberSongs: Int = 0,
    val title: String? = "",
    val duration: Int = 0,
    val photoUrl: String = ""
)
