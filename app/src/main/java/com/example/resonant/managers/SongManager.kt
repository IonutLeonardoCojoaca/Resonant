package com.example.resonant.managers

import android.content.Context
import android.util.Log
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.AddStreamDTO
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.RecommendationResult
import com.example.resonant.data.network.SearchResponse
import com.example.resonant.data.network.SongAudioAnalysisDTO
import com.example.resonant.data.network.SongMetadataDTO
import com.example.resonant.data.network.SongPlaybackDTO
import com.example.resonant.data.network.services.ArtistService
import com.example.resonant.data.network.services.PlaylistService
import com.example.resonant.data.network.services.SongService
import com.example.resonant.data.models.AudioAnalysis
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class SongManager(private val context: Context) {

    // CACHÉ
    private val songsCache = mutableMapOf<String, Song>()
    private val songsCacheTimestamp = mutableMapOf<String, Long>()

    private val albumSongsCache = mutableMapOf<String, List<Song>>()
    private val albumSongsTimestamp = mutableMapOf<String, Long>()

    private val CACHE_DURATION_MS = 20 * 60 * 1000L // 20 Min

    private val songService: SongService = ApiClient.getSongService(context)
    private val artistService: ArtistService = ApiClient.getArtistService(context)
    private val playlistService: PlaylistService = ApiClient.getPlaylistService(context)

    suspend fun getRecommendedSongs(count: Int): RecommendationResult<Song>? {
        // 1. Intentamos obtener recomendaciones de IA
        try {
            val recommendations = songService.getRecommendedSongs(count)

            // Si la IA devuelve lista vacía, lanzamos excepción manual para ir al catch
            if (recommendations.isEmpty()) throw Exception("No recommendations for new user")

            val songs = recommendations.map { it.item }
            formatSongMetadata(songs)
            
            // CACHE THEM
            val now = System.currentTimeMillis()
            songs.forEach { 
                songsCache[it.id] = it
                songsCacheTimestamp[it.id] = now
            }

            val title = recommendations.firstOrNull()?.reason?.message ?: "Recomendado para ti"
            return RecommendationResult(items = songs, title = title)

        } catch (e: Exception) {
            Log.w("SongManager", "Fallo en recomendaciones o usuario nuevo. Error: ${e.message}")
            return null
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

    fun invalidateCache(songId: String) {
        songsCache.remove(songId)
        songsCacheTimestamp.remove(songId)
    }

    suspend fun getSongById(songId: String): Song? {
        val now = System.currentTimeMillis()
        val lastUpdate = songsCacheTimestamp[songId] ?: 0L
        val isExpired = (now - lastUpdate) > CACHE_DURATION_MS

        if (!isExpired && songsCache.containsKey(songId)) {
             return songsCache[songId]
        }

        return try {
            coroutineScope {
                // 1. Fetch Metadata (Title, Artist, Album, Image)
                val metadataDeferred = async { songService.getSongById(songId) }

                // 2. Fetch Playback Info (Stream URL) - Optional failure allowed
                val playbackDeferred = async {
                    try {
                        songService.getSongPlaybackInfo(songId)
                    } catch (e: Exception) {
                        Log.w("SongManager", "Could not fetch playback info for $songId: ${e.message}")
                        null
                    }
                }

                val song = metadataDeferred.await()
                val playbackInfo = playbackDeferred.await()

                // Merge Stream URL if available
                if (playbackInfo != null) {
                    song.url = playbackInfo.streamUrl

                    val effectiveBpm = playbackInfo.bpm ?: 0.0

                    // Map playback info to AudioAnalysis for basic playback/crossfade.
                    // bpmNormalized is set to the same value as bpm when there is no
                    // separate normalized value (the playback endpoint doesn't provide one).
                    // This ensures the intelligent crossfade strategy selector works correctly.
                    val analysis = song.audioAnalysis?.copy(
                        durationMs = playbackInfo.durationMs,
                        bpm = effectiveBpm,
                        bpmNormalized = if (effectiveBpm > 0.0) effectiveBpm
                                        else song.audioAnalysis?.bpmNormalized ?: 0.0,
                        musicalKey = playbackInfo.musicalKey ?: song.audioAnalysis?.musicalKey,
                        loudnessLufs = playbackInfo.loudness?.toFloat() ?: song.audioAnalysis?.loudnessLufs ?: 0f
                    ) ?: AudioAnalysis(
                        id = song.id,
                        songId = song.id,
                        durationMs = playbackInfo.durationMs,
                        bpm = effectiveBpm,
                        bpmNormalized = effectiveBpm, // fallback: same as bpm
                        musicalKey = playbackInfo.musicalKey,
                        loudnessLufs = playbackInfo.loudness?.toFloat() ?: 0f,
                        audioStartMs = playbackInfo.introStartMs ?: 0,
                        audioEndMs = playbackInfo.outroStartMs ?: playbackInfo.durationMs
                    )
                    song.audioAnalysis = analysis
                }

                val enriched = formatSongMetadata(listOf(song)).firstOrNull()

                if (enriched != null) {
                    songsCache[songId] = enriched
                    songsCacheTimestamp[songId] = System.currentTimeMillis()
                }
                enriched
            }
        } catch (e: Exception) {
            Log.e("SongManager", "Error fetching song by ID '$songId'", e)
            songsCache[songId]
        }
    }

    suspend fun getSongAnalysis(songId: String): AudioAnalysis? {
        return try {
            val dto = songService.getSongAnalysis(songId)
            // Map DTO to AudioAnalysis model
            AudioAnalysis(
                id = dto.id,
                songId = songId,
                bpm = dto.bpm ?: 0.0,
                musicalKey = dto.musicalKey,
                segmentsUrl = dto.segmentsUrl
            )
        } catch (e: Exception) {
            Log.e("SongManager", "Error fetching analysis for '$songId'", e)
            null
        }
    }

    suspend fun getSongMetadata(songId: String): SongMetadataDTO? {
        return try {
            songService.getSongMetadata(songId)
        } catch (e: Exception) {
             Log.e("SongManager", "Error fetching metadata for '$songId'", e)
             null
        }
    }

    suspend fun getSongsFromAlbum(albumId: String): List<Song> {
        val now = System.currentTimeMillis()
        val lastUpdate = albumSongsTimestamp[albumId] ?: 0L
        val isExpired = (now - lastUpdate) > CACHE_DURATION_MS

        if (!isExpired && albumSongsCache.containsKey(albumId)) {
             return albumSongsCache[albumId]!!
        }

        return try {
            // Nuevo endpoint: api/albums/{id}/songs (en AlbumService)
            val albumService = ApiClient.getAlbumService(context)
            val songs = albumService.getAlbumSongs(albumId)
            val enriched = formatSongMetadata(songs)
            
            albumSongsCache[albumId] = enriched
            albumSongsTimestamp[albumId] = System.currentTimeMillis()
            
            // Opcional: Cachear individualmente también
            enriched.forEach { 
                songsCache[it.id] = it
                songsCacheTimestamp[it.id] = System.currentTimeMillis()
            }
            
            enriched
        } catch (e: Exception) {
            Log.e("SongManager", "Error fetching songs from album '$albumId'", e)
            albumSongsCache[albumId] ?: emptyList()
        }
    }

    suspend fun getSongsFromArtist(artistId: String): List<Song> {
        return try {
            // Nuevo endpoint: api/artists/{id}/songs
            val songs = artistService.getArtistSongs(artistId)
            formatSongMetadata(songs)
        } catch (e: Exception) {
            Log.e("SongManager", "Error fetching songs from artist '$artistId'", e)
            emptyList()
        }
    }

    suspend fun getFavoriteSongs(): List<Song> {
        return try {
            // Nuevo endpoint: api/songs/favorites (sin userId, extraído del JWT)
            val songs = songService.getFavoriteSongs()
            formatSongMetadata(songs)
        } catch (e: Exception) {
            Log.e("SongManager", "Error fetching favorite songs", e)
            emptyList()
        }
    }

    suspend fun getSongsFromPlaylist(playlistId: String): List<Song> {
        return try {
            // Nuevo endpoint: api/playlists/{id}/songs
            val songs = playlistService.getPlaylistSongs(playlistId)
            formatSongMetadata(songs)
        } catch (e: Exception) {
            Log.e("SongManager", "Error fetching songs from playlist '$playlistId'", e)
            emptyList()
        }
    }

    suspend fun searchSongs(query: String, limit: Int = 30): SearchResponse<Song> {
        return try {
            // Nuevo endpoint: api/songs/search?q=
            val response = songService.searchSongs(query, limit)
            val enriched = formatSongMetadata(response.results)
            SearchResponse(enriched, response.suggestions)
        } catch (e: Exception) {
            Log.e("SongManager", "Error searching songs with query '$query'", e)
            SearchResponse(emptyList(), emptyList())
        }
    }

    suspend fun getMostStreamedSongsByArtist(artistId: String, count: Int): List<Song> {
        return try {
            val songs = artistService.getTopSongsByArtist(artistId, count)
            formatSongMetadata(songs)
        } catch (e: Exception) {
            Log.e("SongManager", "Error fetching most streamed songs for artist '$artistId'", e)
            emptyList()
        }
    }

    fun enrichSingleSong(song: Song): Song {
        return formatSongMetadata(listOf(song)).first()
    }

    suspend fun getPlaybackHistory(limit: Int = 20): List<Song> {
        // Allow exception to propagate for better error handling in ViewModel
        val songs = songService.getPlaybackHistory(limit)
        return formatSongMetadata(songs)
    }

    suspend fun getTopSongs(period: Int, limit: Int = 20): List<Song> {
        return try {
            val songs = songService.getTopSongs(period, limit)
            formatSongMetadata(songs)
        } catch (e: Exception) {
            Log.e("SongManager", "Error fetching top songs for period $period", e)
            emptyList()
        }
    }

    suspend fun getTrendingSongs(limit: Int = 20): List<Song> {
        return try {
            val songs = songService.getTrendingSongs(limit)
            formatSongMetadata(songs)
        } catch (e: Exception) {
            Log.e("SongManager", "Error fetching trending songs", e)
            emptyList()
        }
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