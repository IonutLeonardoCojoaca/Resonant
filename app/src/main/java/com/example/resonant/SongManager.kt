package com.example.resonant.managers // o el paquete que prefieras

import android.content.Context
import android.util.Log
import com.example.resonant.ApiClient
import com.example.resonant.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Singleton Manager para centralizar la obtención y preparación de datos de canciones.
 * Utiliza los endpoints "...WithMetadata" para ser súper eficiente, obteniendo canciones,
 * artistas y análisis en una sola llamada, y luego enriquece los resultados con
 * las URLs de Minio necesarias para la reproducción y visualización.
 */
object SongManager {

    suspend fun getRandomSongs(context: Context, count: Int): List<Song> {
        return try {
            val service = ApiClient.getService(context)

            // 1. Obtenemos todos los IDs (igual que antes)
            val allIds = service.getAllSongIds()
            if (allIds.size < count) return emptyList()

            // 2. Elegimos N IDs al azar
            val randomIds = allIds.shuffled().take(count)

            // --- 3. ¡LA MAGIA DE LA PARALELIZACIÓN! ---
            // Usamos coroutineScope y async para lanzar todas las peticiones a la vez.
            val songs = coroutineScope {
                randomIds.map { id ->
                    async {
                        try {
                            // Usamos el endpoint que ya trae los artistas y el análisis
                            service.getSongByIdWithMetadata(id)
                        } catch (e: Exception) {
                            Log.e("SongManager", "Error fetching song with ID $id in parallel", e)
                            null // Si una canción falla, no rompemos todo el proceso
                        }
                    }
                }.awaitAll().filterNotNull() // Esperamos a que todas terminen y filtramos las que fallaron
            }
            // --- FIN DE LA MAGIA ---

            // 4. Enriquecemos la lista resultante con las URLs de Minio en lote.
            enrichSongsWithUrls(context, songs)
        } catch (e: Exception) {
            Log.e("SongManager", "Error in getRandomSongs", e)
            emptyList()
        }
    }

    /**
     * Obtiene una única canción por su ID, enriquecida con todos sus metadatos y URLs.
     * @return El objeto Song completo, o null si no se encuentra o hay un error.
     */
    suspend fun getSongById(context: Context, songId: String): Song? {
        return try {
            val service = ApiClient.getService(context)
            val song = service.getSongByIdWithMetadata(songId)
            // Usamos un pequeño truco para reutilizar la lógica de enriquecimiento:
            // metemos la canción en una lista, la enriquecemos, y sacamos el primer resultado.
            enrichSongsWithUrls(context, listOf(song)).firstOrNull()
        } catch (e: Exception) {
            Log.e("SongManager", "Error fetching song by ID '$songId'", e)
            null
        }
    }

    /**
     * Obtiene las canciones de un álbum, enriquecidas con todos sus metadatos y URLs.
     */
    suspend fun getSongsFromAlbum(context: Context, albumId: String): List<Song> {
        return try {
            val service = ApiClient.getService(context)
            val songs = service.getSongsByAlbumIdWithMetadata(albumId)
            enrichSongsWithUrls(context, songs)
        } catch (e: Exception) {
            Log.e("SongManager", "Error fetching songs from album '$albumId'", e)
            emptyList()
        }
    }

    /**
     * Obtiene las canciones de un artista, enriquecidas con todos sus metadatos y URLs.
     */
    suspend fun getSongsFromArtist(context: Context, artistId: String): List<Song> {
        return try {
            val service = ApiClient.getService(context)
            val songs = service.getSongsByArtistIdWithMetadata(artistId)
            enrichSongsWithUrls(context, songs)
        } catch (e: Exception) {
            Log.e("SongManager", "Error fetching songs from artist '$artistId'", e)
            emptyList()
        }
    }

    /**
     * Obtiene las canciones favoritas de un usuario, enriquecidas con todos sus metadatos y URLs.
     */
    suspend fun getFavoriteSongs(context: Context, userId: String): List<Song> {
        return try {
            val service = ApiClient.getService(context)
            val songs = service.getSongsByUserIdWithMetadata(userId)
            enrichSongsWithUrls(context, songs)
        } catch (e: Exception) {
            Log.e("SongManager", "Error fetching favorite songs for user '$userId'", e)
            emptyList()
        }
    }

    /**
     * Obtiene las canciones de una playlist, enriquecidas con todos sus metadatos y URLs.
     */
    suspend fun getSongsFromPlaylist(context: Context, playlistId: String): List<Song> {
        return try {
            val service = ApiClient.getService(context)
            val songs = service.getSongsByPlaylistIdWithMetadata(playlistId)
            enrichSongsWithUrls(context, songs)
        } catch (e: Exception) {
            Log.e("SongManager", "Error fetching songs from playlist '$playlistId'", e)
            emptyList()
        }
    }

    /**
     * Realiza una búsqueda de canciones, enriqueciendo los resultados con todos sus metadatos y URLs.
     */
    suspend fun searchSongs(context: Context, query: String): List<Song> {
        return try {
            val service = ApiClient.getService(context)
            val songs = service.searchSongsWithMetadata(query)
            enrichSongsWithUrls(context, songs)
        } catch (e: Exception) {
            Log.e("SongManager", "Error searching songs with query '$query'", e)
            emptyList()
        }
    }

    suspend fun enrichSingleSong(context: Context, song: Song): Song {
        // Simplemente envolvemos la canción en una lista para poder usar nuestra
        // función de enriquecimiento principal y devolvemos el primer (y único) elemento.
        return enrichSongsWithUrls(context, listOf(song)).first()
    }

    /**
     * Función PRIVADA y centralizada que hace el "trabajo sucio":
     * 1. Formatea el nombre de los artistas.
     * 2. Pide las URLs de las canciones a Minio en un solo lote.
     * 3. Pide las URLs de las portadas a Minio en un solo lote.
     */
    private suspend fun enrichSongsWithUrls(context: Context, songs: List<Song>): List<Song> {
        if (songs.isEmpty()) return emptyList()

        val service = ApiClient.getService(context)

        // 1. Formatear nombres de artistas
        songs.forEach { song ->
            if (song.artistName.isNullOrEmpty() && song.artists.isNotEmpty()) {
                song.artistName = song.artists.joinToString(", ") { it.name }
            }
        }

        // 2. Obtener URLs de las canciones
        val songsWithoutUrl = songs.filter { it.url.isNullOrEmpty() }
        if (songsWithoutUrl.isNotEmpty()) {
            val fileNames = songsWithoutUrl.map { it.fileName }
            val urlList = service.getMultipleSongUrls(fileNames)
            val urlMap = urlList.associateBy { it.fileName }
            songsWithoutUrl.forEach { song ->
                song.url = urlMap[song.fileName]?.url
            }
        }

        // 3. Obtener URLs de las portadas
        val songsWithoutCoverUrl = songs.filter { it.coverUrl.isNullOrEmpty() && !it.imageFileName.isNullOrBlank() }
        if (songsWithoutCoverUrl.isNotEmpty()) {
            val coverRequests = songsWithoutCoverUrl.map { it.imageFileName!! to it.albumId }
            val (fileNames, albumIds) = coverRequests.unzip()
            val coverUrlList = service.getMultipleSongCoverUrls(fileNames, albumIds)
            val coverUrlMap = coverUrlList.associateBy({ it.imageFileName to it.albumId }, { it.url })
            songsWithoutCoverUrl.forEach { song ->
                song.coverUrl = coverUrlMap[song.imageFileName to song.albumId]
            }
        }

        return songs
    }
}