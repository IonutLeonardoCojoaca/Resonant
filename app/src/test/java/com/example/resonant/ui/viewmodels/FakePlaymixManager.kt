package com.example.resonant.ui.viewmodels

import com.example.resonant.data.network.PlaymixDTO
import com.example.resonant.data.network.PlaymixDetailDTO
import com.example.resonant.managers.PlaymixManager
import kotlinx.coroutines.CompletableDeferred

/**
 * Gestor falso para los ViewModels de Playmix: respuestas controladas desde el test (cada petición espera a
 * que el test la complete) y contadores de llamadas. No toca las cachés estáticas del manager real.
 */
class FakePlaymixManager : PlaymixManager(null) {
    var cached: PlaymixDetailDTO? = null
    var fresh = false
    var cachedList: List<PlaymixDTO>? = null
    var listFresh = false

    val pendingDetails = ArrayDeque<CompletableDeferred<PlaymixDetailDTO>>()
    val pendingLists = ArrayDeque<CompletableDeferred<List<PlaymixDTO>>>()
    var detailCalls = 0
    var listCalls = 0
    var removedSongs = mutableListOf<String>()
    var deleted = mutableListOf<String>()

    override fun cachedDetail(id: String): PlaymixDetailDTO? = cached
    override fun isDetailFresh(id: String): Boolean = fresh

    override suspend fun getPlaymixDetail(id: String, forceRefresh: Boolean): PlaymixDetailDTO {
        detailCalls++
        return CompletableDeferred<PlaymixDetailDTO>().also { pendingDetails.addLast(it) }.await()
    }

    override suspend fun removeSongFromPlaymix(playmixId: String, playmixSongId: String) {
        removedSongs += playmixSongId
    }

    override fun getCachedMyPlaymixes(): List<PlaymixDTO>? = cachedList
    override fun isPlaymixCacheFresh(): Boolean = listFresh

    override suspend fun getMyPlaymixes(forceRefresh: Boolean): List<PlaymixDTO> {
        listCalls++
        return CompletableDeferred<List<PlaymixDTO>>().also { pendingLists.addLast(it) }.await()
    }

    override suspend fun deletePlaymix(id: String) {
        deleted += id
    }
}

fun detailOf(id: String, name: String = "Mix $id", songIds: List<String> = emptyList()) = PlaymixDetailDTO(
    id = id, name = name, description = null, numberOfTracks = songIds.size, duration = 0, isPublic = false,
    coverUrl = null, transitions = emptyList(),
    songs = songIds.mapIndexed { index, songId ->
        com.example.resonant.data.network.PlaymixSongDTO(
            playmixSongId = "ps-$songId", position = index, songId = songId, title = songId, artist = null,
            duration = 100, customEntryMs = null, customExitMs = null, audioAnalysis = null
        )
    }
)

fun playmixOf(id: String) = PlaymixDTO(
    id = id, name = "Mix $id", description = null, numberOfTracks = 0, duration = 0, isPublic = false,
    coverUrl = null, createdAt = "2026-01-01"
)
