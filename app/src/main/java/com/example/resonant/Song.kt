package com.example.resonant

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
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
    var coverUrl: String? = null,      // <-- NUEVO: URL prefirmada de la portada

    // --- ¡¡¡AQUÍ ESTÁ EL CAMBIO MÁS IMPORTANTE!!! ---
    // Añade esta línea. El nombre "artists" debe coincidir con el del DTO en .NET
    @SerializedName("artists")
    val artists: List<Artist> = emptyList()

) : Parcelable


