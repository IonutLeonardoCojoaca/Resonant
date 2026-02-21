package com.example.resonant.managers

import android.content.Context
import com.example.resonant.data.models.Playlist
import com.example.resonant.data.models.Song
import com.example.resonant.data.models.User
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.services.PlaylistService
import com.example.resonant.data.network.services.UserService

class PlaylistManager(private val context: Context) {

    private val playlistService: PlaylistService = ApiClient.getPlaylistService(context)
    private val userService: UserService = ApiClient.getUserService(context)
    private val songManager = SongManager(context)

    suspend fun createPlaylist(playlist: Playlist): Playlist {
        return playlistService.createPlaylist(playlist)
    }

    suspend fun updatePlaylist(playlist: Playlist) {
        // Nuevo endpoint requiere ID en la ruta
        val response = playlistService.updatePlaylist(playlist.id!!, playlist)

        if (!response.isSuccessful) {
            val errorMsg = response.errorBody()?.string() ?: "Error desconocido"
            throw Exception("Error ${response.code()}: $errorMsg")
        }
    }

    suspend fun getPlaylistById(id: String): Playlist {
        return playlistService.getPlaylistById(id)
    }

    /**
     * Obtiene las playlists del usuario autenticado.
     * El nuevo endpoint api/playlists/mine no necesita userId.
     */
    suspend fun getMyPlaylists(): List<Playlist> {
        return playlistService.getMyPlaylists()
    }

    suspend fun getAllPublicPlaylists(): List<Playlist> {
        return playlistService.getAllPublicPlaylists()
    }

    suspend fun deletePlaylist(id: String) {
        val response = playlistService.deletePlaylist(id)
        if (!response.isSuccessful) {
            throw Exception("Error deleting playlist: ${response.code()}")
        }
    }

    suspend fun addSongToPlaylist(songId: String, playlistId: String) {
        // Nuevo endpoint: POST api/playlists/{id}/songs con songId en el body
        playlistService.addSongToPlaylist(playlistId, songId)
    }

    suspend fun isSongInPlaylist(songId: String, playlistId: String): Boolean {
        // Nuevo endpoint: api/playlists/{id}/songs/{songId}/exists
        return playlistService.isSongInPlaylist(playlistId, songId)
    }

    suspend fun deleteSongFromPlaylist(songId: String, playlistId: String) {
        // Nuevo endpoint: DELETE api/playlists/{id}/songs/{songId}
        playlistService.deleteSongFromPlaylist(playlistId, songId)
    }

    suspend fun updatePlaylistVisibility(playlistId: String, isPublic: Boolean) {
        val response = playlistService.updatePlaylistVisibility(playlistId, isPublic)
        if (!response.isSuccessful) {
            val errorMsg = response.errorBody()?.string() ?: "Error desconocido"
            throw Exception("Error ${response.code()}: $errorMsg")
        }
    }

    // --- MÉTODOS DE USUARIO ---
    suspend fun getUserById(userId: String): User {
        return userService.getUserById(userId)
    }

    // --- OTROS ---
    suspend fun getSongsByPlaylistId(playlistId: String): List<Song> {
        return songManager.getSongsFromPlaylist(playlistId)
    }
}