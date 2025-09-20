package com.example.resonant

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

    suspend fun addSongToPlaylist(songId: String, playlistId: String) {
        api.addSongToPlaylist(songId, playlistId)
    }

    suspend fun isSongInPlaylist(songId: String, playlistId: String): Boolean {
        return api.isSongInPlaylist(songId, playlistId)
    }

    suspend fun deleteSongFromPlaylist(songId: String, playlistId: String) {
        api.deleteSongFromPlaylist(songId, playlistId)
    }

    suspend fun getSongsByPlaylistId(playlistId: String): List<Song> {
        return api.getSongsByPlaylistId(playlistId)
    }

}