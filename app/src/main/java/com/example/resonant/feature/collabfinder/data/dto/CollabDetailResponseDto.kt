package com.example.resonant.feature.collabfinder.data.dto

import com.google.gson.annotations.SerializedName

data class CollabDetailResponseDto(
    @SerializedName("artistA") val artistA: ArtistPreviewDto,
    @SerializedName("artistB") val artistB: ArtistPreviewDto,
    @SerializedName("collaborationCount") val collaborationCount: Int,
    @SerializedName("firstCollabYear") val firstCollabYear: Int?,
    @SerializedName("lastCollabYear") val lastCollabYear: Int?,
    @SerializedName("collabScore") val collabScore: Int,
    @SerializedName("songs") val songs: List<SharedSongDto>,
    @SerializedName("totalElements") val totalElements: Int,
    @SerializedName("totalPages") val totalPages: Int
)
