package com.example.resonant.managers

import android.content.Context
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.Playlist
import com.example.resonant.data.models.Song
import com.example.resonant.data.models.User
import com.example.resonant.services.ApiResonantService

class PlaylistManager(private val api: ApiResonantService) {

    suspend fun createPlaylist(playlist: Playlist): Playlist {
        return api.createPlaylist(playlist)
    }

    suspend fun getPlaylistById(id: String): Playlist {
        return api.getPlaylistById(id)
    }

    suspend fun getPlaylistByUserId(id: String): List<Playlist> {
        return api.getPlaylistByUserId(id)
    }

    suspend fun deletePlaylist(id: String) {
        val response = api.deletePlaylist(id)
        if (!response.isSuccessful) {
            throw Exception("Error deleting playlist: ${response.code()}")
        }
    }

    // Este método ya no es necesario para la carga de playlists,
    // pero puede que lo uses en otras partes de la app.
    suspend fun getArtistsBySongId(songId: String): List<Artist> {
        return api.getArtistsBySongId(songId)
    }

    suspend fun addSongToPlaylist(songId: String, playlistId: String) {
        api.addSongToPlaylist(songId, playlistId)
    }

    suspend fun isSongInPlaylist(songId: String, playlistId: String): Boolean {
        return api.isSongInPlaylist(songId, playlistId)
    }

    suspend fun deleteSongFromPlaylist(songId: String, playlistId: String) {
        api.deleteSongFromPlaylist(songId, playlistId)
    }

    suspend fun getUserById(userId: String): User {
        return api.getUserById(userId)
    }

    // En la misma clase donde tenías la función anterior
    suspend fun getSongsByPlaylistId(
        context: Context, // <-- Importante: ahora necesitamos el contexto
        playlistId: String
    ): List<Song> {
        // Toda la complejidad se delega al SongManager
        return SongManager.getSongsFromPlaylist(context, playlistId)
    }
}