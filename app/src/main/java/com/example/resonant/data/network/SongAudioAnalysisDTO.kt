package com.example.resonant.data.network

data class SongAudioAnalysisDTO(
    val id: String,
    val bpm: Double?,
    val musicalKey: String?,
    val segmentsUrl: String?
)
