package com.example.resonant.data.models

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class Album(
    val id: String = "",
    val title: String? = "",
    var fileName: String? = null,
    val releaseYear: Int = 0,
    val numberOfTracks: Int = 0,
    val duration: Int = 0,

    @SerializedName("imageUrl")
    var url: String? = null,

    @SerializedName("artists")
    val artists: List<Artist> = emptyList(),

    var artistName: String? = null
) : Parcelable