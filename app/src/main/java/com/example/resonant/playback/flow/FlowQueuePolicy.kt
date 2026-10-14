package com.example.resonant.playback.flow

import com.example.resonant.data.models.Song
import com.example.resonant.playback.PlaybackStateRepository
import com.example.resonant.playback.QueueSource

/**
 * Decisiones puras de Flow (autoplay al final de la cola), sin Android ni red, para
 * que [com.example.resonant.services.MusicPlaybackService] solo tenga que aplicarlas.
 */
object FlowQueuePolicy {

    /** Se pide el siguiente lote cuando quedan estas canciones (o menos) por delante. */
    const val PREFETCH_THRESHOLD = 3

    /** Tiempo máximo esperando un lote cuando la cola ya terminó. */
    const val QUEUE_END_TIMEOUT_MS = 10_000L

    /** Máximo de canciones que acepta `POST /api/radio/playlist`. */
    const val MAX_SESSION_SONGS = 200

    /** Colas que no son personales o tienen un final propio: nunca se continúan con Flow. */
    private val NON_PERSONAL_SOURCES = setOf(
        QueueSource.UNISOUND,
        QueueSource.LIVE,
        QueueSource.PLAYMIX,
        QueueSource.DOWNLOADED_SONGS
    )

    fun isEligible(sourceType: QueueSource, repeatMode: Int, enabled: Boolean): Boolean =
        enabled &&
            sourceType !in NON_PERSONAL_SOURCES &&
            repeatMode != PlaybackStateRepository.REPEAT_MODE_ALL

    fun shouldPrefetch(currentIndex: Int, queueSize: Int, inFlight: Boolean): Boolean =
        !inFlight && queueSize > 0 && queueSize - 1 - currentIndex <= PREFETCH_THRESHOLD

    /**
     * Una respuesta solo se aplica si nadie ha empezado otra cola, entrado en una sala o
     * desactivado Flow desde que se pidió.
     */
    fun shouldApply(requestGeneration: Long, currentGeneration: Long, eligibleNow: Boolean): Boolean =
        requestGeneration == currentGeneration && eligibleNow

    /** "Añadir a la cola" va antes de la primera entrada Flow por delante de la actual. */
    fun insertionIndexForAdd(entryIds: List<String>, flowEntryIds: Set<String>, currentIndex: Int): Int {
        for (index in (currentIndex + 1).coerceAtLeast(0) until entryIds.size) {
            if (entryIds[index] in flowEntryIds) return index
        }
        return entryIds.size
    }

    /** Canciones del lote que no están ya en la cola ni repetidas dentro del propio lote. */
    fun songsToAppend(existingSongIds: Collection<String>, batch: List<Song>): List<Song> {
        val seen = existingSongIds.toHashSet()
        return batch.filter { it.id.isNotBlank() && seen.add(it.id) }
    }

    /**
     * Reordena solo la parte elegida por el usuario y deja el bloque Flow detrás, en su
     * orden. Lo usan el aleatorio y la resincronización de la playlist de origen.
     */
    fun <T> reorderPreservingFlow(
        items: List<T>,
        isFlow: (T) -> Boolean,
        reorderUser: (List<T>) -> List<T>
    ): List<T> {
        val (flow, user) = items.partition(isFlow)
        return reorderUser(user) + flow
    }

    const val TARGET_ARTIST = "artist"
    const val TARGET_SONG = "song"

    /**
     * Índices de las entradas Flow por delante de la actual que afecta un feedback: la canción
     * ("no volver a sugerir") o todas las del artista principal ("menos de este artista").
     */
    fun upcomingFlowMatches(
        songs: List<Song>,
        isFlow: (Int) -> Boolean,
        currentIndex: Int,
        targetType: String,
        targetId: String
    ): List<Int> = ((currentIndex + 1).coerceAtLeast(0) until songs.size).filter { index ->
        isFlow(index) && when (targetType) {
            TARGET_SONG -> songs[index].id == targetId
            TARGET_ARTIST -> songs[index].artists.firstOrNull()?.id == targetId
            else -> false
        }
    }

    /** Posición donde vuelve una entrada quitada: la original, si sigue por delante de la actual. */
    fun restoreIndex(originalIndex: Int, currentIndex: Int, queueSize: Int): Int =
        originalIndex.coerceIn((currentIndex + 1).coerceAtMost(queueSize), queueSize)

    /** Canciones de la sesión para guardarla como playlist: desde el inicio hasta la actual. */
    fun sessionSongIds(songs: List<Song>, currentIndex: Int): List<String> =
        songs.take((currentIndex + 1).coerceIn(0, songs.size))
            .map { it.id }
            .filter(String::isNotBlank)
            .distinct()
            .takeLast(MAX_SESSION_SONGS)
}
