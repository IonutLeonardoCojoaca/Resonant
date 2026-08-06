package com.example.resonant.data.network

import com.google.gson.JsonElement

data class ApiErrorDTO(
    val code: String = "",
    val message: String = "",
    val correlationId: String = "",
    val retryable: Boolean = false,
    val retryAfterSeconds: Int? = null
)

data class PlaybackResolveRequestDTO(
    val songIds: List<String>,
    val preferredQuality: String = "auto",
    val networkType: String = "unknown",
    val download: Boolean = false
)

data class PlaybackResolveItemDTO(
    val id: String,
    val streamUrl: String? = null,
    val expiresAtUtc: String? = null,
    val streamId: String? = null,
    val mimeType: String? = null,
    val contentLength: Long? = null,
    val supportsRanges: Boolean? = null,
    val deliveryMode: String? = null,
    val quality: String? = null,
    val error: ApiErrorDTO? = null
)

data class PlaybackResolveResponseDTO(
    val items: List<PlaybackResolveItemDTO> = emptyList(),
    val serverTimeUtc: String = ""
)

data class HomeSectionDTO(
    val id: String = "",
    val title: String = "",
    val type: String = "",
    val items: List<JsonElement> = emptyList(),
    val ttlSeconds: Int = 1,
    val error: ApiErrorDTO? = null
)

data class HomeResponseDTO(
    val generatedAtUtc: String = "",
    val sections: List<HomeSectionDTO> = emptyList()
)

data class PlaybackQoeEventDTO(
    val eventId: String,
    val sessionId: String,
    val streamId: String,
    val source: String,
    val deliveryMode: String,
    val startupTimeMs: Long,
    val rebufferCount: Int,
    val rebufferDurationMs: Long,
    val averageBitrate: Long,
    val networkType: String,
    val fatalErrorCode: String?,
    val occurredAtUtc: String
)

data class PlaybackQoeResponseDTO(
    val eventId: String = "",
    val accepted: Boolean = false,
    val duplicate: Boolean = false
)
