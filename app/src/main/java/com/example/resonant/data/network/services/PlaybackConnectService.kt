package com.example.resonant.data.network.services

import com.example.resonant.data.network.PlaybackConnectCommandAckDTO
import com.example.resonant.data.network.PlaybackConnectHeartbeatRequestDTO
import com.example.resonant.data.network.PlaybackConnectHeartbeatResponseDTO
import com.example.resonant.data.network.PlaybackConnectSnapshotDTO
import com.example.resonant.data.network.PlaybackTransferRequestDTO
import com.example.resonant.data.network.PlaybackTransferResponseDTO
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface PlaybackConnectService {
    @POST("api/v2/connect/devices/heartbeat")
    suspend fun heartbeat(
        @Body request: PlaybackConnectHeartbeatRequestDTO
    ): Response<PlaybackConnectHeartbeatResponseDTO>

    @GET("api/v2/connect/devices")
    suspend fun getDevices(): Response<PlaybackConnectSnapshotDTO>

    @POST("api/v2/connect/transfer")
    suspend fun transfer(
        @Body request: PlaybackTransferRequestDTO
    ): Response<PlaybackTransferResponseDTO>

    @POST("api/v2/connect/commands/{commandId}/ack")
    suspend fun acknowledgeCommand(
        @Path("commandId") commandId: String,
        @Body request: PlaybackConnectCommandAckDTO
    ): Response<Unit>
}

