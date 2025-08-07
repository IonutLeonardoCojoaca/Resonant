package com.example.resonant

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Album(
    val id: String = "",
    val title: String? = "",
    var fileName: String = "",
    val releaseYear: Int,
    val numberOfTracks: Int = 0,
    val duration: Int = 0,
    var url: String? = null,
    var artistName: String? = null
) : Parcelable
