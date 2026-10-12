package com.example.resonant.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.resonant.data.network.UnisoundCodeDTO
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
}
