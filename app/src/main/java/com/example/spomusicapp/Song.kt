package com.example.spomusicapp

import com.google.gson.annotations.SerializedName

data class Song(
    @SerializedName("name")
    val title: String,
    val url: String,
    var artist: String? = null,
    val album: String? = null,
    var duration: String? = null,
    val localCoverPath: String? = null,
    var streams: Int = 0
)

