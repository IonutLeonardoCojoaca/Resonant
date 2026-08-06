package com.example.resonant.data.network

import com.example.resonant.data.models.Playlist
import com.example.resonant.data.models.Song

data class PlaylistTrackDTO(
    val playlistTrackId: String = "",
    val position: Int = 0,
    val song: Song = Song()
)

data class PlaylistOwnerDTO(
    val id: String = "",
    val name: String = ""
)

data class PlaylistDetailV2DTO(
    val playlist: Playlist = Playlist(name = ""),
    val revision: String = "",
    val tracks: List<PlaylistTrackDTO> = emptyList(),
    val owner: PlaylistOwnerDTO? = null,
    val canEdit: Boolean = false,
    val isSaved: Boolean = false
)

data class ReorderPlaylistTracksRequestDTO(
    val orderedPlaylistTrackIds: List<String>
)

data class ReorderPlaylistTracksResponseDTO(
    val revision: String = "",
    val tracks: List<PlaylistTrackDTO> = emptyList(),
    val playlist: Playlist? = null
)

data class SavedPlaylistsPageDTO(
    val items: List<Playlist> = emptyList(),
    val nextCursor: String? = null
)
