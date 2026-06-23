package com.example.resonant.feature.collabfinder.data.dto

import com.google.gson.annotations.SerializedName

data class CollabSummaryDto(
    @SerializedName("totalCollaborators") val totalCollaborators: Int,
    @SerializedName("totalSharedSongs") val totalSharedSongs: Int,
    @SerializedName("firstCollabYear") val firstCollabYear: Int?,
    @SerializedName("lastCollabYear") val lastCollabYear: Int?,
    @SerializedName("topGenresInCollabs") val topGenresInCollabs: List<String>
)
