package com.example.resonant.data.network

import com.google.gson.annotations.SerializedName

data class AriaFeedbackRequest(
    @SerializedName("logId")   val logId: String,
    @SerializedName("rating")  val rating: Int,
    @SerializedName("comment") val comment: String? = null
)

data class AriaFeedbackResponse(
    @SerializedName("status") val status: String
)

/**
 * Optional song snapshot included in an Aria client-side action. It is useful for UI feedback,
 * but it is never trusted as playback metadata: Android always reloads the song by [songId].
 */
data class AriaActionSongDTO(
    @SerializedName("song_id") val songId: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("artist_names") val artistNames: String? = null,
    @SerializedName("album_id") val albumId: String? = null,
    @SerializedName("album_title") val albumTitle: String? = null,
    @SerializedName("duration_seconds") val durationSeconds: Int? = null,
    @SerializedName("release_year") val releaseYear: Int? = null,
    @SerializedName("streams") val streams: Int? = null,
    @SerializedName("selection_mode") val selectionMode: String? = null
)

data class AriaSongDisambiguationDTO(
    @SerializedName("choice_number") val choiceNumber: Int? = null,
    @SerializedName("song_id") val songId: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("artist_names") val artistNames: String? = null,
    @SerializedName("album_id") val albumId: String? = null,
    @SerializedName("album_title") val albumTitle: String? = null,
    @SerializedName("release_year") val releaseYear: Int? = null,
    @SerializedName("duration_seconds") val durationSeconds: Int? = null,
    @SerializedName("genres") val genres: List<String>? = null,
    @SerializedName("match_kind") val matchKind: String? = null
)

data class AriaActionContentDTO(
    @SerializedName("kind") val kind: String? = null,
    @SerializedName("songs") val songs: List<AriaSongDisambiguationDTO>? = null
)

/**
 * Typed part of an SSE `event: action` payload. All server additions are optional and Gson
 * deliberately ignores unknown JSON fields to retain backwards and forwards compatibility.
 */
data class AriaClientActionDTO(
    @SerializedName("type") val type: String? = null,
    @SerializedName("comando") val command: String? = null,
    @SerializedName("client_side") val clientSide: Boolean = false,
    @SerializedName(value = "action_id", alternate = ["actionId"])
    val actionId: String? = null,
    @SerializedName(value = "execution_status", alternate = ["executionStatus"])
    val executionStatus: String? = null,
    @SerializedName("verified") val verified: Boolean? = null,
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("pregunta") val question: String? = null,
    @SerializedName("content") val content: AriaActionContentDTO? = null,
    @SerializedName(value = "song_id", alternate = ["now_playing_song_id"])
    val songId: String? = null,
    @SerializedName(value = "nombre_cancion", alternate = ["title", "now_playing_title"])
    val songTitle: String? = null,
    @SerializedName(value = "nombre_artista", alternate = ["artist"])
    val songArtist: String? = null,
    @SerializedName("song") val song: AriaActionSongDTO? = null,
    @SerializedName("selection_mode") val selectionMode: String? = null,
    @SerializedName(value = "log_id", alternate = ["logId"])
    val logId: String? = null
)

data class AriaFeedbackStats(
    @SerializedName("total")              val total: Int,
    @SerializedName("likes")              val likes: Int,
    @SerializedName("dislikes")           val dislikes: Int,
    @SerializedName("satisfaction_rate")   val satisfactionRate: Float,
    @SerializedName("by_action")          val byAction: Map<String, ActionStats>?,
    @SerializedName("worst_performing")   val worstPerforming: List<String>?,
    @SerializedName("best_performing")    val bestPerforming: List<String>?
)

data class ActionStats(
    @SerializedName("likes")    val likes: Int,
    @SerializedName("dislikes") val dislikes: Int,
    @SerializedName("rate")     val rate: Float,
    @SerializedName("total")    val total: Int
)
