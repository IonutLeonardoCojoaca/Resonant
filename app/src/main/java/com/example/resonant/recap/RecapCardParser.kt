package com.example.resonant.recap

import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.HomeSectionDTO
import com.example.resonant.data.network.HomeSectionItemDecoder
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Tarjeta del resumen mensual ya interpretada. El orden lo marca el servidor. */
sealed class RecapCard {
    abstract val type: String

    data class Minutes(val minutes: Int, val previousMinutes: Int?, val changePercent: Int?) : RecapCard() {
        override val type = RecapCard.TYPE_MINUTES
    }

    data class RankedSong(val rank: Int, val plays: Int, val minutes: Int, val song: Song)

    data class TopSongs(val songs: List<RankedSong>) : RecapCard() {
        override val type = RecapCard.TYPE_TOP_SONGS
    }

    data class RankedArtist(val rank: Int, val plays: Int, val minutes: Int, val artist: Artist)

    data class TopArtists(val artists: List<RankedArtist>, val newArtist: RankedArtist?) : RecapCard() {
        override val type = RecapCard.TYPE_TOP_ARTISTS
    }

    data class GenreShare(
        val id: String,
        val name: String,
        val gradientColors: String?,
        val share: Double,
        val previousShare: Double?
    )

    data class Genres(val top: GenreShare, val rising: GenreShare?) : RecapCard() {
        override val type = RecapCard.TYPE_GENRES
    }

    /** [slot]: `dawn`, `morning`, `afternoon` o `night`. */
    data class Moment(val slot: String, val share: Double) : RecapCard() {
        override val type = RecapCard.TYPE_MOMENT
    }

    data class Streak(val longestStreakDays: Int, val daysListened: Int, val daysInMonth: Int) : RecapCard() {
        override val type = RecapCard.TYPE_STREAK
    }

    data class Unisound(val sessions: Int, val people: Int) : RecapCard() {
        override val type = RecapCard.TYPE_UNISOUND
    }

    data class Aria(val text: String, val fromAria: Boolean) : RecapCard() {
        override val type = RecapCard.TYPE_ARIA
    }

    data class Playlist(val name: String, val songCount: Int, val playlistId: String?) : RecapCard() {
        override val type = RecapCard.TYPE_PLAYLIST
    }

    companion object {
        const val TYPE_MINUTES = "minutes"
        const val TYPE_TOP_SONGS = "top_songs"
        const val TYPE_TOP_ARTISTS = "top_artists"
        const val TYPE_GENRES = "genres"
        const val TYPE_MOMENT = "moment"
        const val TYPE_STREAK = "streak"
        const val TYPE_UNISOUND = "unisound"
        const val TYPE_ARIA = "aria"
        const val TYPE_PLAYLIST = "playlist"
    }
}

/**
 * JSON de `cards` → [RecapCard]. Los tipos desconocidos y las tarjetas rotas se descartan sin error
 * (el servidor puede añadir tipos sin versionar). Las canciones y artistas usan [HomeSectionItemDecoder],
 * el mismo que Inicio y Explorar.
 */
class RecapCardParser(private val decoder: HomeSectionItemDecoder = HomeSectionItemDecoder()) {

    fun parse(cards: List<JsonElement>?): List<RecapCard> =
        cards.orEmpty().mapNotNull { element -> runCatching { parseCard(element) }.getOrNull() }

    private fun parseCard(element: JsonElement): RecapCard? {
        val card = element.asObjectOrNull() ?: return null
        return when (card.string("type")) {
            RecapCard.TYPE_MINUTES -> RecapCard.Minutes(
                minutes = card.int("minutes") ?: return null,
                previousMinutes = card.int("previousMinutes"),
                changePercent = card.int("changePercent")
            )
            RecapCard.TYPE_TOP_SONGS -> card.array("songs").mapNotNull(::rankedSong)
                .takeIf { it.isNotEmpty() }?.let(RecapCard::TopSongs)
            RecapCard.TYPE_TOP_ARTISTS -> {
                val artists = card.array("artists").mapNotNull(::rankedArtist)
                val newArtist = card.get("newArtist")?.let(::rankedArtist)
                if (artists.isEmpty() && newArtist == null) null else RecapCard.TopArtists(artists, newArtist)
            }
            RecapCard.TYPE_GENRES -> {
                val top = card.get("top")?.let(::genre) ?: return null
                RecapCard.Genres(top, card.get("rising")?.let(::genre))
            }
            RecapCard.TYPE_MOMENT -> RecapCard.Moment(
                slot = card.string("slot")?.takeIf { it in RecapTexts.SLOTS } ?: return null,
                share = card.double("share") ?: return null
            )
            RecapCard.TYPE_STREAK -> RecapCard.Streak(
                longestStreakDays = card.int("longestStreakDays") ?: return null,
                daysListened = card.int("daysListened") ?: return null,
                daysInMonth = card.int("daysInMonth") ?: return null
            )
            RecapCard.TYPE_UNISOUND -> RecapCard.Unisound(
                sessions = card.int("sessions")?.takeIf { it > 0 } ?: return null,
                people = card.int("people") ?: 0
            )
            RecapCard.TYPE_ARIA -> RecapCard.Aria(
                text = card.string("text")?.takeIf(String::isNotBlank) ?: return null,
                fromAria = card.string("origin") != ORIGIN_TEMPLATE
            )
            RecapCard.TYPE_PLAYLIST -> RecapCard.Playlist(
                name = card.string("name")?.takeIf(String::isNotBlank) ?: return null,
                songCount = card.int("songCount") ?: 0,
                playlistId = card.string("playlistId")?.takeIf(String::isNotBlank)
            )
            else -> null
        }
    }

    private fun rankedSong(element: JsonElement): RecapCard.RankedSong? {
        val item = element.asObjectOrNull() ?: return null
        val songJson = item.get("song")?.takeIf { it.isJsonObject } ?: return null
        val song = decoder.decodeSongs(HomeSectionDTO(items = listOf(songJson)))?.firstOrNull() ?: return null
        return RecapCard.RankedSong(item.int("rank") ?: return null, item.int("plays") ?: 0, item.int("minutes") ?: 0, song)
    }

    private fun rankedArtist(element: JsonElement): RecapCard.RankedArtist? {
        val item = element.asObjectOrNull() ?: return null
        val artistJson = item.get("artist")?.takeIf { it.isJsonObject } ?: return null
        val artist = decoder.decodeArtists(HomeSectionDTO(items = listOf(artistJson)))?.firstOrNull() ?: return null
        return RecapCard.RankedArtist(item.int("rank") ?: 0, item.int("plays") ?: 0, item.int("minutes") ?: 0, artist)
    }

    private fun genre(element: JsonElement): RecapCard.GenreShare? {
        val item = element.asObjectOrNull() ?: return null
        return RecapCard.GenreShare(
            id = item.string("id") ?: return null,
            name = item.string("name")?.takeIf(String::isNotBlank) ?: return null,
            gradientColors = item.string("gradientColors"),
            share = item.double("share") ?: return null,
            previousShare = item.double("previousShare")
        )
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun JsonObject.primitive(name: String) =
        get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive

    private fun JsonObject.string(name: String): String? = primitive(name)?.asString

    private fun JsonObject.int(name: String): Int? =
        primitive(name)?.takeIf { it.isNumber }?.asInt

    private fun JsonObject.double(name: String): Double? =
        primitive(name)?.takeIf { it.isNumber }?.asDouble

    private fun JsonObject.array(name: String): List<JsonElement> =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()

    companion object {
        const val ORIGIN_TEMPLATE = "template"
    }
}
