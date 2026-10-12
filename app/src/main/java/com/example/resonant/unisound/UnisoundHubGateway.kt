package com.example.resonant.unisound

import com.example.resonant.data.network.UnisoundPlaybackStateDTO
import com.example.resonant.data.network.UnisoundQueueItemDTO
import com.example.resonant.data.network.UnisoundSessionSnapshotDTO
import com.example.resonant.data.network.UnisoundSourceDTO

interface UnisoundHubListener {
    fun onEvent(event: UnisoundEvent)

    /** The connection closed; [error] is null for a clean stop. */
    fun onClosed(error: Throwable?)
}

/**
 * `/hubs/unisound` as seen by [UnisoundManager]. Failures surface as exceptions whose message
 * carries the server code (see [UnisoundErrors.codeOf]). Kept as an interface so the manager
 * can be tested without SignalR.
 */
interface UnisoundHubGateway {
    var listener: UnisoundHubListener?
    val isConnected: Boolean

    suspend fun connect()
    suspend fun disconnect()

    suspend fun syncClock(): Long
    suspend fun joinRoom(sessionId: String, lastRevision: Long): UnisoundSessionSnapshotDTO
    suspend fun leaveRoom(sessionId: String)

    suspend fun play(sessionId: String, expectedRevision: Long, idempotencyKey: String): UnisoundPlaybackStateDTO
    suspend fun pause(sessionId: String, positionMs: Long, expectedRevision: Long, idempotencyKey: String): UnisoundPlaybackStateDTO
    suspend fun seek(sessionId: String, positionMs: Long, expectedRevision: Long, idempotencyKey: String): UnisoundPlaybackStateDTO
    suspend fun skip(sessionId: String, direction: String, expectedRevision: Long, idempotencyKey: String): UnisoundPlaybackStateDTO

    suspend fun addItems(sessionId: String, source: UnisoundSourceDTO, idempotencyKey: String): List<UnisoundQueueItemDTO>
    suspend fun removeItem(sessionId: String, itemId: String, idempotencyKey: String)

    companion object {
        const val SKIP_NEXT = "next"
        const val SKIP_PREVIOUS = "previous"
    }
}
