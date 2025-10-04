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
import com.facebook.shimmer.ShimmerFrameLayout
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

    private lateinit var sharedViewModel: SharedViewModel

    private val songChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MusicPlaybackService.ACTION_SONG_CHANGED) {
                val song = intent.getParcelableExtra<Song>(MusicPlaybackService.EXTRA_CURRENT_SONG)
                song?.let {
                    topSongsAdapter.setCurrentPlayingSong(it.id)
                    sharedViewModel.setCurrentSong(it)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            songChangedReceiver,
            IntentFilter(MusicPlaybackService.ACTION_SONG_CHANGED)
        )
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
        topSongsAdapter = SongAdapter(SongAdapter.VIEW_TYPE_TOP_SONG)
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
            topBar.setBackgroundColor(android.graphics.Color.argb(alpha, 0, 0, 0))
            if (progress > 0f) {
                if (artistNameTopBar.visibility != View.VISIBLE) artistNameTopBar.visibility = View.VISIBLE
                artistNameTopBar.alpha = progress // de 0f (transparente) a 1f (opaco)
            } else {
                if (artistNameTopBar.visibility != View.INVISIBLE) artistNameTopBar.visibility = View.INVISIBLE
                artistNameTopBar.alpha = 0f
            }
        }

        sharedViewModel = ViewModelProvider(requireActivity()).get(SharedViewModel::class.java)

        sharedViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
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
                )
                bottomSheet.show(parentFragmentManager, "SongOptionsBottomSheet")
            }
        }

        topSongsAdapter.onFavoriteClick = { song, wasFavorite ->
            favoritesViewModel.toggleFavoriteSong(song)
        }

        var currentHomeQueueId: String = System.currentTimeMillis().toString() // o UUID.randomUUID().toString()

        topSongsAdapter.onItemClick = { (song, bitmap) ->
            val currentIndex = topSongsAdapter.currentList.indexOfFirst { it.url == song.url }
            val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, song.id) }
            val songList = ArrayList(topSongsAdapter.currentList)

            val playIntent = Intent(context, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_PLAY
                putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, song)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, currentIndex)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, songList)
                putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE, QueueSource.TOP_SONGS_ARTIST)
                putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE_ID, currentHomeQueueId)
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
                    favoritesViewModel.addFavoriteArtist(artist) { success ->
                        if (success) {
                            showResonantSnackbar(
                                text = "¡Artista añadido a favoritos!",
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

        arrowGoBackButton.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(songChangedReceiver)
    }

    private fun loadArtistDetails(artistId: String) {
        lifecycleScope.launch {
            try {
                // 1. Iniciar estado de carga
                recyclerViewAlbums.visibility = View.GONE
                topSongsTitle.visibility = View.GONE
                recyclerViewTopSongs.visibility = View.GONE
                titleAlbumSongs.visibility = View.GONE

                // 2. Obtener detalles del artista
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

                // 3. Obtener los álbumes del artista
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

                val topSongs = api.getMostStreamedSongsByArtist(artistId, 5).toMutableList()

                if (topSongs.isNotEmpty()) {
                    topSongs.forEach { it.artistName = artist.name ?: "Desconocido" }

                    val songsSinUrl = topSongs.filter { it.coverUrl.isNullOrEmpty() }

                    val coversRequest = songsSinUrl.mapNotNull { song ->
                        val fileName = song.imageFileName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val albumId = song.albumId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        fileName.trim() to albumId.trim()
                    }

                    if (coversRequest.isNotEmpty()) {
                        val (fileNames, albumIds) = coversRequest.unzip()
                        try {
                            val coverResponses = api.getMultipleSongCoverUrls(fileNames, albumIds)

                            val coverUrlMap = coverResponses.associate {
                                (it.imageFileName.trim() to it.albumId.trim()) to it.url
                            }

                            songsSinUrl.forEach { song ->
                                if (song.imageFileName != null && song.albumId != null) {
                                    val key = song.imageFileName!!.trim() to song.albumId!!.trim()
                                    song.coverUrl = coverUrlMap[key]
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("ArtistFragment", "Error obteniendo carátulas de canciones batch: ${e.message}")
                        }
                    }

                    val songsSinAudioUrl = topSongs.filter { it.url == null }
                    if (songsSinAudioUrl.isNotEmpty()) {
                        val audioFileNames = songsSinAudioUrl.map { it.fileName }
                        try {
                            val urlList = api.getMultipleSongUrls(audioFileNames)
                            val urlMap = urlList.associateBy { it.fileName }

                            songsSinAudioUrl.forEach { song ->
                                song.url = urlMap[song.fileName]?.url
                            }
                        } catch (e: Exception) {
                            Log.e("ArtistFragment", "Error obteniendo URLs de audio batch: ${e.message}")
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
            if (isFavorite) R.drawable.favorite else R.drawable.favorite_border
        )
    }

}