package com.example.resonant.ui.bottomsheets

import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.navigation.findNavController
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.resonant.R
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.Genre
import com.example.resonant.data.models.Playlist
import com.example.resonant.data.models.Song
import com.example.resonant.managers.PlaylistManager
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.viewmodels.DownloadViewModel
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import kotlinx.coroutines.launch

/**
 * Hojas de opciones ya cableadas para cualquier pantalla: la misma acción que el botón ⋯ y que
 * mantener pulsado un elemento. Navega por id de destino del grafo (vale desde cualquier
 * fragmento) y usa los ViewModels de actividad para favoritos y descargas.
 */
object ItemOptions {

    // ─── Canción ──────────────────────────────────────────────

    fun song(
        fragment: Fragment,
        song: Song,
        playlistId: String? = null,
        onRemoveFromPlaylist: ((Song, String) -> Unit)? = null
    ) {
        if (!fragment.isAdded) return
        val activity = fragment.requireActivity()
        val favorites = ViewModelProvider(activity)[FavoritesViewModel::class.java]
        val downloads = ViewModelProvider(activity)[DownloadViewModel::class.java]
        if (song.artistName.isNullOrBlank()) {
            song.artistName = song.artists.joinToString(", ") { it.name }.ifBlank { "Desconocido" }
        }
        SongOptionsBottomSheet(
            song = song,
            onSeeSongClick = { openSong(fragment, it) },
            onFavoriteToggled = { favorites.toggleFavoriteSong(it) },
            playlistId = playlistId,
            onRemoveFromPlaylistClick = onRemoveFromPlaylist,
            onAddToPlaylistClick = { selected ->
                SelectPlaylistBottomSheet(
                    song = selected,
                    onNoPlaylistsFound = { navigate(fragment, R.id.createPlaylistFragment) }
                ).show(fragment.parentFragmentManager, "SelectPlaylistBottomSheet")
            },
            onDownloadClick = { downloads.downloadSong(it) },
            onRemoveDownloadClick = { downloads.deleteSong(it) },
            onGoToAlbumClick = { albumId -> openAlbum(fragment, albumId) },
            onGoToArtistClick = { openArtist(fragment, it) },
            onAddToPlaymixClick = { selected ->
                SelectPlaymixBottomSheet(
                    song = selected,
                    onNoPlaymixesFound = { navigate(fragment, R.id.playmixListFragment) }
                ).show(fragment.parentFragmentManager, "SelectPlaymixBottomSheet")
            }
        ).show(fragment.parentFragmentManager, "SongOptionsBottomSheet")
    }

    // ─── Álbum y artista ──────────────────────────────────────

    fun album(fragment: Fragment, album: Album) {
        if (!fragment.isAdded) return
        AlbumOptionsBottomSheet(
            album = album,
            onGoToAlbumClick = { openAlbum(fragment, it.id) },
            onGoToArtistClick = { pickArtist(fragment, it.artists) },
            onViewDetailsClick = {
                navigate(fragment, R.id.detailedAlbumFragment, Bundle().apply {
                    putParcelable("album", it)
                    putString("albumId", it.id)
                })
            }
        ).show(fragment.parentFragmentManager, "AlbumOptionsBottomSheet")
    }

    fun artist(fragment: Fragment, artist: Artist) {
        if (!fragment.isAdded) return
        ArtistOptionsBottomSheet(
            artist = artist,
            onGoToArtistClick = { openArtist(fragment, it) },
            onViewDetailsClick = {
                navigate(fragment, R.id.detailedArtistFragment, Bundle().apply {
                    putParcelable("artist", it)
                    putString("artistId", it.id)
                })
            }
        ).show(fragment.parentFragmentManager, "ArtistOptionsBottomSheet")
    }

    // ─── Sin hoja propia: listas públicas, géneros, mixes ─────

    /** Lista de otro usuario o de Resonant (las propias usan PlaylistOptionsBottomSheet). */
    fun playlist(fragment: Fragment, playlist: Playlist) {
        if (!fragment.isAdded || playlist.id.isNullOrBlank()) return
        val tracks = playlist.numberOfTracks
        QuickOptionsBottomSheet(
            title = playlist.name,
            subtitle = if (tracks == 1) "Lista · 1 canción" else "Lista · $tracks canciones",
            imageUrl = playlist.imageUrl,
            placeholder = R.drawable.ic_playlist_stack,
            options = listOf(
                QuickOptionsBottomSheet.Option(R.drawable.ic_play, "Reproducir") { playPlaylist(fragment, playlist) },
                QuickOptionsBottomSheet.Option(R.drawable.ic_queue_list, "Abrir lista") { openPlaylist(fragment, playlist) }
            )
        ).show(fragment.parentFragmentManager, "PlaylistQuickOptions")
    }

