package com.example.resonant.managers

import android.content.Context
import android.util.Log
import com.example.resonant.data.models.Album
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.RecommendationResult
import com.example.resonant.data.network.services.AlbumService
import com.example.resonant.data.network.services.ArtistService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

// CAMBIO: Ahora es un 'object' (Singleton)
object AlbumManager {

    // CACHÉ: Mapas para guardar datos y no re-descargar
    private val albumsCache = mutableMapOf<String, Album>()
    private var cachedRecommendation: RecommendationResult<Album>? = null

    suspend fun getRecommendedAlbums(context: Context, userId: String, count: Int): RecommendationResult<Album>? {
        // 1. REVISAR CACHÉ: Si ya tenemos una recomendación guardada, la devolvemos
        // (Opcional: puedes comentar esto si quieres forzar recarga al hacer pull-to-refresh)
        if (cachedRecommendation != null) {
            return cachedRecommendation
        }

        val albumService = ApiClient.getAlbumService(context)

        try {
            // 2. Intentar recomendaciones de red
            val response = albumService.getRecommendedAlbums(userId, count)

            if (response.isEmpty()) throw Exception("No recs")

            val albums = response.map { it.item }
            val title = response.firstOrNull()?.reason?.message ?: "Álbumes destacados"

            // Rellenar nombres de artistas
            enrichAlbumsWithArtistName(context, albums)

            // 3. GUARDAR EN CACHÉ
            albums.forEach { albumsCache[it.id] = it }

            val result = RecommendationResult(albums, title)
            cachedRecommendation = result // Guardamos la recomendación global

            return result

        } catch (e: Exception) {
            Log.w("AlbumManager", "Fallo en recomendaciones. Usando aleatorios.")

            // 4. FALLBACK: Aleatorios
            val randomAlbums = getRandomAlbums(context, count)

            return if (randomAlbums.isNotEmpty()) {
                val result = RecommendationResult(randomAlbums, "Descubre álbumes")
                cachedRecommendation = result // También cacheamos el fallback
                result
            } else {
                null
            }
        }
    }

    // Función auxiliar para obtener aleatorios
    private suspend fun getRandomAlbums(context: Context, count: Int): List<Album> {
        val albumService = ApiClient.getAlbumService(context)

        return try {
            val allIds = albumService.getAllAlbumIds()
            if (allIds.size < count) return emptyList()

            val randomIds = allIds.shuffled().take(count)

            val albums = coroutineScope {
                randomIds.map { id ->
                    async {
                        // Intentamos sacar del caché individual primero
                        if (albumsCache.containsKey(id)) {
                            albumsCache[id]
                        } else {
                            try {
                                val album = albumService.getAlbumById(id)
                                album?.let { albumsCache[id] = it } // Guardamos
                                album
                            } catch (e: Exception) { null }
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            enrichAlbumsWithArtistName(context, albums)
            albums
        } catch (e: Exception) {
            Log.e("AlbumManager", "Error getting random albums", e)
            emptyList()
        }
    }

    // Función auxiliar para poner nombres de artistas
    private suspend fun enrichAlbumsWithArtistName(context: Context, albums: List<Album>) {
        val artistService = ApiClient.getArtistService(context)

        // Hacemos esto en paralelo para que sea más rápido
        coroutineScope {
            albums.map { album ->
                async {
                    if (album.artistName.isNullOrBlank()) {
                        try {
                            val artists = artistService.getArtistsByAlbumId(album.id)
                            album.artistName = artists.firstOrNull()?.name ?: "Desconocido"
                        } catch (e: Exception) { /* Ignorar */ }
                    }
                }
            }.awaitAll()
        }
    }
}