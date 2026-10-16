package com.example.resonant.data.network

data class SongPlaybackDTO(
    val id: String,
    val streamUrl: String?,
    val durationMs: Int,
    val bpm: Double?,
    val musicalKey: String?,
    val introStartMs: Int?,
    val outroStartMs: Int?,
    val loudness: Double?,
    val expiresAtUtc: String? = null,
    val streamId: String? = null,
    val mimeType: String? = null,
    val contentLength: Long? = null,
    val supportsRanges: Boolean? = null,
    val deliveryMode: String? = null,
    val quality: String? = null,
    // ─── Análisis v2 y cue points (null con análisis v1) ───
    val analysisVersion: Int? = null,
    val integratedLufs: Double? = null,
    val keyCamelot: String? = null,
    val bpmPrecise: Double? = null,
    val firstDownbeatMs: Int? = null,
    val beatsPerBar: Int? = null,
    val mixInMs: Int? = null,
    val mixOutMs: Int? = null,
    val dropMs: Int? = null
)
