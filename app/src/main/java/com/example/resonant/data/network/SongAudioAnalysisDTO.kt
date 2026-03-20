package com.example.resonant.data.network

import com.google.gson.annotations.SerializedName

data class SongAudioAnalysisDTO(
    val id: String,
    val songId: String? = null,
    val durationMs: Int? = null,
    val audioStartMs: Int? = null,
    val audioEndMs: Int? = null,
    val loudnessLufs: Double? = null,
    val bpm: Double?,
    val bpmNormalized: Double? = null,
    val optimalStartPointMs: Int? = null,
    val optimalExitPointMs: Int? = null,
    val musicalKey: String?,
    @SerializedName("sectionsJson", alternate = ["sections"])
    val sectionsJson: Any? = null,
    val segmentsUrl: String?,
    val segmentsFileName: String? = null
)
