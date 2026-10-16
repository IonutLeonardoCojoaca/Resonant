package com.example.resonant.data.network.services

import com.example.resonant.data.network.ListeningHistoryPageDTO
import com.example.resonant.data.network.ListeningSummaryDTO
import com.example.resonant.data.network.ListeningTimelineDTO
import com.example.resonant.data.network.ListeningTopDTO
import com.example.resonant.data.network.OnThisDayDTO
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/** Estadísticas personales e historial propio (detrás de `features.listeningStats`). Sin borrado de historial. */
interface ListeningStatsService {

    @GET("api/me/stats/summary")
    suspend fun getSummary(
        @Query("period") period: String,
        @Query("key") key: String? = null
    ): Response<ListeningSummaryDTO>

    @GET("api/me/stats/top")
    suspend fun getTop(
        @Query("type") type: String,
        @Query("period") period: String,
        @Query("key") key: String? = null,
        @Query("limit") limit: Int? = null
    ): Response<ListeningTopDTO>

    @GET("api/me/stats/timeline")
    suspend fun getTimeline(
        @Query("from") from: String,
        @Query("to") to: String
    ): Response<ListeningTimelineDTO>

    @GET("api/me/stats/on-this-day")
    suspend fun getOnThisDay(): Response<OnThisDayDTO>

    @GET("api/me/history")
    suspend fun getHistory(
        @Query("before") before: String? = null,
        @Query("limit") limit: Int? = null
    ): Response<ListeningHistoryPageDTO>
}
