package com.example.resonant.data.network

import android.os.Parcelable
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.Song
import kotlinx.parcelize.Parcelize

data class UniversalSearchResponse(
    val query: String = "",
    val results: List<UniversalSearchHit> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val counts: Map<String, Int> = emptyMap()
)

@Parcelize
data class UniversalSearchHit(
    val type: String = "",
    val id: String = "",
    val title: String = "",
    val subtitle: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val score: Double = 0.0,
    val matchedOn: String = "title",
    val ownerId: String? = null,
    val ownerName: String? = null,
    val isPublic: Boolean? = null,
    val isOwnedByCurrentUser: Boolean = false,
    val trackCount: Int? = null,
    val duration: Int? = null,
    val gradientColors: String? = null,
    val isSystemPlaylist: Boolean = false,
    val song: Song? = null,
    val album: Album? = null,
    val artist: Artist? = null
) : Parcelable
