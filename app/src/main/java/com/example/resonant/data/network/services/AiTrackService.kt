package com.example.resonant.data.network.services

import com.example.resonant.data.network.AiTrackCreateRequest
import com.example.resonant.data.network.AiTrackDTO
import com.example.resonant.data.network.AiTrackPageDTO
import com.example.resonant.data.network.AiTrackSuggestionDTO
import com.example.resonant.data.network.AiTrackSuggestionRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Pistas generadas con IA. Todas las rutas responden 404 `FEATURE_DISABLED` con el flag del servidor
 * desactivado; los errores llevan `code` estable (ver [com.example.resonant.data.network.AiTrackErrorDTO]).
 */
interface AiTrackService {
    /** 422 `AI_TRACK_INVALID` / `AI_TRACK_INVALID_REFERENCE`. */
    @POST("api/ai-tracks/suggestions")
    suspend fun suggest(@Body request: AiTrackSuggestionRequest): Response<AiTrackSuggestionDTO>

    /** 202 en cola; 422 `AI_TRACK_INVALID`; 429 `AI_TRACK_DAILY_LIMIT`. */
    @POST("api/ai-tracks")
    suspend fun create(@Body request: AiTrackCreateRequest): Response<AiTrackDTO>

    @GET("api/ai-tracks")
    suspend fun list(@Query("page") page: Int, @Query("pageSize") pageSize: Int): Response<AiTrackPageDTO>

    /** 404 `AI_TRACK_NOT_FOUND`. */
    @GET("api/ai-tracks/{id}")
    suspend fun get(@Path("id") id: String): Response<AiTrackDTO>

    /** 202 de vuelta en cola; 409 `AI_TRACK_NOT_RETRYABLE`. */
    @POST("api/ai-tracks/{id}/retry")
    suspend fun retry(@Path("id") id: String): Response<AiTrackDTO>
}
