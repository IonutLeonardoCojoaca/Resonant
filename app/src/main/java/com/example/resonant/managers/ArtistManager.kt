package com.example.resonant.managers

import android.content.Context
import android.util.Log
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.RecommendationResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

object ArtistManager {

    // CAMBIO 2: La MEMORIA (Caché). Aquí se guardan los datos.
    private val artistDetailsCache = mutableMapOf<String, Artist>()
    private val albumsCache = mutableMapOf<String, List<Album>>()
    private val topSongsCache = mutableMapOf<String, List<Song>>()
    private val cacheTimestamps = mutableMapOf<String, Long>() // Para controlar caducidad
    
    private const val CACHE_DURATION_MS = 20 * 60 * 1000L // 20 Minutos

    private var cachedRecommendation: RecommendationResult<Artist>? = null

    suspend fun getFullArtistData(
        context: Context,
        artistId: String,
        songManager: SongManager // Necesitamos songManager para las canciones top
    ): Triple<Artist, List<Album>, List<Song>> = withContext(Dispatchers.IO) {

        val now = System.currentTimeMillis()
        val lastUpdate = cacheTimestamps[artistId] ?: 0L
        val isExpired = (now - lastUpdate) > CACHE_DURATION_MS

        // A. REVISAR CACHÉ
        if (!isExpired &&
            artistDetailsCache.containsKey(artistId) &&
            albumsCache.containsKey(artistId) &&
            topSongsCache.containsKey(artistId)) {

            Log.d("ArtistManager", "Recuperando artista $artistId desde CACHÉ (Válido, < 20 min)")
            return@withContext Triple(
                artistDetailsCache[artistId]!!,
                albumsCache[artistId]!!,
                topSongsCache[artistId]!!
            )
        }

        // B. SI NO ESTÁ O CADUCÓ, DESCARGAR (Internet)
        if (isExpired && artistDetailsCache.containsKey(artistId)) {
            Log.d("ArtistManager", "Cache expirado para $artistId. Descargando nuevo...")
        } else {
            Log.d("ArtistManager", "Descargando artista $artistId desde INTERNET")
        }

        val artistService = ApiClient.getArtistService(context)

        // Llamadas en paralelo usando los nuevos endpoints
        val artistDeferred = async { artistService.getArtistById(artistId) }
        // Nuevo endpoint: api/artists/{id}/albums (sub-recurso de Artist)
        val albumsDeferred = async { artistService.getArtistAlbums(artistId) }
        val topSongsDeferred = async { songManager.getMostStreamedSongsByArtist(artistId, 10) }

        val artist = artistDeferred.await()
        val albums = albumsDeferred.await()
        val topSongs = topSongsDeferred.await()

        // C. GUARDAR EN CACHÉ Y ACTUALIZAR TIMESTAMP
        artistDetailsCache[artistId] = artist
        albumsCache[artistId] = albums
        topSongsCache[artistId] = topSongs
        cacheTimestamps[artistId] = System.currentTimeMillis()

        return@withContext Triple(artist, albums, topSongs)
    }


    // --- TUS FUNCIONES EXISTENTES (Adaptadas al Singleton) ---

    // Recomendaciones (sin userId, extraído del JWT)
    suspend fun getRecommendedArtists(context: Context, count: Int): RecommendationResult<Artist>? {

        val artistService = ApiClient.getArtistService(context)

        try {
            // 1. Intentar recomendaciones (sin userId)
            val response = artistService.getRecommendedArtists(count)

            if (response.isEmpty()) throw Exception("No recs")

            val artists = response.map { it.item }
            val title = response.firstOrNull()?.reason?.message ?: "Artistas para ti"

            val result = RecommendationResult(artists, title)

            // Guardamos en caché también los artistas individuales
            artists.forEach { artistDetailsCache[it.id] = it }
            cachedRecommendation = result

            return result

        } catch (e: Exception) {
            Log.w("ArtistManager", "Fallo en recomendaciones: ${e.message}")
            return null
        }
    }

    suspend fun getArtistSmartPlaylists(context: Context, artistId: String): List<com.example.resonant.data.models.ArtistSmartPlaylist> {
        return try {
            ApiClient.getArtistService(context).getArtistSmartPlaylists(artistId)
        } catch (e: Exception) {
            Log.e("ArtistManager", "Error fetching playlists for $artistId", e)
            emptyList()
        }
    }

    suspend fun getEssentials(context: Context, artistId: String): List<Song> {
        return try {
            ApiClient.getArtistService(context).getEssentials(artistId)
        } catch (e: Exception) {
             Log.e("ArtistManager", "Error fetching Essentials for $artistId", e)
             emptyList()
        }
    }

    suspend fun getRadios(context: Context, artistId: String): List<Song> {
        return try {
            ApiClient.getArtistService(context).getRadios(artistId)
        } catch (e: Exception) {
             Log.e("ArtistManager", "Error fetching Radios for $artistId", e)
             emptyList()
        }
    }

    suspend fun getArtistImages(context: Context, artistId: String): List<String> {
        return try {
            val response = ApiClient.getArtistService(context).getArtistImages(artistId)
            response.galleryImageUrls ?: emptyList()
        } catch (e: Exception) {
             Log.e("ArtistManager", "Error fetching images for $artistId", e)
             emptyList()
        }
    }
}