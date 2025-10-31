package com.example.resonant.managers

import android.content.Context
import android.util.Log
import com.example.resonant.data.network.SearchResponse
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.CoverRequestDTO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.collections.get

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

            val allIds = service.getAllSongIds()
            if (allIds.size < count) return emptyList()

            val randomIds = allIds.shuffled().take(count)

            val songs = coroutineScope {
                randomIds.map { id ->
                    async {
                        try {
                            service.getSongByIdWithMetadata(id)
                        } catch (e: Exception) {
                            Log.e("SongManager", "Error fetching song with ID $id in parallel", e)
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            enrichSongsWithUrls(context, songs)
        } catch (e: Exception) {
            Log.e("SongManager", "Error in getRandomSongs", e)
            emptyList()
        }
    }

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

    suspend fun searchSongs(context: Context, query: String): SearchResponse<Song> {
        return try {
            val service = ApiClient.getService(context)
            val response = service.searchSongsWithMetadata(query)
            val enriched = enrichSongsWithUrls(context, response.results)
            SearchResponse(enriched, response.suggestions)
        } catch (e: Exception) {
            Log.e("SongManager", "Error searching songs with query '$query'", e)
            SearchResponse(emptyList(), emptyList())
        }
    }

    suspend fun getMostStreamedSongsByArtist(context: Context, artistId: String, count: Int): List<Song> {
        return try {
            val service = ApiClient.getService(context)

            val songs = service.getMostStreamedSongsByArtist(artistId, count)

            enrichSongsWithUrls(context, songs)
        } catch (e: Exception) {
            Log.e("SongManager", "Error fetching most streamed songs for artist '$artistId'", e)
            emptyList()
        }
    }

    suspend fun enrichSingleSong(context: Context, song: Song): Song {
        return enrichSongsWithUrls(context, listOf(song)).first()
    }

    private suspend fun enrichSongsWithUrls(context: Context, songs: List<Song>): List<Song> {
        if (songs.isEmpty()) return emptyList()

        val service = ApiClient.getService(context)

        songs.forEach { song ->
            if (song.artistName.isNullOrEmpty() && song.artists.isNotEmpty()) {
                song.artistName = song.artists.joinToString(", ") { it.name }
            }
        }

        val songsWithoutUrl = songs.filter { it.url.isNullOrEmpty() }
        if (songsWithoutUrl.isNotEmpty()) {
            val fileNames = songsWithoutUrl.map { it.fileName }
            val urlList = service.getMultipleSongUrls(fileNames)
            val urlMap = urlList.associateBy { it.fileName }
            songsWithoutUrl.forEach { song ->
                song.url = urlMap[song.fileName]?.url
            }
        }

        val songsWithoutCoverUrl = songs.filter { it.coverUrl.isNullOrEmpty() && !it.imageFileName.isNullOrBlank() }
        if (songsWithoutCoverUrl.isNotEmpty()) {

            // --- ESTA ES LA SOLUCIÓN ---
            // 1. Crea la lista de DTOs (en lugar de dos listas separadas)
            val coverRequests: List<CoverRequestDTO> = songsWithoutCoverUrl.map { song ->
                CoverRequestDTO(
                    imageFileName = song.imageFileName!!, // '!!' está bien por el filtro
                    albumId = song.albumId                // albumId es nulable, perfecto
                )
            }

            // 2. Llama a la API con la lista única de DTOs
            val coverUrlList = service.getMultipleSongCoverUrls(coverRequests)
            // --- FIN DE LA SOLUCIÓN ---


            // 3. El resto de tu código ya funciona
            val coverUrlMap = coverUrlList.associateBy({ it.imageFileName to it.albumId }, { it.url })
            songsWithoutCoverUrl.forEach { song ->
                song.coverUrl = coverUrlMap[song.imageFileName to song.albumId]
            }
        }

        return songs
    }
}