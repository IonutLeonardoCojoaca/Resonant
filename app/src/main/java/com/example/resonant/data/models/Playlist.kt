package com.example.resonant.data.models

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class Playlist(
    val id: String? = null,
    val userId: String? = null,
    var name: String,
    val description: String,
    val numberOfTracks: Int,
    val duration: Int,

    @SerializedName("fileName")
    val fileName: String? = null,

    val isPublic: Boolean,

    @SerializedName("imageUrl")
    val imageUrl: String? = null,

    @SerializedName("songsHash")
    var songsHash: String? = null
) : Parcelable