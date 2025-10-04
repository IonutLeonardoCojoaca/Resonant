package com.example.resonant

import android.content.Context
import android.content.Intent
import android.util.Log

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

    suspend fun getFavoritesSongs(cancionesAnteriores: List<Song>? = null): List<Song> {
        val userId = getUserId() ?: return emptyList()
        return try {
            val favoritos = api.getFavoriteSongsByUser(userId).toMutableList()
            val service = api

            for (song in favoritos) {
                val artistList = service.getArtistsBySongId(song.id)
                song.artistName = artistList.joinToString(", ") { it.name }

                val songCache = cancionesAnteriores?.find { it.id == song.id }
                if (songCache != null) {
                    song.url = songCache.url
                }
            }

            val cancionesSinUrl = favoritos.filter { it.url.isNullOrEmpty() }
            if (cancionesSinUrl.isNotEmpty()) {
                val fileNames = cancionesSinUrl.mapNotNull { it.fileName }
                val urlList = service.getMultipleSongUrls(fileNames)
                val urlMap = urlList.associateBy { it.fileName }
                cancionesSinUrl.forEach { song ->
                    song.url = urlMap[song.fileName]?.url
                }
            }

            val coversRequest = favoritos.mapNotNull { song ->
                song.imageFileName?.takeIf { it.isNotBlank() }?.let { fileName ->
                    song.albumId.takeIf { it.isNotBlank() }?.let { albumId ->
                        fileName to albumId
                    }
                }
            }

            if (coversRequest.isNotEmpty()) {
                val (fileNames, albumIds) = coversRequest.unzip()

                val coverResponses: List<CoverResponse> = service.getMultipleSongCoverUrls(fileNames, albumIds)

                val coverUrlMap: Map<Pair<String, String>, String> = coverResponses.associateBy(
                    keySelector = { it.imageFileName to it.albumId },
                    valueTransform = { it.url }
                )

                favoritos.forEach { song ->
                    val key = song.imageFileName to song.albumId
                    song.coverUrl = coverUrlMap[key]
                }
            }

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