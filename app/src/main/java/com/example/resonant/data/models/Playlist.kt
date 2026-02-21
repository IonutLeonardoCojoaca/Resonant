package com.example.resonant.data.models

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class Playlist(
    val id: String? = null,
    val userId: String? = null,
    var name: String,
    val description: String? = null,
    val numberOfTracks: Int = 0,
    val duration: Int = 0,

    @SerializedName("fileName")
    val fileName: String? = null,

    val isPublic: Boolean? = null,

    @SerializedName("imageUrl")
    val imageUrl: String? = null,

    @SerializedName("songsHash")
    var songsHash: String? = null,

    // Campo local no serializado: se rellena tras obtener info del usuario
    @Transient
    var ownerName: String? = null
) : Parcelable