    fun genre(fragment: Fragment, genre: Genre) {
        if (!fragment.isAdded) return
        QuickOptionsBottomSheet(
            title = genre.name,
            subtitle = "Género",
            imageUrl = null,
            placeholder = R.drawable.ic_disc,
            options = listOf(
                QuickOptionsBottomSheet.Option(R.drawable.ic_artist_person, "Ver artistas del género") {
                    navigate(fragment, R.id.genreArtistsFragment, Bundle().apply {
                        putString("genreId", genre.id)
                        putString("genreName", genre.name)
                        putString("genreGradientColors", genre.gradientColors)
                    })
                }
            )
        ).show(fragment.parentFragmentManager, "GenreQuickOptions")
    }

    /** Mix o lista inteligente de un artista ("Esto es…", radio): abrirla o ir al artista. */
    fun artistMix(fragment: Fragment, title: String, subtitle: String, imageUrl: String?, artist: Artist, onOpen: () -> Unit) {
        if (!fragment.isAdded) return
        QuickOptionsBottomSheet(
            title = title,
            subtitle = subtitle,
            imageUrl = imageUrl,
            placeholder = R.drawable.ic_playlist_stack,
            options = listOf(
                QuickOptionsBottomSheet.Option(R.drawable.ic_play, "Abrir y reproducir", action = onOpen),
                QuickOptionsBottomSheet.Option(R.drawable.ic_artist_person, "Ir a ${artist.name}") { openArtist(fragment, artist) }
            )
        ).show(fragment.parentFragmentManager, "ArtistMixQuickOptions")
    }

    // ─── Pulsación larga ──────────────────────────────────────

    /**
     * Mantener pulsado = abrir las opciones, con la vibración corta del sistema. [action]
     * devuelve si había hoja que abrir: si no, ni vibra ni consume el gesto.
     */
    fun View.onLongPressOptions(action: () -> Boolean) {
        setOnLongClickListener { view ->
            val opened = action()
            if (opened) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            opened
        }
    }

    // ─── Navegación compartida ────────────────────────────────

    /**
     * Navega desde cualquier pantalla. Si el fragmento no cuelga del grafo (reproductor a
     * pantalla completa, hoja de cola), usa el NavController de la actividad y cierra los
     * diálogos de los que viene para que el destino quede a la vista.
     */
    private fun navigate(fragment: Fragment, destination: Int, args: Bundle? = null) {
        if (!fragment.isAdded) return
        val own = runCatching { fragment.findNavController() }.getOrNull()
        val controller = own ?: runCatching {
            fragment.requireActivity().findNavController(R.id.nav_host_fragment)
        }.getOrNull() ?: return
        if (own == null) {
            generateSequence(fragment) { it.parentFragment }
                .filterIsInstance<DialogFragment>()
                .forEach { it.dismissAllowingStateLoss() }
        }
        runCatching { controller.navigate(destination, args) }
    }

    private fun openSong(fragment: Fragment, song: Song) =
        navigate(fragment, R.id.detailedSongFragment, Bundle().apply { putParcelable("song", song) })

    private fun openAlbum(fragment: Fragment, albumId: String) =
        navigate(fragment, R.id.albumFragment, Bundle().apply { putString("albumId", albumId) })

    private fun openArtist(fragment: Fragment, artist: Artist) =
        navigate(fragment, R.id.artistFragment, Bundle().apply {
            putString("artistId", artist.id)
            putString("artistName", artist.name)
            putString("artistImageUrl", artist.url)
        })

    private fun pickArtist(fragment: Fragment, artists: List<Artist>) {
        when {
            artists.isEmpty() -> Unit
            artists.size == 1 -> openArtist(fragment, artists[0])
            else -> ArtistSelectorBottomSheet(artists) { openArtist(fragment, it) }
                .show(fragment.parentFragmentManager, "ArtistSelectorBottomSheet")
        }
    }

    private fun openPlaylist(fragment: Fragment, playlist: Playlist) =
        navigate(fragment, R.id.playlistFragment, Bundle().apply { putString("playlistId", playlist.id) })

    private fun playPlaylist(fragment: Fragment, playlist: Playlist) {
        val id = playlist.id ?: return
        val context = fragment.requireContext().applicationContext
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val songs = runCatching { PlaylistManager(context).getSongsByPlaylistId(id) }.getOrDefault(emptyList())
            if (songs.isEmpty()) {
                Toast.makeText(context, "Esta lista no tiene canciones", Toast.LENGTH_SHORT).show()
                return@launch
            }
            context.startService(Intent(context, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.Companion.ACTION_PLAY
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_SONG, songs[0])
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_INDEX, 0)
                putParcelableArrayListExtra(MusicPlaybackService.Companion.SONG_LIST, ArrayList(songs))
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE, QueueSource.PLAYLIST)
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE_ID, id)
                putExtra("EXTRA_QUEUE_SOURCE_NAME", playlist.name)
            })
        }
    }
}
