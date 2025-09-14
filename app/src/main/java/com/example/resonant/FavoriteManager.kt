package com.example.resonant

import android.content.Context
import android.content.Intent

class FavoriteManager(private val context: Context) {
    private val api = ApiClient.getService(context)
    private val prefs = context.getSharedPreferences("user_data", Context.MODE_PRIVATE)

    private fun getUserId(): String? = prefs.getString("USER_ID", null)

    suspend fun addFavorite(songId: String): Boolean {
        val userId = getUserId() ?: return false
        return try {
            api.addFavoriteSong(userId, songId)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteFavorite(songId: String): Boolean {
        val userId = getUserId() ?: return false
        return try {
            api.deleteFavoriteSong(userId, songId)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getFavorites(cancionesAnteriores: List<Song>? = null): List<Song> {
        val userId = getUserId() ?: return emptyList()
        return try {
            val favoritos = api.getFavoriteSongsByUser(userId).toMutableList()
            val service = api

            for (song in favoritos) {
                // Enriquecer artista
                val artistList = service.getArtistsBySongId(song.id)
                song.artistName = artistList.joinToString(", ") { it.name }

                // Reutilizar url si está en cache
                val songCache = cancionesAnteriores?.find { it.id == song.id }
                if (songCache != null) {
                    song.url = songCache.url
                }
            }

            // Enriquecer url prefirmada si falta
            val cancionesSinUrl = favoritos.filter { it.url.isNullOrEmpty() }
            if (cancionesSinUrl.isNotEmpty()) {
                val fileNames = cancionesSinUrl.mapNotNull { it.fileName }
                val urlList = service.getMultipleSongUrls(fileNames)
                val urlMap = urlList.associateBy { it.fileName }
                cancionesSinUrl.forEach { song ->
                    song.url = urlMap[song.fileName]?.url
                }
            }

            // Opcional: actualiza servicio de playback
            val updateIntent = Intent(context, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.UPDATE_SONGS
                putParcelableArrayListExtra(
                    MusicPlaybackService.SONG_LIST,
                    ArrayList(favoritos)
                )
            }
            context.startService(updateIntent)

            favoritos
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}