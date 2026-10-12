package com.example.resonant.explore

import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.Genre
import com.example.resonant.data.models.Playlist
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.AriaPickItemDTO
import com.example.resonant.data.network.ExplorePlaylistItemDTO
import com.example.resonant.data.network.ExploreSectionDTO
import com.example.resonant.data.network.HomeSectionDTO
import com.example.resonant.data.network.HomeSectionItemDecoder
import com.google.gson.Gson
import com.google.gson.JsonElement

data class AriaPick(
    val song: Song,
    val reason: String,
    val rank: Int
)

/**
 * A renderable Explore section. [items] is null while the section is pending (it arrived with
 * `items: null` and is loaded later through `api/v2/explore/sections/{id}`).
 */
sealed class ExploreSectionUi {
    abstract val id: String
    abstract val title: String
    abstract val subtitle: String?
    abstract val items: List<Any>?

    val isPending: Boolean get() = items == null

    data class Songs(
        override val id: String, override val title: String, override val subtitle: String?,
        override val items: List<Song>?
    ) : ExploreSectionUi()

    data class Albums(
        override val id: String, override val title: String, override val subtitle: String?,
        override val items: List<Album>?
    ) : ExploreSectionUi()

    data class Artists(
        override val id: String, override val title: String, override val subtitle: String?,
        override val items: List<Artist>?
    ) : ExploreSectionUi()

    data class Playlists(
        override val id: String, override val title: String, override val subtitle: String?,
        override val items: List<Playlist>?
    ) : ExploreSectionUi()

    data class Genres(
        override val id: String, override val title: String, override val subtitle: String?,
        override val items: List<Genre>?
    ) : ExploreSectionUi()

    data class CollabFinder(
        override val id: String, override val title: String, override val subtitle: String?,
        override val items: List<Artist>?
    ) : ExploreSectionUi()

    data class AriaPicks(
        override val id: String, override val title: String, override val subtitle: String?,
        override val items: List<AriaPick>?
    ) : ExploreSectionUi()
}

/**
 * Maps an Explore v2 section to [ExploreSectionUi]. Returns null when the section must not be
 * shown: unknown `type`, `error`, `items: []`, or an item with incomplete metadata (same
 * all-or-nothing rule as Home, so the screen never renders a broken card).
 */
class ExploreSectionMapper(
    private val decoder: HomeSectionItemDecoder = HomeSectionItemDecoder(),
    private val gson: Gson = Gson()
) {

    fun map(section: ExploreSectionDTO): ExploreSectionUi? {
        if (section.error != null || section.id.isBlank()) return null
        val raw = section.items
        if (raw != null && raw.isEmpty()) return null

        val id = section.id
        val title = section.title
        val subtitle = ExploreReasonFormatter.format(section.reason, title)
        return when {
            section.type == TYPE_SONGS ->
                ExploreSectionUi.Songs(id, title, subtitle, raw?.let { decodeSongs(it) ?: return null })
            section.type == TYPE_ALBUMS ->
                ExploreSectionUi.Albums(id, title, subtitle, raw?.let { decoder.decodeAlbums(home(it)) ?: return null })
            section.type == TYPE_ARTISTS && id == ID_COLLAB_FINDER ->
                ExploreSectionUi.CollabFinder(id, title, subtitle, raw?.let { decoder.decodeArtists(home(it)) ?: return null })
            section.type == TYPE_ARTISTS ->
                ExploreSectionUi.Artists(id, title, subtitle, raw?.let { decoder.decodeArtists(home(it)) ?: return null })
            section.type == TYPE_PLAYLISTS ->
                ExploreSectionUi.Playlists(id, title, subtitle, raw?.let { decodePlaylists(it) ?: return null })
            section.type == TYPE_GENRES ->
                ExploreSectionUi.Genres(id, title, subtitle, raw?.let { decodeGenres(it) ?: return null })
            section.type == TYPE_ARIA_PICKS ->
                ExploreSectionUi.AriaPicks(id, title, subtitle, raw?.let { decodeAriaPicks(it) ?: return null })
            else -> null
        }
    }

    private fun home(items: List<JsonElement>) = HomeSectionDTO(items = items)

    private fun decodeSongs(items: List<JsonElement>): List<Song>? =
        decoder.decodeSongs(home(items))?.takeIf { it.isNotEmpty() }

    private fun decodePlaylists(items: List<JsonElement>): List<Playlist>? {
        val playlists = items.map { element ->
            val dto = runCatching { gson.fromJson(element, ExplorePlaylistItemDTO::class.java) }.getOrNull()
            val playlistId = dto?.id?.takeIf(String::isNotBlank) ?: return null
            val name = dto.name?.takeIf(String::isNotBlank) ?: return null
            // ownerName is @Transient in Playlist, so Gson never fills it: copy it by hand.
            Playlist(
                id = playlistId,
                name = name,
                numberOfTracks = dto.numberOfTracks,
                imageUrl = dto.imageUrl,
                isSystemPlaylist = dto.isSystemPlaylist
            ).apply { ownerName = dto.ownerName }
        }
        return playlists.takeIf { it.isNotEmpty() }
    }

    private fun decodeGenres(items: List<JsonElement>): List<Genre>? {
        val genres = items.map { element ->
            val genre = runCatching { gson.fromJson(element, Genre::class.java) }.getOrNull() ?: return null
            // Gson bypasses Kotlin defaults, so missing fields arrive as null.
            val genreId: String? = genre.id
            val name: String? = genre.name
            if (genreId.isNullOrBlank() || name.isNullOrBlank()) return null
            genre
        }
        return genres.takeIf { it.isNotEmpty() }
    }

    private fun decodeAriaPicks(items: List<JsonElement>): List<AriaPick>? {
        val withReason = items.mapNotNull { element ->
            val extras = runCatching { gson.fromJson(element, AriaPickItemDTO::class.java) }.getOrNull()
            val reason = extras?.reason?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            Triple(element, reason, extras.rank)
        }
        if (withReason.isEmpty()) return null
        val songs = decoder.decodeSongs(home(withReason.map { it.first })) ?: return null
        return songs.zip(withReason) { song, (_, reason, rank) -> AriaPick(song, reason, rank) }
            .sortedBy { it.rank }
    }

    companion object {
        const val TYPE_SONGS = "songs"
        const val TYPE_ALBUMS = "albums"
        const val TYPE_ARTISTS = "artists"
        const val TYPE_PLAYLISTS = "playlists"
        const val TYPE_GENRES = "genres"
        const val TYPE_ARIA_PICKS = "aria_picks"
        const val ID_COLLAB_FINDER = "collab_finder"
    }
}
