package com.example.resonant.data.network.services

import com.example.resonant.data.models.AppUpdate
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface AppService {
    @GET("api/App/LatestVersion")
    suspend fun getLatestAppVersion(@Query("platform") platform: String): AppUpdate

    @GET("api/App/Download")
    suspend fun getPresignedDownloadUrl(
        @Query("version") version: String,
        @Query("platform") platform: String
    ): Response<ResponseBody>
}