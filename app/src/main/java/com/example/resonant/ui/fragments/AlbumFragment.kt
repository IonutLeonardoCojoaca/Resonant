package com.example.resonant.ui.fragments

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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
import com.example.resonant.data.network.ApiClient
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.utils.ImageRequestHelper
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
import com.example.resonant.data.network.services.AlbumService
import com.example.resonant.data.network.services.ArtistService
import com.example.resonant.managers.SongManager
import com.example.resonant.ui.viewmodels.DownloadViewModel
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import com.example.resonant.managers.AlbumManager

class AlbumFragment : BaseFragment(R.layout.fragment_album) {

    private lateinit var arrowGoBackButton: FrameLayout
    private lateinit var albumName: TextView
    private lateinit var albumArtistName: TextView
    private lateinit var albumImage: ImageView
    private lateinit var recyclerViewSongs: RecyclerView
    private lateinit var nestedScroll: NestedScrollView
    private lateinit var topBar: ConstraintLayout
    private lateinit var albumTopBarText: TextView
    private lateinit var favoriteButton: FrameLayout
    private lateinit var albumMetadataText: TextView

    private lateinit var songAdapter: SongAdapter
    private lateinit var songViewModel: SongViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel
    private var loadedAlbum: Album? = null
    private lateinit var favoriteIcon: ImageView

    private lateinit var shimmerLayout: ShimmerFrameLayout

    private lateinit var albumService: AlbumService
    private lateinit var artistService: ArtistService

    private lateinit var songManager: SongManager
    private lateinit var downloadViewModel: DownloadViewModel

