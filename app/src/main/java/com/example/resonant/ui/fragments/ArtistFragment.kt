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
import android.widget.RelativeLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.data.network.ApiClient
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
import com.example.resonant.data.models.Artist
import com.example.resonant.ui.adapters.AlbumAdapter
import com.example.resonant.ui.viewmodels.ArtistViewModel
import kotlinx.coroutines.launch

class ArtistFragment : BaseFragment(R.layout.fragment_artist) {

    // UI Components
    private lateinit var artistImage: ImageView
    private lateinit var artistNameTopBar: TextView
    private lateinit var artistNameTextView: TextView
    private lateinit var arrowGoBackButton: FrameLayout
    private lateinit var favoriteButton: FrameLayout
    private lateinit var favoriteIcon: ImageView
    private lateinit var nestedScroll: NestedScrollView
    private lateinit var topBar: ConstraintLayout

    // --- SECCIONES ---
    private lateinit var recyclerViewAlbums: RecyclerView
    private lateinit var albumsAdapter: AlbumAdapter
    private lateinit var titleAlbumSongs: TextView

    private lateinit var layoutFeaturedAlbum: RelativeLayout
    private lateinit var recyclerViewFeaturedAlbum: RecyclerView
    private lateinit var featuredAlbumAdapter: AlbumAdapter

    private lateinit var recyclerViewTopSongs: RecyclerView
    private lateinit var topSongsAdapter: SongAdapter
    private lateinit var topSongsTitle: TextView

    // ViewModels
    private lateinit var favoritesViewModel: FavoritesViewModel
    private lateinit var songViewModel: SongViewModel
    private lateinit var artistViewModel: ArtistViewModel // ViewModel dedicado

