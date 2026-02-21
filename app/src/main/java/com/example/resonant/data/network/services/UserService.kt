package com.example.resonant.data.network.services

import com.example.resonant.data.models.User
import retrofit2.http.GET
import retrofit2.http.Path

interface UserService {
    @GET("api/users/me")
    suspend fun getCurrentUser(): User

    @GET("api/users/{id}")
    suspend fun getUserById(@Path("id") id: String): User
}