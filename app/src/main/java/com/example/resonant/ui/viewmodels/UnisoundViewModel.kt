package com.example.resonant.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.resonant.data.network.ApiClient
import com.example.resonant.ui.adapters.UnisoundSearchRow
import com.example.resonant.data.network.UnisoundCodeDTO
import com.example.resonant.data.network.UnisoundReactionDTO
import com.example.resonant.unisound.UnisoundSavedPlaylist
import com.example.resonant.data.network.UnisoundQueueItemDTO
import com.example.resonant.data.network.UnisoundSessionSummaryDTO
import com.example.resonant.data.network.UnisoundSettingsUpdateDTO
import com.example.resonant.unisound.ServerClock
import com.example.resonant.unisound.UnisoundControl
import com.example.resonant.unisound.UnisoundControlOrigin
import com.example.resonant.unisound.UnisoundManager
import com.example.resonant.unisound.UnisoundManagerEvent
import com.example.resonant.unisound.UnisoundResult
import com.example.resonant.unisound.UnisoundRoomState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Activity-scoped access to the Unisound room for the room, share, settings and join screens. */
class UnisoundViewModel(application: Application) : AndroidViewModel(application) {
    private val manager = UnisoundManager.get(application)
    private val universalSearch = ApiClient.getUniversalSearchService(application)

    val state: StateFlow<UnisoundRoomState?> = manager.state
    val events: SharedFlow<UnisoundManagerEvent> = manager.events
    val clock: ServerClock get() = manager.clock

    suspend fun createRoom(): UnisoundResult<UnisoundRoomState> = manager.createRoom()

    suspend fun joinRoom(code: String): UnisoundResult<UnisoundRoomState> = manager.joinRoom(code)

    suspend fun leaveRoom() = manager.leaveRoom()

    suspend fun endRoom(): UnisoundResult<UnisoundSessionSummaryDTO> = manager.endRoom()

    suspend fun updateSettings(update: UnisoundSettingsUpdateDTO): UnisoundResult<Unit> =
        manager.updateSettings(update)

    suspend fun regenerateCode(): UnisoundResult<UnisoundCodeDTO> = manager.regenerateCode()

    suspend fun kick(userId: String): UnisoundResult<Unit> = manager.kick(userId)

    suspend fun transferHost(userId: String): UnisoundResult<Unit> = manager.transferHost(userId)

    suspend fun addSong(songId: String): UnisoundResult<List<UnisoundQueueItemDTO>> = manager.addSong(songId)

    suspend fun removeItem(itemId: String): UnisoundResult<Unit> = manager.removeItem(itemId)

    fun control(control: UnisoundControl, positionMs: Long? = null) =
        manager.control(control, positionMs, UnisoundControlOrigin.APP)

    val reactions: SharedFlow<UnisoundReactionDTO> = manager.reactions
    val hiddenEmojis: StateFlow<Set<String>> = manager.hiddenEmojis

    suspend fun moveItem(itemId: String, toIndex: Int) = manager.moveItem(itemId, toIndex)

    suspend fun react(emoji: String) = manager.react(emoji)

    suspend fun savePlaylist(sessionId: String, name: String?): UnisoundResult<UnisoundSavedPlaylist> =
        manager.savePlaylist(sessionId, name)

    suspend fun getSummary(sessionId: String): UnisoundSessionSummaryDTO? = manager.getSummary(sessionId)

    suspend fun addSource(type: String, id: String): UnisoundResult<List<UnisoundQueueItemDTO>> =
        manager.addSource(type, id)

    /** Songs, albums and playlists for the room's "Añadir" tab (same endpoint as the app search). */
    suspend fun search(query: String): List<UnisoundSearchRow> = runCatching {
        universalSearch.search(query).results.mapNotNull { hit ->
            when (hit.type) {
                SOURCE_SONG -> hit.song?.let { song ->
                    UnisoundSearchRow(
                        SOURCE_SONG, song.id, song.title,
                        song.artistName ?: song.artists.joinToString(", ") { it.name },
                        song.coverUrl ?: song.imageFileName
                    )
                }
                SOURCE_ALBUM -> UnisoundSearchRow(
                    SOURCE_ALBUM, hit.id, hit.title,
                    "Álbum · ${hit.subtitle ?: hit.album?.artists?.joinToString(", ") { it.name }.orEmpty()}",
                    hit.imageUrl
                )
                SOURCE_PLAYLIST -> UnisoundSearchRow(
                    SOURCE_PLAYLIST, hit.id, hit.title,
                    hit.trackCount?.let { "Playlist · $it canciones" } ?: "Playlist",
                    hit.imageUrl
                )
                else -> null
            }
        }.take(SEARCH_LIMIT)
    }.getOrDefault(emptyList())

    private companion object {
        const val SEARCH_LIMIT = 30
        const val SOURCE_SONG = "song"
        const val SOURCE_ALBUM = "album"
        const val SOURCE_PLAYLIST = "playlist"
    }
}
