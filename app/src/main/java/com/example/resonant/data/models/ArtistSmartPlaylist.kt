package com.example.resonant.data.models

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class ArtistSmartPlaylist(
    @SerializedName("artistId") val artistId: String,
    @SerializedName("playlistType") val playlistType: String, // "Essentials", "Radio"
    @SerializedName("name") val name: String,
    @SerializedName("coverUrl") val coverUrl: String?,
    @SerializedName("description") val description: String,
    @SerializedName("songCount") val songCount: Int
) : Parcelable
