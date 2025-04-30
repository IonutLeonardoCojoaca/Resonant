package com.example.spomusicapp

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class SongRepository {
    private val service = Retrofit.Builder()
        .baseUrl("https://listsongs-4ptxqjnupq-uc.a.run.app/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(FirebaseFunctionService::class.java)

    suspend fun fetchSongs(limit: Int, offset: Int): List<Song>? {
        return try {
            val response = service.getSongs(limit, offset)
            response.songs
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

}
