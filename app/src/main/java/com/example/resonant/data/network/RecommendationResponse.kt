package com.example.resonant.data.network

import com.google.gson.annotations.SerializedName

data class RecommendationResponse<T>(
    @SerializedName("item")
    val item: T,

    @SerializedName("score")
    val score: Double,

    @SerializedName("reason")
    val reason: ReasonDTO?,

    /** `requestId` común de la respuesta (telemetría activa); se envía como `recommendationRequestId`. */
    @SerializedName("requestId")
    val requestId: String? = null,

    @SerializedName("position")
    val position: Int? = null
)
