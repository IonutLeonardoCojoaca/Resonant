package com.example.resonant.data.models

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class AlbumSimpleDTO(
    val id: String = "",
    val title: String = "",
    @SerializedName("imageUrl")
    val url: String? = null
) : Parcelable {
    fun toAlbum(): Album {
        return Album(
            id = this.id,
            title = this.title,
            url = this.url
        )
    }
}
