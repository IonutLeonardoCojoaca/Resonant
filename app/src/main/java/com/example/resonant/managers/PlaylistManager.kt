package com.example.resonant.managers

import android.content.Context
import com.example.resonant.data.models.Playlist
import com.example.resonant.data.models.Song
import com.example.resonant.data.models.User
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.services.PlaylistService
import com.example.resonant.data.network.services.UserService
import com.example.resonant.data.network.PlaylistTrackDTO
import com.example.resonant.data.network.ReorderPlaylistTracksRequestDTO
import com.example.resonant.data.network.V2EndpointAvailability
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PlaylistDetailResult(
    val playlist: Playlist,
    val tracks: List<PlaylistTrackDTO>,
    val ownerName: String,
    val supportsReorder: Boolean
)

class PlaylistFeatureUnavailableException(message: String) : Exception(message)
class PlaylistOrderConflictException(message: String) : Exception(message)

class PlaylistManager(private val context: Context) {

    companion object {
        private const val USER_CACHE_TTL_MS = 20 * 60 * 1000L
        private const val FEATURE_PLAYLIST_V2 = "playlist_detail"
        // Dueños de playlists públicas: Explorar pide uno por playlist (N+1) en cada apertura; 20 min por id.
        private val userCache = ConcurrentHashMap<String, Pair<User, Long>>()

        /** Vacía la caché estática (cierre de sesión o cambio de cuenta). */
        fun clear() = userCache.clear()
    }

    private val playlistService: PlaylistService = ApiClient.getPlaylistService(context)
    private val userService: UserService = ApiClient.getUserService(context)
    private val songManager = SongManager(context)

    suspend fun createPlaylist(playlist: Playlist): Playlist {
        return playlistService.createPlaylist(playlist)
    }

    suspend fun updatePlaylist(playlist: Playlist) {
        // Nuevo endpoint requiere ID en la ruta
        val response = playlistService.updatePlaylist(playlist.id!!, playlist)

        if (!response.isSuccessful) {
            val errorMsg = response.errorBody()?.string() ?: "Error desconocido"
            throw Exception("Error ${response.code()}: $errorMsg")
        }
    }

    suspend fun getPlaylistById(id: String): Playlist {
        return playlistService.getPlaylistById(id)
    }

    suspend fun getPlaylistDetail(id: String): PlaylistDetailResult {
        // Un backend sin v2 no recibe la misma petición fallida en cada apertura (cooldown de V2EndpointAvailability).
        val tryV2 = V2EndpointAvailability.shouldTry(context, FEATURE_PLAYLIST_V2)
        val v2Response = if (tryV2) runCatching { playlistService.getPlaylistDetailV2(id) }.getOrNull() else null
        if (v2Response != null && v2Response.code() in setOf(404, 405, 501)) {
            V2EndpointAvailability.markUnsupported(context, FEATURE_PLAYLIST_V2)
        }
        if (v2Response?.isSuccessful == true) {
            V2EndpointAvailability.markAvailable(context, FEATURE_PLAYLIST_V2)
            val body = v2Response.body()
            if (body != null) {
                val revision = v2Response.headers()["ETag"]
                    ?: body.revision
                    ?: ""
                val playlist = body.playlist.copy(
                    isSaved = body.isSaved,
                    canEdit = body.canEdit,
                    revision = revision
                )
                val ownerName = body.owner?.name
                    ?.takeIf(String::isNotBlank)
                    ?: playlist.userId?.let { userId ->
                        runCatching { getUserById(userId).name.orEmpty() }.getOrDefault("")
                    }.orEmpty()
                return PlaylistDetailResult(
                    playlist = playlist,
                    tracks = body.tracks.sortedBy(PlaylistTrackDTO::position),
                    ownerName = ownerName,
                    supportsReorder = revision.isNotBlank() &&
                        body.tracks.all { it.playlistTrackId.isNotBlank() }
                )
            }
        }

        // Legacy: lista y canciones a la vez en vez de una tras otra.
        val (playlist, songs) = coroutineScope {
            val playlistDeferred = async { getPlaylistById(id) }
            val songsDeferred = async { getSongsByPlaylistId(id) }
            playlistDeferred.await() to songsDeferred.await()
        }
        val owner = playlist.userId?.let {
            runCatching { getUserById(it).name.orEmpty() }.getOrDefault("")
        }.orEmpty()
        return PlaylistDetailResult(
            playlist = playlist,
            tracks = songs.mapIndexed { index, song ->
                PlaylistTrackDTO(
                    playlistTrackId = "legacy:$index:${song.id}",
                    position = index,
                    song = song
                )
            },
            ownerName = owner,
            supportsReorder = false
        )
    }

    /**
     * Obtiene las playlists del usuario autenticado.
     * El nuevo endpoint api/playlists/mine no necesita userId.
     */
    suspend fun getMyPlaylists(): List<Playlist> {
        return playlistService.getMyPlaylists()
    }

    suspend fun getAllPublicPlaylists(): List<Playlist> {
        return playlistService.getAllPublicPlaylists()
    }

