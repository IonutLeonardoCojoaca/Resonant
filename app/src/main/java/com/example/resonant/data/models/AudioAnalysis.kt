package com.example.resonant.data.models

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue

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

    @SerializedName("optimalStartPointMs")
    val optimalStartPointMs: Int? = null,

    @SerializedName("optimalExitPointMs")
    val optimalExitPointMs: Int? = null,

    @SerializedName("musicalKey")
    val musicalKey: String? = null,

    @SerializedName("sectionsJson")
    val sectionsJson: @RawValue Any? = null,

    @SerializedName("segmentsUrl")
    val segmentsUrl: String? = null,

    @SerializedName("segmentsFileName")
    val segmentsFileName: String? = null

) : Parcelable