package com.example.resonant.data.network.services

import com.example.resonant.data.network.AuthResponse
import com.example.resonant.data.network.GoogleTokenDTO
import com.example.resonant.data.network.RefreshTokenDTO
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

interface AuthService {
    @POST("api/Auth/Google")
    suspend fun loginWithGoogle(
        @Body request: GoogleTokenDTO
    ): AuthResponse

    @POST("api/Auth/Refresh")
    fun refreshToken(
        @Body request: RefreshTokenDTO
    ): Call<AuthResponse>
}