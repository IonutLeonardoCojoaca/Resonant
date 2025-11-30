package com.example.resonant.managers

import android.content.Context
import android.util.Log
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.services.AlbumService
import com.example.resonant.data.network.services.ArtistService
import com.example.resonant.data.network.services.SongService

class FavoriteManager(private val context: Context) {

    private val songService: SongService = ApiClient.getSongService(context)
    private val artistService: ArtistService = ApiClient.getArtistService(context)
    private val albumService: AlbumService = ApiClient.getAlbumService(context)

    private val songManager = SongManager(context)

    private val prefs = context.getSharedPreferences("user_data", Context.MODE_PRIVATE)
    private fun getUserId(): String? = prefs.getString("USER_ID", null)

    suspend fun addFavoriteSong(songId: String): Boolean {
        val userId = getUserId() ?: return false
        return try {
            songService.addFavoriteSong(userId, songId)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteFavoriteSong(songId: String): Boolean {
        val userId = getUserId() ?: return false
        return try {
            songService.deleteFavoriteSong(userId, songId)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getFavoritesSongs(): List<Song> {
        val userId = getUserId() ?: return emptyList()
        return try {
            songManager.getFavoriteSongs(userId)
        } catch (e: Exception) {
            Log.e("FavoriteManager", "Error al obtener las canciones favoritas", e)
            emptyList()
        }
    }

    suspend fun addFavoriteArtist(artistId: String): Boolean {
        val userId = getUserId() ?: return false
        return try {
            artistService.addFavoriteArtist(userId, artistId)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteFavoriteArtist(artistId: String): Boolean {
        val userId = getUserId() ?: return false
        return try {
            artistService.deleteFavoriteArtist(userId, artistId)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getFavoriteArtists(): List<Artist> {
        val userId = getUserId() ?: return emptyList()
        return try {
            artistService.getFavoriteArtistsByUser(userId)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun addFavoriteAlbum(albumId: String): Boolean {
        val userId = getUserId() ?: return false
        return try {
            albumService.addFavoriteAlbum(userId, albumId)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteFavoriteAlbum(albumId: String): Boolean {
        val userId = getUserId() ?: return false
        return try {
            albumService.deleteFavoriteAlbum(userId, albumId)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getFavoriteAlbums(): List<Album> {
        val userId = getUserId() ?: return emptyList()
        return try {
            val favoritos = albumService.getFavoriteAlbumsByUser(userId).toMutableList()
            favoritos.forEach { album ->
                try {
                    val artists = artistService.getArtistsByAlbumId(album.id)
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