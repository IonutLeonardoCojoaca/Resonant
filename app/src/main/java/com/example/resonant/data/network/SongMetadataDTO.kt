package com.example.resonant.data.network

data class SongMetadataDTO(
    val songId: String,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList()
)
