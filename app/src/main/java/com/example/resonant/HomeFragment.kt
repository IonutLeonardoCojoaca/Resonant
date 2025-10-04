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
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.facebook.shimmer.ShimmerFrameLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HomeFragment : BaseFragment(R.layout.fragment_home){

    private lateinit var recyclerViewArtists: RecyclerView
    private lateinit var artistAdapter: ArtistAdapter
    private var artistasCargados: List<Artist>? = null
    private var artistsList: MutableList<Artist> = mutableListOf()

    private lateinit var recyclerViewAlbums: RecyclerView
    private lateinit var albumsAdapter: AlbumAdapter
    private var albumsCargados: List<Album>? = null
    private var albumList: MutableList<Album> = mutableListOf()

    lateinit var recyclerViewSongs: RecyclerView
    lateinit var songAdapter: SongAdapter
    private var songList: List<Song>? = null

    private lateinit var rechargeSongs: ImageButton
    private lateinit var shimmerLayout: ShimmerFrameLayout

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

        checkSongs()
        checkAlbums()
        checkArtists()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        shimmerLayout = view.findViewById(R.id.shimmerLayout)
        rechargeSongs = view.findViewById(R.id.rechargeSongs)

        recyclerViewArtists = view.findViewById(R.id.listArtistsRecycler)
        recyclerViewArtists.layoutManager = GridLayoutManager(context, 3)
        artistAdapter = ArtistAdapter(artistsList)
        recyclerViewArtists.adapter = artistAdapter
        artistAdapter.setViewType(ArtistAdapter.VIEW_TYPE_GRID)

        recyclerViewAlbums = view.findViewById(R.id.listAlbumsRecycler)
        recyclerViewAlbums.layoutManager = GridLayoutManager(context, 3)
        albumsAdapter = AlbumAdapter(albumList, 0)
        recyclerViewAlbums.adapter = albumsAdapter
        val spacing = resources.getDimensionPixelSize(R.dimen.grid_spacing)
        recyclerViewAlbums.addItemDecoration(GridSpacingItemDecoration(3, spacing, true))

        recyclerViewSongs = view.findViewById(R.id.allSongList)
        recyclerViewSongs.layoutManager = LinearLayoutManager(requireContext())
        songAdapter = SongAdapter(SongAdapter.VIEW_TYPE_FULL)
        recyclerViewSongs.adapter = songAdapter

        sharedViewModel = ViewModelProvider(requireActivity()).get(SharedViewModel::class.java)

        sharedViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            currentSong?.let {
                songAdapter.setCurrentPlayingSong(it.id)
            }
        }

        var currentHomeQueueId: String = System.currentTimeMillis().toString() // o UUID.randomUUID().toString()

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
                putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE, QueueSource.HOME)
                putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE_ID, currentHomeQueueId) // id único por recarga
            }
            requireContext().startService(playIntent)
        }

        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]

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
                            R.id.action_homeFragment_to_detailedSongFragment,
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

        rechargeSongs.setOnClickListener {
            lifecycleScope.launch {
                showShimmer(true)
                recyclerViewSongs.visibility = View.GONE

                val shimmerStart = System.currentTimeMillis()

                val nuevas = getRandomSongs(requireContext(), cancionesAnteriores = songList)

                if (nuevas.isNotEmpty()) {
                    songList = nuevas
                    currentHomeQueueId = System.currentTimeMillis().toString() // <-- NUEVO id por cada recarga
                    songAdapter.submitList(nuevas) {
                        val elapsed = System.currentTimeMillis() - shimmerStart
                        val remaining = (1200 - elapsed).coerceAtLeast(0)

                        lifecycleScope.launch {
                            delay(remaining)
                            hideShimmer()

                            val controller = AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_animation_fade_slide)
                            recyclerViewSongs.layoutAnimation = controller
                            recyclerViewSongs.scheduleLayoutAnimation()

                            recyclerViewSongs.visibility = View.VISIBLE

                        }
                    }


                } else {
                    hideShimmer()
                    recyclerViewSongs.visibility = View.VISIBLE
                    Toast.makeText(requireContext(), "Error al recargar", Toast.LENGTH_SHORT).show()
                }
            }
        }

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(songChangedReceiver)
    }

    fun updateCurrentSong(song: Song) {
        _currentSongLiveData.postValue(song)
    }

    fun setCurrentPlayingSong(url: String) {
        songAdapter.setCurrentPlayingSong(url)
    }

    suspend fun getRandomSongs(
        context: Context,
        count: Int = 7,
        cancionesAnteriores: List<Song>? = null
    ): List<Song> {
        return try {
            val service = ApiClient.getService(context)
            val allIds = service.getAllSongIds()

            if (allIds.size < count) return emptyList()

            val randomIds = allIds.shuffled().take(count)
            val cancionesNuevas = mutableListOf<Song>()

            for (id in randomIds) {
                val song = service.getSongById(id)
                val artistList = service.getArtistsBySongId(id)
                song.artistName = artistList.joinToString(", ") { it.name }

                // Reaprovechar URL de audio y cover de canciones anteriores si existen
                val cachedSong = cancionesAnteriores?.find { it.id == song.id }
                if (cachedSong != null) {
                    song.url = cachedSong.url
                    song.coverUrl = cachedSong.coverUrl
                }

                cancionesNuevas.add(song)
            }

// === Obtener portadas de forma batch ===
            val coversRequest = cancionesNuevas.mapNotNull { song ->
                val fileName = song.imageFileName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val albumId = song.albumId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                // Normalizamos fileName para asegurar match con la API
                fileName.trim() to albumId.trim()
            }

            if (coversRequest.isNotEmpty()) {
                val (fileNames, albumIds) = coversRequest.unzip()

                try {
                    val coverResponses: List<CoverResponse> =
                        service.getMultipleSongCoverUrls(fileNames, albumIds)

                    // Map clave (fileName, albumId) -> URL
                    val coverUrlMap: Map<Pair<String, String>, String> = coverResponses.associate {
                        (it.imageFileName.trim() to it.albumId.trim()) to it.url
                    }

                    // Asignamos URL a cada canción
                    cancionesNuevas.forEach { song ->
                        val key = song.imageFileName?.trim() to song.albumId.trim()
                        val coverUrl = coverUrlMap[key]
                        if (!coverUrl.isNullOrBlank()) {
                            song.coverUrl = coverUrl
                        } else {
                            song.coverUrl = null
                        }
                    }

                } catch (e: Exception) {
                    Log.e("getRandomSongs", "Error obteniendo portadas batch: ${e.message}")
                }
            }



            // === Obtener URLs de audio de forma batch ===
            val cancionesSinUrl = cancionesNuevas.filter { it.url == null }
            if (cancionesSinUrl.isNotEmpty()) {
                val fileNames = cancionesSinUrl.map { it.fileName }
                val urlList = service.getMultipleSongUrls(fileNames)
                val urlMap = urlList.associateBy { it.fileName }

                cancionesSinUrl.forEach { song ->
                    song.url = urlMap[song.fileName]?.url
                }
            }

            // Actualizar MusicPlaybackService
            val updateIntent = Intent(context, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.UPDATE_SONGS
                putParcelableArrayListExtra(
                    MusicPlaybackService.SONG_LIST,
                    ArrayList(cancionesNuevas)
                )
            }
            context.startService(updateIntent)

            cancionesNuevas

        } catch (e: Exception) {
            Log.i("getRandomSongs", e.toString())
            e.printStackTrace()
            emptyList()
        }
    }


    suspend fun getRandomArtists(context: Context, count: Int = 6): List<Artist> {
        return try {
            val service = ApiClient.getService(context)
            val allIds = service.getAllArtistIds()

            if (allIds.size < count) return emptyList()

            val randomIds = allIds.shuffled().take(count)
            val artistasNuevos = mutableListOf<Artist>()

            for (id in randomIds) {
                val artist = service.getArtistById(id)
                artistasNuevos.add(artist)
            }

            val fileNames = artistasNuevos.mapNotNull { it.fileName }
            val urlList = service.getMultipleArtistUrls(fileNames)
            val urlMap = urlList.associateBy { it.fileName }

            artistasNuevos.forEach { artist ->
                artist.url = urlMap[artist.fileName]?.url ?: ""
            }

            artistasNuevos
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun getRandomAlbums(
        context: Context,
        count: Int = 3,
        albumsAnteriores: List<Album>? = null
    ): List<Album> {
        return try {
            val service = ApiClient.getService(context)
            val allIds = service.getAllAlbumIds()

            if (allIds.size < count) return emptyList()

            val randomIds = allIds.shuffled().take(count)
            val albumsNuevos = mutableListOf<Album>()

            for (id in randomIds) {
                val album = service.getAlbumById(id)

                // Obtener artista
                val artistas = service.getArtistsByAlbumId(album.id)
                album.artistName = artistas.firstOrNull()?.name ?: "Desconocido"

                // Restaurar caché de imagen
                val albumCache = albumsAnteriores?.find { it.id == album.id }
                if (albumCache != null) {
                    album.fileName = albumCache.fileName
                    album.url = albumCache.url
                }

                albumsNuevos.add(album)
            }

            val albumsSinUrl = albumsNuevos.filter { it.url.isNullOrEmpty() }

            if (albumsSinUrl.isNotEmpty()) {
                // Crear lista de fileNames no nulos para la API
                val fileNames = albumsSinUrl.map { album ->
                    val fileName = album.fileName
                    fileName.takeIf { it?.isNotBlank() == true } ?: "${album.id}.jpg"
                }

                val urlList = service.getMultipleAlbumUrls(fileNames)
                val urlMap = urlList.associateBy { it.fileName }

                albumsSinUrl.forEach { album ->
                    val fileName = album.fileName.takeIf { it?.isNotBlank() == true } ?: "${album.id}.jpg"
                    album.fileName = fileName
                    album.url = urlMap[fileName]?.url
                }
            }

            albumsNuevos
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }


    fun checkSongs () {
        if (songList != null) {
            hideShimmer()
            songAdapter.submitList(songList)
        } else {
            lifecycleScope.launch {
                val canciones = getRandomSongs(requireContext())
                if (!isAdded || context == null) return@launch
                if (canciones.isNotEmpty()) {
                    songList = canciones
                    hideShimmer()
                    songAdapter.submitList(canciones)
                } else {
                    context?.let { ctx ->
                        Toast.makeText(ctx, "No se encontraron canciones", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    fun checkAlbums () {
        if (albumsCargados != null) {
            albumList.clear()
            albumList.addAll(albumsCargados!!)
            albumsAdapter.notifyDataSetChanged()
        } else {
            lifecycleScope.launch {
                // Solo accede a context si el fragmento sigue attached
                val ctx = context ?: return@launch
                val albums = getRandomAlbums(ctx, albumsAnteriores = albumList)
                if (!isAdded || context == null) return@launch

                if (albums.isNotEmpty()) {
                    albumsCargados = albums
                    albumList.clear()
                    albumList.addAll(albums)
                    albumsAdapter.notifyDataSetChanged()
                } else {
                    // Usa context?.let para mostrar el Toast de forma segura
                    context?.let { safeContext ->
                        Toast.makeText(
                            safeContext,
                            "No se encontraron álbumes",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    fun checkArtists () {
        if (artistasCargados != null) {
            artistsList.clear()
            artistsList.addAll(artistasCargados!!)
            artistAdapter.notifyDataSetChanged()
        } else {
            lifecycleScope.launch {
                val ctx = context ?: return@launch
                val artistas = getRandomArtists(ctx)
                if (!isAdded || context == null) return@launch

                if (artistas.isNotEmpty()) {
                    artistasCargados = artistas
                    artistsList.clear()
                    artistsList.addAll(artistas)
                    artistAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    fun showShimmer(show: Boolean) {
        if (show) {
            shimmerLayout.visibility = View.VISIBLE
            shimmerLayout.startShimmer()
        } else {
            shimmerLayout.stopShimmer()
            shimmerLayout.visibility = View.GONE
        }
    }

    private fun hideShimmer() {
        shimmerLayout.stopShimmer()
        shimmerLayout.visibility = View.GONE
        recyclerViewSongs.visibility = View.VISIBLE
    }

}






