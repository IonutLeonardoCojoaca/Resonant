package com.example.resonant.data.network

import com.google.gson.annotations.SerializedName

data class AriaFeedbackRequest(
    @SerializedName("logId")   val logId: String,
    @SerializedName("rating")  val rating: Int,
    @SerializedName("comment") val comment: String? = null
)

data class AriaFeedbackResponse(
    @SerializedName("status") val status: String
)

data class AriaFeedbackStats(
    @SerializedName("total")              val total: Int,
    @SerializedName("likes")              val likes: Int,
    @SerializedName("dislikes")           val dislikes: Int,
    @SerializedName("satisfaction_rate")   val satisfactionRate: Float,
    @SerializedName("by_action")          val byAction: Map<String, ActionStats>?,
    @SerializedName("worst_performing")   val worstPerforming: List<String>?,
    @SerializedName("best_performing")    val bestPerforming: List<String>?
)

data class ActionStats(
    @SerializedName("likes")    val likes: Int,
    @SerializedName("dislikes") val dislikes: Int,
    @SerializedName("rate")     val rate: Float,
    @SerializedName("total")    val total: Int
)