    // Estado local de reproducción
    private var isPlaying: Boolean = false
    private lateinit var playButton: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_album, container, false)

        // Inicializar vistas
        initViews(view)

        val albumId = arguments?.getString("albumId") ?: return view

        // --- INICIALIZACIÓN ---
        val context = requireContext()
        albumService = ApiClient.getAlbumService(context)
        artistService = ApiClient.getArtistService(context)
        songManager = SongManager(context)

        setupAnimations()

        // --- VIEWMODELS ---
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]
        songViewModel = ViewModelProvider(requireActivity())[SongViewModel::class.java]

        setupObservers(albumId)

        // --- NUEVO: Configurar lógica visual del botón play/pause ---
        setupPlayButtonLogic()

        setupClickListeners()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // --- LÓGICA DE DESCARGAS ---
        lifecycleScope.launch {
            downloadViewModel.downloadedSongIds.collect { downloadedIds ->
                songAdapter.downloadedSongIds = downloadedIds
                if (songAdapter.currentList.isNotEmpty()) {
                    songAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val albumId = arguments?.getString("albumId")
        if (!albumId.isNullOrBlank()) {
             loadAlbumDetails(albumId)
        }
    }

    private fun initViews(view: View) {
        arrowGoBackButton = view.findViewById(R.id.arrowGoBackBackground)
        recyclerViewSongs = view.findViewById(R.id.albumSongsContainer)
        recyclerViewSongs.layoutManager = LinearLayoutManager(requireContext())

        // Inicializar Adapter
        songAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_FULL)
        recyclerViewSongs.adapter = songAdapter

        topBar = view.findViewById(R.id.topBar)
        shimmerLayout = view.findViewById(R.id.shimmerSongLayout)
        albumName = view.findViewById(R.id.albumName)
        albumTopBarText = view.findViewById(R.id.albumTopBarText)
        albumArtistName = view.findViewById(R.id.albumArtistName)
        albumImage = view.findViewById(R.id.artistImage)
        albumMetadataText = view.findViewById(R.id.albumMetadataText)
        nestedScroll = view.findViewById(R.id.nested_scroll)
        favoriteButton = view.findViewById(R.id.favoriteBackground)
        favoriteIcon = view.findViewById(R.id.favoriteButton)
        playButton = view.findViewById(R.id.playButton)
    }

    private fun setupAnimations() {
        // Animación inicial de la imagen
        albumImage.scaleX = 1.1f
        albumImage.scaleY = 1.1f
        albumImage.alpha = 0f
        albumImage.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(1000).start()

        // 1. PREPARAR EL COLOR (Fuera del loop)
        val themeColor = ContextCompat.getColor(requireContext(), R.color.primaryColorTheme)
        val red = Color.red(themeColor)
        val green = Color.green(themeColor)
        val blue = Color.blue(themeColor)

        val startFade = 200
        val endFade = 500

        albumTopBarText.visibility = View.INVISIBLE
        albumTopBarText.alpha = 0f

        nestedScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val parallaxFactor = 0.3f
            val offset = -scrollY * parallaxFactor
            albumImage.translationY = offset

            val progress = ((scrollY - startFade).toFloat() / (endFade - startFade).toFloat()).coerceIn(0f, 1f)
            val alpha = (progress * 255).toInt()

            // 2. APLICAR EL COLOR GRIS OSCURO (#121212) CON LA TRANSPARENCIA CALCULADA
            topBar.setBackgroundColor(Color.argb(alpha, red, green, blue))

            if (progress > 0f) {
                if (albumTopBarText.visibility != View.VISIBLE) albumTopBarText.visibility = View.VISIBLE
                albumTopBarText.alpha = progress
            } else {
                if (albumTopBarText.visibility != View.INVISIBLE) albumTopBarText.visibility = View.INVISIBLE
                albumTopBarText.alpha = 0f
            }
        }
    }

    private fun setupObservers(albumId: String) {
        favoritesViewModel.loadAllFavorites()
        favoritesViewModel.favoriteAlbumIds.observe(viewLifecycleOwner) { ids ->
            val isFavorite = albumId.let { ids.contains(it) }
            updateFavoriteIcon(isFavorite)
        }

        // Ya observamos currentSongLiveData dentro de setupPlayButtonLogic,
        // pero mantenemos esto por si hay lógica extra en setupPlayButtonLogic
        // que no cubra setCurrentPlayingSong.
        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            currentSong?.let { songAdapter.setCurrentPlayingSong(it.id) }
        }

        favoritesViewModel.loadFavoriteSongs()
        favoritesViewModel.favoriteSongIds.observe(viewLifecycleOwner) { songIds ->
            songAdapter.favoriteSongIds = songIds
            if(songAdapter.currentList.isNotEmpty()) songAdapter.notifyDataSetChanged()
        }
    }

    // --- NUEVO: Lógica para sincronizar el botón de Play con el estado del servicio ---
    private fun setupPlayButtonLogic() {
        fun updateButtonState() {
            val serviceIsPlaying = songViewModel.isPlayingLiveData.value ?: false
            val currentSongId = songViewModel.currentSongLiveData.value?.id

            // Verificamos si la canción sonando está en la lista de este álbum
            val isSongInList = songAdapter.currentList.any { it.id == currentSongId }

            // Solo mostramos "Pause" si la música suena Y pertenece a este álbum
            this.isPlaying = serviceIsPlaying && isSongInList
            updatePlayPauseIcon(this.isPlaying)
        }

        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) {
            songAdapter.setCurrentPlayingSong(it?.id)
            updateButtonState()
        }

        songViewModel.isPlayingLiveData.observe(viewLifecycleOwner) {
            updateButtonState()
        }
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        // Asegúrate de tener un icono de pausa (ic_pause) y uno de play (ic_play_arrow)
        val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        playButton.setIconResource(iconRes)
        // Opcional: Cambiar texto si lo deseas
        // playButton.text = if (isPlaying) "Pausar" else "Reproducir"
    }

    private fun setupClickListeners() {
        arrowGoBackButton.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        favoriteButton.setOnClickListener {
            loadedAlbum?.let { album ->
                val isFavorite = favoritesViewModel.favoriteAlbumIds.value?.contains(album.id) ?: false
                if (isFavorite) {
                    favoritesViewModel.deleteFavoriteAlbum(album.id) { success ->
                        if (success) showResonantSnackbar(text = "¡Álbum eliminado!", colorRes = R.color.successColor, iconRes = R.drawable.ic_success)
                        else showResonantSnackbar(text = "Error", colorRes = R.color.errorColor, iconRes = R.drawable.ic_error)
                    }
                } else {
                    favoritesViewModel.addFavoriteAlbum(album) { success ->
                        if (success) showResonantSnackbar(text = "¡Álbum añadido!", colorRes = R.color.successColor, iconRes = R.drawable.ic_success)
                        else showResonantSnackbar(text = "Error", colorRes = R.color.errorColor, iconRes = R.drawable.ic_error)
                    }
                }
            }
        }

        // --- CORREGIDO: Lógica del botón Play ---
        playButton.setOnClickListener {
            val currentList = ArrayList(songAdapter.currentList)
            if (currentList.isEmpty()) return@setOnClickListener

            if (isPlaying) {
                // Si está sonando, pausamos
                val intent = Intent(requireContext(), MusicPlaybackService::class.java)
                intent.action = MusicPlaybackService.Companion.ACTION_PAUSE
                requireContext().startService(intent)
            } else {
                // Si está pausado o es otra canción, iniciamos desde el principio (index 0)
                val firstSong = currentList[0]
                val albumId = loadedAlbum?.id ?: "ALBUM_UNKNOWN"
                val bitmapPath: String? = null // Opcional: Utils.saveBitmapToCache(...)

                val playIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                    action = MusicPlaybackService.Companion.ACTION_PLAY
                    putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_SONG, firstSong)
                    putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_INDEX, 0)
                    putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                    putParcelableArrayListExtra(MusicPlaybackService.Companion.SONG_LIST, currentList)
                    putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE, QueueSource.ALBUM)
                    putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE_ID, albumId)
                }
                requireContext().startService(playIntent)
            }
        }

        songAdapter.onItemClick = { (song, bitmap) ->
            val currentIndex = songAdapter.currentList.indexOfFirst { it.id == song.id }
            val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, song.id) }
            val songList = ArrayList(songAdapter.currentList)
            val albumId = loadedAlbum?.id ?: song.album?.id ?: ""

            val playIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
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

        songAdapter.onFavoriteClick = { song, _ ->
            favoritesViewModel.toggleFavoriteSong(song)
        }

        songAdapter.onSettingsClick = { song ->
            lifecycleScope.launch {
                // Use artists already embedded in the song from the API
                song.artistName = song.artists.joinToString(", ") { it.name }

                val bottomSheet = SongOptionsBottomSheet(
                    song = song,
                    onSeeSongClick = { selectedSong ->
                        val bundle = Bundle().apply { putParcelable("song", selectedSong) }
                        findNavController().navigate(R.id.action_albumFragment_to_detailedSongFragment, bundle)
                    },
                    onFavoriteToggled = { toggledSong -> favoritesViewModel.toggleFavoriteSong(toggledSong) },
                    onAddToPlaylistClick = { songToAdd ->
                        val selectPlaylistBottomSheet = SelectPlaylistBottomSheet(
                            song = songToAdd,
                            onNoPlaylistsFound = { findNavController().navigate(R.id.action_global_to_createPlaylistFragment) }
                        )
                        selectPlaylistBottomSheet.show(parentFragmentManager, "SelectPlaylistBottomSheet")
                    },
                    onDownloadClick = { songToDownload ->
                        downloadViewModel.downloadSong(songToDownload)
                    },
                    onRemoveDownloadClick = { songToDelete ->
                        downloadViewModel.deleteSong(songToDelete)
                    },
                    onGoToAlbumClick = { albumId ->
                        // If we are already in this album, maybe just scroll to top? 
                        // Or simply re-navigate (simplest for now)
                        if (albumId != loadedAlbum?.id) {
                            val bundle = Bundle().apply { putString("albumId", albumId) }
                            // Uses self action or global action
                            findNavController().navigate(R.id.albumFragment, bundle)
                        }
                    },
                    onGoToArtistClick = { artist ->
                         val bundle = Bundle().apply { 
                             putString("artistId", artist.id)
                             putString("artistName", artist.name)
                             putString("artistImageUrl", artist.url)
                        }
                        findNavController().navigate(R.id.artistFragment, bundle)
                    }
                )
                bottomSheet.show(parentFragmentManager, "SongOptionsBottomSheet")
            }
        }
    }

    private fun loadAlbumDetails(albumId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Solo mostrar shimmer si no tenemos datos ya cargados
                if (loadedAlbum == null) {
                    shimmerLayout.visibility = View.VISIBLE
                    recyclerViewSongs.visibility = View.GONE
                }

                // USAR MANAGER en lugar de servicio directo
                val albumDeferred = async { AlbumManager.getAlbumById(requireContext(), albumId) }
                val songsDeferred = async { songManager.getSongsFromAlbum(albumId) }

                val album = albumDeferred.await()
                val songs = songsDeferred.await()
                
                if (album == null) {
                    Toast.makeText(requireContext(), "Error Cargando Álbum", Toast.LENGTH_SHORT).show()
                    // findNavController().popBackStack() // Opcional
                    shimmerLayout.visibility = View.GONE
                    return@launch
                }

                val artistName = album.artistName
                    ?: album.artists.joinToString(", ") { it.name }.ifEmpty { "Artista desconocido" }

                val albumImageUrl = album.url.orEmpty()

                albumName.text = album.title ?: "Sin título"
                albumArtistName.text = artistName
                albumTopBarText.text = album.title

                val albumImageModel = if (albumImageUrl.isNotBlank()) {
                    ImageRequestHelper.buildGlideModel(requireContext(), albumImageUrl)
                } else {
                    R.drawable.ic_disc
                }

                Glide.with(this@AlbumFragment)
                    .load(albumImageModel)
                    .error(R.drawable.ic_album_stack)
                    .into(albumImage)

                val durationStr = Utils.formatDuration(album.duration)
                val tracksStr = "${album.numberOfTracks} canciones"

                albumMetadataText.text = "$durationStr  ●  $tracksStr"
                albumMetadataText.visibility = View.VISIBLE

                loadedAlbum = album

                songAdapter.submitList(songs) {
                    songAdapter.notifyDataSetChanged()
                    // Importante: Llamamos a esto una vez se cargan los datos para actualizar el estado del botón Play
                    // en caso de que ya estemos reproduciendo una canción de este álbum al entrar.
                    if (isAdded) { // Chequeo de seguridad de Fragment
                        val currentSongId = songViewModel.currentSongLiveData.value?.id
                        val isPlayingService = songViewModel.isPlayingLiveData.value ?: false
                        val isSongInList = songs.any { it.id == currentSongId }
                        isPlaying = isPlayingService && isSongInList
                        updatePlayPauseIcon(isPlaying)
                    }
                }

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
            if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
    }
}