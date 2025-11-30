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

// CAMBIO 1: Usamos 'object' en lugar de 'class'.
// Esto hace que 'ArtistManager' sea ÚNICO en toda la app y siempre esté vivo.
object ArtistManager {

    // CAMBIO 2: La MEMORIA (Caché). Aquí se guardan los datos.
    private val artistDetailsCache = mutableMapOf<String, Artist>()
    private val albumsCache = mutableMapOf<String, List<Album>>()
    private val topSongsCache = mutableMapOf<String, List<Song>>()

    // Variable para guardar la última recomendación y no recargarla si vuelves atrás
    private var cachedRecommendation: RecommendationResult<Artist>? = null

    // --- NUEVA FUNCIÓN: Para tu ArtistFragment (Con Caché) ---
    // Esta es la función que debes llamar desde tu ArtistViewModel
    suspend fun getFullArtistData(
        context: Context,
        artistId: String,
        songManager: SongManager // Necesitamos songManager para las canciones top
    ): Triple<Artist, List<Album>, List<Song>> = withContext(Dispatchers.IO) {

        // A. REVISAR CACHÉ: ¿Ya tenemos los datos de este artista?
        if (artistDetailsCache.containsKey(artistId) &&
            albumsCache.containsKey(artistId) &&
            topSongsCache.containsKey(artistId)) {

            Log.d("ArtistManager", "Recuperando artista $artistId desde CACHÉ (Sin Internet)")
            return@withContext Triple(
                artistDetailsCache[artistId]!!,
                albumsCache[artistId]!!,
                topSongsCache[artistId]!!
            )
        }

        // B. SI NO ESTÁ, DESCARGAR (Internet)
        Log.d("ArtistManager", "Descargando artista $artistId desde INTERNET")

        val artistService = ApiClient.getArtistService(context)
        val albumService = ApiClient.getAlbumService(context)

        // Llamadas en paralelo
        val artistDeferred = async { artistService.getArtistById(artistId) }
        val albumsDeferred = async { albumService.getByArtistId(artistId) }
        val topSongsDeferred = async { songManager.getMostStreamedSongsByArtist(artistId, 5) }

        val artist = artistDeferred.await()
        val albums = albumsDeferred.await()
        val topSongs = topSongsDeferred.await()

        // C. GUARDAR EN CACHÉ
        artistDetailsCache[artistId] = artist
        albumsCache[artistId] = albums
        topSongsCache[artistId] = topSongs

        return@withContext Triple(artist, albums, topSongs)
    }


    // --- TUS FUNCIONES EXISTENTES (Adaptadas al Singleton) ---

    // Función para recomendaciones (Ahora con caché opcional)
    suspend fun getRecommendedArtists(context: Context, userId: String, count: Int): RecommendationResult<Artist>? {
        // Opción: Si ya tenemos una recomendación cargada, la devolvemos?
        // Descomenta esto si quieres que las recomendaciones no cambien al rotar pantalla o volver:
        /* if (cachedRecommendation != null) {
            return cachedRecommendation
        }
        */

        val artistService = ApiClient.getArtistService(context)

        try {
            // 1. Intentar recomendaciones
            val response = artistService.getRecommendedArtists(userId, count)

            if (response.isEmpty()) throw Exception("No recs")

            val artists = response.map { it.item }
            val title = response.firstOrNull()?.reason?.message ?: "Artistas para ti"

            val result = RecommendationResult(artists, title)

            // Guardamos en caché también los artistas individuales por si luego entras en su perfil
            artists.forEach { artistDetailsCache[it.id] = it }
            cachedRecommendation = result // Guardamos la recomendación global

            return result

        } catch (e: Exception) {
            Log.w("ArtistManager", "Fallo en recomendaciones. Usando aleatorios.")

            // Pasamos 'context' porque getRandomArtists ahora lo necesita
            val randomArtists = getRandomArtists(context, count)

            return if (randomArtists.isNotEmpty()) {
                val result = RecommendationResult(randomArtists, "Descubre artistas")
                cachedRecommendation = result
                result
            } else {
                null
            }
        }
    }

    private suspend fun getRandomArtists(context: Context, count: Int): List<Artist> {
        val artistService = ApiClient.getArtistService(context)
        return try {
            val allIds = artistService.getAllArtistIds()
            if (allIds.size < count) return emptyList()

            val randomIds = allIds.shuffled().take(count)

            coroutineScope {
                randomIds.map { id ->
                    async {
                        // Intentamos coger de caché primero, si no, descargamos
                        if (artistDetailsCache.containsKey(id)) {
                            artistDetailsCache[id]
                        } else {
                            try {
                                val artist = artistService.getArtistById(id)
                                artistDetailsCache[id] = artist // Guardamos para el futuro
                                artist
                            } catch (e: Exception) { null }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        } catch (e: Exception) {
            Log.e("ArtistManager", "Error getting random artists", e)
            emptyList()
        }
    }
}