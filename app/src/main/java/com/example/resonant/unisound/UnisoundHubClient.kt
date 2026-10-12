package com.example.resonant.unisound

import android.content.Context
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.UnisoundMemberDTO
import com.example.resonant.data.network.UnisoundPlaybackStateDTO
import com.example.resonant.data.network.UnisoundQueueItemDTO
import com.example.resonant.data.network.UnisoundReactionDTO
import com.example.resonant.data.network.UnisoundSessionSnapshotDTO
import com.example.resonant.data.network.UnisoundSessionSummaryDTO
import com.example.resonant.data.network.UnisoundSettingsDTO
import com.example.resonant.data.network.UnisoundSourceDTO
import com.example.resonant.managers.SessionManager
import com.google.gson.reflect.TypeToken
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.HubConnectionState
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/** SignalR implementation of [UnisoundHubGateway], built like `PlaybackConnectRepository`. */
class UnisoundHubClient(context: Context) : UnisoundHubGateway {
    private val sessionManager = SessionManager.getInstance(context.applicationContext)

    @Volatile
    private var connection: HubConnection? = null

    override var listener: UnisoundHubListener? = null

    override val isConnected: Boolean
        get() = connection?.connectionState == HubConnectionState.CONNECTED

    override suspend fun connect() = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext
        if (sessionManager.getValidAccessToken().isNullOrBlank()) {
            throw IOException("No access token for /hubs/unisound")
        }
        val hub = HubConnectionBuilder
            .create("${ApiClient.baseUrl().trimEnd('/')}/hubs/unisound")
            .withAccessTokenProvider(
                Single.defer { Single.just(sessionManager.getAccessToken().orEmpty()) }
            )
            .withKeepAliveInterval(KEEP_ALIVE_MS)
            .withServerTimeout(SERVER_TIMEOUT_MS)
            .build()
        registerEvents(hub)
        hub.onClosed { error ->
            if (connection === hub) {
                connection = null
                listener?.onClosed(error)
            }
        }
        connection = hub
        try {
            hub.start().timeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS).blockingAwait()
        } catch (error: Throwable) {
            if (connection === hub) connection = null
            runCatching { hub.stop().blockingAwait() }
            throw error
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        val hub = connection ?: return@withContext
        connection = null
        runCatching { hub.stop().blockingAwait() }
        Unit
    }

    override suspend fun syncClock(): Long =
        call(java.lang.Long::class.java, "SyncClock").toLong()

    override suspend fun joinRoom(sessionId: String, lastRevision: Long): UnisoundSessionSnapshotDTO =
        call(UnisoundSessionSnapshotDTO::class.java, "JoinRoom", sessionId, lastRevision)

    override suspend fun leaveRoom(sessionId: String) = callVoid("LeaveRoom", sessionId)

    override suspend fun play(sessionId: String, expectedRevision: Long, idempotencyKey: String) =
        call(UnisoundPlaybackStateDTO::class.java, "Play", sessionId, expectedRevision, idempotencyKey)

    override suspend fun pause(sessionId: String, positionMs: Long, expectedRevision: Long, idempotencyKey: String) =
        call(UnisoundPlaybackStateDTO::class.java, "Pause", sessionId, positionMs, expectedRevision, idempotencyKey)

    override suspend fun seek(sessionId: String, positionMs: Long, expectedRevision: Long, idempotencyKey: String) =
        call(UnisoundPlaybackStateDTO::class.java, "Seek", sessionId, positionMs, expectedRevision, idempotencyKey)

    override suspend fun skip(sessionId: String, direction: String, expectedRevision: Long, idempotencyKey: String) =
        call(UnisoundPlaybackStateDTO::class.java, "Skip", sessionId, direction, expectedRevision, idempotencyKey)

    override suspend fun addItems(
        sessionId: String,
        source: UnisoundSourceDTO,
        idempotencyKey: String
    ): List<UnisoundQueueItemDTO> = withContext(Dispatchers.IO) {
        requireConnection()
            .invoke<List<UnisoundQueueItemDTO>>(QUEUE_ITEMS_TYPE, "AddItems", sessionId, source, idempotencyKey)
            .timeout(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .blockingGet()
    }

    override suspend fun removeItem(sessionId: String, itemId: String, idempotencyKey: String) =
        callVoid("RemoveItem", sessionId, itemId, idempotencyKey)

    override suspend fun moveItem(
        sessionId: String,
        itemId: String,
        toIndex: Int,
        expectedRevision: Long,
        idempotencyKey: String
    ): List<UnisoundQueueItemDTO> = withContext(Dispatchers.IO) {
        requireConnection()
            .invoke<List<UnisoundQueueItemDTO>>(
                QUEUE_ITEMS_TYPE, "MoveItem", sessionId, itemId, toIndex, expectedRevision, idempotencyKey
            )
            .timeout(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .blockingGet()
    }

    override suspend fun react(sessionId: String, emoji: String) = callVoid("React", sessionId, emoji)

    private fun registerEvents(hub: HubConnection) {
        hub.on(
            "SessionSnapshot",
            { snapshot: UnisoundSessionSnapshotDTO -> emit(UnisoundEvent.Snapshot(snapshot)) },
            UnisoundSessionSnapshotDTO::class.java
        )
        hub.on(
            "PlaybackStateChanged",
            { state: UnisoundPlaybackStateDTO -> emit(UnisoundEvent.PlaybackChanged(state)) },
            UnisoundPlaybackStateDTO::class.java
        )
        hub.on<java.lang.Long, List<UnisoundQueueItemDTO>>(
            "QueueChanged",
            { revision, items -> emit(UnisoundEvent.QueueChanged(revision.toLong(), items.orEmpty())) },
            java.lang.Long::class.java,
            QUEUE_ITEMS_TYPE
        )
        hub.on<List<UnisoundMemberDTO>>(
            "MembersChanged",
            { members -> emit(UnisoundEvent.MembersChanged(members.orEmpty())) },
            MEMBERS_TYPE
        )
        hub.on(
            "PolicyChanged",
            { settings: UnisoundSettingsDTO -> emit(UnisoundEvent.PolicyChanged(settings)) },
            UnisoundSettingsDTO::class.java
        )
        hub.on(
            "ReactionReceived",
            { reaction: UnisoundReactionDTO -> emit(UnisoundEvent.Reaction(reaction)) },
            UnisoundReactionDTO::class.java
        )
        hub.on(
            "SessionEnded",
            { summary: UnisoundSessionSummaryDTO -> emit(UnisoundEvent.Ended(summary)) },
            UnisoundSessionSummaryDTO::class.java
        )
    }

    private fun emit(event: UnisoundEvent) {
        listener?.onEvent(event)
    }

    private fun requireConnection(): HubConnection =
        connection?.takeIf { it.connectionState == HubConnectionState.CONNECTED }
            ?: throw IOException("Unisound hub is not connected")

    private suspend fun <T> call(type: Class<T>, method: String, vararg args: Any): T =
        withContext(Dispatchers.IO) {
            requireConnection()
                .invoke(type, method, *args)
                .timeout(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .blockingGet()
        }

    private suspend fun callVoid(method: String, vararg args: Any) = withContext(Dispatchers.IO) {
        requireConnection()
            .invoke(method, *args)
            .timeout(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .blockingAwait()
    }

    companion object {
        private const val KEEP_ALIVE_MS = 30_000L
        private const val SERVER_TIMEOUT_MS = 75_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        const val COMMAND_TIMEOUT_MS = 5_000L
        private val QUEUE_ITEMS_TYPE = object : TypeToken<List<UnisoundQueueItemDTO>>() {}.type
        private val MEMBERS_TYPE = object : TypeToken<List<UnisoundMemberDTO>>() {}.type
    }
}
