package com.example.resonant.unisound

import com.example.resonant.data.models.Song
import com.example.resonant.data.network.UnisoundMeDTO
import com.example.resonant.data.network.UnisoundMemberDTO
import com.example.resonant.data.network.UnisoundPlaybackStateDTO
import com.example.resonant.data.network.UnisoundQueueItemDTO
import com.example.resonant.data.network.UnisoundSessionSnapshotDTO
import com.example.resonant.data.network.UnisoundSettingsDTO

/** Hand-written builders shared by the Unisound unit tests. */
object UnisoundFixtures {
    const val SESSION = "c1f3a2de-6a0b-4b8e-9d7e-2f0c4a9b1e55"
    const val HOST = "host-user"
    const val GUEST = "guest-user"

    fun item(id: String, addedBy: String = HOST, durationMs: Long = 200_000L) = UnisoundQueueItemDTO(
        itemId = id,
        songId = "song-$id",
        durationMs = durationMs,
        addedBy = "user",
        addedByUserId = addedBy,
        addedByName = addedBy,
        song = Song(id = "song-$id", title = "Song $id")
    )

    fun playback(
        revision: Long,
        status: String = UnisoundRoomState.STATUS_PLAYING,
        currentItemId: String? = "x",
        positionMs: Long = 0L,
        anchorMs: Long = 0L
    ) = UnisoundPlaybackStateDTO(
        revision = revision,
        status = status,
        currentItemId = currentItemId,
        songId = currentItemId?.let { "song-$it" },
        positionMsAtAnchor = positionMs,
        anchorServerTimeUtcMs = anchorMs
    )

    fun members(host: String = HOST, guestConnected: Boolean = true) = listOf(
        UnisoundMemberDTO(userId = HOST, name = "Ionut", role = if (host == HOST) "host" else "guest", connected = true),
        UnisoundMemberDTO(userId = GUEST, name = "Ana", role = if (host == GUEST) "host" else "guest", connected = guestConnected)
    )

    fun snapshot(
        revision: Long = 10L,
        current: UnisoundQueueItemDTO? = item("x"),
        queue: List<UnisoundQueueItemDTO> = listOf(item("y"), item("z")),
        settings: UnisoundSettingsDTO = UnisoundSettingsDTO(guestsCanAdd = true, guestsCanControl = true, joinOpen = true),
        status: String = UnisoundRoomState.STATUS_PLAYING
    ) = UnisoundSessionSnapshotDTO(
        sessionId = SESSION,
        code = "K7M2QX",
        joinUrl = "https://resonantapp.ddns.net/u/K7M2QX",
        hostUserId = HOST,
        me = UnisoundMeDTO(role = "guest", canAdd = true, canControl = true),
        settings = settings,
        state = playback(revision, status = status, currentItemId = current?.itemId),
        current = current,
        queue = queue,
        members = members(),
        serverTimeUtcMs = 0L
    )

    fun room(
        myUserId: String = GUEST,
        snapshot: UnisoundSessionSnapshotDTO = snapshot(),
        connection: UnisoundConnection = UnisoundConnection.CONNECTED,
        localHold: Boolean = false
    ) = UnisoundRoomState.fromSnapshot(snapshot, myUserId)
        .copy(connection = connection, localHold = localHold)
}
