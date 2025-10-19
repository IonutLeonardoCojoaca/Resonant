package com.example.resonant

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.resonant.managers.SongManager

class FavoriteManager(private val context: Context) {
    private val api = ApiClient.getService(context)
    private val prefs = context.getSharedPreferences("user_data", Context.MODE_PRIVATE)

    private fun getUserId(): String? = prefs.getString("USER_ID", null)

    suspend fun addFavoriteSong(songId: String): Boolean {
        val userId = getUserId() ?: return false
        return try {
            api.addFavoriteSong(userId, songId)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteFavoriteSong(songId: String): Boolean {
        val userId = getUserId() ?: return false
        return try {
            api.deleteFavoriteSong(userId, songId)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getFavoritesSongs(context: Context): List<Song> {
        val userId = getUserId() ?: return emptyList()

        return try {
            SongManager.getFavoriteSongs(context, userId)

        } catch (e: Exception) {
            Log.e("FavoriteManager", "Error al obtener las canciones favoritas", e)
            emptyList()
        }
    }

    suspend fun addFavoriteArtist(artistId: String): Boolean {
        val userId = getUserId() ?: return false
        return try {
            api.addFavoriteArtist(userId, artistId)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteFavoriteArtist(artistId: String): Boolean {
        val userId = getUserId() ?: return false
        return try {
            api.deleteFavoriteArtist(userId, artistId)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getFavoriteArtists(): List<Artist> {
        val userId = getUserId() ?: return emptyList()
        return try {
            // Obtiene los artistas favoritos
            val favoritos = api.getFavoriteArtistsByUser(userId).toMutableList()

            // Obtiene los nombres de archivo de las imágenes (fileName)
            val fileNames = favoritos.mapNotNull { it.fileName }
            if (fileNames.isNotEmpty()) {
                val urlList = api.getMultipleArtistUrls(fileNames)
                val urlMap = urlList.associateBy { it.fileName }
                favoritos.forEach { artist ->
                    artist.url = urlMap[artist.fileName]?.url // Guarda la URL en el campo url del artista
                }
            }

            favoritos
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun addFavoriteAlbum(albumId: String): Boolean {
        val userId = getUserId() ?: return false
        return try {
            api.addFavoriteAlbum(userId, albumId)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteFavoriteAlbum(albumId: String): Boolean {
        val userId = getUserId() ?: return false
        return try {
            api.deleteFavoriteAlbum(userId, albumId)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getFavoriteAlbums(): List<Album> {
        val userId = getUserId() ?: return emptyList()
        return try {
            val favoritos = api.getFavoriteAlbumsByUser(userId).toMutableList()

            val fileNames = favoritos.mapNotNull { it.fileName }
            if (fileNames.isNotEmpty()) {
                val urlList = api.getMultipleAlbumUrls(fileNames)
                val urlMap = urlList.associateBy { it.fileName }
                favoritos.forEach { album ->
                    album.url = urlMap[album.fileName]?.url
                }
            }

            favoritos.forEach { album ->
                try {
                    val artists = api.getArtistsByAlbumId(album.id) // Devuelve List<Artist>
                    album.artistName = artists.joinToString(", ") { it.name }
                } catch (ex: Exception) {
                    album.artistName = "Unknown"
                    Log.i("Error Album", ex.toString())
                }
            }

            favoritos
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}