package com.example.resonant.feature.collabfinder.data.dto

import com.google.gson.annotations.SerializedName

data class CollabFinderResponseDto(
    @SerializedName("artist") val artist: ArtistPreviewDto,
    @SerializedName("summary") val summary: CollabSummaryDto,
    @SerializedName("collaborators") val collaborators: List<CollaboratorDto>,
    @SerializedName("page") val page: Int,
    @SerializedName("size") val size: Int,
    @SerializedName("totalElements") val totalElements: Int,
    @SerializedName("totalPages") val totalPages: Int
)
