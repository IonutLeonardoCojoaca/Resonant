package com.example.resonant.data.network

/**
 * Contrato de Resonant Studio (`api/playmix/{id}/transitions/{tId}/studio`, spec playmix-studio).
 * El documento de mezcla es la única fuente de verdad de cómo suena una transición en todos los clientes.
 */
data class StudioTransitionDTO(
    val transitionId: String,
    val revision: Int,
    /** true si el documento se ha derivado de los campos antiguos y aún no está guardado en Studio. */
    val derived: Boolean,
    val canEdit: Boolean,
    val document: MixDocumentDTO,
    val songA: StudioSongDTO,
    val songB: StudioSongDTO,
    val limits: StudioLimitsDTO? = null,
    val render: PlaymixRenderDTO? = null
)

data class StudioSongDTO(
    val songId: String,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Int,
    val bpm: Double? = null,
    val beatsPerBar: Int? = null,
    val beatGrid: List<Int>? = null,
    val downbeats: List<Int>? = null,
    val phrases: List<PhraseDTO>? = null,
    val camelot: String? = null,
    val musicalKey: String? = null,
    val integratedLufs: Double? = null,
    val mixInMs: Int? = null,
    val mixOutMs: Int? = null,
    val dropMs: Int? = null
)

data class StudioLimitsDTO(
    val maxDurationMs: Int = 180_000,
    val maxPointsPerLane: Int = 256,
    val eqMinDb: Double = -40.0,
    val eqMaxDb: Double = 6.0
)

data class StudioSaveRequest(val revision: Int, val document: MixDocumentDTO)

/** Estilo y duración en compases (2, 4, 8, 16 o 32; null = la nativa del estilo). */
data class StudioPresetRequest(val presetCode: String, val bars: Int? = null)

data class StudioPresetResponseDTO(
    val document: MixDocumentDTO,
    val preset: StudioPresetInfoDTO,
    val warnings: List<String> = emptyList(),
    val appliedFallbacks: List<String> = emptyList()
)

data class StudioPresetInfoDTO(val code: String, val name: String)

/** Error de Studio con código estable (`StudioErrors` lo mapea a mensajes). */
data class StudioErrorDTO(
    val code: String,
    val message: String? = null,
    val field: String? = null,
    val maxAllowedMs: Int? = null,
    val currentRevision: Int? = null
)
