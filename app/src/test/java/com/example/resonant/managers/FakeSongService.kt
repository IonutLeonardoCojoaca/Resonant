package com.example.resonant.managers

import com.example.resonant.data.models.Song
import com.example.resonant.data.network.AddStreamDTO
import com.example.resonant.data.network.PlaybackResolveRequestDTO
import com.example.resonant.data.network.PlaybackResolveResponseDTO
import com.example.resonant.data.network.RecommendationResponse
import com.example.resonant.data.network.SearchResponse
import com.example.resonant.data.network.SongAudioAnalysisDTO
import com.example.resonant.data.network.SongMetadataDTO
import com.example.resonant.data.network.SongPlaybackDTO
import com.example.resonant.data.network.services.SongService
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.IOException

/** Fake REST escrito a mano: sirve canciones y playback-info del mapa, cuenta llamadas y falla con IOException si faltan. */
class FakeSongService : SongService {
    val songs = mutableMapOf<String, Song>()
    val playback = mutableMapOf<String, SongPlaybackDTO>()
    var songByIdCalls = 0
    var playbackCalls = 0

    override suspend fun getSongById(songId: String): Song {
        songByIdCalls++
        // Copia nueva en cada llamada, como haría Gson con cada respuesta.
        return songs[songId]?.copy() ?: throw IOException("offline")
    }

    override suspend fun getSongPlaybackInfo(songId: String, deliveryMode: String?, preferredQuality: String?, capabilities: String?): SongPlaybackDTO {
        playbackCalls++
        return playback[songId] ?: throw IOException("offline")
    }

    override suspend fun addStream(streamData: AddStreamDTO): Response<ResponseBody> = unused()
    override suspend fun resolvePlayback(request: PlaybackResolveRequestDTO, capabilities: String): PlaybackResolveResponseDTO = unused()
    override suspend fun searchSongs(query: String, limit: Int): SearchResponse<Song> = unused()
    override suspend fun getFavoriteSongs(sort: String?, limit: Int?): List<Song> = unused()
    override suspend fun isFavoriteSong(songId: String): Boolean = unused()
    override suspend fun addFavoriteSong(songId: String) = unused()
    override suspend fun deleteFavoriteSong(songId: String) = unused()
    override suspend fun getRecommendedSongs(count: Int): List<RecommendationResponse<Song>> = unused()
    override suspend fun getTopSongs(period: Int, limit: Int): List<Song> = unused()
    override suspend fun getTrendingSongs(limit: Int): List<Song> = unused()
    override suspend fun getSonicMatch(songId: String): List<Song> = unused()
    override suspend fun getSongMetadata(songId: String): SongMetadataDTO = unused()
    override suspend fun getSongAnalysis(songId: String): SongAudioAnalysisDTO = unused()
    override suspend fun getPlaybackHistory(limit: Int): List<Song> = unused()
    override suspend fun getNewReleaseSongs(limit: Int): List<Song> = unused()
    override suspend fun getSongsByYear(yearFrom: Int?, yearTo: Int?, limit: Int): List<Song> = unused()
    override suspend fun getMostFavoritedSongs(limit: Int): List<Song> = unused()
    override suspend fun getMostListenedSongs(limit: Int): List<Song> = unused()
    override suspend fun getRelatedSongs(songId: String, limit: Int): Response<List<Song>> = unused()

    private fun unused(): Nothing = error("not used by this test")
}
