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

    // Ya no necesitamos userId, el backend lo extrae del JWT token
    suspend fun addFavoriteSong(songId: String): Boolean {
        return try {
            songService.addFavoriteSong(songId)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteFavoriteSong(songId: String): Boolean {
        return try {
            songService.deleteFavoriteSong(songId)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getFavoritesSongs(): List<Song> = fetchFavoriteSongs().orEmpty()

    /** `null` si la petición falló: el que llama conserva lo que tenía en vez de mostrar "sin favoritos". */
    suspend fun fetchFavoriteSongs(): List<Song>? {
        return try {
            songManager.fetchFavoriteSongs()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("FavoriteManager", "Error al obtener las canciones favoritas", e)
            null
        }
    }

    suspend fun addFavoriteArtist(artistId: String): Boolean {
        return try {
            artistService.addFavoriteArtist(artistId)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteFavoriteArtist(artistId: String): Boolean {
        return try {
            artistService.deleteFavoriteArtist(artistId)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getFavoriteArtists(): List<Artist> = fetchFavoriteArtists().orEmpty()

    /** `null` si la petición falló. */
    suspend fun fetchFavoriteArtists(): List<Artist>? {
        return try {
            artistService.getFavoriteArtistsByUser()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun addFavoriteAlbum(albumId: String): Boolean {
        return try {
            albumService.addFavoriteAlbum(albumId)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteFavoriteAlbum(albumId: String): Boolean {
        return try {
            albumService.deleteFavoriteAlbum(albumId)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getFavoriteAlbums(): List<Album> = fetchFavoriteAlbums().orEmpty()

    /** `null` si la petición falló. */
    suspend fun fetchFavoriteAlbums(): List<Album>? {
        return try {
            val favoritos = albumService.getFavoriteAlbumsByUser().toMutableList()
            favoritos.forEach { album ->
                if (album.artistName.isNullOrBlank()) {
                    album.artistName = album.artists.joinToString(", ") { it.name }
                        .takeIf { it.isNotBlank() }
                }
            }
            favoritos
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}