    // Variable auxiliar para el botón de favoritos (se llena desde el VM)
    private var currentArtist: Artist? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_artist, container, false)

        initViews(view)
        setupAdapters()

        val artistId = arguments?.getString("artistId") ?: return view

        // Animación inicial
        startEnterAnimation()
        setupScrollListener()

        // Inicializar ViewModels
        setupViewModels(artistId)

        // Cargar datos (El ViewModel decide si usa caché o red)
        artistViewModel.loadData(artistId)

        setupClickListeners()

        return view
    }

    private fun initViews(view: View) {
        artistImage = view.findViewById(R.id.artistImage)
        artistNameTextView = view.findViewById(R.id.artistName)
        artistNameTopBar = view.findViewById(R.id.albumTopBarText)
        arrowGoBackButton = view.findViewById(R.id.arrowGoBackBackground)
        favoriteButton = view.findViewById(R.id.favoriteBackground)
        nestedScroll = view.findViewById(R.id.nested_scroll)
        favoriteIcon = view.findViewById(R.id.favoriteButton)
        topBar = view.findViewById(R.id.topBar)
        topSongsTitle = view.findViewById(R.id.titleTopSongs)
        recyclerViewTopSongs = view.findViewById(R.id.topSongsList)
        titleAlbumSongs = view.findViewById(R.id.titleAlbumSongs)
        recyclerViewAlbums = view.findViewById(R.id.listAlbumsRecycler)
        layoutFeaturedAlbum = view.findViewById(R.id.albumFeatured)
        recyclerViewFeaturedAlbum = view.findViewById(R.id.featuredAlbumList)
    }

    private fun setupAdapters() {
        // Featured
        featuredAlbumAdapter = AlbumAdapter(mutableListOf(), AlbumAdapter.VIEW_TYPE_FEATURED)
        recyclerViewFeaturedAlbum.layoutManager = LinearLayoutManager(requireContext())
        recyclerViewFeaturedAlbum.adapter = featuredAlbumAdapter
        recyclerViewFeaturedAlbum.isNestedScrollingEnabled = false

        // Albums Normales
        albumsAdapter = AlbumAdapter(mutableListOf(), AlbumAdapter.VIEW_TYPE_DETAILED)
        recyclerViewAlbums.layoutManager = LinearLayoutManager(requireContext())
        recyclerViewAlbums.adapter = albumsAdapter
        recyclerViewAlbums.isNestedScrollingEnabled = false

        // Top Songs
        topSongsAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_TOP_SONG)
        recyclerViewTopSongs.layoutManager = LinearLayoutManager(requireContext())
        recyclerViewTopSongs.adapter = topSongsAdapter
        recyclerViewTopSongs.isNestedScrollingEnabled = false
    }

    private fun startEnterAnimation() {
        artistImage.scaleX = 1.1f
        artistImage.scaleY = 1.1f
        artistImage.alpha = 0f
        artistImage.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(1000).start()
    }

    private fun setupScrollListener() {
        val startFade = 200
        val endFade = 500
        artistNameTopBar.visibility = View.INVISIBLE
        artistNameTopBar.alpha = 0f

        nestedScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val parallaxFactor = 0.3f
            val offset = -scrollY * parallaxFactor
            artistImage.translationY = offset
            val progress = ((scrollY - startFade).toFloat() / (endFade - startFade).toFloat()).coerceIn(0f, 1f)
            val alpha = (progress * 255).toInt()
            topBar.setBackgroundColor(Color.argb(alpha, 0, 0, 0))

            if (progress > 0f) {
                if (artistNameTopBar.visibility != View.VISIBLE) artistNameTopBar.visibility = View.VISIBLE
                artistNameTopBar.alpha = progress
            } else {
                if (artistNameTopBar.visibility != View.INVISIBLE) artistNameTopBar.visibility = View.INVISIBLE
                artistNameTopBar.alpha = 0f
            }
        }
    }

    private fun setupViewModels(artistId: String) {
        // 1. Song ViewModel (Reproducción actual)
        songViewModel = ViewModelProvider(requireActivity()).get(SongViewModel::class.java)
        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            currentSong?.let { topSongsAdapter.setCurrentPlayingSong(it.id) }
        }

        // 2. Favorites ViewModel
        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]
        favoritesViewModel.loadAllFavorites()

        favoritesViewModel.favoriteArtistIds.observe(viewLifecycleOwner) { ids ->
            val isFavorite = artistId.let { ids.contains(it) }
            updateFavoriteIcon(isFavorite)
        }

        favoritesViewModel.favorites.observe(viewLifecycleOwner) { favorites ->
            val songIds = favorites.filterIsInstance<FavoriteItem.SongItem>().map { it.song.id }.toSet()
            topSongsAdapter.favoriteSongIds = songIds
        }

        // 3. Artist ViewModel (DATOS PRINCIPALES)
        artistViewModel = ViewModelProvider(this)[ArtistViewModel::class.java]

        // Observer: Artista Info
        artistViewModel.artist.observe(viewLifecycleOwner) { artist ->
            if (artist != null) {
                currentArtist = artist // Guardamos referencia para el click de favorito
                artistNameTextView.text = artist.name
                artistNameTopBar.text = artist.name
                if (!artist.url.isNullOrEmpty()) {
                    Glide.with(requireContext()).load(artist.url).into(artistImage)
                } else {
                    artistImage.setImageDrawable(null)
                }
            }
        }

        // Observer: Álbum Destacado
        artistViewModel.featuredAlbum.observe(viewLifecycleOwner) { list ->
            if (list.isNotEmpty()) {
                layoutFeaturedAlbum.visibility = View.VISIBLE
                featuredAlbumAdapter.updateList(list)
            } else {
                layoutFeaturedAlbum.visibility = View.GONE
            }
        }

        // Observer: Álbumes Normales
        artistViewModel.normalAlbums.observe(viewLifecycleOwner) { list ->
            if (list.isNotEmpty()) {
                titleAlbumSongs.visibility = View.VISIBLE
                recyclerViewAlbums.visibility = View.VISIBLE
                albumsAdapter.updateList(list)
            } else {
                recyclerViewAlbums.visibility = View.GONE
                titleAlbumSongs.visibility = View.GONE
            }
        }

        // Observer: Top Songs
        artistViewModel.topSongs.observe(viewLifecycleOwner) { list ->
            if (list.isNotEmpty()) {
                topSongsTitle.visibility = View.VISIBLE
                recyclerViewTopSongs.visibility = View.VISIBLE
                topSongsAdapter.submitList(list)
            } else {
                topSongsTitle.visibility = View.GONE
                recyclerViewTopSongs.visibility = View.GONE
            }
        }

        // Observer: Errores
        artistViewModel.errorMessage.observe(viewLifecycleOwner) { msg ->
            msg?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun setupClickListeners() {
        arrowGoBackButton.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        // Lógica de reproducción
        val currentHomeQueueId = System.currentTimeMillis().toString()
        topSongsAdapter.onItemClick = { (song, bitmap) ->
            val currentIndex = topSongsAdapter.currentList.indexOfFirst { it.url == song.url }
            val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, song.id) }
            val songList = ArrayList(topSongsAdapter.currentList)

            val playIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
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

        topSongsAdapter.onFavoriteClick = { song, _ -> favoritesViewModel.toggleFavoriteSong(song) }

        topSongsAdapter.onSettingsClick = { song ->
            // Nota: Esta pequeña llamada de red para obtener el artista del bottom sheet
            // se puede quedar aquí o moverse al VM si quieres ser muy estricto,
            // pero por simplicidad está bien dejarla aquí en una coroutine pequeña.
            lifecycleScope.launch {
                try {
                    val artistService = ApiClient.getArtistService(requireContext())
                    val artistList = artistService.getArtistsBySongId(song.id)
                    song.artistName = artistList.joinToString(", ") { it.name }

                    val bottomSheet = SongOptionsBottomSheet(
                        song = song,
                        onSeeSongClick = { selectedSong ->
                            val bundle = Bundle().apply { putParcelable("song", selectedSong) }
                            findNavController().navigate(R.id.action_artistFragment_to_detailedSongFragment, bundle)
                        },
                        onFavoriteToggled = { toggledSong -> favoritesViewModel.toggleFavoriteSong(toggledSong) },
                        onAddToPlaylistClick = { songToAdd ->
                            val sheet = SelectPlaylistBottomSheet(
                                song = songToAdd,
                                onNoPlaylistsFound = { findNavController().navigate(R.id.action_global_to_createPlaylistFragment) }
                            )
                            sheet.show(parentFragmentManager, "SelectPlaylistBottomSheet")
                        }
                    )
                    bottomSheet.show(parentFragmentManager, "SongOptionsBottomSheet")
                } catch (e: Exception) {
                    Log.e("ArtistFragment", "Error loading song artist details", e)
                }
            }
        }

        // Botón Favorito Artista (Usando la variable currentArtist actualizada por el VM)
        favoriteButton.setOnClickListener {
            currentArtist?.let { artist ->
                val isFavorite = favoritesViewModel.favoriteArtistIds.value?.contains(artist.id) ?: false
                if (isFavorite) {
                    favoritesViewModel.deleteFavoriteArtist(artist.id) { success ->
                        if (success) showResonantSnackbar(text = "¡Artista eliminado!", colorRes = R.color.successColor, iconRes = R.drawable.ic_success)
                        else showResonantSnackbar(text = "Error", colorRes = R.color.errorColor, iconRes = R.drawable.ic_error)
                    }
                } else {
                    favoritesViewModel.addFavoriteArtist(artist) { success ->
                        if (success) showResonantSnackbar(text = "¡Artista añadido!", colorRes = R.color.successColor, iconRes = R.drawable.ic_success)
                        else showResonantSnackbar(text = "Error", colorRes = R.color.errorColor, iconRes = R.drawable.ic_error)
                    }
                }
            }
        }
    }

    private fun updateFavoriteIcon(isFavorite: Boolean) {
        favoriteIcon.setImageResource(
            if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
    }
}