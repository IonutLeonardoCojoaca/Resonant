package com.example.resonant.data.network

import com.google.gson.annotations.SerializedName

data class LyricsResponse(

    @SerializedName("songId")
    val songId: String,

    @SerializedName("source")
    val source: String?,

    @SerializedName("hasSync")
    val hasSync: Boolean,

    @SerializedName("language")
    val language: String?,

    @SerializedName("lyricsUrl")
    val lyricsUrl: String?
)
