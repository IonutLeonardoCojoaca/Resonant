package com.example.resonant.data.network

import com.google.gson.annotations.SerializedName

data class RecommendationResponse<T>(
    @SerializedName("item")
    val item: T,

    @SerializedName("score")
    val score: Double,

    @SerializedName("reason")
    val reason: ReasonDTO?
)
