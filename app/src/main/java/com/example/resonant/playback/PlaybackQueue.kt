package com.example.resonant.playback

import com.example.resonant.data.models.Song
import java.util.UUID

/**
 * Mutable, in-memory representation of the active playback queue.
 *
 * [songs] and [entryIds] stay public `var`s so every existing call site in
 * [com.example.resonant.services.MusicPlaybackService] keeps working
 * unchanged, but assigning a new value to [songs] now transparently
 * invalidates a lazily-built `songId -> index` lookup table so
 * [indexOfSongId] resolves in O(1) instead of the O(n) linear scans
 * (`songs.indexOfFirst { it.id == ... }`) that used to run on every
 * skip/seek/queue-mutation.
 */
class PlaybackQueue(
    var sourceId: String,
    var sourceType: QueueSource,
    songs: List<Song>,
    var currentIndex: Int,
    entryIds: List<String> = songs.map { UUID.randomUUID().toString() }
) {

    var songs: List<Song> = songs
        set(value) {
            field = value
            songIndexById = null
        }

    var entryIds: List<String> = entryIds

    /** Entradas añadidas por Flow (autoplay), siempre detrás de las elegidas por el usuario. */
    var flowEntryIds: Set<String> = emptySet()

    /** Motivo visible de cada canción de Flow, por id de canción. */
    var flowReasons: Map<String, String> = emptyMap()

    /**
     * Origen con el que empezó la cola. Editarla a mano cambia [sourceType] a QUEUE, pero
     * Flow debe seguir sembrándose con el álbum o la playlist de partida.
     */
    var flowSeedSourceType: QueueSource = sourceType
    var flowSeedSourceId: String = sourceId

    /** Lazily built, invalidated automatically whenever [songs] is reassigned. */
    private var songIndexById: Map<String, Int>? = null

    /**
     * O(1) average lookup of a song's position in [songs] by id. Falls back
     * gracefully to -1 when not found, same contract as `indexOfFirst`.
     */
    fun indexOfSongId(songId: String): Int {
        val cache = songIndexById ?: buildIndexCache()
        return cache[songId] ?: -1
    }

    private fun buildIndexCache(): Map<String, Int> {
        val map = HashMap<String, Int>(songs.size)
        for (index in songs.indices) {
            // First occurrence wins; duplicate ids in a queue are not
            // expected but this keeps behaviour identical to indexOfFirst.
            map.putIfAbsent(songs[index].id, index)
        }
        songIndexById = map
        return map
    }

    fun resetEntriesForSongs() {
        entryIds = songs.map { UUID.randomUUID().toString() }
    }

    fun ensureEntryIds() {
        if (entryIds.size != songs.size || entryIds.any(String::isBlank)) {
            resetEntriesForSongs()
        }
    }

    /**
     * Appends [newSongs] to the end of the queue while preserving the entry
     * identity of every song already present. Unlike calling
     * `songs = songs + newSongs` followed by [resetEntriesForSongs] (the
     * previous pattern used for Resonant Radio autoplay), this never
     * reshuffles the UUIDs of songs already in the queue, so the "Up Next"
     * RecyclerView only has to bind the newly appended rows instead of
     * rebinding/re-animating the entire list on every autoplay batch.
     */
    fun appendSongs(newSongs: List<Song>) {
        if (newSongs.isEmpty()) return
        ensureEntryIds()
        songs = songs + newSongs
        entryIds = entryIds + newSongs.map { UUID.randomUUID().toString() }
    }

    /** Añade un lote de Flow al final y marca sus entradas. */
    fun appendFlowSongs(newSongs: List<Song>, reasonsBySongId: Map<String, String> = emptyMap()) {
        if (newSongs.isEmpty()) return
        appendSongs(newSongs)
        flowEntryIds = flowEntryIds + entryIds.takeLast(newSongs.size)
        flowReasons = flowReasons + reasonsBySongId.filterKeys { id -> newSongs.any { it.id == id } }
    }

    fun isFlowEntry(index: Int): Boolean = entryIds.getOrNull(index)?.let { it in flowEntryIds } == true

    fun flowSongIds(): Set<String> = songs.indices
        .filter(::isFlowEntry)
        .mapTo(HashSet()) { songs[it].id }

    /**
     * Vuelve a marcar como Flow las entradas de [songIds] después de que la cola se haya
     * reconstruido con identidades nuevas (aleatorio, resincronizar playlist).
     */
    fun remarkFlowSongs(songIds: Set<String>) {
        flowEntryIds = songs.indices
            .filter { songs[it].id in songIds }
            .mapTo(HashSet()) { entryIds[it] }
        flowReasons = flowReasons.filterKeys { it in songIds }
    }

    /** Olvida las marcas de entradas que ya no están en la cola. */
    fun pruneFlowEntries() {
        if (flowEntryIds.isEmpty()) return
        val present = entryIds.toHashSet()
        flowEntryIds = flowEntryIds.filterTo(HashSet()) { it in present }
    }
}
