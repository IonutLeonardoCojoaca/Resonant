package com.example.resonant.ui.fragments

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.ApiClient
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.AlbumAdapter
import com.example.resonant.ui.adapters.SongAdapter
import com.example.resonant.ui.bottomsheets.SelectPlaylistBottomSheet
import com.example.resonant.ui.bottomsheets.SongOptionsBottomSheet
import com.example.resonant.ui.viewmodels.ArtistViewModel
import com.example.resonant.ui.viewmodels.DownloadViewModel
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.example.resonant.utils.Utils
import com.google.android.material.button.MaterialButton // Importamos el botón
import kotlinx.coroutines.launch

import com.example.resonant.ui.adapters.ArtistImageAdapter
import com.example.resonant.ui.adapters.ArtistSmartPlaylistAdapter
import com.example.resonant.data.models.ArtistSmartPlaylist
import com.example.resonant.managers.ArtistManager

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
    private lateinit var artistDescription: TextView

    private lateinit var descriptionContainer: LinearLayout
    private lateinit var btnReadMore: MaterialButton
    
    // Gallery
    private lateinit var galleryContainer: LinearLayout
    private lateinit var galleryRecycler: RecyclerView
    private lateinit var galleryAdapter: ArtistImageAdapter

    // Smart Playlists
    private lateinit var smartPlaylistsContainer: LinearLayout
    private lateinit var smartPlaylistsRecycler: RecyclerView
    private lateinit var smartPlaylistsAdapter: ArtistSmartPlaylistAdapter

    private lateinit var btnSeeMoreSongs: MaterialButton
    private lateinit var listSongsContainer: LinearLayout // Para la animación
    private var fullTopSongsList: List<Song> = emptyList() // Guardamos las 10 aquí
    private var isSongsExpanded = false // Controla si vemos 5 o 10

    private var isDescriptionExpanded = false // Controla el estado
    private var hasDescriptionOverflow = false
    private var hasGalleryImages = false

    private lateinit var recyclerViewAlbums: RecyclerView
    private lateinit var albumsAdapter: AlbumAdapter
    private lateinit var titleAlbumSongs: TextView

    private lateinit var layoutFeaturedAlbum: RelativeLayout
    private lateinit var recyclerViewFeaturedAlbum: RecyclerView
    private lateinit var featuredAlbumAdapter: AlbumAdapter

    private lateinit var recyclerViewTopSongs: RecyclerView
    private lateinit var topSongsAdapter: SongAdapter
    private lateinit var topSongsTitle: TextView

    // --- NUEVO: Variables del Botón Play ---
    private lateinit var playButton: MaterialButton
    private var isPlaying: Boolean = false

    private lateinit var favoritesViewModel: FavoritesViewModel
    private lateinit var songViewModel: SongViewModel
    private lateinit var artistViewModel: ArtistViewModel
    private lateinit var downloadViewModel: DownloadViewModel

    private var currentArtist: Artist? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_artist, container, false)

        initViews(view)
        setupAdapters()

        val artistId = arguments?.getString("artistId") ?: return view

        startEnterAnimation()
        setupScrollListener()
        setupViewModels(artistId)

        // --- NUEVO: Configuración del botón Play ---
        setupPlayButtonLogic()
        setupClickListeners()

        return view
    }

    override fun onResume() {
        super.onResume()
        arguments?.getString("artistId")?.let { artistId ->
            // [CAMBIO] Cargar datos en onResume asegura que si vuelves a la app tras horas, se verifique la caducidad
            if (::artistViewModel.isInitialized) {
                artistViewModel.loadData(artistId)
            }
        }
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
        artistDescription = view.findViewById(R.id.artistDescription)
        descriptionContainer = view.findViewById(R.id.descriptionContainer)
        artistDescription = view.findViewById(R.id.artistDescription)
        btnReadMore = view.findViewById(R.id.btnReadMore)
        
        // Gallery
        galleryContainer = view.findViewById(R.id.galleryContainer)
        galleryRecycler = view.findViewById(R.id.galleryRecycler)
        
        // Smart Playlists
        smartPlaylistsContainer = view.findViewById(R.id.smartPlaylistsContainer)
        smartPlaylistsRecycler = view.findViewById(R.id.smartPlaylistsRecycler)

        btnSeeMoreSongs = view.findViewById(R.id.btnSeeMoreSongs)
        listSongsContainer = view.findViewById(R.id.listSongsContainer)

        topSongsTitle = view.findViewById(R.id.titleTopSongs)
        recyclerViewTopSongs = view.findViewById(R.id.topSongsList)

        titleAlbumSongs = view.findViewById(R.id.titleAlbumSongs)
        recyclerViewAlbums = view.findViewById(R.id.listAlbumsRecycler)

        layoutFeaturedAlbum = view.findViewById(R.id.albumFeatured)
        recyclerViewFeaturedAlbum = view.findViewById(R.id.featuredAlbumList)

        // --- Inicializamos el botón Play ---
        playButton = view.findViewById(R.id.playButton)
    }

    private fun setupAdapters() {
        // Gallery
        galleryAdapter = ArtistImageAdapter().apply {
            onImageClick = { imageUrl ->
                showFullScreenImage(imageUrl)
            }
        }
        galleryRecycler.adapter = galleryAdapter
        galleryRecycler.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)

        // Smart Playlists
        smartPlaylistsAdapter = ArtistSmartPlaylistAdapter { playlist ->
            onSmartPlaylistClick(playlist)
        }
        smartPlaylistsRecycler.adapter = smartPlaylistsAdapter
        // LayoutManager ya definido en XML, pero aseguramos horizontal
        smartPlaylistsRecycler.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)

        // Mantenemos tus adapters originales tal cual estaban

        // Featured
        // Featured
        featuredAlbumAdapter = AlbumAdapter(mutableListOf(), AlbumAdapter.VIEW_TYPE_FEATURED)
        featuredAlbumAdapter.onAlbumClick = { album ->
            val bundle = Bundle().apply { putString("albumId", album.id) }
            findNavController().navigate(R.id.action_artistFragment_to_albumFragment, bundle)
        }
        
        featuredAlbumAdapter.onSettingsClick = { album ->
             val bottomSheet = com.example.resonant.ui.bottomsheets.AlbumOptionsBottomSheet(
                 album = album,
                onGoToAlbumClick = {
                    val bundle = Bundle().apply { putString("albumId", it.id) }
                    findNavController().navigate(R.id.action_artistFragment_to_albumFragment, bundle)
                },
                onGoToArtistClick = {
                    // Already in Artist Fragment, but maybe related artist?
                    // Logic similar to HomeFragment
                    val artists = it.artists
                     if (artists.isNotEmpty()) {
                        if (artists.size > 1) {
                            val selector = com.example.resonant.ui.bottomsheets.ArtistSelectorBottomSheet(artists) { selectedArtist ->
                                val bundle = Bundle().apply { 
                                     putString("artistId", selectedArtist.id)
                                     putString("artistName", selectedArtist.name)
                                     putString("artistImageUrl", selectedArtist.url)
                                }
                                // Navigate to self (different artist)
                                findNavController().navigate(R.id.action_artistFragment_self, bundle)
                            }
                            selector.show(parentFragmentManager, "ArtistSelectorBottomSheet")
                        } else {
                            val artist = artists[0]
                             val bundle = Bundle().apply { 
                                 putString("artistId", artist.id)
                                 putString("artistName", artist.name)
                                 putString("artistImageUrl", artist.url)
                            }
                            findNavController().navigate(R.id.action_artistFragment_self, bundle)
                        }
                    }
                },
                onViewDetailsClick = {
                     val bundle = Bundle().apply { 
                         putParcelable("album", it)
                         putString("albumId", it.id)
                     }
                     findNavController().navigate(R.id.action_global_to_detailedAlbumFragment, bundle)
                }
             )
             bottomSheet.show(parentFragmentManager, "AlbumOptionsBottomSheet")
        }
        
        recyclerViewFeaturedAlbum.layoutManager = LinearLayoutManager(requireContext())
        recyclerViewFeaturedAlbum.adapter = featuredAlbumAdapter
        recyclerViewFeaturedAlbum.isNestedScrollingEnabled = false

        // Albums Normales
        // Albums Normales
        albumsAdapter = AlbumAdapter(mutableListOf(), AlbumAdapter.VIEW_TYPE_DETAILED)
        albumsAdapter.onAlbumClick = { album ->
            val bundle = Bundle().apply { putString("albumId", album.id) }
            findNavController().navigate(R.id.action_artistFragment_to_albumFragment, bundle)
        }
        
        albumsAdapter.onSettingsClick = { album ->
             val bottomSheet = com.example.resonant.ui.bottomsheets.AlbumOptionsBottomSheet(
                 album = album,
                onGoToAlbumClick = {
                    val bundle = Bundle().apply { putString("albumId", it.id) }
                    findNavController().navigate(R.id.action_artistFragment_to_albumFragment, bundle)
                },
                onGoToArtistClick = {
                    val artists = it.artists
                     if (artists.isNotEmpty()) {
                        if (artists.size > 1) {
                            val selector = com.example.resonant.ui.bottomsheets.ArtistSelectorBottomSheet(artists) { selectedArtist ->
                                val bundle = Bundle().apply { 
                                     putString("artistId", selectedArtist.id)
                                     putString("artistName", selectedArtist.name)
                                     putString("artistImageUrl", selectedArtist.url)
                                }
                                findNavController().navigate(R.id.action_artistFragment_self, bundle)
                            }
                            selector.show(parentFragmentManager, "ArtistSelectorBottomSheet")
                        } else {
                            val artist = artists[0]
                             val bundle = Bundle().apply { 
                                 putString("artistId", artist.id)
                                 putString("artistName", artist.name)
                                 putString("artistImageUrl", artist.url)
                            }
                            findNavController().navigate(R.id.action_artistFragment_self, bundle)
                        }
                    }
                },
                onViewDetailsClick = {
                     val bundle = Bundle().apply { 
                         putParcelable("album", it)
                         putString("albumId", it.id)
                     }
                     findNavController().navigate(R.id.action_global_to_detailedAlbumFragment, bundle)
                }
             )
             bottomSheet.show(parentFragmentManager, "AlbumOptionsBottomSheet")
        }
        
        recyclerViewAlbums.layoutManager = LinearLayoutManager(requireContext())
        recyclerViewAlbums.adapter = albumsAdapter
        recyclerViewAlbums.isNestedScrollingEnabled = false

        // Top Songs
        topSongsAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_TOP_SONG)
        recyclerViewTopSongs.layoutManager = LinearLayoutManager(requireContext())
        recyclerViewTopSongs.adapter = topSongsAdapter
        recyclerViewTopSongs.isNestedScrollingEnabled = false

        recyclerViewTopSongs.setItemViewCacheSize(20)
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

        // 1. OBTENER TU COLOR DEL TEMA (Fuera del loop para rendimiento)
        // Usamos requireContext() asumiendo que estás en un Fragment
        val themeColor = ContextCompat.getColor(requireContext(), R.color.primaryColorTheme)

        // 2. EXTRAER LOS COMPONENTES RGB DE ESE COLOR (#121212)
        val red = Color.red(themeColor)
        val green = Color.green(themeColor)
        val blue = Color.blue(themeColor)

        artistNameTopBar.visibility = View.INVISIBLE
        artistNameTopBar.alpha = 0f

        nestedScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val parallaxFactor = 0.3f
            val offset = -scrollY * parallaxFactor
            artistImage.translationY = offset

            val progress = ((scrollY - startFade).toFloat() / (endFade - startFade).toFloat()).coerceIn(0f, 1f)
            val alpha = (progress * 255).toInt()

            // 3. APLICAR EL ALPHA CALCULADO A TU COLOR RGB
            // En lugar de (alpha, 0, 0, 0), usamos tus variables r, g, b
            topBar.setBackgroundColor(Color.argb(alpha, red, green, blue))

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
        songViewModel = ViewModelProvider(requireActivity()).get(SongViewModel::class.java)
        // Eliminado el observer simple que tenías aquí porque ahora lo manejamos en setupPlayButtonLogic
        // para controlar tanto el adapter como el botón grande.

        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]

        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]
        favoritesViewModel.loadAllFavorites()

        favoritesViewModel.favoriteArtistIds.observe(viewLifecycleOwner) { ids ->
            val isFavorite = artistId.let { ids.contains(it) }
            updateFavoriteIcon(isFavorite)
        }

        favoritesViewModel.favoriteSongIds.observe(viewLifecycleOwner) { songIds ->
            topSongsAdapter.favoriteSongIds = songIds
        }

        artistViewModel = ViewModelProvider(this)[ArtistViewModel::class.java]

        artistViewModel.artist.observe(viewLifecycleOwner) { artist ->
            if (artist != null) {
                currentArtist = artist
                artistNameTextView.text = artist.name
                artistNameTopBar.text = artist.name
                if (!artist.url.isNullOrEmpty()) {
                    Glide.with(requireContext()).load(artist.url).into(artistImage)
                } else {
                    artistImage.setImageDrawable(null)
                }

                setupArtistDescription(artist.description)

            }
        }

        // Observer: Playlists Inteligentes
        artistViewModel.artistPlaylists.observe(viewLifecycleOwner) { playlists ->
            if (playlists.isNotEmpty()) {
                smartPlaylistsContainer.visibility = View.VISIBLE
                smartPlaylistsAdapter.submitList(playlists)
            } else {
                smartPlaylistsContainer.visibility = View.GONE
            }
        }
        
        // Observer: Gallery Images
        artistViewModel.artistImages.observe(viewLifecycleOwner) { images ->
            if (images.isNotEmpty()) {
                galleryAdapter.submitList(images)
                hasGalleryImages = true
            } else {
                hasGalleryImages = false
            }
            updateReadMoreVisibility()

            // Si ya estaba expandido, actualizamos visibilidad
            if (isDescriptionExpanded && hasGalleryImages) {
                galleryContainer.visibility = View.VISIBLE
            } else {
                galleryContainer.visibility = View.GONE
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
                fullTopSongsList = list // 1. Guardamos la lista completa (10)

                topSongsTitle.visibility = View.VISIBLE
                recyclerViewTopSongs.visibility = View.VISIBLE

                // 2. Llamamos a una función auxiliar para pintar la lista
                updateSongsListDisplay()

            } else {
                topSongsTitle.visibility = View.GONE
                recyclerViewTopSongs.visibility = View.GONE
                btnSeeMoreSongs.visibility = View.GONE
            }
        }

        // Observer: Errores
        artistViewModel.errorMessage.observe(viewLifecycleOwner) { msg ->
            msg?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun onSmartPlaylistClick(playlist: ArtistSmartPlaylist) {
        val rawArtistName = currentArtist?.name ?: run {
            if (playlist.playlistType == "Essentials") {
                playlist.name.removePrefix("Esto es ")
            } else {
                playlist.name.removeSuffix(" Radio")
            }
        }

        val bundle = Bundle().apply {
            putString("artistId", playlist.artistId)
            putString("playlistType", playlist.playlistType)
            putString("artistName", rawArtistName)
            putString("artworkUrl", playlist.coverUrl)
        }
        findNavController().navigate(R.id.action_artistFragment_to_artistSmartPlaylistFragment, bundle)
    }

    private fun updateSongsListDisplay() {
        if (fullTopSongsList.isEmpty()) {
            Log.w("ArtistFragment", "updateSongsListDisplay: fullTopSongsList is empty")
            btnSeeMoreSongs.visibility = View.GONE
            return
        }

        val listToShow = if (isSongsExpanded) {
            fullTopSongsList // Mostramos las 10
        } else {
            fullTopSongsList.take(5) // Mostramos solo las primeras 5
        }

        Log.d("ArtistFragment", "updateSongsListDisplay: showing ${listToShow.size} songs, expanded=$isSongsExpanded")

        // Enviamos la sub-lista al adaptador
        topSongsAdapter.submitList(listToShow) {
            if (isAdded) checkPlayButtonState()
        }

        // Lógica del botón
        if (fullTopSongsList.size > 5) {
            btnSeeMoreSongs.visibility = View.VISIBLE
            if (isSongsExpanded) {
                btnSeeMoreSongs.text = "Ver menos"
                btnSeeMoreSongs.setIconResource(R.drawable.ic_keyboard_arrow_up)
            } else {
                val remaining = fullTopSongsList.size - 5
                btnSeeMoreSongs.text = "Ver $remaining más"
                btnSeeMoreSongs.setIconResource(R.drawable.ic_keyboard_arrow_down)
            }
        } else {
            btnSeeMoreSongs.visibility = View.GONE
        }
    }

    private fun setupArtistDescription(description: String?) {
        if (!description.isNullOrEmpty()) {
            artistDescription.text = description
            artistDescription.visibility = View.VISIBLE
            descriptionContainer.visibility = View.VISIBLE

            artistDescription.post {
                val layout = artistDescription.layout
                if (layout != null) {
                    val lines = layout.lineCount
                    if (lines > 0 && layout.getEllipsisCount(lines - 1) > 0) {
                        hasDescriptionOverflow = true
                    } else {
                        hasDescriptionOverflow = false
                    }
                    updateReadMoreVisibility()
                }
            }
        } else {
            descriptionContainer.visibility = View.GONE
            hasDescriptionOverflow = false
            updateReadMoreVisibility()
        }
    }

    private fun updateReadMoreVisibility() {
        if (hasDescriptionOverflow || hasGalleryImages) {
            btnReadMore.visibility = View.VISIBLE
            
            val toggleListener = View.OnClickListener {
                toggleDescriptionAnimation()
            }
            btnReadMore.setOnClickListener(toggleListener)
            
            if (hasDescriptionOverflow) {
                artistDescription.setOnClickListener(toggleListener)
            } else {
                artistDescription.setOnClickListener(null)
            }
        } else {
            btnReadMore.visibility = View.GONE
            btnReadMore.setOnClickListener(null)
            artistDescription.setOnClickListener(null)
        }
    }

    private fun toggleDescriptionAnimation() {
        TransitionManager.beginDelayedTransition(descriptionContainer.parent as ViewGroup)
        isDescriptionExpanded = !isDescriptionExpanded

        if (isDescriptionExpanded) {
            // EXPANDIR
            artistDescription.maxLines = Int.MAX_VALUE
            btnReadMore.text = "Ocultar"
            btnReadMore.setIconResource(R.drawable.ic_keyboard_arrow_up)
            
            if (hasGalleryImages) {
                galleryContainer.visibility = View.VISIBLE
            }
        } else {
            // COLAPSAR
            artistDescription.maxLines = 3
            btnReadMore.text = "Más info"
            btnReadMore.setIconResource(R.drawable.ic_keyboard_arrow_down)
            
            galleryContainer.visibility = View.GONE
        }
    }

    private fun setupPlayButtonLogic() {
        // Observamos cambios en la canción actual
        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            // Actualizamos el adapter (las barritas animadas en la canción individual)
            currentSong?.let { topSongsAdapter.setCurrentPlayingSong(it.id) }
            // Actualizamos el botón grande
            checkPlayButtonState()
        }

        // Observamos si está en Play o Pause
        songViewModel.isPlayingLiveData.observe(viewLifecycleOwner) {
            checkPlayButtonState()
        }
    }

    private fun checkPlayButtonState() {
        val serviceIsPlaying = songViewModel.isPlayingLiveData.value ?: false
        val currentSongId = songViewModel.currentSongLiveData.value?.id

        // Verificamos si la canción que suena está en la lista de Top Songs
        val isSongInList = topSongsAdapter.currentList.any { it.id == currentSongId }

        this.isPlaying = serviceIsPlaying && isSongInList
        updatePlayPauseIcon(this.isPlaying)
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        playButton.setIconResource(iconRes)
    }

    private fun setupClickListeners() {
        arrowGoBackButton.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        btnSeeMoreSongs.setOnClickListener {
            try {
                // Creamos una transición personalizada
                val transition = AutoTransition()
                transition.duration = 200
                transition.excludeChildren(recyclerViewTopSongs, true)

                // Aplicamos la transición AL CONTENEDOR ESPECÍFICO
                TransitionManager.beginDelayedTransition(listSongsContainer, transition)

                // Cambiamos estado
                isSongsExpanded = !isSongsExpanded

                // Actualizamos la lista
                updateSongsListDisplay()
            } catch (e: Exception) {
                Log.e("ArtistFragment", "Error during songs expansion", e)
                isSongsExpanded = !isSongsExpanded
                updateSongsListDisplay()
            }
        }

        // --- NUEVO: Listener del Botón Play Grande ---
        playButton.setOnClickListener {
            val currentList = ArrayList(topSongsAdapter.currentList)
            if (currentList.isEmpty()) return@setOnClickListener

            if (isPlaying) {
                // Si está sonando esta lista, pausamos
                val intent = Intent(requireContext(), MusicPlaybackService::class.java)
                intent.action = MusicPlaybackService.Companion.ACTION_PAUSE
                requireContext().startService(intent)
            } else {
                // Si no, reproducimos desde el principio (index 0)
                val firstSong = currentList[0]
                val queueId = currentArtist?.id ?: "ARTIST_UNKNOWN"

                val playIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                    action = MusicPlaybackService.Companion.ACTION_PLAY
                    putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_SONG, firstSong)
                    putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_INDEX, 0)
                    putParcelableArrayListExtra(MusicPlaybackService.Companion.SONG_LIST, currentList)
                    putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE, QueueSource.TOP_SONGS_ARTIST)
                    putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE_ID, queueId)
                }
                requireContext().startService(playIntent)
            }
        }
        // ---------------------------------------------

        // Listener canciones individuales (Tu lógica original)
        val currentHomeQueueId = System.currentTimeMillis().toString() // Nota: Quizás quieras usar el ID del artista aquí, pero dejé tu lógica original.
        topSongsAdapter.onItemClick = { (song, bitmap) ->
            val currentIndex = topSongsAdapter.currentList.indexOfFirst { it.id == song.id }
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

        lifecycleScope.launch {
            downloadViewModel.downloadedSongIds.collect { downloadedIds ->
                topSongsAdapter.downloadedSongIds = downloadedIds
                if (topSongsAdapter.currentList.isNotEmpty()) {
                    topSongsAdapter.notifyDataSetChanged()
                }
            }
        }

        topSongsAdapter.onFavoriteClick = { song, _ -> favoritesViewModel.toggleFavoriteSong(song) }

        topSongsAdapter.onSettingsClick = { song ->
            lifecycleScope.launch {
                try {
                    // Use artists already embedded in the song from the API
                    song.artistName = song.artists.joinToString(", ") { it.name }

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
                        },
                        onDownloadClick = { songToDownload ->
                            downloadViewModel.downloadSong(songToDownload)
                        },
                        onRemoveDownloadClick = { songToDelete ->
                            downloadViewModel.deleteSong(songToDelete)
                        },
                        onGoToAlbumClick = { albumId ->
                             val bundle = Bundle().apply { putString("albumId", albumId) }
                             findNavController().navigate(R.id.action_artistFragment_to_albumFragment, bundle)
                        },
                        onGoToArtistClick = { artist ->
                             val bundle = Bundle().apply { 
                                 putString("artistId", artist.id)
                                 putString("artistName", artist.name)
                                 putString("artistImageUrl", artist.url)
                            }
                            findNavController().navigate(R.id.action_artistFragment_self, bundle)
                        }
                    )
                    bottomSheet.show(parentFragmentManager, "SongOptionsBottomSheet")
                } catch (e: Exception) {
                    Log.e("ArtistFragment", "Error loading song artist details", e)
                }
            }
        }

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

    private fun showFullScreenImage(imageUrl: String) {
        val dialog = android.app.Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_fullscreen_image)

        // Fondo de la ventana del diálogo transparente para que se vean las esquinas redondeadas
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val imageView = dialog.findViewById<ImageView>(R.id.fullscreenImageView)
        val btnClose = dialog.findViewById<View>(R.id.btnCloseGallery)

        Glide.with(this)
            .load(imageUrl)
            .into(imageView)

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        imageView.setOnClickListener {
            dialog.dismiss() // Permite cerrarla tocando la foto
        }

        dialog.show()
    }
}