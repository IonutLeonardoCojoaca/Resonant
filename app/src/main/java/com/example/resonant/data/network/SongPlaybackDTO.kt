package com.example.resonant.data.network

data class SongPlaybackDTO(
    val id: String,
    val streamUrl: String?,
    val durationMs: Int,
    val bpm: Double?,
    val musicalKey: String?,
    val introStartMs: Int?,
    val outroStartMs: Int?,
    val loudness: Double?
)