    suspend fun getSavedPlaylists(): List<Playlist> {
        val collected = mutableListOf<Playlist>()
        var cursor: String? = null
        do {
            val response = playlistService.getSavedPlaylists(cursor = cursor)
            if (!response.isSuccessful) {
                if (response.code() in setOf(404, 405, 501)) {
                    throw PlaylistFeatureUnavailableException(
                        "El servidor todavía no permite guardar playlists"
                    )
                }
                throw Exception("Error ${response.code()} al cargar playlists guardadas")
            }
            val page = response.body() ?: break
            collected += page.items.map { it.copy(isSaved = true) }
            cursor = page.nextCursor
        } while (!cursor.isNullOrBlank())
        return collected.distinctBy(Playlist::id)
    }

    suspend fun setPlaylistSaved(playlistId: String, saved: Boolean) {
        val response = if (saved) {
            playlistService.savePlaylist(playlistId)
        } else {
            playlistService.removeSavedPlaylist(playlistId)
        }
        if (!response.isSuccessful) {
            if (response.code() in setOf(404, 405, 501)) {
                throw PlaylistFeatureUnavailableException(
                    "El backend todavía no tiene activada la biblioteca de playlists"
                )
            }
            throw Exception("Error ${response.code()} al actualizar la biblioteca")
        }
    }

    suspend fun reorderPlaylistTracks(
        playlistId: String,
        revision: String,
        orderedTrackIds: List<String>
    ): PlaylistDetailResult {
        if (revision.isBlank() || orderedTrackIds.any(String::isBlank)) {
            throw PlaylistFeatureUnavailableException(
                "Esta playlist no expone una revisión y filas reordenables"
            )
        }
        val response = playlistService.reorderPlaylistTracks(
            id = playlistId,
            revision = revision,
            idempotencyKey = UUID.randomUUID().toString(),
            request = ReorderPlaylistTracksRequestDTO(orderedTrackIds)
        )
        if (response.code() == 409 || response.code() == 412) {
            throw PlaylistOrderConflictException(
                "La playlist cambió en otro dispositivo. Se ha recargado su orden."
            )
        }
        if (!response.isSuccessful) {
            if (response.code() in setOf(404, 405, 501)) {
                throw PlaylistFeatureUnavailableException(
                    "El servidor todavía no permite guardar el orden"
                )
            }
            throw Exception("Error ${response.code()} al guardar el orden")
        }
        val body = response.body()
        val fresh = getPlaylistDetail(playlistId)
        val revisionFromResponse = response.headers()["ETag"]
            ?: body?.revision?.takeIf(String::isNotBlank)
            ?: fresh.playlist.revision
        val tracks = body?.tracks
            ?.takeIf(List<PlaylistTrackDTO>::isNotEmpty)
            ?.sortedBy(PlaylistTrackDTO::position)
            ?: fresh.tracks
        return fresh.copy(
            playlist = (body?.playlist ?: fresh.playlist).copy(
                revision = revisionFromResponse
            ),
            tracks = tracks,
            supportsReorder = !revisionFromResponse.isNullOrBlank() &&
                tracks.all { it.playlistTrackId.isNotBlank() }
        )
    }

    suspend fun deletePlaylist(id: String) {
        val response = playlistService.deletePlaylist(id)
        if (!response.isSuccessful) {
            throw Exception("Error deleting playlist: ${response.code()}")
        }
    }

    suspend fun addSongToPlaylist(songId: String, playlistId: String) {
        // Nuevo endpoint: POST api/playlists/{id}/songs con songId en el body
        val response = playlistService.addSongToPlaylist(playlistId, songId)
        if (!response.isSuccessful) {
            throw Exception("Error ${response.code()} al añadir la canción")
        }
    }

    suspend fun isSongInPlaylist(songId: String, playlistId: String): Boolean {
        // Nuevo endpoint: api/playlists/{id}/songs/{songId}/exists
        return playlistService.isSongInPlaylist(playlistId, songId)
    }

    suspend fun deleteSongFromPlaylist(songId: String, playlistId: String) {
        // Nuevo endpoint: DELETE api/playlists/{id}/songs/{songId}
        playlistService.deleteSongFromPlaylist(playlistId, songId)
    }

    suspend fun updatePlaylistVisibility(playlistId: String, isPublic: Boolean) {
        val response = playlistService.updatePlaylistVisibility(playlistId, isPublic)
        if (!response.isSuccessful) {
            val errorMsg = response.errorBody()?.string() ?: "Error desconocido"
            throw Exception("Error ${response.code()}: $errorMsg")
        }
    }

    suspend fun updatePlaylistName(playlistId: String, name: String) {
        val response = playlistService.updatePlaylistName(playlistId, name)
        if (!response.isSuccessful) {
            val errorMsg = response.errorBody()?.string() ?: "Error desconocido"
            throw Exception("Error ${response.code()}: $errorMsg")
        }
    }

    // --- MÉTODOS DE USUARIO ---
    suspend fun getUserById(userId: String): User {
        userCache[userId]?.let { (user, cachedAt) ->
            if (System.currentTimeMillis() - cachedAt <= USER_CACHE_TTL_MS) return user
        }
        return userService.getUserById(userId).also { userCache[userId] = it to System.currentTimeMillis() }
    }

    // --- OTROS ---
    suspend fun getSongsByPlaylistId(playlistId: String): List<Song> {
        return songManager.getSongsFromPlaylist(playlistId)
    }
}
