package com.example.resonant.data.network.services

import com.example.resonant.data.models.User
import retrofit2.http.GET
import retrofit2.http.Query

interface UserService {
    @GET("/api/User/GetByEmail")
    suspend fun getUserByEmail(@Query("email") email: String): User

    @GET("/api/User/GetById")
    suspend fun getUserById(@Query("id") id: String): User
}