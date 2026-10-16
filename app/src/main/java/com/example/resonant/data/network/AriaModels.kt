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
    @SerializedName("songs") val songs: List<AriaSongDisambiguationDTO>? = null,
    // content.kind = "streak" (same fields as the action's `streak`)
    @SerializedName("current_days") val currentDays: Int? = null,
    @SerializedName("longest_days") val longestDays: Int? = null,
    @SerializedName("today_qualified") val todayQualified: Boolean? = null,
    @SerializedName("today_seconds") val todaySeconds: Int? = null,
    @SerializedName("goal_seconds") val goalSeconds: Int? = null,
    @SerializedName("freezes_available") val freezesAvailable: Int? = null,
    @SerializedName("next_milestone") val nextMilestone: Int? = null,
    // content.kind = "pick_explanation"
    @SerializedName("song") val song: AriaPickSongDTO? = null,
    @SerializedName("source") val source: String? = null,
    @SerializedName("reason") val reason: String? = null,
    /** Already formatted sentences for the card chips. */
    @SerializedName("evidence") val evidence: List<String>? = null
)

/** `streak` of a `streak_status` action (add-aria-context-v2). */
data class AriaStreakDTO(
    @SerializedName("current_days") val currentDays: Int? = null,
    @SerializedName("longest_days") val longestDays: Int? = null,
    @SerializedName("today_qualified") val todayQualified: Boolean? = null,
    @SerializedName("today_seconds") val todaySeconds: Int? = null,
    @SerializedName("goal_seconds") val goalSeconds: Int? = null,
    @SerializedName("freezes_available") val freezesAvailable: Int? = null,
    @SerializedName("next_milestone") val nextMilestone: Int? = null
)

/** Song of a `pick_explanation` card. */
data class AriaPickSongDTO(
    @SerializedName("id") val id: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("artist") val artist: String? = null,
    @SerializedName("cover_url") val coverUrl: String? = null
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
    val logId: String? = null,
    @SerializedName(value = "client_artist_id")
    val clientArtistId: String? = null,
    @SerializedName(value = "client_artist_name")
    val clientArtistName: String? = null,
    /** Present on transport orders given inside a Unisound room: they go to the room. */
    @SerializedName("unisound_session_id")
    val unisoundSessionId: String? = null,
    // add-aria-context-v2: Live, navigation, streak, explain_pick and Flow feedback.
    @SerializedName("live_channel_slug") val liveChannelSlug: String? = null,
    @SerializedName("live_channel_name") val liveChannelName: String? = null,
    /** `open_screen`: explore, streak, live_channel, unisound_room, aria_picks, stats or recap. */
    @SerializedName("destination") val destination: String? = null,
    @SerializedName("streak") val streak: AriaStreakDTO? = null,
    /** Raw facts of `explain_pick` (`{type, ...}`); the card uses `content.evidence`. */
    @SerializedName("evidence") val evidence: List<Map<String, Any?>>? = null,
    @SerializedName("target_type") val targetType: String? = null,
    @SerializedName("target_name") val targetName: String? = null,
    /** `flow_feedback`: less, never or undone. */
    @SerializedName("feedback") val feedback: String? = null,
    @SerializedName("source") val source: String? = null,
    @SerializedName("reason") val reason: String? = null,
    @SerializedName("suggested_followups") val suggestedFollowups: List<String>? = null
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
