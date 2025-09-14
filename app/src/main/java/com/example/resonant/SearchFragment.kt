package com.example.resonant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchFragment : Fragment() {

    private lateinit var sharedPref: SharedPreferences
    private var searchJob: Job? = null
    private var restoringState = false
    private lateinit var sharedViewModel: SharedViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel

    private lateinit var noSongsFounded: TextView
    private lateinit var loadingAnimation: LottieAnimationView

    private lateinit var searchResultAdapter: SearchResultAdapter
    private lateinit var resultsRecyclerView: RecyclerView
    private var editTextQuery: EditText? = null

    private lateinit var chipSongs: Chip
    private lateinit var chipAlbums: Chip
    private lateinit var chipArtists: Chip

    private val songResults = mutableListOf<Song>()
    private val albumResults = mutableListOf<Album>()
    private val artistResults = mutableListOf<Artist>()

    private var showSongs = true
    private var showAlbums = true
    private var showArtists = true

    private var lastResults: List<SearchResult> = emptyList()

    private val songChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MusicPlaybackService.ACTION_SONG_CHANGED) {
                val song = intent.getParcelableExtra<Song>(MusicPlaybackService.EXTRA_CURRENT_SONG)
                song?.let {
                    searchResultAdapter.setCurrentPlayingSong(it.id)
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
        val view = inflater.inflate(R.layout.fragment_search, container, false)

        // QUITADO: registro duplicado del receiver (ya se registra en onViewCreated)

        chipSongs = view.findViewById(R.id.chipSongs)
        chipSongs.typeface = ResourcesCompat.getFont(requireContext(), R.font.unageo_medium)
        chipAlbums = view.findViewById(R.id.chipAlbums)
        chipAlbums.typeface = ResourcesCompat.getFont(requireContext(), R.font.unageo_medium)
        chipArtists = view.findViewById(R.id.chipArtists)
        chipArtists.typeface = ResourcesCompat.getFont(requireContext(), R.font.unageo_medium)

        noSongsFounded = view.findViewById(R.id.noSongFoundedText)
        resultsRecyclerView = view.findViewById(R.id.filteredListResults)
        resultsRecyclerView.layoutManager = LinearLayoutManager(context)
        searchResultAdapter = SearchResultAdapter()
        resultsRecyclerView.adapter = searchResultAdapter
        editTextQuery = view.findViewById(R.id.editTextQuery)
        loadingAnimation = view.findViewById(R.id.loadingAnimation)

        sharedPref = requireContext().getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        sharedViewModel = ViewModelProvider(requireActivity()).get(SharedViewModel::class.java)

        sharedViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            currentSong?.let { searchResultAdapter.setCurrentPlayingSong(it.id) }
        }

        // Restaurar estado (chips + query + resultados)
        showSongs = savedInstanceState?.getBoolean("showSongs") ?: true
        showAlbums = savedInstanceState?.getBoolean("showAlbums") ?: true
        showArtists = savedInstanceState?.getBoolean("showArtists") ?: true

        // Establecer estado de chips ANTES de añadir listeners para no dispararlos innecesariamente
        chipSongs.isChecked = showSongs
        chipAlbums.isChecked = showAlbums
        chipArtists.isChecked = showArtists

        val restoredQuery = savedInstanceState?.getString("query")
        val restoredResults = savedInstanceState?.getParcelableArrayList<SearchResult>("results")
        if (!restoredQuery.isNullOrBlank() && !restoredResults.isNullOrEmpty()) {
            restoringState = true
            editTextQuery?.setText(restoredQuery)
            loadingAnimation.visibility = View.GONE

            lastResults = restoredResults
            searchResultAdapter.submitList(applyActiveFilters(lastResults))

            songResults.clear()
            songResults.addAll(restoredResults.filterIsInstance<SearchResult.SongItem>().map { it.song })

            albumResults.clear()
            albumResults.addAll(restoredResults.filterIsInstance<SearchResult.AlbumItem>().map { it.album })

            artistResults.clear()
            artistResults.addAll(restoredResults.filterIsInstance<SearchResult.ArtistItem>().map { it.artist })

            updateFilteredResults()
            restoringState = false
        }

        // Listeners de chips
        chipSongs.setOnCheckedChangeListener { chip, isChecked ->
            showSongs = isChecked
            updateFilteredResults()
            AnimationsUtils.animateChip(chip as Chip, isChecked)
            AnimationsUtils.animateChipColor(chip, isChecked)
        }
        chipAlbums.setOnCheckedChangeListener { chip, isChecked ->
            showAlbums = isChecked
            updateFilteredResults()
            AnimationsUtils.animateChip(chip as Chip, isChecked)
            AnimationsUtils.animateChipColor(chip, isChecked)
        }
        chipArtists.setOnCheckedChangeListener { chip, isChecked ->
            showArtists = isChecked
            updateFilteredResults()
            AnimationsUtils.animateChip(chip as Chip, isChecked)
            AnimationsUtils.animateChipColor(chip, isChecked)
        }

        searchResultAdapter.onSongClick = { (song, bitmap) ->
            val currentIndex = songResults.indexOfFirst { it.url == song.url }
            val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, song.id) }
            val songList = ArrayList(songResults)

            val playIntent = Intent(context, MusicPlaybackService::class.java).apply {
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
            searchResultAdapter.favoriteSongIds = ids
            searchResultAdapter.submitList(searchResultAdapter.currentList)
        }

        searchResultAdapter.onFavoriteClick = { song, newState ->
            if (newState) {
                favoritesViewModel.addFavorite(song) { success ->
                    if (!success) {
                        Toast.makeText(requireContext(), "Error al añadir favorito", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                favoritesViewModel.deleteFavorite(song.id) { success ->
                    if (!success) {
                        Toast.makeText(requireContext(), "Error al eliminar favorito", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        editTextQuery?.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (restoringState) return
                if (s.isNullOrBlank()) {
                    // Reset limpio
                    searchJob?.cancel()
                    lastResults = emptyList()
                    searchResultAdapter.submitList(emptyList())
                    songResults.clear()
                    albumResults.clear()
                    artistResults.clear()
                    loadingAnimation.visibility = View.INVISIBLE
                    resultsRecyclerView.visibility = View.INVISIBLE
                    noSongsFounded.visibility = View.GONE
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(newText: CharSequence?, start: Int, before: Int, count: Int) {
                if (restoringState) return

                val query = newText?.toString()?.trim().orEmpty()
                searchJob?.cancel()

                if (query.isEmpty()) {
                    // Ya manejado en afterTextChanged
                    return
                }

                noSongsFounded.visibility = View.GONE
                resultsRecyclerView.visibility = View.INVISIBLE
                loadingAnimation.visibility = View.VISIBLE

                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    // Debounce
                    delay(500)

                    val service = ApiClient.getService(requireContext())
                    try {
                        // Ejecutar las 3 búsquedas en paralelo
                        val songsDeferred = async { service.searchSongsByQuery(query) }
                        val albumsDeferred = async { service.searchAlbumsByQuery(query) }
                        val artistsDeferred = async { service.searchArtistsByQuery(query) }

                        val songs = songsDeferred.await()
                        val albums = albumsDeferred.await()
                        val artists = artistsDeferred.await()

                        // Resolver URLs en lote para evitar N llamadas por item
                        // ARTISTAS
                        val artistFileNames = artists.mapNotNull { it.fileName?.takeIf { fn -> fn.isNotBlank() } }
                        val artistUrls = if (artistFileNames.isNotEmpty())
                            service.getMultipleArtistUrls(artistFileNames)
                        else emptyList()
                        val artistUrlMap = artistFileNames.zip(artistUrls.map { it.url }).toMap()
                        val artistsPrepared = artists.map { artist ->
                            val url = artist.fileName?.let { artistUrlMap[it] }.orEmpty()
                            artist.copy(
                                fileName = url,
                                description = artist.description ?: ""
                            )
                        }

                        // CANCIONES: nombres de artistas y URLs de canciones faltantes
                        // Nombres de artistas por canción (si tu API permite batch, cámbialo; si no, mantenlo)
                        songs.forEach { song ->
                            val songArtists = service.getArtistsBySongId(song.id)
                            song.artistName = songArtists.filterNotNull().map { it.name ?: "Desconocido" }.joinToString(", ")
                        }
                        val missingSongFileNames = songs.filter { it.url.isNullOrEmpty() }
                            .mapNotNull { it.fileName?.takeIf { fn -> fn.isNotBlank() } }
                        if (missingSongFileNames.isNotEmpty()) {
                            val songUrls = service.getMultipleSongUrls(missingSongFileNames)
                            val songUrlMap = missingSongFileNames.zip(songUrls.map { it.url }).toMap()
                            songs.forEach { s ->
                                if (s.url.isNullOrEmpty()) {
                                    s.fileName?.let { fn -> s.url = songUrlMap[fn] }
                                }
                            }
                        }

                        // ÁLBUMES: nombres de artistas y URLs de portadas en lote
                        albums.forEach { album ->
                            val albumArtists = service.getArtistsByAlbumId(album.id)
                            album.artistName = albumArtists.filterNotNull().map { it.name ?: "Desconocido" }.joinToString(", ")
                        }
                        val albumSafeNames = albums.map { a ->
                            a.fileName = a.fileName?.takeIf { it.isNotBlank() } ?: "${a.id}.jpg"
                            a.fileName!!
                        }
                        if (albumSafeNames.isNotEmpty()) {
                            val albumUrls = service.getMultipleAlbumUrls(albumSafeNames)
                            val albumUrlMap = albumSafeNames.zip(albumUrls.map { it.url }).toMap()
                            albums.forEach { a -> a.url = albumUrlMap[a.fileName] }
                        }

                        // Intercalar y ordenar por relevancia
                        val combined = intercalateAndSortResults(songs, albums, artistsPrepared, query)

                        // Actualizar colecciones internas
                        songResults.clear(); songResults.addAll(songs)
                        albumResults.clear(); albumResults.addAll(albums)
                        artistResults.clear(); artistResults.addAll(artistsPrepared)

                        // ACTUALIZAR lastResults ANTES de filtrar (fix principal)
                        lastResults = combined

                        val finalList = applyActiveFilters(lastResults)

                        loadingAnimation.visibility = View.INVISIBLE
                        resultsRecyclerView.visibility = View.VISIBLE

                        if (finalList.isEmpty()) {
                            searchResultAdapter.submitList(emptyList())
                            noSongsFounded.visibility = View.VISIBLE
                        } else {
                            searchResultAdapter.submitList(finalList)
                            noSongsFounded.visibility = View.GONE
                        }

                    } catch (e: Exception) {
                        loadingAnimation.visibility = View.INVISIBLE
                        resultsRecyclerView.visibility = View.INVISIBLE
                        searchResultAdapter.submitList(emptyList())
                        noSongsFounded.visibility = View.VISIBLE
                        e.printStackTrace()
                        Toast.makeText(requireContext(), "Error al buscar resultados", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })

        return view
    }

    private fun intercalateAndSortResults(
        songs: List<Song>,
        albums: List<Album>,
        artists: List<Artist>,
        query: String
    ): List<SearchResult> {
        val lowerQuery = query.lowercase()

        // Ordenar por relevancia por tipo
        val sortedSongs = songs.sortedByDescending { song ->
            val t = song.title.lowercase()
            val a = song.artistName?.lowercase()
            when {
                t == lowerQuery -> 40
                a == lowerQuery -> 35
                t.contains(lowerQuery) -> 30
                a?.contains(lowerQuery) == true -> 20
                else -> 0
            }
        }.map { SearchResult.SongItem(it) }

        val sortedAlbums = albums.sortedByDescending { album ->
            val t = album.title?.lowercase()
            when {
                t == lowerQuery -> 70
                t?.startsWith(lowerQuery) == true -> 60
                t?.contains(lowerQuery) == true -> 50
                else -> 0
            }
        }.map { SearchResult.AlbumItem(it) }

        val sortedArtists = artists.sortedByDescending { artist ->
            val n = artist.name.lowercase()
            when {
                n == lowerQuery -> 100
                n.startsWith(lowerQuery) -> 90
                n.contains(lowerQuery) -> 80
                else -> 0
            }
        }.map { SearchResult.ArtistItem(it) }

        // Intercalado round-robin
        val lists = mutableListOf(sortedArtists, sortedSongs, sortedAlbums)
        val combined = mutableListOf<SearchResult>()
        while (lists.any { it.isNotEmpty() }) {
            lists.forEachIndexed { index, list ->
                if (list.isNotEmpty()) {
                    combined.add(list.first())
                    lists[index] = list.drop(1)
                }
            }
        }
        return combined
    }

    private fun applyActiveFilters(results: List<SearchResult>): List<SearchResult> {
        return results.filter { item ->
            when (item) {
                is SearchResult.SongItem -> showSongs
                is SearchResult.AlbumItem -> showAlbums
                is SearchResult.ArtistItem -> showArtists
            }
        }
    }

    private fun updateFilteredResults() {
        val filtered = applyActiveFilters(lastResults)
        searchResultAdapter.submitList(filtered)
        noSongsFounded.visibility = if (filtered.isEmpty() && lastResults.isNotEmpty()) View.VISIBLE else View.GONE
        resultsRecyclerView.visibility = if (filtered.isNotEmpty()) View.VISIBLE else View.INVISIBLE
        loadingAnimation.visibility = View.INVISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(songChangedReceiver)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        view?.let {
            outState.putString("query", editTextQuery?.text?.toString() ?: "")
        }
        outState.putParcelableArrayList("results", ArrayList(lastResults))
        outState.putBoolean("showSongs", showSongs)
        outState.putBoolean("showAlbums", showAlbums)
        outState.putBoolean("showArtists", showArtists)
    }

}