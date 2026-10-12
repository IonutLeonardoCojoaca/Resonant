package com.example.resonant.unisound

import com.example.resonant.data.network.UnisoundMemberDTO
import com.example.resonant.data.network.UnisoundPlaybackStateDTO
import com.example.resonant.data.network.UnisoundQueueItemDTO
import com.example.resonant.data.network.UnisoundSessionSnapshotDTO
import com.example.resonant.data.network.UnisoundSessionSummaryDTO
import com.example.resonant.data.network.UnisoundSettingsDTO

enum class UnisoundConnection { CONNECTING, CONNECTED, RECONNECTING }

/**
 * Immutable client view of a Unisound room. Permissions are recomputed locally from the
 * room settings and the current host, mirroring the server policy, so `PolicyChanged` and
 * `MembersChanged` update the UI without waiting for a new snapshot.
 */
data class UnisoundRoomState(
    val sessionId: String,
    val myUserId: String?,
    val code: String?,
    val joinUrl: String?,
    val hostUserId: String,
    val settings: UnisoundSettingsDTO,
    val playback: UnisoundPlaybackStateDTO,
    val current: UnisoundQueueItemDTO?,
    val queue: List<UnisoundQueueItemDTO>,
    val members: List<UnisoundMemberDTO>,
    /** Every item seen in this room (current, queued and played), by item id. */
    val knownItems: Map<String, UnisoundQueueItemDTO> = emptyMap(),
    /** Role reported by the server, used when the local user id is unknown. */
    val serverRole: String? = null,
    val connection: UnisoundConnection = UnisoundConnection.CONNECTING,
    /** This device is paused locally (focus loss, noisy, Connect) while the room goes on. */
    val localHold: Boolean = false
) {
    val revision: Long get() = playback.revision
    val isHost: Boolean
        get() = myUserId?.let { it == hostUserId } ?: (serverRole == ROLE_HOST)
    val canAdd: Boolean get() = isHost || settings.guestsCanAdd
    val canControl: Boolean get() = isHost || settings.guestsCanControl
    val isPlaying: Boolean get() = playback.status == STATUS_PLAYING
    val connectedMembers: Int get() = members.count(UnisoundMemberDTO::connected)
    val currentDurationMs: Long get() = current?.durationMs ?: 0L

    /** Where the room should be at [serverNowMs], clamped to the current song. */
    fun expectedPositionMs(serverNowMs: Long): Long {
        val elapsed = if (isPlaying) serverNowMs - playback.anchorServerTimeUtcMs else 0L
        val position = playback.positionMsAtAnchor + elapsed
        val duration = currentDurationMs
        return if (duration > 0L) position.coerceIn(0L, duration) else position.coerceAtLeast(0L)
    }

    fun canRemove(item: UnisoundQueueItemDTO): Boolean =
        canControl || (myUserId != null && item.addedByUserId == myUserId)

    companion object {
        const val ROLE_HOST = "host"
        const val STATUS_PLAYING = "playing"
        const val STATUS_PAUSED = "paused"
        const val END_REASON_KICKED = "kicked"

        fun fromSnapshot(
            snapshot: UnisoundSessionSnapshotDTO,
            myUserId: String?,
            previous: UnisoundRoomState? = null
        ): UnisoundRoomState {
            val queue = snapshot.queue.orEmpty()
            val seen = listOfNotNull(snapshot.current) + queue
            return UnisoundRoomState(
                sessionId = snapshot.sessionId,
                myUserId = myUserId,
                code = snapshot.code,
                joinUrl = snapshot.joinUrl,
                hostUserId = snapshot.hostUserId,
                settings = snapshot.settings ?: UnisoundSettingsDTO(),
                playback = snapshot.state ?: UnisoundPlaybackStateDTO(),
                current = snapshot.current,
                queue = queue,
                members = snapshot.members.orEmpty(),
                knownItems = previous?.knownItems.orEmpty() + seen.associateBy(UnisoundQueueItemDTO::itemId),
                serverRole = snapshot.me?.role,
                connection = previous?.connection ?: UnisoundConnection.CONNECTING,
                localHold = previous?.localHold ?: false
            )
        }
    }
}

sealed class UnisoundEvent {
    data class Snapshot(val snapshot: UnisoundSessionSnapshotDTO) : UnisoundEvent()
    data class PlaybackChanged(val state: UnisoundPlaybackStateDTO) : UnisoundEvent()
    data class QueueChanged(val revision: Long, val items: List<UnisoundQueueItemDTO>) : UnisoundEvent()
    data class MembersChanged(val members: List<UnisoundMemberDTO>) : UnisoundEvent()
    data class PolicyChanged(val settings: UnisoundSettingsDTO) : UnisoundEvent()
    data class Ended(val summary: UnisoundSessionSummaryDTO) : UnisoundEvent()
}

