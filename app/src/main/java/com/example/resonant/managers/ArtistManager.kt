package com.example.resonant.managers

import android.content.Context
import android.util.Log
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.ArtistStatsDTO
import com.example.resonant.data.models.Genre
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.RecommendationResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.ConcurrentHashMap

object ArtistManager {

    // CAMBIO 2: La MEMORIA (Caché). Aquí se guardan los datos.
    // Mapas concurrentes: se escriben desde IO (getFullArtistData) y desde Main (el resto).
    private val artistDetailsCache = ConcurrentHashMap<String, Artist>()
    private val albumsCache = ConcurrentHashMap<String, List<Album>>()
    private val topSongsCache = ConcurrentHashMap<String, List<Song>>()
    private val singlesCache = ConcurrentHashMap<String, List<Song>>()
    private val playlistsCache = ConcurrentHashMap<String, List<com.example.resonant.data.models.ArtistSmartPlaylist>>()
    private val imagesCache = ConcurrentHashMap<String, List<String>>()
    private val statsCache = ConcurrentHashMap<String, ArtistStatsDTO>()
    private val collaboratorsCache = ConcurrentHashMap<String, List<Artist>>()
    private val cacheTimestamps = ConcurrentHashMap<String, Long>() // Caducidad de getFullArtistData
    // Cada subcaché tiene su propia caducidad: antes todas leían cacheTimestamps, que solo escribe
    // getFullArtistData, así que fuera de la ficha del artista nunca acertaban y siempre iban a red.
    private val singlesTimestamps = ConcurrentHashMap<String, Long>()
    private val playlistsTimestamps = ConcurrentHashMap<String, Long>()
    private val imagesTimestamps = ConcurrentHashMap<String, Long>()
    private val statsTimestamps = ConcurrentHashMap<String, Long>()
    private val collaboratorsTimestamps = ConcurrentHashMap<String, Long>()

    private const val CACHE_DURATION_MS = 20 * 60 * 1000L // 20 Minutos

    @Volatile
    private var cachedRecommendation: RecommendationResult<Artist>? = null

    private fun isFresh(timestamp: Long?): Boolean =
        timestamp != null && System.currentTimeMillis() - timestamp <= CACHE_DURATION_MS

    /** Vacía todas las cachés (cierre de sesión o cambio de cuenta). */
    fun clear() {
        listOf(
            artistDetailsCache, albumsCache, topSongsCache, singlesCache, playlistsCache, imagesCache,
            statsCache, collaboratorsCache, cacheTimestamps, singlesTimestamps, playlistsTimestamps,
            imagesTimestamps, statsTimestamps, collaboratorsTimestamps
        ).forEach { it.clear() }
        cachedRecommendation = null
    }

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
        playlistsCache[artistId]?.let { if (isFresh(playlistsTimestamps[artistId])) return it }
        return try {
            val result = ApiClient.getArtistService(context).getArtistSmartPlaylists(artistId)
            playlistsCache[artistId] = result
            playlistsTimestamps[artistId] = System.currentTimeMillis()
            result
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
        imagesCache[artistId]?.let { if (isFresh(imagesTimestamps[artistId])) return it }
        return try {
            val response = ApiClient.getArtistService(context).getArtistImages(artistId)
            val result = response.galleryImageUrls ?: emptyList()
            imagesCache[artistId] = result
            imagesTimestamps[artistId] = System.currentTimeMillis()
            result
        } catch (e: Exception) {
             Log.e("ArtistManager", "Error fetching images for $artistId", e)
             emptyList()
        }
    }

    suspend fun getArtistSingles(context: Context, artistId: String): List<Song> {
        singlesCache[artistId]?.let { if (isFresh(singlesTimestamps[artistId])) return it }
        return try {
            val result = ApiClient.getArtistService(context).getArtistSingles(artistId)
            singlesCache[artistId] = result
            singlesTimestamps[artistId] = System.currentTimeMillis()
            result
        } catch (e: Exception) {
            Log.e("ArtistManager", "Error fetching singles for $artistId", e)
            emptyList()
        }
    }

    suspend fun getRecentlyAddedArtists(context: Context, limit: Int = 20): List<Artist> {
        return try {
            val result = ApiClient.getArtistService(context).getRecentlyAddedArtists(limit)
            Log.d("ArtistManager", "getRecentlyAddedArtists: OK, ${result.size} items")
            result
        } catch (e: Exception) {
            Log.e("ArtistManager", "getRecentlyAddedArtists: EXCEPTION ${e.javaClass.simpleName}: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getMostListenedArtists(context: Context, limit: Int = 20): List<Artist> {
        return try {
            ApiClient.getArtistService(context).getMostListenedArtists(limit)
        } catch (e: Exception) {
            Log.e("ArtistManager", "Error fetching most listened artists", e)
            emptyList()
        }
    }

    suspend fun getArtistStats(context: Context, artistId: String): ArtistStatsDTO? {
        statsCache[artistId]?.let { if (isFresh(statsTimestamps[artistId])) return it }
        return try {
            val result = ApiClient.getArtistService(context).getArtistStats(artistId)
            statsCache[artistId] = result
            statsTimestamps[artistId] = System.currentTimeMillis()
            result
        } catch (e: Exception) {
            Log.e("ArtistManager", "Error fetching stats for $artistId", e)
            null
        }
    }

    suspend fun getArtistGenres(context: Context, artistId: String): List<Genre> {
        return try {
            ApiClient.getArtistService(context).getArtistGenres(artistId)
        } catch (e: Exception) {
            Log.e("ArtistManager", "Error fetching genres for $artistId", e)
            emptyList()
        }
    }

    suspend fun getArtistCollaborators(context: Context, artistId: String, limit: Int = 20): List<Artist> {
        collaboratorsCache[artistId]?.let { if (isFresh(collaboratorsTimestamps[artistId])) return it }
        return try {
            val result = ApiClient.getArtistService(context).getArtistCollaborators(artistId, limit)
            collaboratorsCache[artistId] = result
            collaboratorsTimestamps[artistId] = System.currentTimeMillis()
            result
        } catch (e: Exception) {
            Log.e("ArtistManager", "Error fetching collaborators for $artistId", e)
            emptyList()
        }
    }
}