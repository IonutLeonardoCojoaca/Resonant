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

    var artistName: String? = null,

    @SerializedName("playCount")
    val playCount: Int? = null,

    @SerializedName("positionChange")
    val positionChange: Int? = null,

    @SerializedName(value = "playbackMimeType", alternate = ["mimeType"])
    var playbackMimeType: String? = null,

    var playbackExpiresAtUtc: String? = null,

    var playbackStreamId: String? = null,

    var playbackDeliveryMode: String? = null,

    var playbackQuality: String? = null,

    var playbackContentLength: Long? = null,

    var playbackSupportsRanges: Boolean? = null,

    // Solo presente en la respuesta de GET api/songs/favorites — momento
    // (ISO-8601 UTC) en que el usuario marcó la canción como favorita.
    @SerializedName("favoritedAt")
    var favoritedAt: String? = null

) : Parcelable