sealed class UnisoundEffect {
    /** Current song, queue or playback state changed: the player must follow. */
    object PlaybackUpdated : UnisoundEffect()
    data class ResyncNeeded(val lastRevision: Long) : UnisoundEffect()
    data class Ended(val summary: UnisoundSessionSummaryDTO) : UnisoundEffect()
    data class Kicked(val summary: UnisoundSessionSummaryDTO) : UnisoundEffect()
    object BecameHost : UnisoundEffect()
}

data class UnisoundReduction(
    /** Null once the room is over for this client. */
    val state: UnisoundRoomState?,
    val effects: List<UnisoundEffect> = emptyList()
)

/**
 * Applies hub events in revision order. The server raises the revision once per mutation and
 * sends `PlaybackStateChanged(r)` followed, when the queue changed, by `QueueChanged(r)` with
 * the same `r`; member and policy events carry no revision.
 */
object UnisoundRoomReducer {

    fun reduce(state: UnisoundRoomState, event: UnisoundEvent): UnisoundReduction = when (event) {
        is UnisoundEvent.Snapshot -> applySnapshot(state, event.snapshot)
        is UnisoundEvent.PlaybackChanged -> applyPlayback(state, event.state)
        is UnisoundEvent.QueueChanged -> applyQueue(state, event.revision, event.items)
        is UnisoundEvent.MembersChanged -> applyMembers(state, event.members)
        is UnisoundEvent.PolicyChanged -> UnisoundReduction(state.copy(settings = event.settings))
        is UnisoundEvent.Ended -> UnisoundReduction(
            state = null,
            effects = listOf(
                if (event.summary.reason == UnisoundRoomState.END_REASON_KICKED) {
                    UnisoundEffect.Kicked(event.summary)
                } else {
                    UnisoundEffect.Ended(event.summary)
                }
            )
        )
    }

    private fun applySnapshot(
        state: UnisoundRoomState,
        snapshot: UnisoundSessionSnapshotDTO
    ): UnisoundReduction {
        val incomingRevision = snapshot.state?.revision ?: 0L
        if (incomingRevision < state.revision) {
            // Older than what we applied: keep playback, refresh the rest.
            val members = snapshot.members ?: state.members
            return UnisoundReduction(
                state.copy(
                    settings = snapshot.settings ?: state.settings,
                    members = members,
                    hostUserId = snapshot.hostUserId.ifBlank { state.hostUserId },
                    code = snapshot.code ?: state.code,
                    joinUrl = snapshot.joinUrl ?: state.joinUrl
                )
            )
        }
        val next = UnisoundRoomState.fromSnapshot(snapshot, state.myUserId, previous = state)
        return UnisoundReduction(next, listOf(UnisoundEffect.PlaybackUpdated) + hostEffect(state, next))
    }

    private fun applyPlayback(
        state: UnisoundRoomState,
        playback: UnisoundPlaybackStateDTO
    ): UnisoundReduction {
        if (playback.revision <= state.revision) return UnisoundReduction(state)
        val effects = mutableListOf<UnisoundEffect>(UnisoundEffect.PlaybackUpdated)
        if (playback.revision > state.revision + 1) {
            effects += UnisoundEffect.ResyncNeeded(state.revision)
        }

        val currentId = playback.currentItemId
        val current = currentId?.let(state.knownItems::get)
        if (currentId != null && current == null && effects.none { it is UnisoundEffect.ResyncNeeded }) {
            effects += UnisoundEffect.ResyncNeeded(state.revision)
        }
        // A song change is always followed by QueueChanged with the same revision, which
        // brings the authoritative pending queue; until then just drop the new current.
        return UnisoundReduction(
            state.copy(
                playback = playback,
                current = current,
                queue = state.queue.filterNot { it.itemId == currentId }
            ),
            effects
        )
    }

    private fun applyQueue(
        state: UnisoundRoomState,
        revision: Long,
        items: List<UnisoundQueueItemDTO>
    ): UnisoundReduction {
        if (revision < state.revision) return UnisoundReduction(state)
        val effects = mutableListOf<UnisoundEffect>(UnisoundEffect.PlaybackUpdated)
        var playback = state.playback
        if (revision > state.revision) {
            if (revision > state.revision + 1) effects += UnisoundEffect.ResyncNeeded(state.revision)
            playback = playback.copy(revision = revision)
        }
        val currentId = state.current?.itemId
        return UnisoundReduction(
            state.copy(
                playback = playback,
                queue = items.filterNot { it.itemId == currentId },
                knownItems = state.knownItems + items.associateBy(UnisoundQueueItemDTO::itemId)
            ),
            effects
        )
    }

    private fun applyMembers(
        state: UnisoundRoomState,
        members: List<UnisoundMemberDTO>
    ): UnisoundReduction {
        val host = members.firstOrNull { it.role == UnisoundRoomState.ROLE_HOST }?.userId
        val next = state.copy(members = members, hostUserId = host ?: state.hostUserId)
        return UnisoundReduction(next, hostEffect(state, next))
    }

    private fun hostEffect(before: UnisoundRoomState, after: UnisoundRoomState): List<UnisoundEffect> =
        if (!before.isHost && after.isHost) listOf(UnisoundEffect.BecameHost) else emptyList()
}
