package com.example.resonant.managers

import android.content.Context
import android.util.Log
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.AlbumStatsDTO
import com.example.resonant.data.models.Artist
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.RecommendationResult
import com.example.resonant.data.network.services.AlbumService
import java.util.concurrent.ConcurrentHashMap

// CAMBIO: Ahora es un 'object' (Singleton)
object AlbumManager {

    // CACHÉ: Mapas para guardar datos y no re-descargar
    private val albumsCache = ConcurrentHashMap<String, Album>()
    private val cacheTimestamps = ConcurrentHashMap<String, Long>() // Timestamp de la última actualización
    private const val CACHE_DURATION_MS = 20 * 60 * 1000L // 20 Minutos

    // Recomendaciones: caducan como el resto y solo valen para el mismo count (antes no caducaban nunca).
    @Volatile
    private var cachedRecommendation: RecommendationResult<Album>? = null
    @Volatile
    private var cachedRecommendationCount = 0
    @Volatile
    private var cachedRecommendationAt = 0L

    /** Vacía todas las cachés (cierre de sesión o cambio de cuenta). */
    fun clear() {
        albumsCache.clear()
        cacheTimestamps.clear()
        cachedRecommendation = null
        cachedRecommendationAt = 0L
    }

    // Obtener Álbum por ID con Cacheo Inteligente
    suspend fun getAlbumById(context: Context, albumId: String): Album? {
        val now = System.currentTimeMillis()
        val lastUpdate = cacheTimestamps[albumId] ?: 0L
        val isExpired = (now - lastUpdate) > CACHE_DURATION_MS

        // 1. Si está en caché y es válido, devolverlo
        if (!isExpired && albumsCache.containsKey(albumId)) {
            Log.d("AlbumManager", "Álbum $albumId recuperado de CACHÉ (Válido, < 20 min)")
            return albumsCache[albumId]
        }

        // 2. Descargar si no está o caducó
        if (isExpired && albumsCache.containsKey(albumId)) {
            Log.d("AlbumManager", "Cache expirado para álbum $albumId. Actualizando...")
        } else {
            Log.d("AlbumManager", "Descargando álbum $albumId desde INTERNET")
        }

        val albumService = ApiClient.getAlbumService(context)
        return try {
            // Nuevo endpoint: api/albums/{id}
            val album = albumService.getAlbumById(albumId)

            // 3. Guardar en Caché
            albumsCache[albumId] = album
            cacheTimestamps[albumId] = System.currentTimeMillis()
            album
        } catch (e: Exception) {
            Log.e("AlbumManager", "Error updateando álbum $albumId", e)
            // Si falla la red, intentamos devolver el caché viejo como fallback
            albumsCache[albumId]
        }
    }

    suspend fun getRecommendedAlbums(context: Context, count: Int): RecommendationResult<Album>? {
        // 1. REVISAR CACHÉ
        cachedRecommendation?.let { cached ->
            val fresh = System.currentTimeMillis() - cachedRecommendationAt <= CACHE_DURATION_MS
            if (fresh && cachedRecommendationCount == count) return cached
        }

        val albumService = ApiClient.getAlbumService(context)

        try {
            // 2. Intentar recomendaciones de red (sin userId, extraído del JWT)
            val response = albumService.getRecommendedAlbums(count)

            if (response.isEmpty()) throw Exception("No recs")

            val albums = response.map { it.item }
            val title = response.firstOrNull()?.reason?.message ?: "Álbumes destacados"

            // 3. GUARDAR EN CACHÉ INDIVIDUAL TAMBIÉN
            val now = System.currentTimeMillis()
            albums.forEach { 
                albumsCache[it.id] = it 
                cacheTimestamps[it.id] = now
            }

            val result = RecommendationResult(albums, title)
            cachedRecommendation = result
            cachedRecommendationCount = count
            cachedRecommendationAt = now

            return result

        } catch (e: Exception) {
            Log.w("AlbumManager", "Fallo en recomendaciones: ${e.message}")
            return null
        }
    }

    suspend fun getNewReleaseAlbums(context: Context, limit: Int = 20): List<Album> {
        return try {
            val result = ApiClient.getAlbumService(context).getNewReleaseAlbums(limit)
            Log.d("AlbumManager", "getNewReleaseAlbums: OK, ${result.size} items")
            result
        } catch (e: Exception) {
            Log.e("AlbumManager", "getNewReleaseAlbums: EXCEPTION ${e.javaClass.simpleName}: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getAlbumsByYear(context: Context, yearFrom: Int? = null, yearTo: Int? = null, limit: Int = 50): List<Album> {
        return try {
            ApiClient.getAlbumService(context).getAlbumsByYear(yearFrom, yearTo, limit)
        } catch (e: Exception) {
            Log.e("AlbumManager", "Error fetching albums by year ($yearFrom-$yearTo)", e)
            emptyList()
        }
    }

    suspend fun getMostListenedAlbums(context: Context, limit: Int = 20): List<Album> {
        return try {
            ApiClient.getAlbumService(context).getMostListenedAlbums(limit)
        } catch (e: Exception) {
            Log.e("AlbumManager", "Error fetching most listened albums", e)
            emptyList()
        }
    }

    suspend fun getAlbumStats(context: Context, albumId: String): AlbumStatsDTO? {
        return try {
            ApiClient.getAlbumService(context).getAlbumStats(albumId)
        } catch (e: Exception) {
            Log.e("AlbumManager", "Error fetching stats for album $albumId", e)
            null
        }
    }

    suspend fun getAlbumArtists(context: Context, albumId: String): List<Artist> {
        return try {
            ApiClient.getAlbumService(context).getAlbumArtists(albumId)
        } catch (e: Exception) {
            Log.e("AlbumManager", "Error fetching artists for album $albumId", e)
            emptyList()
        }
    }
}