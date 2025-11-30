package com.example.resonant.data.models

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class Artist(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    var fileName: String? = null,

    @SerializedName("imageUrl")
    var url: String? = null
) : Parcelable