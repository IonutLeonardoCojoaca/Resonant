package com.example.resonant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
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

    private lateinit var noSongsFounded: TextView
    private lateinit var loadingAnimation: LottieAnimationView

    private lateinit var searchResultAdapter: SearchResultAdapter
    private lateinit var resultsRecyclerView: RecyclerView
    private lateinit var editTextQuery: EditText

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
        var view = inflater.inflate(R.layout.fragment_search, container, false)

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            songChangedReceiver,
            IntentFilter(MusicPlaybackService.ACTION_SONG_CHANGED)
        )

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
            currentSong?.let {
                searchResultAdapter.setCurrentPlayingSong(it.id)
            }
        }

        val restoredQuery = savedInstanceState?.getString("query")
        val restoredResults = savedInstanceState?.getParcelableArrayList<SearchResult>("results")
        if (!restoredQuery.isNullOrBlank() && !restoredResults.isNullOrEmpty()) {
            restoringState = true
            editTextQuery.setText(restoredQuery)
            // ❌ OCULTA animación de carga si no estás cargando
            loadingAnimation.visibility = View.GONE

            lastResults = restoredResults
            searchResultAdapter.submitList(restoredResults)

            songResults.clear()
            songResults.addAll(restoredResults.filterIsInstance<SearchResult.SongItem>().map { it.song })

            albumResults.clear()
            albumResults.addAll(restoredResults.filterIsInstance<SearchResult.AlbumItem>().map { it.album })

            artistResults.clear()
            artistResults.addAll(restoredResults.filterIsInstance<SearchResult.ArtistItem>().map { it.artist })

            updateFilteredResults()

            restoringState = false
        }

        chipSongs.setOnCheckedChangeListener { _, isChecked ->
            showSongs = isChecked
            updateFilteredResults()
        }

        chipAlbums.setOnCheckedChangeListener { _, isChecked ->
            showAlbums = isChecked
            updateFilteredResults()
        }

        chipArtists.setOnCheckedChangeListener { _, isChecked ->
            showArtists = isChecked
            updateFilteredResults()
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

        editTextQuery.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (restoringState) return  // ✅ NO hagas nada si estás restaurando

                if (s.isNullOrBlank()) {
                    searchResultAdapter.submitList(emptyList())
                    songResults.clear()
                    albumResults.clear()
                    artistResults.clear()
                    loadingAnimation.visibility = View.INVISIBLE
                    noSongsFounded.visibility = View.GONE
                    return
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(newText: CharSequence?, start: Int, before: Int, count: Int) {
                if (restoringState) return

                val query = newText.toString().trim()
                searchJob?.cancel()

                if (query.isEmpty()) {
                    searchResultAdapter.submitList(emptyList())
                    return
                }

                noSongsFounded.visibility = View.GONE
                resultsRecyclerView.visibility = View.INVISIBLE
                loadingAnimation.visibility = View.VISIBLE

                searchJob = lifecycleScope.launch {
                    delay(800)

                    val service = ApiClient.getService(requireContext())

                    try {
                        // Ejecutamos los 3 endpoints en paralelo
                        val songsDeferred = async { service.searchSongsByQuery(query) }
                        val albumsDeferred = async { service.searchAlbumsByQuery(query) }
                        val artistsDeferred = async { service.searchArtistsByQuery(query) }

                        val songs = songsDeferred.await()
                        val albums = albumsDeferred.await()
                        val artists = artistsDeferred.await()

// Obtener URLs reales de imagen para artistas
                        val artistsPrepared = artists.toMutableList()
                        val artistsWithoutUrl = artistsPrepared.filter { it.imageUrl.isNotBlank() } // Asumimos que imageUrl guarda el "fileName"

                        if (artistsWithoutUrl.isNotEmpty()) {
                            val fileNames = artistsWithoutUrl.map { it.imageUrl } // imageUrl contiene el nombre del archivo
                            val urlList = service.getMultipleArtistUrls(fileNames)
                            val urlMap = urlList.associateBy { it.fileName }

                            artistsWithoutUrl.forEach { artist ->
                                artist.imageUrl = urlMap[artist.imageUrl]?.url ?: ""
                            }
                        }


                        // Obtener nombres de artistas para las canciones
                        for (song in songs) {
                            val songArtists = service.getArtistsBySongId(song.id)
                            song.artistName = songArtists.joinToString(", ") { it.name }
                        }

                        // Obtener URLs faltantes para canciones
                        val songsWithoutUrl = songs.filter { it.url == null }
                        if (songsWithoutUrl.isNotEmpty()) {
                            val fileNames = songsWithoutUrl.map { it.fileName }
                            val urlList = service.getMultipleSongUrls(fileNames)
                            val urlMap = urlList.associateBy { it.fileName }

                            songsWithoutUrl.forEach { song ->
                                song.url = urlMap[song.fileName]?.url
                            }
                        }

                        // Obtener nombres de artistas para los álbumes
                        for (album in albums) {
                            val albumArtists = service.getArtistsByAlbumId(album.id)
                            album.artistName = albumArtists.joinToString(", ") { it.name } // Asegúrate de tener esta propiedad en el modelo Album
                        }

                        val albumsWithoutUrl = albums.filter { it.url == null }
                        if (albumsWithoutUrl.isNotEmpty()) {
                            val fileNames = albumsWithoutUrl.map { it.fileName }
                            val urlList = service.getMultipleAlbumUrls(fileNames)
                            val urlMap = urlList.associateBy { it.fileName }

                            albumsWithoutUrl.forEach { album ->
                                album.url = urlMap[album.fileName]?.url
                            }
                        }

                        val combinedResults = mutableListOf<SearchResult>().apply {
                            addAll(songs.map { SearchResult.SongItem(it) })
                            addAll(albums.map { SearchResult.AlbumItem(it) })
                            addAll(artistsPrepared.map { SearchResult.ArtistItem(it) })
                        }

                        val sortedResults = sortByRelevance(combinedResults, query)
                        lastResults = sortedResults

                        // Guardamos las listas por separado si usas filtros por tipo
                        songResults.clear()
                        albumResults.clear()
                        artistResults.clear()

                        songResults.addAll(songs)
                        albumResults.addAll(albums)
                        artistResults.addAll(artistsPrepared) // <-- usar la lista con URLs ya asignadas

                        // Aplica filtros si los usas, si no, simplemente usa combinedResults
                        val finalList = applyActiveFilters(sortedResults)

                        if (songs.isNotEmpty()) {
                            val updateIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                                action = MusicPlaybackService.UPDATE_SONGS
                                putParcelableArrayListExtra(
                                    MusicPlaybackService.SONG_LIST,
                                    ArrayList(songs)
                                )
                            }
                            requireContext().startService(updateIntent)
                        }

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
                        e.printStackTrace()
                        Toast.makeText(requireContext(), "Error al buscar resultados", Toast.LENGTH_SHORT).show()
                        searchResultAdapter.submitList(emptyList())
                    }
                }
            }
        })


        return view
    }

    private fun sortByRelevance(results: List<SearchResult>, query: String): List<SearchResult> {
        val lowerQuery = query.trim().lowercase()

        return results.sortedWith(compareByDescending { item ->
            when (item) {
                is SearchResult.ArtistItem -> {
                    val name = item.artist.name.lowercase()
                    when {
                        name == lowerQuery -> 100
                        name.startsWith(lowerQuery) -> 90
                        name.contains(lowerQuery) -> 80
                        else -> 0
                    }
                }
                is SearchResult.AlbumItem -> {
                    val title = item.album.title?.lowercase()
                    when {
                        title == lowerQuery -> 70
                        title?.startsWith(lowerQuery) == true -> 60
                        title?.contains(lowerQuery) == true -> 50
                        else -> 0
                    }
                }
                is SearchResult.SongItem -> {
                    val title = item.song.title.lowercase()
                    val artistName = item.song.artistName?.lowercase()
                    when {
                        title == lowerQuery -> 40
                        artistName == lowerQuery -> 35
                        title.contains(lowerQuery) -> 30
                        artistName?.contains(lowerQuery) == true -> 20
                        else -> 0
                    }
                }
            }
        })
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
        noSongsFounded.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }


    override fun onDestroyView() {
        super.onDestroyView()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(songChangedReceiver)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("query", editTextQuery.text.toString())
        outState.putParcelableArrayList("results", ArrayList(lastResults))
    }


}