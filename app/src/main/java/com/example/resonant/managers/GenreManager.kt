package com.example.resonant.managers

import android.content.Context
import com.example.resonant.data.models.Genre
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.services.GenreService

class GenreManager(private val context: Context) {

    private val genreService: GenreService = ApiClient.getGenreService(context)

    suspend fun getAllGenres(): List<Genre> {
        return genreService.getAllGenres()
    }

    suspend fun getGenresByArtistId(artistId: String): List<Genre> {
        return genreService.getGenresByArtistId(artistId)
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