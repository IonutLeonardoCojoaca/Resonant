package com.example.resonant.managers

import android.content.Context
import com.example.resonant.data.network.AddSongToPlaymixRequest
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.CreatePlaymixRequest
import com.example.resonant.data.network.EditPlaymixSongRequest
import com.example.resonant.data.network.PlaymixDTO
import com.example.resonant.data.network.PlaymixDetailDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.data.network.PlaymixTransitionUpdateDTO
import com.example.resonant.data.network.ReorderSongsRequest
import com.example.resonant.data.network.WaveformResponseDTO
import com.example.resonant.data.network.services.PlaymixService

class PlaymixManager(private val context: Context) {

    private val playmixService: PlaymixService = ApiClient.getPlaymixService(context)

    suspend fun createPlaymix(name: String, description: String? = null): PlaymixDTO {
        return playmixService.createPlaymix(CreatePlaymixRequest(name, description))
    }

    suspend fun getMyPlaymixes(): List<PlaymixDTO> {
        return playmixService.getMyPlaymixes()
    }

    suspend fun getPlaymixDetail(id: String): PlaymixDetailDTO {
        return playmixService.getPlaymixDetail(id)
    }

    suspend fun deletePlaymix(id: String) {
        val response = playmixService.deletePlaymix(id)
        if (!response.isSuccessful) {
            val errorMsg = response.errorBody()?.string() ?: "Error desconocido"
            throw Exception("Error ${response.code()}: $errorMsg")
        }
    }

    suspend fun addSongToPlaymix(playmixId: String, songId: String) {
        val response = playmixService.addSongToPlaymix(playmixId, AddSongToPlaymixRequest(songId))
        if (!response.isSuccessful) {
            val errorMsg = response.errorBody()?.string() ?: "Error desconocido"
            throw Exception("Error ${response.code()}: $errorMsg")
        }
    }

    suspend fun removeSongFromPlaymix(playmixId: String, playmixSongId: String) {
        val response = playmixService.removeSongFromPlaymix(playmixId, playmixSongId)
        if (!response.isSuccessful) {
            val errorMsg = response.errorBody()?.string() ?: "Error desconocido"
            throw Exception("Error ${response.code()}: $errorMsg")
        }
    }

    suspend fun editPlaymixSong(playmixId: String, playmixSongId: String, customEntryMs: Int?, customExitMs: Int?) {
        val response = playmixService.editPlaymixSong(
            playmixId, playmixSongId,
            EditPlaymixSongRequest(customEntryMs, customExitMs)
        )
        if (!response.isSuccessful) {
            val errorMsg = response.errorBody()?.string() ?: "Error desconocido"
            throw Exception("Error ${response.code()}: $errorMsg")
        }
    }

    suspend fun reorderSongs(playmixId: String, orderedSongIds: List<String>) {
        val response = playmixService.reorderSongs(playmixId, ReorderSongsRequest(orderedSongIds))
        if (!response.isSuccessful) {
            val errorMsg = response.errorBody()?.string() ?: "Error desconocido"
            throw Exception("Error ${response.code()}: $errorMsg")
        }
    }

    suspend fun updateTransition(
        playmixId: String,
        transitionId: String,
        update: PlaymixTransitionUpdateDTO
    ): PlaymixTransitionDTO {
        return playmixService.updateTransition(playmixId, transitionId, update)
    }

    suspend fun getWaveformData(playmixId: String, transitionId: String): WaveformResponseDTO {
        return playmixService.getWaveformData(playmixId, transitionId)
    }
}
