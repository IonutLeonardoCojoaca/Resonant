package com.example.resonant.data.models

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class AudioAnalysis(
    val id: String = "",

    val songId: String = "",

    @SerializedName("durationMs")
    val durationMs: Int = 0,

    @SerializedName("audioStartMs")
    val audioStartMs: Int = 0,

    @SerializedName("audioEndMs")
    val audioEndMs: Int = 0,

    @SerializedName("loudnessLufs")
    val loudnessLufs: Float = 0f,

    @SerializedName("bpm")
    val bpm: Double = 0.0,

    @SerializedName("bpmNormalized")
    val bpmNormalized: Double = 0.0,

    @SerializedName("beatGridJson")
    val beatGridJson: List<Int> = emptyList(), // Lista de timestamps de los beats

    @SerializedName("optimalStartPointMs")
    val optimalStartPointMs: Int? = null, // Punto óptimo de entrada para mezclas

    @SerializedName("optimalExitPointMs")
    val optimalExitPointMs: Int? = null, // Punto óptimo de salida para mezclas

    @SerializedName("musicalKey")
    val musicalKey: String? = null // Tonalidad de la canción (ej. "Am", "C")

) : Parcelable