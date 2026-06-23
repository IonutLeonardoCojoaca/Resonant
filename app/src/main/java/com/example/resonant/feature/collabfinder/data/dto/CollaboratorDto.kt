package com.example.resonant.feature.collabfinder.data.dto

import com.google.gson.annotations.SerializedName

data class CollaboratorDto(
    @SerializedName("artist") val artist: ArtistPreviewDto,
    @SerializedName("collaborationCount") val collaborationCount: Int,
    @SerializedName("firstCollabYear") val firstCollabYear: Int?,
    @SerializedName("lastCollabYear") val lastCollabYear: Int?,
    @SerializedName("topSongs") val topSongs: List<SharedSongPreviewDto>
)
