package com.example.resonant.managers

import com.example.resonant.data.network.AddSongToPlaymixRequest
import com.example.resonant.data.network.CopyTransitionRequest
import com.example.resonant.data.network.CopyTransitionResponseDTO
import com.example.resonant.data.network.CreatePlaymixRequest
import com.example.resonant.data.network.EditPlaymixSongRequest
import com.example.resonant.data.network.PlaymixCopyRequest
import com.example.resonant.data.network.PlaymixDTO
import com.example.resonant.data.network.PlaymixDetailDTO
import com.example.resonant.data.network.PlaymixMergeRequest
import com.example.resonant.data.network.PlaymixPageDTO
import com.example.resonant.data.network.PlaymixSortRequest
import com.example.resonant.data.network.PlaymixSortResponseDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.data.network.PlaymixTransitionUpdateDTO
import com.example.resonant.data.network.PlaymixVisibilityRequest
import com.example.resonant.data.network.RenamePlaymixRequest
import com.example.resonant.data.network.ReorderSongsRequest
import com.example.resonant.data.network.WaveformResponseDTO
import com.example.resonant.data.network.services.PlaymixService
import com.google.gson.JsonObject
import retrofit2.Response
import java.io.IOException

/** Fake REST escrito a mano: sirve los detalles del mapa, cuenta llamadas y falla con IOException si faltan. */
class FakePlaymixService : PlaymixService {
    val details = mutableMapOf<String, PlaymixDetailDTO>()
    var myPlaymixes: List<PlaymixDTO> = emptyList()
    var detailCalls = 0
    var listCalls = 0

    override suspend fun getPlaymixDetail(id: String): PlaymixDetailDTO {
        detailCalls++
        return details[id] ?: throw IOException("offline")
    }

    override suspend fun getMyPlaymixes(): List<PlaymixDTO> {
        listCalls++
        return myPlaymixes
    }

    override suspend fun renamePlaymix(id: String, request: RenamePlaymixRequest): PlaymixDetailDTO =
        details.getValue(id).copy(name = request.name).also { details[id] = it }

    override suspend fun removeSongFromPlaymix(playmixId: String, playmixSongId: String): Response<Unit> = Response.success(Unit)
    override suspend fun deletePlaymix(id: String): Response<Unit> = Response.success(Unit)

    override suspend fun createPlaymix(request: CreatePlaymixRequest): PlaymixDTO = unused()
    override suspend fun setVisibility(id: String, request: PlaymixVisibilityRequest): PlaymixDetailDTO = unused()
    override suspend fun getPublicPlaymixes(sort: String, limit: Int, cursor: String?): PlaymixPageDTO = unused()
    override suspend fun getLikedPlaymixes(limit: Int, cursor: String?): PlaymixPageDTO = unused()
    override suspend fun likePlaymix(id: String): Response<Unit> = unused()
    override suspend fun unlikePlaymix(id: String): Response<Unit> = unused()
    override suspend fun copyPlaymix(id: String, request: PlaymixCopyRequest): PlaymixDetailDTO = unused()
    override suspend fun sortPlaymix(id: String, request: PlaymixSortRequest): PlaymixSortResponseDTO = unused()
    override suspend fun mergePlaymixes(request: PlaymixMergeRequest): PlaymixDetailDTO = unused()
    override suspend fun addSongToPlaymix(playmixId: String, request: AddSongToPlaymixRequest): Response<Unit> = unused()
    override suspend fun editPlaymixSong(playmixId: String, playmixSongId: String, request: EditPlaymixSongRequest): Response<Unit> = unused()
    override suspend fun reorderSongs(playmixId: String, request: ReorderSongsRequest): Response<Unit> = unused()
    override suspend fun updateTransition(playmixId: String, transitionId: String, request: PlaymixTransitionUpdateDTO): PlaymixTransitionDTO = unused()
    override suspend fun updateTransitionRaw(playmixId: String, transitionId: String, request: JsonObject): PlaymixTransitionDTO = unused()
    override suspend fun getWaveformData(playmixId: String, transitionId: String): WaveformResponseDTO = unused()
    override suspend fun copyTransition(playmixId: String, transitionId: String, request: CopyTransitionRequest): CopyTransitionResponseDTO = unused()

    private fun unused(): Nothing = error("not used by this test")
}

/** Detalle mínimo para los tests de Playmix. */
fun playmixDetail(id: String, name: String = "Mix $id") = PlaymixDetailDTO(
    id = id, name = name, description = null, numberOfTracks = 0, duration = 0, isPublic = false,
    coverUrl = null, songs = emptyList(), transitions = emptyList()
)
