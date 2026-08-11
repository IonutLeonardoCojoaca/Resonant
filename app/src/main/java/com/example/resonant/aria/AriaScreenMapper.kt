package com.example.resonant.aria

import com.example.resonant.R

object AriaScreenMapper {
    fun fromDestinationId(destinationId: Int): String? = when (destinationId) {
        R.id.homeFragment -> "home"
        R.id.searchFragment,
        R.id.exploreFragment -> "search"
        R.id.savedFragment,
        R.id.downloadedSongsFragment -> "library"
        R.id.songFragment -> "player"
        R.id.playlistFragment,
        R.id.editPlaylistFragment -> "playlist_detail"
        R.id.artistFragment,
        R.id.detailedArtistFragment,
        R.id.artistSmartPlaylistFragment -> "artist_detail"
        R.id.albumFragment,
        R.id.detailedAlbumFragment -> "album_detail"
        R.id.detailedSongFragment -> "song_detail"
        R.id.ariaFragment,
        R.id.ariaInfoFragment -> "aria_chat"
        R.id.settingsFragment -> "settings"
        R.id.topChartsFragment,
        R.id.topArtistsFragment,
        R.id.topAlbumsFragment,
        R.id.historyFragment -> "stats"
        else -> null
    }

    fun isAriaDestination(destinationId: Int): Boolean =
        destinationId == R.id.ariaFragment || destinationId == R.id.ariaInfoFragment
}
