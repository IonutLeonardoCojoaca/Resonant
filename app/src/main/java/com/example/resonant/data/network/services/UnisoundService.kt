package com.example.resonant.data.network.services

import com.example.resonant.data.network.UnisoundCodeDTO
import com.example.resonant.data.network.UnisoundJoinRequestDTO
import com.example.resonant.data.network.UnisoundSessionSnapshotDTO
import com.example.resonant.data.network.UnisoundSessionSummaryDTO
import com.example.resonant.data.network.UnisoundSettingsUpdateDTO
import com.example.resonant.data.network.UnisoundTransferHostRequestDTO
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

interface UnisoundService {
    @POST("api/unisound/sessions")
    suspend fun createSession(): Response<UnisoundSessionSnapshotDTO>

    /** 200 with the snapshot, or 204 when the user is not in any room. */
    @GET("api/unisound/sessions/current")
    suspend fun getCurrentSession(): Response<UnisoundSessionSnapshotDTO>

    @POST("api/unisound/join")
    suspend fun join(@Body request: UnisoundJoinRequestDTO): Response<UnisoundSessionSnapshotDTO>

    @GET("api/unisound/sessions/{id}")
    suspend fun getSession(@Path("id") sessionId: String): Response<UnisoundSessionSnapshotDTO>

    @POST("api/unisound/sessions/{id}/leave")
    suspend fun leave(@Path("id") sessionId: String): Response<Unit>

    @POST("api/unisound/sessions/{id}/end")
    suspend fun end(@Path("id") sessionId: String): Response<UnisoundSessionSummaryDTO>

    @PATCH("api/unisound/sessions/{id}/settings")
    suspend fun updateSettings(
        @Path("id") sessionId: String,
        @Body request: UnisoundSettingsUpdateDTO
    ): Response<UnisoundSessionSnapshotDTO>

    @POST("api/unisound/sessions/{id}/code/regenerate")
    suspend fun regenerateCode(@Path("id") sessionId: String): Response<UnisoundCodeDTO>

    @POST("api/unisound/sessions/{id}/members/{userId}/kick")
    suspend fun kick(
        @Path("id") sessionId: String,
        @Path("userId") userId: String
    ): Response<Unit>

    @POST("api/unisound/sessions/{id}/host")
    suspend fun transferHost(
        @Path("id") sessionId: String,
        @Body request: UnisoundTransferHostRequestDTO
    ): Response<UnisoundSessionSnapshotDTO>
}
