package com.example.resonant.ui.fragments

import android.content.Intent
import android.graphics.Color
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
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.data.network.ApiClient
import com.example.resonant.services.ApiResonantService
import com.example.resonant.ui.viewmodels.FavoriteItem
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.playback.QueueSource
import com.example.resonant.R
import com.example.resonant.ui.bottomsheets.SelectPlaylistBottomSheet
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.example.resonant.ui.adapters.SongAdapter
import com.example.resonant.ui.bottomsheets.SongOptionsBottomSheet
import com.example.resonant.utils.Utils
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.managers.SongManager
import com.example.resonant.ui.adapters.AlbumAdapter
import kotlinx.coroutines.launch

class ArtistFragment : BaseFragment(R.layout.fragment_artist) {

    private lateinit var api: ApiResonantService

    private lateinit var artistImage: ImageView
    private lateinit var artistNameTopBar: TextView
    private lateinit var artistNameTextView: TextView
    private lateinit var arrowGoBackButton: FrameLayout
    private lateinit var favoriteButton: FrameLayout
    private lateinit var favoriteIcon: ImageView
    private lateinit var nestedScroll: NestedScrollView
    private lateinit var recyclerViewAlbums: RecyclerView
    private lateinit var albumsAdapter: AlbumAdapter
    private var albumList: MutableList<Album> = mutableListOf()

    private lateinit var recyclerViewTopSongs: RecyclerView
    private lateinit var topSongsAdapter: SongAdapter
    private lateinit var topSongsTitle: TextView
    private lateinit var titleAlbumSongs: TextView
    private lateinit var topBar: ConstraintLayout

