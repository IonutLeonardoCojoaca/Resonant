package com.example.resonant.explore

import com.example.resonant.data.network.ExploreResponseDTO
import com.example.resonant.data.network.ExploreSectionDTO
import com.google.gson.Gson

private val gson = Gson()

fun sectionJson(json: String): ExploreSectionDTO = gson.fromJson(json, ExploreSectionDTO::class.java)

fun responseJson(json: String): ExploreResponseDTO = gson.fromJson(json, ExploreResponseDTO::class.java)

fun songJson(id: String, title: String = "Song $id", artist: String = "Artist") =
    """{ "id": "$id", "title": "$title", "artistName": "$artist", "artists": [], "imageUrl": "u", "duration": 200 }"""

fun ariaPickJson(id: String, reason: String?, rank: Int): String {
    val reasonJson = reason?.let { "\"$it\"" } ?: "null"
    return """{ "id": "$id", "title": "Song $id", "artistName": "Artist", "artists": [], "imageUrl": "u",
               "reason": $reasonJson, "rank": $rank }"""
}

fun songsSection(id: String, vararg songIds: String) =
    """{ "id": "$id", "type": "songs", "title": "Title $id", "items": [ ${songIds.joinToString { songJson(it) }} ] }"""

fun pendingSection(id: String, type: String = "songs") =
    """{ "id": "$id", "type": "$type", "title": "Title $id", "items": null }"""

fun ariaSection(vararg picks: Pair<String, Int>) =
    """{ "id": "aria_picks", "type": "aria_picks", "title": "Aria ha elegido para ti",
         "items": [ ${picks.joinToString { (id, rank) -> ariaPickJson(id, "Motivo $id", rank) }} ] }"""

fun layoutJson(vararg sections: String, daypart: String = "evening") =
    """{ "localDate": "2026-10-12", "daypart": "$daypart", "isWeekend": false,
         "sections": [ ${sections.joinToString()} ] }"""
