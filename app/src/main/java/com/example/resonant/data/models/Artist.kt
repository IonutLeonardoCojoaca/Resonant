package com.example.resonant.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Artist(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    var fileName: String? = null,
    var url: String? = null
) : Parcelable