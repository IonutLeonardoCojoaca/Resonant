package com.example.resonant.feature.collabfinder.data.dto

import com.google.gson.annotations.SerializedName

data class CollabPathResponseDto(
    @SerializedName("found") val found: Boolean,
    @SerializedName("hops") val hops: Int,
    @SerializedName("path") val path: List<CollabPathStepDto>
)

data class CollabPathStepDto(
    @SerializedName("artist") val artist: ArtistPreviewDto,
    @SerializedName("connectedVia") val connectedVia: CollabPathSongDto?
)

data class CollabPathSongDto(
    @SerializedName("songId") val songId: String,
    @SerializedName("songTitle") val songTitle: String,
    @SerializedName("albumCoverUrl") val albumCoverUrl: String?,
    @SerializedName("releaseYear") val releaseYear: Int?
)
