package com.example.resonant.data.network

import com.google.gson.JsonElement

/**
 * Explore v2 contract (`GET api/v2/explore` and `GET api/v2/explore/sections/{id}`), camelCase as
 * served by the backend. Items stay raw because each section `type` decodes them differently
 * (see `explore/ExploreSectionMapper`).
 */
data class ExploreResponseDTO(
    val generatedAtUtc: String = "",
    val localDate: String = "",
    /** `morning`, `afternoon`, `evening` or `night`. */
    val daypart: String = "",
    val isWeekend: Boolean = false,
    val sections: List<ExploreSectionDTO>? = null
)

data class ExploreSectionDTO(
    val id: String = "",
    val type: String = "",
    val title: String = "",
    val reason: ExploreReasonDTO? = null,
    /** Null when the section is loaded later through `api/v2/explore/sections/{id}`. */
    val items: List<JsonElement>? = null,
    val error: ApiErrorDTO? = null
)

data class ExploreReasonDTO(
    val code: String = "",
    val args: Map<String, JsonElement>? = null
)

/** Item of the `community_playlists` section; `ownerName` comes resolved by the server. */
data class ExplorePlaylistItemDTO(
    val id: String? = null,
    val name: String? = null,
    val imageUrl: String? = null,
    val numberOfTracks: Int = 0,
    val isSystemPlaylist: Boolean = false,
    val ownerName: String? = null
)

/** Extra fields of an `aria_picks` item; the rest of the item is a regular song. */
data class AriaPickItemDTO(
    val reason: String? = null,
    val rank: Int = 0
)

/** Body of `POST api/recommendations/aria/{songId}/feedback`: `liked` or `dismissed`. */
data class AriaPickFeedbackRequest(
    val feedback: String
)
