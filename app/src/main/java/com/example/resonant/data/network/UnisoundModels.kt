package com.example.resonant.data.network

import com.example.resonant.data.models.Song

/**
 * Unisound contract (REST under `api/unisound` and the `/hubs/unisound` hub), camelCase as served by the
 * backend. Gson ignores unknown fields, so additive server changes never break parsing.
 */
data class UnisoundSessionSnapshotDTO(
    val sessionId: String = "",
    val code: String? = null,
    val joinUrl: String? = null,
    val hostUserId: String = "",
    val me: UnisoundMeDTO? = null,
    val settings: UnisoundSettingsDTO? = null,
    val state: UnisoundPlaybackStateDTO? = null,
    val current: UnisoundQueueItemDTO? = null,
    val queue: List<UnisoundQueueItemDTO>? = null,
    val members: List<UnisoundMemberDTO>? = null,
    val serverTimeUtcMs: Long = 0L
)

data class UnisoundMeDTO(
    val role: String = "",
    val canAdd: Boolean = false,
    val canControl: Boolean = false
)

data class UnisoundSettingsDTO(
    val guestsCanAdd: Boolean = false,
    val guestsCanControl: Boolean = false,
    val joinOpen: Boolean = false
)

data class UnisoundPlaybackStateDTO(
    val revision: Long = 0L,
    val status: String = "",
    val currentItemId: String? = null,
    val songId: String? = null,
    val positionMsAtAnchor: Long = 0L,
    val anchorServerTimeUtcMs: Long = 0L
)

data class UnisoundQueueItemDTO(
    val itemId: String = "",
    val songId: String = "",
    val durationMs: Long = 0L,
    val addedBy: String = "",
    val addedByUserId: String? = null,
    val addedByName: String? = null,
    val song: Song? = null
)

data class UnisoundMemberDTO(
    val userId: String = "",
    val name: String = "",
    val role: String = "",
    val connected: Boolean = false,
    val joinedAtUtc: String? = null
)

data class UnisoundSessionSummaryDTO(
    val sessionId: String = "",
    val reason: String? = null,
    val startedAtUtc: String? = null,
    val endedAtUtc: String? = null,
    val durationMs: Long = 0L,
    val songsPlayed: Int = 0,
    val songIds: List<String>? = null
)

data class UnisoundCodeDTO(
    val code: String = "",
    val joinUrl: String = ""
)

data class UnisoundJoinRequestDTO(
    val code: String
)

/** Partial update: null fields are omitted by Gson and left unchanged by the server. */
data class UnisoundSettingsUpdateDTO(
    val guestsCanAdd: Boolean? = null,
    val guestsCanControl: Boolean? = null,
    val joinOpen: Boolean? = null
)

data class UnisoundTransferHostRequestDTO(
    val userId: String
)

/** Hub `AddItems` source: `type` is "song" in this client version. */
data class UnisoundSourceDTO(
    val type: String,
    val id: String
)
