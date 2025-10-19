package com.example.resonant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.SnackbarUtils.showResonantSnackbar
import com.example.resonant.managers.SongManager
import com.facebook.shimmer.ShimmerFrameLayout
import kotlinx.coroutines.launch

class AlbumFragment : BaseFragment(R.layout.fragment_album) {

    private lateinit var arrowGoBackButton: FrameLayout

    private lateinit var albumName: TextView
    private lateinit var albumArtistName: TextView
    private lateinit var albumImage: ImageView
    private lateinit var albumDuration: TextView
    private lateinit var albumNumberOfTracks: TextView
    private lateinit var recyclerViewSongs: RecyclerView
    private lateinit var nestedScroll: NestedScrollView
    private lateinit var topBar: ConstraintLayout
    private lateinit var albumTopBarText: TextView
    private lateinit var favoriteButton: FrameLayout

    private lateinit var songAdapter: SongAdapter
    private lateinit var sharedViewModel: SharedViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel
    private var loadedAlbum: Album? = null
    private lateinit var favoriteIcon: ImageView

    private lateinit var shimmerLayout: ShimmerFrameLayout
    private lateinit var api: ApiResonantService

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_album, container, false)

        arrowGoBackButton = view.findViewById(R.id.arrowGoBackBackground)
        recyclerViewSongs = view.findViewById(R.id.albumSongsContainer)
        recyclerViewSongs.layoutManager = LinearLayoutManager(requireContext())
        songAdapter = SongAdapter(SongAdapter.VIEW_TYPE_FULL)
        recyclerViewSongs.adapter = songAdapter
        topBar = view.findViewById(R.id.topBar)

        shimmerLayout = view.findViewById(R.id.shimmerLayout)

        albumName = view.findViewById(R.id.albumName)
        albumTopBarText = view.findViewById(R.id.albumTopBarText)
        albumArtistName = view.findViewById(R.id.albumArtistName)
        albumImage = view.findViewById(R.id.artistImage)
        albumDuration = view.findViewById(R.id.albumDuration)
        albumNumberOfTracks = view.findViewById(R.id.albumNumberOfTracks)
        nestedScroll = view.findViewById(R.id.nested_scroll)
        favoriteButton = view.findViewById(R.id.favoriteBackground)
        favoriteIcon = view.findViewById(R.id.favoriteButton)

        val albumId = arguments?.getString("albumId") ?: return view

        api = ApiClient.getService(requireContext())
        loadAlbumDetails(albumId)

        albumImage.scaleX = 1.1f
        albumImage.scaleY = 1.1f
        albumImage.alpha = 0f

        albumImage.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(1000)
            .start()

        val startFade = 200
        val endFade = 500

        albumTopBarText.visibility = View.INVISIBLE
        albumTopBarText.alpha = 0f

        nestedScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val parallaxFactor = 0.3f
            val offset = -scrollY * parallaxFactor
            albumImage.translationY = offset

            val progress =
                ((scrollY - startFade).toFloat() / (endFade - startFade).toFloat()).coerceIn(0f, 1f)
            val alpha = (progress * 255).toInt()

            topBar.setBackgroundColor(android.graphics.Color.argb(alpha, 0, 0, 0))

            if (progress > 0f) {
                if (albumTopBarText.visibility != View.VISIBLE) albumTopBarText.visibility = View.VISIBLE
                albumTopBarText.alpha = progress // de 0f (transparente) a 1f (opaco)
            } else {
                if (albumTopBarText.visibility != View.INVISIBLE) albumTopBarText.visibility = View.INVISIBLE
                albumTopBarText.alpha = 0f
            }
        }

        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]

        favoritesViewModel.loadAllFavorites()

        favoritesViewModel.favoriteAlbumIds.observe(viewLifecycleOwner) { ids ->
            val isFavorite = albumId?.let { ids.contains(it) } ?: false
            updateFavoriteIcon(isFavorite)
        }

        favoriteButton.setOnClickListener {
            loadedAlbum?.let { album ->
                val isFavorite = favoritesViewModel.favoriteAlbumIds.value?.contains(album.id) ?: false
                if (isFavorite) {
                    favoritesViewModel.deleteFavoriteAlbum(album.id) { success ->
                        if (success) {
                            showResonantSnackbar(
                                text = "¡Álbum eliminado de favoritos!",
                                colorRes = R.color.successColor,
                                iconRes = R.drawable.success
                            )
                        } else {
                            showResonantSnackbar(
                                text = "Error al eliminar favorito",
                                colorRes = R.color.errorColor,
                                iconRes = R.drawable.error
                            )
                        }
                    }
                } else {
                    favoritesViewModel.addFavoriteAlbum(album) { success ->
                        if (success) {
                            showResonantSnackbar(
                                text = "¡Álbum añadido a favoritos!",
                                colorRes = R.color.successColor,
                                iconRes = R.drawable.success
                            )
                        } else {
                            showResonantSnackbar(
                                text = "Error al añadir favorito",
                                colorRes = R.color.errorColor,
                                iconRes = R.drawable.error
                            )
                        }
                    }
                }
            }
        }

        sharedViewModel = ViewModelProvider(requireActivity())[SharedViewModel::class.java]

        sharedViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            currentSong?.let {
                songAdapter.setCurrentPlayingSong(it.id)
            }
        }

        songAdapter.onItemClick = { (song, bitmap) ->
            val currentIndex = songAdapter.currentList.indexOfFirst { it.url == song.url }
            val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, song.id) }
            val songList = ArrayList(songAdapter.currentList)
            val albumId = song.albumId

            val playIntent = Intent(context, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_PLAY
                putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, song)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, currentIndex)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, songList)
                putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE, QueueSource.ALBUM)
                putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE_ID, albumId)
            }

            requireContext().startService(playIntent)
        }

        favoritesViewModel.loadFavoriteSongs()

        favoritesViewModel.favorites.observe(viewLifecycleOwner) { favorites ->
            val songIds = favorites
                .filterIsInstance<FavoriteItem.SongItem>()
                .map { it.song.id }
                .toSet()
            songAdapter.favoriteSongIds = songIds
            songAdapter.submitList(songAdapter.currentList)
        }

        songAdapter.onFavoriteClick = { song, wasFavorite ->
            favoritesViewModel.toggleFavoriteSong(song)
        }

        songAdapter.onSettingsClick = { song ->
            val service = ApiClient.getService(requireContext())
            lifecycleScope.launch {
                val artistList = service.getArtistsBySongId(song.id)
                song.artistName = artistList.joinToString(", ") { it.name }

                val bottomSheet = SongOptionsBottomSheet(
                    song = song,
                    onSeeSongClick = { selectedSong ->
                        val bundle = Bundle().apply {
                            putParcelable("song", selectedSong)
                        }
                        findNavController().navigate(
                            R.id.action_albumFragment_to_detailedSongFragment,
                            bundle
                        )
                    },
                    onFavoriteToggled = { toggledSong ->
                        favoritesViewModel.toggleFavoriteSong(toggledSong)
                    },
                    onAddToPlaylistClick = { songToAdd ->
                        val selectPlaylistBottomSheet = SelectPlaylistBottomSheet(
                            song = songToAdd,
                            onNoPlaylistsFound = {
                                findNavController().navigate(R.id.action_global_to_createPlaylistFragment)
                            }
                        )
                        selectPlaylistBottomSheet.show(parentFragmentManager, "SelectPlaylistBottomSheet")
                    }
                )
                bottomSheet.show(parentFragmentManager, "SongOptionsBottomSheet")
            }
        }

        arrowGoBackButton.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        return view
    }

    private fun loadAlbumDetails(albumId: String) {
        lifecycleScope.launch {
            try {
                shimmerLayout.visibility = View.VISIBLE
                recyclerViewSongs.visibility = View.GONE

                // --- 1. OBTENER DETALLES DEL ÁLBUM (Esta parte no cambia) ---
                val album = api.getAlbumById(albumId)
                val albumFileName = album.fileName
                val albumImageUrl = albumFileName?.let { api.getAlbumUrl(it).url }.orEmpty()
                val artistName = album.artistName ?: run {
                    val artistList = api.getArtistsByAlbumId(albumId)
                    artistList.firstOrNull()?.name ?: "Artista desconocido"
                }

                albumName.text = album.title ?: "Sin título"
                albumArtistName.text = artistName
                albumTopBarText.text = album.title

                val albumImageModel = if (albumImageUrl.isNotBlank())
                    ImageRequestHelper.buildGlideModel(requireContext(), albumImageUrl)
                else
                    R.drawable.album_cover
                Glide.with(this@AlbumFragment)
                    .load(albumImageModel)
                    .placeholder(R.drawable.album_cover)
                    .error(R.drawable.album_cover)
                    .into(albumImage)

                albumDuration.text = Utils.formatDuration(album.duration)
                albumNumberOfTracks.text = "${album.numberOfTracks} canciones"

                // --- 2. ¡EL CAMBIO MÁGICO! OBTENER CANCIONES CON EL MANAGER ---
                // Una sola línea reemplaza toda tu función getSongsFromAlbum.
                val songs = SongManager.getSongsFromAlbum(requireContext(), albumId)
                // La lista 'songs' ya viene completa con artistas, URLs y datos de análisis.
                // -----------------------------------------------------------

                loadedAlbum = album

                songAdapter.submitList(songs)
                shimmerLayout.visibility = View.GONE
                recyclerViewSongs.visibility = View.VISIBLE

            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error al cargar el álbum", Toast.LENGTH_SHORT).show()
                shimmerLayout.visibility = View.GONE
                recyclerViewSongs.visibility = View.VISIBLE
            }
        }
    }

    private fun updateFavoriteIcon(isFavorite: Boolean) {
        favoriteIcon.setImageResource(
            if (isFavorite) R.drawable.favorite else R.drawable.favorite_border
        )
    }

}