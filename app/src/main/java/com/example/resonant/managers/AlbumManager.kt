package com.example.resonant.managers

import android.content.Context
import android.util.Log
import com.example.resonant.data.models.Album
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.RecommendationResult
import com.example.resonant.data.network.services.AlbumService

// CAMBIO: Ahora es un 'object' (Singleton)
object AlbumManager {

    // CACHÉ: Mapas para guardar datos y no re-descargar
    private val albumsCache = mutableMapOf<String, Album>()
    private val cacheTimestamps = mutableMapOf<String, Long>() // Timestamp de la última actualización
    private const val CACHE_DURATION_MS = 20 * 60 * 1000L // 20 Minutos

    private var cachedRecommendation: RecommendationResult<Album>? = null

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
        if (cachedRecommendation != null) {
            return cachedRecommendation
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

            return result

        } catch (e: Exception) {
            Log.w("AlbumManager", "Fallo en recomendaciones: ${e.message}")
            return null
        }
    }
}