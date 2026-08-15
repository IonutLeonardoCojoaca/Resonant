package com.example.resonant.managers

import android.content.Context
import com.example.resonant.data.models.Genre
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.services.GenreService

class GenreManager(private val context: Context) {

    private val genreService: GenreService = ApiClient.getGenreService(context)

    companion object {
        // Estático para sobrevivir a la recreación del ViewModel/GenreManager
        // al volver a navegar al mismo artista.
        private val genresByArtistCache = mutableMapOf<String, List<Genre>>()
        private val genresByArtistCacheTimestamps = mutableMapOf<String, Long>()
        private const val CACHE_DURATION_MS = 20 * 60 * 1000L
    }

    suspend fun getAllGenres(): List<Genre> {
        return genreService.getAllGenres()
    }

    suspend fun getGenresByArtistId(artistId: String): List<Genre> {
        val now = System.currentTimeMillis()
        val lastUpdate = genresByArtistCacheTimestamps[artistId] ?: 0L
        if ((now - lastUpdate) <= CACHE_DURATION_MS && genresByArtistCache.containsKey(artistId)) {
            return genresByArtistCache[artistId]!!
        }
        val result = genreService.getGenresByArtistId(artistId)
        genresByArtistCache[artistId] = result
        genresByArtistCacheTimestamps[artistId] = now
        return result
    }

    suspend fun getPopularGenres(count: Int = 10): List<Genre> {
        return genreService.getPopularGenres(count)
    }

    suspend fun getRelatedGenres(genreId: String): List<Genre> {
        return genreService.getRelatedGenres(genreId)
    }

    suspend fun getFavoriteGenres(userId: String): List<Genre> {
        return genreService.getFavoriteGenres(userId)
    }
}