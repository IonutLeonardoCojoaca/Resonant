package com.example.resonant.ui.fragments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.data.network.ApiClient
import com.example.resonant.ui.viewmodels.FavoriteItem
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.ui.views.GridSpacingItemDecoration
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.playback.QueueSource
import com.example.resonant.R
import com.example.resonant.ui.bottomsheets.SelectPlaylistBottomSheet
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.ui.adapters.SongAdapter
import com.example.resonant.ui.bottomsheets.SongOptionsBottomSheet
import com.example.resonant.utils.Utils
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.Song
import com.example.resonant.managers.SongManager
import com.example.resonant.ui.adapters.AlbumAdapter
import com.example.resonant.ui.adapters.ArtistAdapter
import com.facebook.shimmer.ShimmerFrameLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.collections.get

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

    private lateinit var songViewModel: SongViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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
        artistAdapter.setViewType(ArtistAdapter.Companion.VIEW_TYPE_GRID)

        recyclerViewAlbums = view.findViewById(R.id.listAlbumsRecycler)
        recyclerViewAlbums.layoutManager = GridLayoutManager(context, 3)
        albumsAdapter = AlbumAdapter(albumList, 0)
        recyclerViewAlbums.adapter = albumsAdapter
        val spacing = resources.getDimensionPixelSize(R.dimen.grid_spacing)
        recyclerViewAlbums.addItemDecoration(GridSpacingItemDecoration(3, spacing, true))

        recyclerViewSongs = view.findViewById(R.id.allSongList)
        recyclerViewSongs.layoutManager = LinearLayoutManager(requireContext())
        songAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_FULL)
        recyclerViewSongs.adapter = songAdapter

        songViewModel = ViewModelProvider(requireActivity()).get(SongViewModel::class.java)

        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
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
                action = MusicPlaybackService.Companion.ACTION_PLAY
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_SONG, song)
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_INDEX, currentIndex)
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                putParcelableArrayListExtra(MusicPlaybackService.Companion.SONG_LIST, songList)
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE, QueueSource.HOME)
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE_ID, currentHomeQueueId) // id único por recarga
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

        rechargeSongs.setOnClickListener {
            lifecycleScope.launch {
                showShimmer(true)
                recyclerViewSongs.visibility = View.GONE

                val shimmerStart = System.currentTimeMillis()

                val nuevas = SongManager.getRandomSongs(requireContext(), count = 7)

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

    suspend fun getRandomArtists(context: Context, count: Int = 6): List<Artist> {
        return try {
            val service = ApiClient.getService(context)

            // 1. Obtenemos todos los IDs y los barajamos para procesarlos en orden aleatorio.
            val shuffledIds = service.getAllArtistIds().shuffled().toMutableList()
            val artistsWithPhoto = mutableListOf<Artist>()

            // 2. Iteramos sobre los IDs aleatorios HASTA que encontremos 'count' artistas
            //    con foto, o hasta que nos quedemos sin IDs.
            while (artistsWithPhoto.size < count && shuffledIds.isNotEmpty()) {
                val artistId = shuffledIds.removeAt(0) // Tomamos un ID de la lista
                val artist = service.getArtistById(artistId)

                // 3. Añadimos el artista a la lista SOLO si tiene un 'fileName'.
                if (artist.fileName != null) {
                    artistsWithPhoto.add(artist)
                }
            }

            // Si al final del bucle no encontramos ningún artista con foto, terminamos.
            if (artistsWithPhoto.isEmpty()) {
                return emptyList()
            }

            // 4. Ahora, obtenemos las URLs únicamente para los artistas que ya hemos validado.
            val fileNames = artistsWithPhoto.mapNotNull { it.fileName }
            val urlList = service.getMultipleArtistUrls(fileNames)
            val urlMap = urlList.associateBy { it.fileName }

            artistsWithPhoto.forEach { artist ->
                artist.url = urlMap[artist.fileName]?.url ?: ""
            }

            artistsWithPhoto // Devolvemos la lista final

        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun getRandomAlbums(context: Context, count: Int = 3, albumsAnteriores: List<Album>? = null): List<Album> {
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

    fun checkSongs() {
        // ✅ PASO 1: SIEMPRE muestra el estado de carga al principio.
        showShimmer(true)
        recyclerViewSongs.visibility = View.GONE // Oculta la lista explícitamente

        if (songList != null) {
            // Si ya tenemos las canciones, las mostramos.
            songAdapter.submitList(songList)
            hideShimmer() // Oculta el shimmer y muestra la lista.
        } else {
            // Si no tenemos canciones, las descargamos.
            lifecycleScope.launch {
                val canciones = SongManager.getRandomSongs(requireContext(), count = 7)

                if (!isAdded) return@launch // Comprobación de seguridad

                if (canciones.isNotEmpty()) {
                    songList = canciones
                    songAdapter.submitList(canciones)
                } else {
                    Toast.makeText(requireContext(), "No se encontraron canciones", Toast.LENGTH_SHORT).show()
                }

                // ✅ PASO 2: SIEMPRE oculta el shimmer al final, haya canciones o no.
                hideShimmer()
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