package com.example.resonant

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Artist(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    var fileName: String = ""
) : Parcelable
