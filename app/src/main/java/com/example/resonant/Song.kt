package com.example.resonant

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class Song(
    val id: String = "",
    val albumId: String = "",
    var artistName: String? = null,
    val title: String = "",
    var duration: String? = null,
    var streams: Int = 0,
    var position: Int = 0,
    var fileName: String = "",
    var imageFileName: String? = null,
    var url: String? = null,
    var coverUrl: String? = null,

    @SerializedName("artists")
    val artists: List<ArtistDTO> = emptyList(),

    @SerializedName("audioAnalysis")
    val audioAnalysis: AudioAnalysis? = null
) : Parcelable