    private lateinit var favoritesViewModel: FavoritesViewModel
    private var loadedArtist: Artist? = null
    private lateinit var songViewModel: SongViewModel

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_artist, container, false)

        artistImage = view.findViewById(R.id.artistImage)
        artistNameTextView = view.findViewById(R.id.artistName)
        artistNameTopBar = view.findViewById(R.id.albumTopBarText)
        arrowGoBackButton = view.findViewById(R.id.arrowGoBackBackground)
        favoriteButton = view.findViewById(R.id.favoriteBackground)
        nestedScroll = view.findViewById(R.id.nested_scroll)
        recyclerViewAlbums = view.findViewById(R.id.listAlbumsRecycler)
        favoriteIcon = view.findViewById(R.id.favoriteButton)
        topBar = view.findViewById(R.id.topBar)
        topSongsTitle = view.findViewById(R.id.titleTopSongs)
        titleAlbumSongs = view.findViewById(R.id.titleAlbumSongs)

        recyclerViewAlbums.layoutManager = LinearLayoutManager(requireContext())
        albumsAdapter = AlbumAdapter(albumList, 1)
        recyclerViewAlbums.adapter = albumsAdapter

        recyclerViewTopSongs = view.findViewById(R.id.topSongsList)
        recyclerViewTopSongs.layoutManager = LinearLayoutManager(requireContext())
        topSongsAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_TOP_SONG)
        recyclerViewTopSongs.adapter = topSongsAdapter
        recyclerViewTopSongs.isNestedScrollingEnabled = false

        api = ApiClient.getService(requireContext())
        val artistId = arguments?.getString("artistId") ?: return view
        loadArtistDetails(artistId)

        artistImage.scaleX = 1.1f
        artistImage.scaleY = 1.1f
        artistImage.alpha = 0f

        artistImage.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(1000)
            .start()

        val startFade = 200
        val endFade = 500

        artistNameTopBar.visibility = View.INVISIBLE
        artistNameTopBar.alpha = 0f

        nestedScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val parallaxFactor = 0.3f
            val offset = -scrollY * parallaxFactor
            artistImage.translationY = offset
            val progress =
                ((scrollY - startFade).toFloat() / (endFade - startFade).toFloat()).coerceIn(0f, 1f)
            val alpha = (progress * 255).toInt()
            topBar.setBackgroundColor(Color.argb(alpha, 0, 0, 0))
            if (progress > 0f) {
                if (artistNameTopBar.visibility != View.VISIBLE) artistNameTopBar.visibility = View.VISIBLE
                artistNameTopBar.alpha = progress // de 0f (transparente) a 1f (opaco)
            } else {
                if (artistNameTopBar.visibility != View.INVISIBLE) artistNameTopBar.visibility = View.INVISIBLE
                artistNameTopBar.alpha = 0f
            }
        }

        songViewModel = ViewModelProvider(requireActivity()).get(SongViewModel::class.java)

        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            currentSong?.let {
               topSongsAdapter.setCurrentPlayingSong(it.id)
            }
        }

        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]

        favoritesViewModel.loadAllFavorites()

        favoritesViewModel.favoriteArtistIds.observe(viewLifecycleOwner) { ids ->
            val isFavorite = artistId?.let { ids.contains(it) } ?: false
            updateFavoriteIcon(isFavorite)
        }

        favoritesViewModel.favorites.observe(viewLifecycleOwner) { favorites ->
            val songIds = favorites
                .filterIsInstance<FavoriteItem.SongItem>()
                .map { it.song.id }
                .toSet()
            topSongsAdapter.favoriteSongIds = songIds
        }

        topSongsAdapter.onSettingsClick = { song ->
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
                            R.id.action_artistFragment_to_detailedSongFragment,
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
                        selectPlaylistBottomSheet.show(
                            parentFragmentManager,
                            "SelectPlaylistBottomSheet"
                        )
                    }
                )
                bottomSheet.show(parentFragmentManager, "SongOptionsBottomSheet")
            }
        }

        topSongsAdapter.onFavoriteClick = { song, wasFavorite ->
            favoritesViewModel.toggleFavoriteSong(song)
        }

        var currentHomeQueueId: String = System.currentTimeMillis().toString()

        topSongsAdapter.onItemClick = { (song, bitmap) ->
            val currentIndex = topSongsAdapter.currentList.indexOfFirst { it.url == song.url }
            val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, song.id) }
            val songList = ArrayList(topSongsAdapter.currentList)

            val playIntent = Intent(context, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.Companion.ACTION_PLAY
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_SONG, song)
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_INDEX, currentIndex)
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                putParcelableArrayListExtra(MusicPlaybackService.Companion.SONG_LIST, songList)
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE, QueueSource.TOP_SONGS_ARTIST)
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE_ID, currentHomeQueueId)
            }
            requireContext().startService(playIntent)
        }

        favoriteButton.setOnClickListener {
            loadedArtist?.let { artist ->
                val isFavorite = favoritesViewModel.favoriteArtistIds.value?.contains(artist.id) ?: false
                if (isFavorite) {
                    favoritesViewModel.deleteFavoriteArtist(artist.id) { success ->
                        if (success) {
                            showResonantSnackbar(
                                text = "¡Artista eliminado de favoritos!",
                                colorRes = R.color.successColor,
                                iconRes = R.drawable.ic_success
                            )
                        } else {
                            showResonantSnackbar(
                                text = "Error al eliminar favorito",
                                colorRes = R.color.errorColor,
                                iconRes = R.drawable.ic_error
                            )
                        }
                    }
                } else {
                    favoritesViewModel.addFavoriteArtist(artist) { success ->
                        if (success) {
                            showResonantSnackbar(
                                text = "¡Artista añadido a favoritos!",
                                colorRes = R.color.successColor,
                                iconRes = R.drawable.ic_success
                            )
                        } else {
                            showResonantSnackbar(
                                text = "Error al añadir favorito",
                                colorRes = R.color.errorColor,
                                iconRes = R.drawable.ic_error
                            )
                        }
                    }
                }
            }
        }

        arrowGoBackButton.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        return view
    }

    private fun loadArtistDetails(artistId: String) {
        lifecycleScope.launch {
            try {
                recyclerViewAlbums.visibility = View.GONE
                topSongsTitle.visibility = View.GONE
                recyclerViewTopSongs.visibility = View.GONE
                titleAlbumSongs.visibility = View.GONE

                val artist = api.getArtistById(artistId)
                loadedArtist = artist
                artistNameTextView.text = artist.name ?: "Artista desconocido"
                artistNameTopBar.text = artist.name
                artist.url = artist.fileName?.takeIf { it.isNotEmpty() }?.let { api.getArtistUrl(it).url }
                if (!artist.url.isNullOrEmpty()) {
                    Glide.with(requireContext()).load(artist.url).into(artistImage)
                } else {
                    artistImage.setImageDrawable(null)
                }

                val albums = api.getByArtistId(artistId).toMutableList()
                albums.forEach { it.artistName = artist.name ?: "Desconocido" }
                val albumsSinUrl = albums.filter { it.url.isNullOrEmpty() }
                if (albumsSinUrl.isNotEmpty()) {
                    val fileNames = albumsSinUrl.map { it.fileName.takeIf { f -> !f.isNullOrBlank() } ?: "${it.id}.jpg" }
                    val urlList = api.getMultipleAlbumUrls(fileNames)
                    val urlMap = urlList.associateBy { it.fileName }
                    albumsSinUrl.forEach { album ->
                        val fileName = album.fileName.takeIf { f -> !f.isNullOrBlank() } ?: "${album.id}.jpg"
                        album.url = urlMap[fileName]?.url
                    }
                    titleAlbumSongs.visibility = View.VISIBLE
                }
                albumsAdapter = AlbumAdapter(albums, 1)
                recyclerViewAlbums.adapter = albumsAdapter

                val topSongs = SongManager.getMostStreamedSongsByArtist(requireContext(), artistId, 5)

                if (topSongs.isNotEmpty()) {

                    topSongs.forEach {
                        if (it.artistName.isNullOrEmpty()) {
                            it.artistName = artist.name ?: "Desconocido"
                        }
                    }

                    topSongsAdapter.submitList(topSongs)
                    topSongsTitle.visibility = View.VISIBLE
                    recyclerViewTopSongs.visibility = View.VISIBLE
                }

                recyclerViewAlbums.visibility = View.VISIBLE

            } catch (e: Exception) {
                Log.e("ArtistFragment", "Error al cargar detalles del artista", e)
                Toast.makeText(requireContext(), "Error al cargar el artista", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateFavoriteIcon(isFavorite: Boolean) {
        favoriteIcon.setImageResource(
            if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
    }

}