package com.example.resonant.data.models

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class Genre(
    val id: String = "",
    val name: String = "",
    val fileName: String? = null,

    @SerializedName("gradientColors")
    val gradientColors: String? = null
) : Parcelable