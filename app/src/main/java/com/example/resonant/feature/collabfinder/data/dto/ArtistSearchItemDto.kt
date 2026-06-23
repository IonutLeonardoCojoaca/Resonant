package com.example.resonant.feature.collabfinder.data.dto

import com.google.gson.annotations.SerializedName

data class ArtistSearchItemDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("imageUrl") val imageUrl: String?
)
