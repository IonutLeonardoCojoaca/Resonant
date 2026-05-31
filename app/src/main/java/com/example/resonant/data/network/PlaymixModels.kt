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
    val imageUrl: String? = null,
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
    @SerializedName("eqSettingsA") val eqSettingsA: EqSettingsDTO? = null,
    @SerializedName("eqSettingsB") val eqSettingsB: EqSettingsDTO? = null,
    @SerializedName("mixMode") val mixMode: String? = "crossfade",
    val compatibility: CompatibilityDTO?,
    // ─── Per-band fade types ───────────────────
    val bandFadeTypes: BandFadeTypesDTO? = null,
    // ─── Preset fields ─────────────────────────
    val presetId: String? = null,
    val presetCode: String? = null,
    val presetName: String? = null,
    val presetAppliedAt: String? = null,
    val isPresetModified: Boolean = false,
    val isActive: Boolean = false,
    val gapMs: Int = 0
)

data class EqSettingsDTO(
    val bands: List<EqBandDTO>
)

data class EqBandDTO(
    val freq: Int,
    val gainDb: Double
)

data class BandFadeTypesDTO(
    val bass: String = "linear",
    val mid: String = "linear",
    val treble: String = "linear"
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

data class RenamePlaymixRequest(
    val name: String
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
    val eqSettings: EqSettingsDTO?,
    @SerializedName("eqSettingsA") val eqSettingsA: EqSettingsDTO? = null,
    @SerializedName("eqSettingsB") val eqSettingsB: EqSettingsDTO? = null,
    @SerializedName("mixMode") val mixMode: String = "crossfade",
    val bandFadeTypes: BandFadeTypesDTO? = null,
    val gapMs: Int = 0,
    val presetCode: String? = null,
    val isPresetModified: Boolean = false,
    val isActive: Boolean = true
)

// ─── Transition Presets ─────────────────────
data class TransitionPresetDTO(
    val id: String,
    val code: String,
    val name: String,
    val description: String,
    val iconName: String,
    val category: String,
    val isSystem: Boolean,
    val sortOrder: Int
)

data class ApplyPresetRequest(
    val presetCode: String
)

data class TransitionPresetPreviewDTO(
    val presetCode: String,
    val presetName: String,
    val calculatedValues: CalculatedTransitionValues,
    val metadata: TransitionMetadata,
    val warnings: List<String>,
    val appliedFallbacks: List<String>
)

data class CalculatedTransitionValues(
    val exitPointMs: Int,
    val entryPointMs: Int,
    val crossfadeDurationMs: Int,
    val fadeCurveType: String,
    val eqSettingsJson: com.google.gson.JsonObject?,
    val gapMs: Int
)

data class TransitionMetadata(
    val fromSongBpm: Double?,
    val toSongBpm: Double?,
    val bpmDelta: Double?,
    val keyCompatibility: String?,
    val loudnessDeltaLufs: Double?
)

// ─── Copy Transition ────────────────────────
data class CopyTransitionRequest(
    val targetPlaymixId: String
)

data class CopyTransitionResponseDTO(
    val targetPlaymixId: String,
    val newFromPlaymixSongId: String,
    val newToPlaymixSongId: String,
    val newTransitionId: String
)
