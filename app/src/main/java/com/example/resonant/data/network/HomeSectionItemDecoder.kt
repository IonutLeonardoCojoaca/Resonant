package com.example.resonant.data.network

import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.Song
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Compatibility boundary between the heterogeneous Home v2 payload and the
 * strongly typed models used by the Android UI.
 *
 * Home can contain either catalog objects directly or recommendation envelopes
 * (`{ "item": ... }`). A section is rejected as a whole when it contains
 * incomplete catalog metadata so the ViewModel can load only that section from
 * its legacy endpoint instead of rendering broken cards.
 */
class HomeSectionItemDecoder(
    private val gson: Gson = Gson()
) {

    fun decodeSongs(section: HomeSectionDTO): List<Song>? {
        val decoded = decodeAll(
            section = section,
            type = Song::class.java,
            envelopeKeys = SONG_ENVELOPE_KEYS,
            imageUrlAliases = SONG_IMAGE_URL_ALIASES
        ) { element, song ->
            val artistName = song.artistName
                ?.takeIf(String::isNotBlank)
                ?: song.artists
                    .map { it.name.trim() }
                    .filter(String::isNotBlank)
                    .joinToString(", ")
                    .takeIf(String::isNotBlank)
                ?: element.asObjectOrNull()?.findArtistName()

            song.apply { this.artistName = artistName }
        } ?: return null

        return decoded.takeIf { songs ->
            songs.all { song ->
                song.id.isNotBlank() &&
                    song.title.isNotBlank() &&
                    !song.artistName.isNullOrBlank()
            }
        }
    }

    fun decodeArtists(section: HomeSectionDTO): List<Artist>? {
        val decoded = decodeAll(
            section = section,
            type = Artist::class.java,
            envelopeKeys = ARTIST_ENVELOPE_KEYS,
            imageUrlAliases = CATALOG_IMAGE_URL_ALIASES
        ) { _, artist -> artist } ?: return null

        return decoded.takeIf { artists ->
            artists.all { artist ->
                artist.id.isNotBlank() && artist.name.isNotBlank()
            }
        }
    }

    fun decodeAlbums(section: HomeSectionDTO): List<Album>? {
        val decoded = decodeAll(
            section = section,
            type = Album::class.java,
            envelopeKeys = ALBUM_ENVELOPE_KEYS,
            imageUrlAliases = CATALOG_IMAGE_URL_ALIASES
        ) { element, album ->
            val artistName = album.artistName
                ?.takeIf(String::isNotBlank)
                ?: album.artists
                    .map { it.name.trim() }
                    .filter(String::isNotBlank)
                    .joinToString(", ")
                    .takeIf(String::isNotBlank)
                ?: element.asObjectOrNull()?.findArtistName()

            album.apply { this.artistName = artistName }
        } ?: return null

        return decoded.takeIf { albums ->
            albums.all { album ->
                album.id.isNotBlank() &&
                    !album.title.isNullOrBlank() &&
                    !album.artistName.isNullOrBlank()
            }
        }
    }

    private fun <T> decodeAll(
        section: HomeSectionDTO,
        type: Class<T>,
        envelopeKeys: List<String>,
        imageUrlAliases: List<String>,
        transform: (JsonElement, T) -> T
    ): List<T>? {
        if (section.items.isEmpty()) return emptyList()

        val decoded = section.items.mapNotNull { rawElement ->
            val catalogElement = rawElement
                .unwrapCatalogItem(envelopeKeys)
                .withImageUrlAlias(imageUrlAliases)
            runCatching {
                val item = gson.fromJson(catalogElement, type)
                transform(catalogElement, item)
            }.getOrNull()
        }

        return decoded.takeIf { it.size == section.items.size }
    }

    private fun JsonElement.unwrapCatalogItem(envelopeKeys: List<String>): JsonElement {
        var current = this
        repeat(MAX_ENVELOPE_DEPTH) {
            val objectValue = current.asObjectOrNull() ?: return current
            val nested = envelopeKeys
                .firstNotNullOfOrNull { key -> objectValue.get(key)?.takeUnless { it.isJsonNull } }
                ?: return current
            if (!nested.isJsonObject) return current
            current = nested
        }
        return current
    }

    private fun JsonElement.withImageUrlAlias(aliases: List<String>): JsonElement {
        val source = asObjectOrNull() ?: return this
        if (source.has("imageUrl")) return this

        val imageUrl = aliases
            .firstNotNullOfOrNull { key -> source.get(key)?.takeUnless { it.isJsonNull } }
            ?: return this

        return source.deepCopy().apply { add("imageUrl", imageUrl.deepCopy()) }
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? {
        return takeIf(JsonElement::isJsonObject)?.asJsonObject
    }

    private fun JsonObject.findArtistName(): String? {
        firstNonBlankString(
            "artistName",
            "artist",
            "artistNames",
            "primaryArtistName"
        )?.let { return it }

        listOf("artistNames", "artists").forEach { key ->
            val names = get(key)
                ?.takeIf(JsonElement::isJsonArray)
                ?.asJsonArray
                ?.mapNotNull { item ->
                    when {
                        item.isJsonPrimitive ->
                            item.asString.trim().takeIf(String::isNotBlank)
                        item.isJsonObject ->
                            item.asJsonObject.firstNonBlankString("name", "artistName")
                        else -> null
                    }
                }
                .orEmpty()
            if (names.isNotEmpty()) return names.joinToString(", ")
        }

        return listOf("artist", "primaryArtist").firstNotNullOfOrNull { key ->
            get(key)
                ?.takeIf(JsonElement::isJsonObject)
                ?.asJsonObject
                ?.firstNonBlankString("name", "artistName")
        }
    }

    private fun JsonObject.firstNonBlankString(vararg keys: String): String? {
        return keys.firstNotNullOfOrNull { key ->
            get(key)
                ?.takeIf(JsonElement::isJsonPrimitive)
                ?.asString
                ?.trim()
                ?.takeIf(String::isNotBlank)
        }
    }

    private companion object {
        const val MAX_ENVELOPE_DEPTH = 2
        val SONG_ENVELOPE_KEYS = listOf("item", "song", "data")
        val ARTIST_ENVELOPE_KEYS = listOf("item", "artist", "data")
        val ALBUM_ENVELOPE_KEYS = listOf("item", "album", "data")
        val SONG_IMAGE_URL_ALIASES = listOf("coverUrl", "image", "artworkUrl")
        val CATALOG_IMAGE_URL_ALIASES = listOf(
            "url",
            "coverUrl",
            "image",
            "artworkUrl"
        )
    }
}
