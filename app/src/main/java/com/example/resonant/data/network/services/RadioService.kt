package com.example.resonant.data.network.services

import com.example.resonant.data.network.RadioRequestDTO
import com.example.resonant.data.network.RadioResponseDTO
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface RadioService {
    /**
     * Fetches the next batch of tracks for Resonant Radio (autoplay when
     * the current queue ends).
     *
     * Returns [Response] rather than the body directly so callers can
     * inspect status codes: 204 means the server declined to seed
     * (e.g. offline-only context), which is not an error — the client
     * simply stops autoplay silently.
     */
    @POST("api/radio")
    suspend fun getRadio(
        @Body request: RadioRequestDTO
    ): Response<RadioResponseDTO>
}
