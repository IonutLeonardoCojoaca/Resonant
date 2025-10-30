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
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.example.resonant.managers.SongManager
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchFragment : BaseFragment(R.layout.fragment_search) {

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
    private lateinit var suggestionAdapter: SuggestionAdapter
    private lateinit var suggestionsRecyclerView: RecyclerView

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

    override fun onResume() {
        super.onResume()
        sharedViewModel.currentSongLiveData.value?.let { currentSong ->
            searchResultAdapter.setCurrentPlayingSong(currentSong.id)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_search, container, false)

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

        suggestionsRecyclerView = view.findViewById(R.id.suggestionsRecyclerView)
        suggestionAdapter = SuggestionAdapter { suggestion ->
            editTextQuery?.setText(suggestion.text)
            editTextQuery?.setSelection(suggestion.text.length)
        }
        suggestionsRecyclerView.adapter = suggestionAdapter
        suggestionsRecyclerView.layoutManager = LinearLayoutManager(requireContext())


        sharedPref = requireContext().getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        sharedViewModel = ViewModelProvider(requireActivity())[SharedViewModel::class.java]

        sharedViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            searchResultAdapter.setCurrentPlayingSong(currentSong?.id)
        }

        showSongs = savedInstanceState?.getBoolean("showSongs") ?: true
        showAlbums = savedInstanceState?.getBoolean("showAlbums") ?: true
        showArtists = savedInstanceState?.getBoolean("showArtists") ?: true

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

            // Usamos la nueva función centralizada también aquí
            updateAdapterList(applyActiveFilters(lastResults))

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

        var currentHomeQueueId: String = System.currentTimeMillis().toString() // o UUID.randomUUID().toString()

        searchResultAdapter.onSongClick = { (song, bitmap) ->
            val currentIndex = songResults.indexOfFirst { it.id == song.id }
            val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, song.id) }
            val songList = ArrayList(songResults)

            val playIntent = Intent(context, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_PLAY
                putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, song)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, currentIndex)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, songList)
                putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE, QueueSource.SEARCH) // <-- Contexto: home random
                putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE_ID, currentHomeQueueId) // <-- id único para esta cola (puede ser fijo o dinámico)
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
            searchResultAdapter.favoriteSongIds = songIds
        }

        searchResultAdapter.onFavoriteClick = { song, wasFavorite ->
            // Le decimos al ViewModel que se encargue de la lógica.
            // Es exactamente la misma llamada que hicimos desde el BottomSheet.
            favoritesViewModel.toggleFavoriteSong(song)
        }

        searchResultAdapter.onSettingsClick = { song ->
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
                            R.id.action_searchFragment_to_detailedSongFragment,
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
                        selectPlaylistBottomSheet.show(parentFragmentManager, "SelectPlaylistBottomSheet")
                    }
                )
                bottomSheet.show(parentFragmentManager, "SongOptionsBottomSheet")
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
                suggestionsRecyclerView.visibility = View.GONE
                loadingAnimation.visibility = View.VISIBLE

                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(500) // debounce

                    val service = ApiClient.getService(requireContext())

                    try {
                        // ✅ Ejecutamos las 3 búsquedas paralelas: usando SongManager solo para canciones
                        val songsDeferred = async { SongManager.searchSongs(requireContext(), query) }
                        val albumsDeferred = async { service.searchAlbumsByQuery(query) }
                        val artistsDeferred = async { service.searchArtistsByQuery(query) }

                        val songsResponse = songsDeferred.await()
                        val albumsResponse = albumsDeferred.await()
                        val artistsResponse = artistsDeferred.await()

                        val songs = songsResponse.results
                        val albums = albumsResponse.results
                        val artists = artistsResponse.results

                        val combinedSuggestions = intercalateAndSortSuggestions(
                            songsResponse.suggestions,
                            albumsResponse.suggestions,
                            artistsResponse.suggestions,
                            query
                        )

                        // === ARTISTAS ===
                        val artistFileNames = artists.mapNotNull { it.fileName?.takeIf { fn -> fn.isNotBlank() } }
                        val artistUrls = if (artistFileNames.isNotEmpty())
                            service.getMultipleArtistUrls(artistFileNames)
                        else emptyList()
                        val artistUrlMap = artistUrls.associateBy { it.fileName }
                        val artistsPrepared = artists.map { artist ->
                            val url = artist.fileName?.let { artistUrlMap[it]?.url }.orEmpty()
                            artist.copy(
                                url = url,
                                description = artist.description ?: ""
                            )
                        }

                        // === ÁLBUMES ===
                        albums.forEach { album ->
                            val albumArtists = service.getArtistsByAlbumId(album.id)
                            album.artistName = albumArtists.filterNotNull()
                                .map { it.name ?: "Desconocido" }
                                .joinToString(", ")
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

                        // === COMBINAR Y MOSTRAR RESULTADOS ===
                        val combinedResults = intercalateAndSortResults(songs, albums, artistsPrepared, query)

                        songResults.clear(); songResults.addAll(songs)
                        albumResults.clear(); albumResults.addAll(albums)
                        artistResults.clear(); artistResults.addAll(artistsPrepared)
                        lastResults = combinedResults

                        val finalList = applyActiveFilters(lastResults)

                        loadingAnimation.visibility = View.INVISIBLE

                        when {
                            finalList.isNotEmpty() -> {
                                // ✅ Mostrar resultados
                                resultsRecyclerView.visibility = View.VISIBLE
                                suggestionsRecyclerView.visibility = View.GONE
                                noSongsFounded.visibility = View.GONE
                                searchResultAdapter.submitList(finalList)
                            }

                            combinedSuggestions.isNotEmpty() -> {
                                // ✅ Mostrar sugerencias
                                resultsRecyclerView.visibility = View.GONE
                                suggestionsRecyclerView.visibility = View.VISIBLE
                                noSongsFounded.visibility = View.GONE
                                suggestionAdapter.submitList(combinedSuggestions)
                            }

                            else -> {
                                // 🚫 Nada
                                resultsRecyclerView.visibility = View.GONE
                                suggestionsRecyclerView.visibility = View.GONE
                                noSongsFounded.visibility = View.VISIBLE
                                searchResultAdapter.submitList(emptyList())
                            }
                        }

                    } catch (e: Exception) {
                        loadingAnimation.visibility = View.INVISIBLE
                        resultsRecyclerView.visibility = View.INVISIBLE
                        suggestionsRecyclerView.visibility = View.GONE
                        noSongsFounded.visibility = View.VISIBLE
                        searchResultAdapter.submitList(emptyList())
                        Log.e("SearchFragment", "Error during search", e)
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

    private fun intercalateAndSortSuggestions(
        songSuggestions: List<String>,
        albumSuggestions: List<String>,
        artistSuggestions: List<String>,
        query: String
    ): List<SuggestionItem> {
        val lowerQuery = query.lowercase()

        fun scoreSuggestion(text: String, baseWeight: Int): Int {
            val t = text.lowercase()
            return when {
                t == lowerQuery -> baseWeight + 100
                t.startsWith(lowerQuery) -> baseWeight + 80
                t.contains(lowerQuery) -> baseWeight + 60
                else -> baseWeight
            }
        }

        val scoredSongs = songSuggestions.map { SuggestionItem(it, DataType.SONG) to scoreSuggestion(it, 40) }
        val scoredAlbums = albumSuggestions.map { SuggestionItem(it, DataType.ALBUM) to scoreSuggestion(it, 60) }
        val scoredArtists = artistSuggestions.map { SuggestionItem(it, DataType.ARTIST) to scoreSuggestion(it, 80) }

        val sortedSongs = scoredSongs.sortedByDescending { it.second }.map { it.first }
        val sortedAlbums = scoredAlbums.sortedByDescending { it.second }.map { it.first }
        val sortedArtists = scoredArtists.sortedByDescending { it.second }.map { it.first }

        val lists = mutableListOf(sortedArtists, sortedSongs, sortedAlbums)
        val combined = mutableListOf<SuggestionItem>()

        while (lists.any { it.isNotEmpty() }) {
            lists.forEachIndexed { index, list ->
                if (list.isNotEmpty()) {
                    combined.add(list.first())
                    lists[index] = list.drop(1)
                }
            }
        }

        return combined.distinctBy { it.text }
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

    private fun updateAdapterList(list: List<SearchResult>) {
        searchResultAdapter.submitList(list)
        sharedViewModel.currentSongLiveData.value?.let { currentSong ->
            searchResultAdapter.setCurrentPlayingSong(currentSong.id)
        }

        // Actualiza la visibilidad de los elementos de la UI
        val resultsAreEmpty = list.isEmpty()
        noSongsFounded.visibility = if (resultsAreEmpty && editTextQuery?.text?.isNotEmpty() == true) View.VISIBLE else View.GONE
        resultsRecyclerView.visibility = if (!resultsAreEmpty) View.VISIBLE else View.INVISIBLE
        loadingAnimation.visibility = View.INVISIBLE
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