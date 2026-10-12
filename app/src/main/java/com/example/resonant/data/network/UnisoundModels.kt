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
    val joinOpen: Boolean = false,
    /** Null when the server does not support room Flow: the switch is not offered then. */
    val roomFlowEnabled: Boolean? = null
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
    /** song | album | playlist, or null for room Flow items. */
    val sourceType: String? = null,
    val sourceId: String? = null,
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
    val songIds: List<String>? = null,
    val ariaLine: String? = null,
    /** none | pending | ready | unavailable */
    val ariaLineStatus: String? = null
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
    val joinOpen: Boolean? = null,
    val roomFlowEnabled: Boolean? = null
)

data class UnisoundTransferHostRequestDTO(
    val userId: String
)

/** Hub `AddItems` source: `type` is "song", "album" or "playlist". */
data class UnisoundSourceDTO(
    val type: String,
    val id: String
)

/** Hub `ReactionReceived`: ephemeral, never stored. */
data class UnisoundReactionDTO(
    val userId: String = "",
    val name: String = "",
    val emoji: String = "",
    val songId: String? = null,
    val serverTimeUtcMs: Long = 0L
)

data class UnisoundSavePlaylistRequestDTO(
    val name: String? = null
)

data class UnisoundSavedPlaylistDTO(
    val playlistId: String = "",
    val name: String = "",
    val songCount: Int = 0,
    val addedCount: Int = 0
)
