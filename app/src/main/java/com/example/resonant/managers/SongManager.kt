package com.example.resonant.managers

import android.content.Context
import android.util.Log
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.AddStreamDTO
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.RecommendationResult
import com.example.resonant.data.network.SearchResponse
import com.example.resonant.data.network.services.SongService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class SongManager(private val context: Context) {

    private val songService: SongService = ApiClient.getSongService(context)

    suspend fun getRandomSongs(count: Int): List<Song> {
        return try {
            val allIds = songService.getAllSongIds()
            if (allIds.size < count) return emptyList()

            val randomIds = allIds.shuffled().take(count)

            val songs = coroutineScope {
                randomIds.map { id ->
                    async {
                        try {
                            songService.getSongByIdWithMetadata(id)
                        } catch (e: Exception) {
                            Log.e("SongManager", "Error fetching song with ID $id in parallel", e)
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            // Solo formateamos nombres, ya no descargamos URLs
            formatSongMetadata(songs)
        } catch (e: Exception) {
            Log.e("SongManager", "Error in getRandomSongs", e)
            emptyList()
        }
    }

    suspend fun getRecommendedSongs(userId: String, count: Int): RecommendationResult<Song>? {
        // 1. Intentamos obtener recomendaciones de IA
        try {
            val recommendations = songService.getRecommendedSongs(userId, count)

            // Si la IA devuelve lista vacía, lanzamos excepción manual para ir al catch
            if (recommendations.isEmpty()) throw Exception("No recommendations for new user")

            val songs = recommendations.map { it.item }
            formatSongMetadata(songs)

            val title = recommendations.firstOrNull()?.reason?.message ?: "Recomendado para ti"
            return RecommendationResult(items = songs, title = title)

        } catch (e: Exception) {
            Log.w("SongManager", "Fallo en recomendaciones o usuario nuevo. Obteniendo aleatorios. Error: ${e.message}")

            // 2. FALLBACK: Obtenemos canciones aleatorias (Lo antiguo)
            val randomSongs = getRandomSongs(count)

            return if (randomSongs.isNotEmpty()) {
                RecommendationResult(items = randomSongs, title = "Descubre canciones")
            } else {
                null // Si fallan los aleatorios también, entonces sí devolvemos null (Error real)
            }
        }
    }

    suspend fun addStream(streamData: AddStreamDTO) {
        try {
            songService.addStream(streamData)
            Log.d("SongManager", "Stream añadido correctamente para: ${streamData.songId}")
        } catch (e: Exception) {
            Log.e("SongManager", "Error al añadir stream", e)
        }
    }

    suspend fun getSongById(songId: String): Song? {
        return try {
            val song = songService.getSongByIdWithMetadata(songId)
            formatSongMetadata(listOf(song)).firstOrNull()
        } catch (e: Exception) {
            Log.e("SongManager", "Error fetching song by ID '$songId'", e)
            null
        }
    }

    suspend fun getSongsFromAlbum(albumId: String): List<Song> {
        return try {
            val songs = songService.getSongsByAlbumIdWithMetadata(albumId)
            formatSongMetadata(songs)
        } catch (e: Exception) {
            Log.e("SongManager", "Error fetching songs from album '$albumId'", e)
            emptyList()
        }
    }

    suspend fun getSongsFromArtist(artistId: String): List<Song> {
        return try {
            val songs = songService.getSongsByArtistIdWithMetadata(artistId)
            formatSongMetadata(songs)
        } catch (e: Exception) {
            Log.e("SongManager", "Error fetching songs from artist '$artistId'", e)
            emptyList()
        }
    }

    suspend fun getRecentlyPlayedSongs(userId: String, count: Int): List<Song> {
        return try {
            val songsList = songService.getHistorySongByIdWithMetadata(userId, count)
            formatSongMetadata(songsList)
        } catch (e: Exception) {
            Log.e("SongManager", "Error fetching recently played songs for user '$userId'", e)
            emptyList()
        }
    }

    suspend fun getFavoriteSongs(userId: String): List<Song> {
        return try {
            val songs = songService.getSongsByUserIdWithMetadata(userId)
            formatSongMetadata(songs)
        } catch (e: Exception) {
            Log.e("SongManager", "Error fetching favorite songs for user '$userId'", e)
            emptyList()
        }
    }

    suspend fun getSongsFromPlaylist(playlistId: String): List<Song> {
        return try {
            val songs = songService.getSongsByPlaylistIdWithMetadata(playlistId)
            formatSongMetadata(songs)
        } catch (e: Exception) {
            Log.e("SongManager", "Error fetching songs from playlist '$playlistId'", e)
            emptyList()
        }
    }

    suspend fun searchSongs(query: String): SearchResponse<Song> {
        return try {
            val response = songService.searchSongsWithMetadata(query)
            val enriched = formatSongMetadata(response.results)
            SearchResponse(enriched, response.suggestions)
        } catch (e: Exception) {
            Log.e("SongManager", "Error searching songs with query '$query'", e)
            SearchResponse(emptyList(), emptyList())
        }
    }

    suspend fun getMostStreamedSongsByArtist(artistId: String, count: Int): List<Song> {
        return try {
            val songs = songService.getMostStreamedSongsByArtist(artistId, count)
            formatSongMetadata(songs)
        } catch (e: Exception) {
            Log.e("SongManager", "Error fetching most streamed songs for artist '$artistId'", e)
            emptyList()
        }
    }

    fun enrichSingleSong(song: Song): Song {
        return formatSongMetadata(listOf(song)).first()
    }

    private fun formatSongMetadata(songs: List<Song>): List<Song> {
        if (songs.isEmpty()) return emptyList()
        songs.forEach { song ->
            if (song.artistName.isNullOrEmpty() && song.artists.isNotEmpty()) {
                song.artistName = song.artists.joinToString(", ") { it.name }
            }
        }
        return songs
    }
}