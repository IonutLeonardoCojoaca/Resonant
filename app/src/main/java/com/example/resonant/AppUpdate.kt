package com.example.resonant

import com.google.gson.annotations.SerializedName

data class AppUpdate(
    @SerializedName(value = "Id", alternate = ["id"])
    val id: String? = null,

    @SerializedName(value = "FileName", alternate = ["fileName"])
    val fileName: String? = null,

    @SerializedName(value = "Version", alternate = ["version"])
    val version: String,

    @SerializedName(value = "Platform", alternate = ["platform"])
    val platform: String,

    @SerializedName(value = "Title", alternate = ["title"])
    val title: String? = null,

    @SerializedName(value = "Description", alternate = ["description"])
    val description: String? = null,

    @SerializedName(value = "ReleaseDate", alternate = ["releaseDate"])
    val releaseDate: String? = null,

    @SerializedName(value = "ForceUpdate", alternate = ["forceUpdate"])
    val forceUpdate: Boolean = false,

    @SerializedName(value = "Status", alternate = ["status"])
    val status: String? = null
)