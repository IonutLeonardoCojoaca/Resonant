package com.example.resonant.data.network

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ArtistDTO(
    val id: String,
    val name: String
): Parcelable