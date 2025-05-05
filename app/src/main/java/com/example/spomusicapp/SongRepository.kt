package com.example.spomusicapp

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class SongRepository {

    private val getAllSongsService = Retrofit.Builder()
        .baseUrl("https://listsongs-4ptxqjnupq-uc.a.run.app/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(FirebaseFunctionService::class.java)

    private val searchSongService = Retrofit.Builder()
        .baseUrl("https://searchsongs-4ptxqjnupq-uc.a.run.app/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(FirebaseFunctionService::class.java)

    suspend fun fetchSongs(): List<Song>? {
        return try {
            val response = getAllSongsService.getSongs()
            response.songs
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun searchSongs(query: String = "", limit: Int = 10, offset: Int = 0): List<Song>? {
        return try {
            val response = searchSongService.searchSongs(query, limit, offset)
            response.songs
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

}
