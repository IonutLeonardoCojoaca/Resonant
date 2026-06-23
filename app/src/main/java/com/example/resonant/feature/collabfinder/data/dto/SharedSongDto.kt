package com.example.resonant.feature.collabfinder.data.dto

import com.google.gson.annotations.SerializedName

data class SharedSongDto(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("albumId") val albumId: String?,
    @SerializedName("albumTitle") val albumTitle: String?,
    @SerializedName("albumCoverUrl") val albumCoverUrl: String?,
    @SerializedName("releaseYear") val releaseYear: Int?,
    @SerializedName("durationMs") val durationMs: Long?,
    @SerializedName("streams") val streams: Long?,
    @SerializedName("allArtists") val allArtists: List<SongArtistDto>
)

data class SongArtistDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String
)
