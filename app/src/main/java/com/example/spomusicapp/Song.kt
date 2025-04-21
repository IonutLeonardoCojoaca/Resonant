package com.example.spomusicapp

import com.google.gson.annotations.SerializedName

data class Song(
    @SerializedName("name")
    val title: String,
    val url: String
)

