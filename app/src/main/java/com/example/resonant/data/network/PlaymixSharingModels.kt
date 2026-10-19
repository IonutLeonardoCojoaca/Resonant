package com.example.resonant.data.network

/**
 * Compartir y descubrir Playmix (playmix-sharing-discovery). Contrato en
 * `docs/playmix-sharing-contract.md` del repo de infraestructura.
 */
object PlaymixVisibility {
    const val PRIVATE = "private"
    const val UNLISTED = "unlisted"
    const val PUBLIC = "public"

    /** Visibilidad efectiva: la del servidor o, con un backend anterior, la deducida de `isPublic`. */
    fun of(visibility: String?, isPublic: Boolean): String = when (visibility) {
        PRIVATE, UNLISTED, PUBLIC -> visibility
        else -> if (isPublic) PUBLIC else PRIVATE
    }
}

val PlaymixDTO.effectiveVisibility: String get() = PlaymixVisibility.of(visibility, isPublic)
val PlaymixDetailDTO.effectiveVisibility: String get() = PlaymixVisibility.of(visibility, isPublic)

/** Body de `PATCH api/playmix/{id}/visibility`. */
data class PlaymixVisibilityRequest(val visibility: String)

/** Página de `GET api/playmix/public` y `GET api/playmix/liked`. */
data class PlaymixPageDTO(
    val items: List<PlaymixDTO>?,
    val nextCursor: String?
)

/** Body de `POST api/playmix/{id}/copy`; `name` null = "<original> (copia)". */
data class PlaymixCopyRequest(val name: String? = null)

/** Body de `POST api/playmix/{id}/sort`. */
data class PlaymixSortRequest(
    val criterion: String,
    val direction: String = "asc",
    val preview: Boolean,
    val seed: Int? = null
)

/** Respuesta de `POST api/playmix/{id}/sort`: canciones en el orden propuesto y semilla en `shuffle`. */
data class PlaymixSortResponseDTO(
    val songs: List<PlaymixSongDTO>?,
    val seed: Int?
)

/** Body de `POST api/playmix/merge`. */
data class PlaymixMergeRequest(
    val playmixIds: List<String>,
    val name: String
)

/** Criterios de ordenación del servidor. */
object PlaymixSortCriterion {
    const val BPM = "bpm"
    const val KEY = "key"
    const val HARMONIC = "harmonic"
    const val LOUDNESS = "loudness"
    const val TITLE = "title"
    const val ARTIST = "artist"
    const val SHUFFLE = "shuffle"

    val ALL = listOf(HARMONIC, BPM, KEY, LOUDNESS, TITLE, ARTIST, SHUFFLE)
}
