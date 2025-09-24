package com.example.resonant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.bumptech.glide.Glide
import com.example.resonant.SnackbarUtils.showResonantSnackbar
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistFragment : BaseFragment(R.layout.fragment_playlist) {

    private lateinit var songAdapter: SongAdapter

    private lateinit var noSongsInPlaylistText: TextView
    private lateinit var playlistNumberOfTracks: TextView
    private lateinit var playlistName: TextView
    private lateinit var playlistOwner: TextView
    private lateinit var playlistDuration: TextView
    private lateinit var separator: TextView
    private lateinit var playlistImage: FrameLayout
    private lateinit var recyclerView: RecyclerView

    private val _currentSongLiveData = MutableLiveData<Song>()
    private lateinit var sharedViewModel: SharedViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel

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
        val view = inflater.inflate(R.layout.fragment_playlist, container, false)

        val playlistId = arguments?.getString("playlistId")

        noSongsInPlaylistText = view.findViewById(R.id.noSongsInPlaylist)
        playlistName = view.findViewById(R.id.playlistName)
        playlistOwner = view.findViewById(R.id.playlistOwner)
        playlistDuration = view.findViewById(R.id.playlistDuration)
        separator = view.findViewById(R.id.playlistDuration)
        playlistNumberOfTracks = view.findViewById(R.id.playlistNumberOfTracks)
        playlistImage = view.findViewById(R.id.playlistCollageContainer)
        recyclerView = view.findViewById(R.id.songList)

        songAdapter = SongAdapter()
        recyclerView.apply {
            adapter = songAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }

        val playlistLoader = view.findViewById<LottieAnimationView>(R.id.lottieLoader)

        if (playlistId != null) {
            playlistLoader.visibility = View.VISIBLE // Muestra el loader

            val service = ApiClient.getService(requireContext())
            val playlistManager = PlaylistManager(service)

            lifecycleScope.launch {
                try {
                    val playlist = playlistManager.getPlaylistById(playlistId)
                    val songs = getSongsFromPlaylist(requireContext(), playlistId)

                    if (songs.isNotEmpty()) {
                        noSongsInPlaylistText.visibility = View.INVISIBLE
                    }

                    var ownerName = ""
                    playlist.userId?.let { userId ->
                        try {
                            val user = service.getUserById(userId)
                            ownerName = user.name ?: ""
                        } catch (e: Exception) {
                            ownerName = ""
                        }
                    }
                    playlistOwner.text = ownerName
                    playlistName.text = playlist.name ?: ""
                    playlistNumberOfTracks.text = "${playlist.numberOfTracks} canciones"
                    playlistDuration.text = Utils.formatDuration(playlist.duration ?: 0)

// === Nuevo Collage ===
// Obtén las 4 primeras canciones
                    val firstSongs = songs.take(4)
// Obtén sus URLs de portada correctas (coverUrl)
                    val coverUrls = firstSongs.mapNotNull { it.coverUrl }

// Descarga los Bitmaps usando Glide en IO
                    val bitmaps = withContext(Dispatchers.IO) {
                        coverUrls.map { url ->
                            try {
                                Glide.with(requireContext())
                                    .asBitmap()
                                    .load(url)
                                    .submit()
                                    .get()
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }

// Rellena con nulls si faltan portadas para tener siempre 4
                    val paddedBitmaps = (bitmaps + List(4 - bitmaps.size) { null }).take(4)

// Asegúrate que playlistImage.width tenga valor (o pon 200 si aún es 0)
                    val collageSize = playlistImage.width.takeIf { it > 0 } ?: 200

// Crea el collage
                    val collageBitmap = createCollageBitmap(
                        requireContext(),
                        paddedBitmaps,
                        R.drawable.playlist_stack,
                        collageSize
                    )

// Obtén las ImageViews del contenedor
                    val collageContainer = view?.findViewById<FrameLayout>(R.id.playlistCollageContainer)
                    val imgViews = listOfNotNull(
                        collageContainer?.findViewById<ShapeableImageView>(R.id.img0),
                        collageContainer?.findViewById<ShapeableImageView>(R.id.img1),
                        collageContainer?.findViewById<ShapeableImageView>(R.id.img2),
                        collageContainer?.findViewById<ShapeableImageView>(R.id.img3)
                    )

// Asigna los bitmaps a las ImageViews (usa placeholder si no hay bitmap)
                    val placeholder = ContextCompat.getDrawable(requireContext(), R.drawable.playlist_stack)
                    imgViews.forEachIndexed { idx, imgView ->
                        val bmp = paddedBitmaps.getOrNull(idx)
                        if (bmp != null) {
                            imgView.setImageBitmap(bmp)
                        } else {
                            imgView.setImageDrawable(placeholder)
                        }
                    }
// === Fin Collage ===

                    songAdapter.submitList(songs)
                    playlistLoader.visibility = View.GONE
                    separator.visibility = View.VISIBLE // Oculta el loader

                } catch (e: Exception) {
                    playlistLoader.visibility = View.GONE // Oculta el loader
                    Toast.makeText(requireContext(), "Error cargando playlist", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(requireContext(), "No se encontró la playlist", Toast.LENGTH_SHORT).show()
        }

        sharedViewModel = ViewModelProvider(requireActivity()).get(SharedViewModel::class.java)

        sharedViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            currentSong?.let {
                songAdapter.setCurrentPlayingSong(it.id)
            }
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

        songAdapter.onItemClick = { (song, bitmap) ->
            val currentIndex = songAdapter.currentList.indexOfFirst { it.url == song.url }
            val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, song.id) }
            val songList = ArrayList(songAdapter.currentList)

            val playIntent = Intent(context, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_PLAY
                putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, song)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, currentIndex)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, songList)
            }

            requireContext().startService(playIntent)
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
                            R.id.action_playlistFragment_to_detailedSongFragment,
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
                    },
                    playlistId = playlistId,
                    onRemoveFromPlaylistClick = { songToRemove, id ->
                        lifecycleScope.launch {
                            try {
                                service.deleteSongFromPlaylist(songToRemove.id, id) // tu método API

                                val updatedList = songAdapter.currentList.toMutableList().apply {
                                    removeAll { it.id == songToRemove.id }
                                }
                                songAdapter.submitList(updatedList)

                                showResonantSnackbar(
                                    text = "Canción eliminada de la playlist",
                                    colorRes = R.color.successColor,
                                    iconRes = R.drawable.success
                                )

                            } catch (e: Exception) {
                                // Mostrar snackbar de error
                                showResonantSnackbar(
                                    text = "Error al eliminar canción",
                                    colorRes = R.color.errorColor,
                                    iconRes = R.drawable.error
                                )
                            }
                        }
                    }
                )
                bottomSheet.show(parentFragmentManager, "SongOptionsBottomSheet")
            }
        }

        view.findViewById<ImageButton>(R.id.arrowGoBackButton).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        return view
    }

    suspend fun getSongsFromPlaylist(
        context: Context,
        playlistId: String,
        cancionesAnteriores: List<Song>? = null
    ): List<Song> {
        return try {
            val service = ApiClient.getService(context)
            val cancionesDePlaylist = service.getSongsByPlaylistId(playlistId).toMutableList()
            Log.d("PlaylistFragment", "Canciones obtenidas del servidor: ${cancionesDePlaylist.size}")

            // Enriquecer con artistas y reutilizar URLs previas si existen
            for (song in cancionesDePlaylist) {
                val artistList = service.getArtistsBySongId(song.id)
                song.artistName = artistList.joinToString(", ") { it.name }
                val cachedSong = cancionesAnteriores?.find { it.id == song.id }
                if (cachedSong != null) {
                    song.url = cachedSong.url
                    song.coverUrl = cachedSong.coverUrl // reaprovechar si ya estaba cargada
                }
            }

            // Obtener URLs prefirmadas de audio si faltan
            val cancionesSinUrl = cancionesDePlaylist.filter { it.url == null }
            if (cancionesSinUrl.isNotEmpty()) {
                val fileNames = cancionesSinUrl.map { it.fileName }
                val urlList = service.getMultipleSongUrls(fileNames)
                val urlMap = urlList.associateBy { it.fileName }
                cancionesSinUrl.forEach { song ->
                    song.url = urlMap[song.fileName]?.url
                }
            }

// === NUEVO BLOQUE PARA PORTADAS ===
// Construir request solo con canciones que tengan ambos datos
            val coversRequest = cancionesDePlaylist.mapNotNull { song ->
                song.imageFileName?.takeIf { it.isNotBlank() }?.let { fileName ->
                    song.albumId.takeIf { it.isNotBlank() }?.let { albumId ->
                        fileName to albumId
                    }
                }
            }

            if (coversRequest.isNotEmpty()) {
                val (fileNames, albumIds) = coversRequest.unzip()

                // Retrofit devuelve List<CoverResponse>
                val coverResponses: List<CoverResponse> = service.getMultipleSongCoverUrls(fileNames, albumIds)

                // Convertimos a Map (imageFileName, albumId) -> url
                val coverUrlMap: Map<Pair<String, String>, String> = coverResponses.associateBy(
                    keySelector = { it.imageFileName to it.albumId },
                    valueTransform = { it.url }
                )

                // Asignamos URLs a las canciones correspondientes
                cancionesDePlaylist.forEach { song ->
                    val key = song.imageFileName to song.albumId
                    song.coverUrl = coverUrlMap[key]
                }
            }


            // Avisar al servicio de reproducción que se actualizó la playlist
            val updateIntent = Intent(context, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.UPDATE_SONGS
                putParcelableArrayListExtra(
                    MusicPlaybackService.SONG_LIST,
                    ArrayList(cancionesDePlaylist)
                )
            }
            context.startService(updateIntent)

            cancionesDePlaylist
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun createCollageBitmap(context: Context, bitmaps: List<Bitmap?>, placeholderRes: Int, size: Int): Bitmap {
        val collage = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(collage)
        val half = size / 2
        val drawable = context.getDrawable(placeholderRes)!!
        val placeholderBitmap = drawableToBitmap(drawable, half)

        for (i in 0 until 4) {
            var bmp = bitmaps.getOrNull(i) ?: placeholderBitmap
            val left = if (i % 2 == 0) 0 else half
            val top = if (i < 2) 0 else half
            val rect = Rect(left, top, left + half, top + half)
            canvas.drawBitmap(bmp, null, rect, null)
        }
        return collage
    }

    fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

}