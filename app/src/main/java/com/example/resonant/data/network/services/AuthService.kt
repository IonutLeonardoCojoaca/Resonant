package com.example.resonant.data.network.services

import com.example.resonant.data.network.AuthResponse
import com.example.resonant.data.network.GoogleTokenDTO
import com.example.resonant.data.network.RefreshTokenDTO
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface AuthService {
    @POST("api/auth/google")
    suspend fun loginWithGoogle(@Body request: GoogleTokenDTO): AuthResponse

    @POST("api/auth/refresh")
    fun refreshToken(@Body request: RefreshTokenDTO): Call<AuthResponse>

    @POST("api/auth/logout")
    suspend fun logout(@Body request: RefreshTokenDTO): Response<Void>
}