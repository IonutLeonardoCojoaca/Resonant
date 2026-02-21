package com.example.resonant.data.models

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class ArtistSimpleDTO(
    val id: String = "",
    val name: String = "",
    @SerializedName("imageUrl")
    val url: String? = null
) : Parcelable {
    fun toArtist(): Artist {
        return Artist(
            id = this.id,
            name = this.name,
            url = this.url
        )
    }
}
