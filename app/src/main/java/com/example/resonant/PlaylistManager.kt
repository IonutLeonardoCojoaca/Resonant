package com.example.resonant

import android.util.Log

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

    // --- ¡AQUÍ ESTÁ LA FUNCIÓN REFACTORIZADA Y OPTIMIZADA! ---
    suspend fun getSongsByPlaylistId(
        playlistId: String,
        previousSongs: List<Song>? = null // Este parámetro ya no es realmente necesario aquí
    ): List<Song> {
        Log.d("PlaylistManager", "Llamando al nuevo endpoint unificado para obtener canciones con artistas...")

        // 1. UNA SOLA LLAMADA A LA RED
        // Llamamos al nuevo método de Retrofit que trae las canciones y sus artistas anidados.
        val cancionesConArtistas = api.getSongsByPlaylistIdWithArtists(playlistId)
        Log.d("PlaylistManager", "Respuesta recibida. ${cancionesConArtistas.size} canciones cargadas eficientemente.")


        // 2. FORMATEO (Retrocompatibilidad)
        // Rellenamos el campo 'artistName' para que el resto de tu app (como el Adapter)
        // siga funcionando sin cambios.
        cancionesConArtistas.forEach { song ->
            song.artistName = song.artists.joinToString(", ") { it.name }
        }

        // -------------------------------------------------------------------------
        // --- CÓDIGO ANTIGUO ELIMINADO ---
        // Se ha borrado todo el bucle 'for' que hacía una llamada a la API por cada
        // canción. Este era el culpable del bloqueo.
        // -------------------------------------------------------------------------


        // 3. OBTENER URLS DE AUDIO Y PORTADAS (Lógica mantenida por ahora)
        // Esta lógica puede permanecer si tu API aún no devuelve estas URLs en la llamada principal.
        // Si en el futuro tu API también devuelve estas URLs, podrás eliminar estos bloques.

        // Obtener URLs prefirmadas de audio si faltan
        val cancionesSinUrl = cancionesConArtistas.filter { it.url == null }
        if (cancionesSinUrl.isNotEmpty()) {
            val fileNames = cancionesSinUrl.map { it.fileName }
            val urlList = api.getMultipleSongUrls(fileNames)
            val urlMap = urlList.associateBy { it.fileName }
            cancionesSinUrl.forEach { song ->
                song.url = urlMap[song.fileName]?.url
            }
        }

        // Obtener URLs de portadas
        val coversRequest = cancionesConArtistas.mapNotNull { song ->
            song.imageFileName?.takeIf { it.isNotBlank() }?.let { fileName ->
                song.albumId.takeIf { it.isNotBlank() }?.let { albumId ->
                    fileName to albumId
                }
            }
        }

        if (coversRequest.isNotEmpty()) {
            val (fileNames, albumIds) = coversRequest.unzip()
            val coverResponses: List<CoverResponse> = api.getMultipleSongCoverUrls(fileNames, albumIds)
            val coverUrlMap: Map<Pair<String, String>, String> = coverResponses.associateBy(
                keySelector = { it.imageFileName to it.albumId },
                valueTransform = { it.url }
            )
            cancionesConArtistas.forEach { song ->
                val key = song.imageFileName to song.albumId
                song.coverUrl = coverUrlMap[key]
            }
        }

        return cancionesConArtistas
    }
}