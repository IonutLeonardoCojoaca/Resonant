package com.example.resonant.unisound

import com.example.resonant.data.network.UnisoundCodeDTO
import com.example.resonant.data.network.UnisoundJoinRequestDTO
import com.example.resonant.data.network.UnisoundPlaybackStateDTO
import com.example.resonant.data.network.UnisoundQueueItemDTO
import com.example.resonant.data.network.UnisoundSessionSnapshotDTO
import com.example.resonant.data.network.UnisoundSessionSummaryDTO
import com.example.resonant.data.network.UnisoundSettingsUpdateDTO
import com.example.resonant.data.network.UnisoundSourceDTO
import com.example.resonant.data.network.UnisoundTransferHostRequestDTO
import com.example.resonant.data.network.services.UnisoundService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.io.IOException

fun <T> errorResponse(status: Int, code: String?): Response<T> {
    val body = code?.let { """{ "code": "$it", "message": "x", "correlationId": "c", "retryable": false }""" } ?: ""
    return Response.error(status, body.toResponseBody("application/json".toMediaType()))
}

/** Hand-written REST fake: each call returns the configured response or throws [IOException]. */
class FakeUnisoundService : UnisoundService {
    var createResponse: Response<UnisoundSessionSnapshotDTO>? = null
    var currentResponse: Response<UnisoundSessionSnapshotDTO>? = null
    var joinResponse: Response<UnisoundSessionSnapshotDTO>? = null
    var leaveResponses = ArrayDeque<Response<Unit>?>()
    var endResponse: Response<UnisoundSessionSummaryDTO>? = null
    var settingsResponse: Response<UnisoundSessionSnapshotDTO>? = null
    var codeResponse: Response<UnisoundCodeDTO>? = null

    var currentCalls = 0
    var leaveCalls = 0
    val joinedCodes = mutableListOf<String>()

    private fun <T> respond(response: Response<T>?): Response<T> = response ?: throw IOException("offline")

    override suspend fun createSession() = respond(createResponse)

    override suspend fun getCurrentSession(): Response<UnisoundSessionSnapshotDTO> {
        currentCalls++
        return respond(currentResponse)
    }

    override suspend fun join(request: UnisoundJoinRequestDTO): Response<UnisoundSessionSnapshotDTO> {
        joinedCodes += request.code
        return respond(joinResponse)
    }

    override suspend fun getSession(sessionId: String) = respond(currentResponse)

    override suspend fun leave(sessionId: String): Response<Unit> {
        leaveCalls++
        return respond(leaveResponses.removeFirstOrNull())
    }

    override suspend fun end(sessionId: String) = respond(endResponse)

    override suspend fun updateSettings(sessionId: String, request: UnisoundSettingsUpdateDTO) =
        respond(settingsResponse)

    override suspend fun regenerateCode(sessionId: String) = respond(codeResponse)

    override suspend fun kick(sessionId: String, userId: String): Response<Unit> = Response.success(Unit)

    override suspend fun transferHost(sessionId: String, request: UnisoundTransferHostRequestDTO) =
        respond(settingsResponse)
}

/** Hand-written hub fake. Command handlers can be replaced per test. */
class FakeUnisoundHubGateway : UnisoundHubGateway {
    override var listener: UnisoundHubListener? = null
    override var isConnected = false

    var connectError: Throwable? = null
    var joinSnapshot: UnisoundSessionSnapshotDTO = UnisoundFixtures.snapshot()
    var joinError: Throwable? = null
    var serverTimeMs = 0L

    var connectCalls = 0
    var syncCalls = 0
    val joinRevisions = mutableListOf<Long>()
    val commandKeys = mutableListOf<String>()
    val commands = mutableListOf<String>()

    var onPlay: (Long) -> UnisoundPlaybackStateDTO = { UnisoundFixtures.playback(it + 1) }
    var onPause: (Long, Long) -> UnisoundPlaybackStateDTO =
        { position, revision -> UnisoundFixtures.playback(revision + 1, status = UnisoundRoomState.STATUS_PAUSED, positionMs = position) }

    override suspend fun connect() {
        connectCalls++
        connectError?.let { throw it }
        isConnected = true
    }

    override suspend fun disconnect() {
        isConnected = false
    }

    override suspend fun syncClock(): Long {
        syncCalls++
        return serverTimeMs
    }

    override suspend fun joinRoom(sessionId: String, lastRevision: Long): UnisoundSessionSnapshotDTO {
        joinRevisions += lastRevision
        joinError?.let { throw it }
        return joinSnapshot
    }

    override suspend fun leaveRoom(sessionId: String) {
        commands += "LeaveRoom"
    }

    override suspend fun play(sessionId: String, expectedRevision: Long, idempotencyKey: String): UnisoundPlaybackStateDTO {
        record("Play", idempotencyKey)
        return onPlay(expectedRevision)
    }

    override suspend fun pause(sessionId: String, positionMs: Long, expectedRevision: Long, idempotencyKey: String): UnisoundPlaybackStateDTO {
        record("Pause", idempotencyKey)
        return onPause(positionMs, expectedRevision)
    }

    override suspend fun seek(sessionId: String, positionMs: Long, expectedRevision: Long, idempotencyKey: String) =
        UnisoundFixtures.playback(expectedRevision + 1, positionMs = positionMs).also { record("Seek", idempotencyKey) }

    override suspend fun skip(sessionId: String, direction: String, expectedRevision: Long, idempotencyKey: String) =
        UnisoundFixtures.playback(expectedRevision + 1, currentItemId = "y").also { record("Skip:$direction", idempotencyKey) }

    override suspend fun addItems(sessionId: String, source: UnisoundSourceDTO, idempotencyKey: String): List<UnisoundQueueItemDTO> {
        record("AddItems:${source.type}", idempotencyKey)
        return listOf(UnisoundFixtures.item("new"))
    }

    override suspend fun removeItem(sessionId: String, itemId: String, idempotencyKey: String) {
        record("RemoveItem", idempotencyKey)
    }

    private fun record(command: String, key: String) {
        commands += command
        commandKeys += key
    }
}
