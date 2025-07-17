package com.example.resonant

data class Song(
    val id: String = "",
    val albumId: String = "",
    var artistName: String?,
    val title: String = "",
    var duration: String? = null,
    var streams: Int = 0,
    var position: Int = 0,
    var fileName: String = "",
    var url: String? = null
)

