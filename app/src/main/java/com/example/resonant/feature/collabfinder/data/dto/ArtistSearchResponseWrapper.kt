package com.example.resonant.feature.collabfinder.data.dto

import com.google.gson.annotations.SerializedName

data class ArtistSearchResponseWrapper(
    @SerializedName("content") val content: List<ArtistSearchItemDto>?,
    @SerializedName("results") val results: List<ArtistSearchItemDto>?,
    @SerializedName("data") val data: List<ArtistSearchItemDto>?,
    @SerializedName("items") val items: List<ArtistSearchItemDto>?
) {
    fun getArtists(): List<ArtistSearchItemDto> {
        return content ?: results ?: data ?: items ?: emptyList()
    }
}
