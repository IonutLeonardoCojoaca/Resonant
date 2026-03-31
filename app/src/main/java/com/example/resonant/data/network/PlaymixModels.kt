package com.example.resonant.data.network

import com.google.gson.annotations.SerializedName

// ─── Lista de Playmixes ─────────────────────
data class PlaymixDTO(
    val id: String,
    val name: String,
    val description: String?,
    val numberOfTracks: Int,
    val duration: Int,
    val isPublic: Boolean,
    val coverUrl: String?,
    val createdAt: String
)

// ─── Detalle completo ───────────────────────
data class PlaymixDetailDTO(
    val id: String,
    val name: String,
    val description: String?,
    val numberOfTracks: Int,
    val duration: Int,
    val isPublic: Boolean,
    val coverUrl: String?,
    val songs: List<PlaymixSongDTO>,
    val transitions: List<PlaymixTransitionDTO>
)

data class PlaymixSongDTO(
    val playmixSongId: String,
    val position: Int,
    val songId: String,
    val title: String?,
    val artist: String?,
    val duration: Int,
    @com.google.gson.annotations.SerializedName("coverUrl")
    val coverUrl: String? = null,
    val customEntryMs: Int?,
    val customExitMs: Int?,
    val audioAnalysis: PlaymixAudioAnalysisDTO?
)

data class PlaymixAudioAnalysisDTO(
    val bpm: Double?,
    val bpmNormalized: Double?,
    val musicalKey: String?,
    val loudnessLufs: Double?,
    val optimalStartPointMs: Int?,
    val optimalExitPointMs: Int?,
    val durationMs: Int
)

// ─── Transiciones ───────────────────────────
data class PlaymixTransitionDTO(
    val id: String,
    val fromPlaymixSongId: String,
    val toPlaymixSongId: String,
    val exitPointMs: Int,
    val entryPointMs: Int,
    val crossfadeDurationMs: Int,
    val fadeCurveType: String,
    val eqSettings: EqSettingsDTO?,
    val compatibility: CompatibilityDTO?
)

data class EqSettingsDTO(
    val bands: List<EqBandDTO>
)

data class EqBandDTO(
    val freq: Int,
    val gainDb: Double
)

data class CompatibilityDTO(
    val bpmDelta: Double,
    val bpmDeltaPercent: Double,
    val keyCompatibility: String,
    val keyRelationship: String,
    val loudnessDeltaLufs: Double,
    val overallScore: Int,
    val verdict: String,
    val suggestions: List<String>
)

// ─── Waveforms (editor visual) ──────────────
data class WaveformResponseDTO(
    val transition: PlaymixTransitionDTO,
    val songA: WaveformSongDTO,
    val songB: WaveformSongDTO,
    val compatibility: CompatibilityDTO
)

data class WaveformSongDTO(
    val songId: String,
    val title: String?,
    val artist: String?,
    val durationMs: Int,
    val bpm: Double?,
    val musicalKey: String?,
    val loudnessLufs: Double?,
    val waveformUrl: String?,
    val beatGrid: List<Int>?
)

// ─── Request bodies ─────────────────────────
data class CreatePlaymixRequest(
    val name: String,
    val description: String? = null
)

data class AddSongToPlaymixRequest(
    val songId: String
)

data class ReorderSongsRequest(
    val orderedSongIds: List<String>
)

data class EditPlaymixSongRequest(
    val customEntryMs: Int?,
    val customExitMs: Int?
)

data class PlaymixTransitionUpdateDTO(
    val exitPointMs: Int,
    val entryPointMs: Int,
    val crossfadeDurationMs: Int,
    val fadeCurveType: String,
    val eqSettings: EqSettingsDTO?
)
