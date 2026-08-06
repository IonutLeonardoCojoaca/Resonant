package com.example.resonant.data.network

data class PlaybackConnectQueueItemDTO(
    val songId: String,
    val title: String,
    val artistName: String?,
    val coverUrl: String?,
    val durationMs: Long?
)

data class PlaybackConnectStateDTO(
    val stateRevision: Long,
    val queueRevision: Long,
    val queueItems: List<PlaybackConnectQueueItemDTO>? = null,
    val queueTruncated: Boolean = false,
    val currentIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val sourceType: String,
    val sourceId: String?,
    val repeatMode: Int,
    val shuffleEnabled: Boolean
)

data class PlaybackConnectDeviceDTO(
    val deviceId: String,
    val name: String,
    val platform: String,
    val deviceType: String,
    val appVersion: String?,
    val isOnline: Boolean,
    val isActive: Boolean,
    val lastSeenAtUtc: String?,
    val playback: PlaybackConnectStateDTO?
)

data class PlaybackConnectSnapshotDTO(
    val revision: Long,
    val activeDeviceId: String?,
    val devices: List<PlaybackConnectDeviceDTO>,
    val serverTimeUtc: String?
)

data class PlaybackConnectHeartbeatRequestDTO(
    val deviceId: String,
    val deviceName: String,
    val platform: String = "android",
    val deviceType: String = "phone",
    val appVersion: String,
    val capabilities: List<String>,
    val playback: PlaybackConnectStateDTO?
)

data class PlaybackConnectCommandDTO(
    val commandId: String,
    val sequence: Long,
    val type: String,
    val expiresAtUtc: String?,
    val playback: PlaybackConnectStateDTO?
)

data class PlaybackConnectHeartbeatResponseDTO(
    val snapshot: PlaybackConnectSnapshotDTO,
    val commands: List<PlaybackConnectCommandDTO> = emptyList(),
    val acceptedQueueRevision: Long? = null
)

data class PlaybackTransferRequestDTO(
    val sourceDeviceId: String,
    val targetDeviceId: String,
    val expectedRevision: Long?,
    val idempotencyKey: String,
    /** Optional: the source device's current playback state.  When present
     *  the backend should use it directly for the TRANSFER_IN payload sent
     *  to the target instead of looking up the last heartbeat snapshot. */
    val playback: PlaybackConnectStateDTO? = null
)

data class PlaybackTransferResponseDTO(
    val accepted: Boolean,
    val commandId: String?,
    val snapshot: PlaybackConnectSnapshotDTO
)

data class PlaybackConnectCommandAckDTO(
    val deviceId: String,
    val status: String,
    val errorCode: String? = null
)

