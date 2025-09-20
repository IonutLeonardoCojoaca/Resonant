package com.example.resonant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.facebook.shimmer.ShimmerFrameLayout
import com.squareup.picasso.Picasso
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

    private lateinit var songAdapter: SongAdapter
    private lateinit var sharedViewModel: SharedViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel

    private lateinit var shimmerLayout: ShimmerFrameLayout

    private lateinit var api: ApiResonantService

    private val songChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MusicPlaybackService.ACTION_SONG_CHANGED) {
                val song = intent.getParcelableExtra<Song>(MusicPlaybackService.EXTRA_CURRENT_SONG)
                song?.let {
                    songAdapter.setCurrentPlayingSong(it.id)
                    sharedViewModel.setCurrentSong(it)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_album, container, false)

        arrowGoBackButton = view.findViewById(R.id.arrowGoBackBackground)
        recyclerViewSongs = view.findViewById(R.id.albumSongsContainer)
        recyclerViewSongs.layoutManager = LinearLayoutManager(requireContext())
        songAdapter = SongAdapter()
        recyclerViewSongs.adapter = songAdapter

        shimmerLayout = view.findViewById(R.id.shimmerLayout)

        albumName = view.findViewById(R.id.albumName)
        albumArtistName = view.findViewById(R.id.albumArtistName)
        albumImage = view.findViewById(R.id.artistImage)
        albumDuration = view.findViewById(R.id.albumDuration)
        albumNumberOfTracks = view.findViewById(R.id.albumNumberOfTracks)
        nestedScroll = view.findViewById(R.id.nested_scroll)

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

        nestedScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val parallaxFactor = 0.3f
            val offset = -scrollY * parallaxFactor
            albumImage.translationY = offset
        }

        sharedViewModel = ViewModelProvider(requireActivity())[SharedViewModel::class.java]

        sharedViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            currentSong?.let {
                songAdapter.setCurrentPlayingSong(it.id)
            }
        }

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            songChangedReceiver,
            IntentFilter(MusicPlaybackService.ACTION_SONG_CHANGED)
        )

        songAdapter.onItemClick = { (song, bitmap) ->
            val currentIndex = songAdapter.currentList.indexOfFirst { it.url == song.url }
            val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, song.id) }
            val songList = ArrayList(songAdapter.currentList)

            val playIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_PLAY
                putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, song)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, currentIndex)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, songList)
            }

            requireContext().startService(playIntent)
        }

        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]

        // Cargar favoritos al inicio
        favoritesViewModel.loadFavorites()

        favoritesViewModel.favorites.observe(viewLifecycleOwner) { favorites ->
            val ids = favorites.map { it.id }.toSet()
            songAdapter.favoriteSongIds = ids
            songAdapter.submitList(songAdapter.currentList)
        }

        songAdapter.onFavoriteClick = { song, isFavorite ->
            if (isFavorite) {
                songAdapter.favoriteSongIds = songAdapter.favoriteSongIds - song.id
                songAdapter.notifyItemChanged(
                    songAdapter.currentList.indexOfFirst { it.id == song.id },
                    "silent"
                )

                favoritesViewModel.deleteFavorite(song.id) { success ->
                    if (!success) {
                        // Revertir si falla
                        songAdapter.favoriteSongIds = songAdapter.favoriteSongIds + song.id
                        songAdapter.notifyItemChanged(
                            songAdapter.currentList.indexOfFirst { it.id == song.id },
                            "silent"
                        )
                        Toast.makeText(requireContext(), "Error al eliminar favorito", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                songAdapter.favoriteSongIds = songAdapter.favoriteSongIds + song.id
                songAdapter.notifyItemChanged(
                    songAdapter.currentList.indexOfFirst { it.id == song.id },
                    "silent"
                )

                favoritesViewModel.addFavorite(song) { success ->
                    if (!success) {
                        // Revertir si falla
                        songAdapter.favoriteSongIds = songAdapter.favoriteSongIds - song.id
                        songAdapter.notifyItemChanged(
                            songAdapter.currentList.indexOfFirst { it.id == song.id },
                            "silent"
                        )
                        Toast.makeText(requireContext(), "Error al añadir favorito", Toast.LENGTH_SHORT).show()
                    }
                }
            }
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
                    onFavoriteToggled = { toggledSong, wasFavorite ->
                        if (wasFavorite) {
                            songAdapter.favoriteSongIds = songAdapter.favoriteSongIds - toggledSong.id
                        } else {
                            songAdapter.favoriteSongIds = songAdapter.favoriteSongIds + toggledSong.id
                        }
                        songAdapter.notifyItemChanged(
                            songAdapter.currentList.indexOfFirst { it.id == toggledSong.id },
                            "silent"
                        )
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

                val album = api.getAlbumById(albumId)
                val albumFileName = album.fileName
                val albumImageUrl = albumFileName?.let { api.getAlbumUrl(it).url }.orEmpty()
                val artistName = album.artistName ?: run {
                    val artistList = api.getArtistsByAlbumId(albumId)
                    artistList.firstOrNull()?.name ?: "Artista desconocido"
                }

                albumName.text = album.title ?: "Sin título"
                albumArtistName.text = artistName

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

                val songs = getSongsFromAlbum(requireContext(), albumId)

                if (albumImageUrl.isNotBlank()) {
                    songs.forEach { it.albumImageUrl = albumImageUrl }
                } else {
                    songs.forEach { it.albumImageUrl = null }
                }

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


    suspend fun getSongsFromAlbum(
        context: Context,
        albumId: String,
        cancionesAnteriores: List<Song>? = null
    ): List<Song> {
        return try {
            val service = ApiClient.getService(context)
            val cancionesDelAlbum = service.getSongsByAlbumId(albumId).toMutableList()
            Log.d("AlbumFragment", "Canciones obtenidas del servidor: ${cancionesDelAlbum.size}")

            for (song in cancionesDelAlbum) {
                val artistList = service.getArtistsBySongId(song.id)
                song.artistName = artistList.joinToString(", ") { it.name }

                val cachedSong = cancionesAnteriores?.find { it.id == song.id }
                if (cachedSong != null) {
                    song.url = cachedSong.url
                }
            }

            val cancionesSinUrl = cancionesDelAlbum.filter { it.url == null }
            if (cancionesSinUrl.isNotEmpty()) {
                val fileNames = cancionesSinUrl.map { it.fileName }
                val urlList = service.getMultipleSongUrls(fileNames)
                val urlMap = urlList.associateBy { it.fileName }

                cancionesSinUrl.forEach { song ->
                    song.url = urlMap[song.fileName]?.url
                }
            }

            val updateIntent = Intent(context, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.UPDATE_SONGS
                putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, ArrayList(cancionesDelAlbum))
            }
            context.startService(updateIntent)

            cancionesDelAlbum
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }


}