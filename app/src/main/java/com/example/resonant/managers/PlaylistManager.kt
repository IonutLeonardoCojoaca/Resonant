package com.example.resonant.managers

import android.content.Context
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.Playlist
import com.example.resonant.data.models.Song
import com.example.resonant.data.models.User
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.services.ArtistService
import com.example.resonant.data.network.services.PlaylistService
import com.example.resonant.data.network.services.UserService

class PlaylistManager(private val context: Context) {

    private val playlistService: PlaylistService = ApiClient.getPlaylistService(context)
    private val artistService: ArtistService = ApiClient.getArtistService(context)
    private val userService: UserService = ApiClient.getUserService(context)

    // CAMBIO IMPORTANTE: Creamos una instancia de SongManager aquí
    private val songManager = SongManager(context)

    suspend fun createPlaylist(playlist: Playlist): Playlist {
        return playlistService.createPlaylist(playlist)
    }

    suspend fun getPlaylistById(id: String): Playlist {
        return playlistService.getPlaylistById(id)
    }

    suspend fun getPlaylistByUserId(id: String): List<Playlist> {
        return playlistService.getPlaylistByUserId(id)
    }

    suspend fun deletePlaylist(id: String) {
        val response = playlistService.deletePlaylist(id)
        if (!response.isSuccessful) {
            throw Exception("Error deleting playlist: ${response.code()}")
        }
    }

    suspend fun addSongToPlaylist(songId: String, playlistId: String) {
        playlistService.addSongToPlaylist(songId, playlistId)
    }

    suspend fun isSongInPlaylist(songId: String, playlistId: String): Boolean {
        return playlistService.isSongInPlaylist(songId, playlistId)
    }

    suspend fun deleteSongFromPlaylist(songId: String, playlistId: String) {
        playlistService.deleteSongFromPlaylist(songId, playlistId)
    }

    // --- MÉTODOS DE ARTISTA ---
    suspend fun getArtistsBySongId(songId: String): List<Artist> {
        return artistService.getArtistsBySongId(songId)
    }

    // --- MÉTODOS DE USUARIO ---
    suspend fun getUserById(userId: String): User {
        return userService.getUserById(userId)
    }

    // --- OTROS ---

    suspend fun getSongsByPlaylistId(
        // Ya no necesitamos pasar 'context' aquí porque la clase ya lo tiene
        playlistId: String
    ): List<Song> {
        // CAMBIO: Usamos la instancia 'songManager'
        // Y ya no le pasamos el contexto, porque SongManager ya lo tiene.
        return songManager.getSongsFromPlaylist(playlistId)
    }
}