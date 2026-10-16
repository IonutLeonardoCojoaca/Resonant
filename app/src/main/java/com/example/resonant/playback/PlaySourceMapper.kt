package com.example.resonant.playback

/**
 * Valores canónicos de `playSource` que entiende el servidor (add-recommendation-telemetry). El servidor
 * también acepta texto libre y lo normaliza, así que enviar estos valores a un backend antiguo no rompe nada.
 */
object PlaySourceMapper {
    const val HOME_FOR_YOU = "HOME_FOR_YOU"
    const val HOME_HISTORY = "HOME_HISTORY"
    const val HOME_OTHER = "HOME_OTHER"
    const val EXPLORE = "EXPLORE"
    const val RADIO = "RADIO"
    const val RELATED = "RELATED"
    const val ARIA_PICK = "ARIA_PICK"
    const val SONG_OF_DAY = "SONG_OF_DAY"
    const val FAVORITES = "FAVORITES"
    const val ARTIST = "ARTIST"
    const val OTHER = "OTHER"

    /**
     * Fuente canónica de una cola sin atribución explícita. `QueueSource` no cambia: la radio del servidor
     * depende de sus nombres. Las fuentes sin equivalente propio conservan su nombre (p. ej. `STATS`, que
     * el historial muestra tal cual y el servidor normaliza a `OTHER`).
     */
    fun canonical(source: QueueSource?): String = when (source) {
        null -> QueueSource.UNKNOWN.name
        QueueSource.HOME -> HOME_OTHER
        QueueSource.FAVORITE_SONGS -> FAVORITES
        QueueSource.TOP_SONGS_ARTIST -> ARTIST
        QueueSource.RELATED_SONGS -> RELATED
        QueueSource.COLLAB_FINDER, QueueSource.DOWNLOADED_SONGS -> OTHER
        else -> source.name
    }
}
