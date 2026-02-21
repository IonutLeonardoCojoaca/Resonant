package com.example.resonant.data.models

import android.os.Parcelable
import com.example.resonant.data.models.AudioAnalysis
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class Song(
    val id: String = "",
    val title: String = "",
    var duration: String? = null,
    var streams: Int = 0,
    var position: Int = 0,
    var fileName: String = "",
    var imageFileName: String? = null,
    var releaseYear: Int = 0,

    @SerializedName("songUrl")
    var url: String? = null,

    @SerializedName("imageUrl")
    var coverUrl: String? = null,

    @SerializedName("artists")
    val artists: List<ArtistSimpleDTO> = emptyList(),

    @SerializedName("album")
    val album: AlbumSimpleDTO? = null,

    @SerializedName("audioAnalysis")
    var audioAnalysis: AudioAnalysis? = null,

    var sizeBytes: Long = 0L,

    var artistName: String? = null

) : Parcelable