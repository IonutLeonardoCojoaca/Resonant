package com.example.resonant

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Song(
    val id: String = "",
    val albumId: String = "",
    var artistName: String? = null,
    val title: String = "",
    var duration: String? = null,
    var streams: Int = 0,
    var position: Int = 0,
    var fileName: String = "",
    var imageFileName: String? = null, // <-- sigue siendo el nombre del archivo en Minio
    var url: String? = null,          // URL de audio
    var coverUrl: String? = null      // <-- NUEVO: URL prefirmada de la portada
) : Parcelable


