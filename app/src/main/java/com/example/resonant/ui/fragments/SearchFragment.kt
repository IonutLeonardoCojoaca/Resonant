package com.example.resonant.ui.fragments

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.example.resonant.R
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.DataType
import com.example.resonant.data.models.Song
import com.example.resonant.data.models.SuggestionItem
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.services.AlbumService
import com.example.resonant.data.network.services.ArtistService
import com.example.resonant.managers.SearchHistoryManager
import com.example.resonant.managers.SongManager
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.SearchHistoryAdapter
import com.example.resonant.ui.adapters.SearchResult
import com.example.resonant.ui.adapters.SearchResultAdapter
import com.example.resonant.ui.adapters.SuggestionAdapter
import com.example.resonant.ui.bottomsheets.SelectPlaylistBottomSheet
import com.example.resonant.ui.bottomsheets.SongOptionsBottomSheet
import com.example.resonant.ui.viewmodels.DownloadViewModel
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.utils.AnimationsUtils
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.example.resonant.utils.Utils
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.collections.get

class SearchFragment : BaseFragment(R.layout.fragment_search) {

    private lateinit var sharedPref: SharedPreferences
    private val queryFlow = MutableStateFlow("")
    private var restoringState = false
    private lateinit var songViewModel: SongViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel
    private lateinit var userProfileImage: ImageView

    private lateinit var noSongsFounded: TextView
    private lateinit var loadingAnimation: LottieAnimationView

    private lateinit var searchResultAdapter: SearchResultAdapter
    private lateinit var resultsRecyclerView: RecyclerView
    private var editTextQuery: EditText? = null
    private lateinit var suggestionAdapter: SuggestionAdapter
    private lateinit var suggestionsRecyclerView: RecyclerView

    private lateinit var searchHistoryManager: SearchHistoryManager
    private lateinit var historyAdapter: SearchHistoryAdapter
    private lateinit var historyRecyclerView: RecyclerView

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

    private lateinit var albumService: AlbumService
    private lateinit var artistService: ArtistService
    private lateinit var songManager: SongManager

    private lateinit var downloadViewModel: DownloadViewModel

    override fun onResume() {
        super.onResume()
        songViewModel.currentSongLiveData.value?.let { currentSong ->
            searchResultAdapter.setCurrentPlayingSong(currentSong.id)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_search, container, false)

        val context = requireContext()
        albumService = ApiClient.getAlbumService(context)
        artistService = ApiClient.getArtistService(context)
        songManager = SongManager(context)

        searchHistoryManager = SearchHistoryManager(requireContext())

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

        userProfileImage = view.findViewById(R.id.userProfile)
        Utils.loadUserProfile(requireContext(), userProfileImage)

        // --- SETUP HISTORIAL ---
        historyRecyclerView = view.findViewById(R.id.historyRecyclerView)
        historyRecyclerView.layoutManager = LinearLayoutManager(context)
        historyAdapter = SearchHistoryAdapter(
            onItemClick = { query ->
                searchHistoryManager.addSearch(query)
                editTextQuery?.setText(query)
                editTextQuery?.setSelection(query.length)
            },
            onDeleteClick = { query ->
                searchHistoryManager.removeSearch(query)
                loadHistory()
            }
        )
        historyRecyclerView.adapter = historyAdapter

        // --- SETUP SUGERENCIAS ---
        suggestionsRecyclerView = view.findViewById(R.id.suggestionsRecyclerView)
        suggestionAdapter = SuggestionAdapter { suggestion ->
            // Guardamos historial al clickar sugerencia
            searchHistoryManager.addSearch(suggestion.text)
            editTextQuery?.setText(suggestion.text)
            editTextQuery?.setSelection(suggestion.text.length)
        }
        suggestionsRecyclerView.adapter = suggestionAdapter
        suggestionsRecyclerView.layoutManager = LinearLayoutManager(requireContext())


        sharedPref = requireContext().getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        songViewModel = ViewModelProvider(requireActivity())[SongViewModel::class.java]

        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            searchResultAdapter.setCurrentPlayingSong(currentSong?.id)
        }

        showSongs = savedInstanceState?.getBoolean("showSongs") ?: true
        showAlbums = savedInstanceState?.getBoolean("showAlbums") ?: true
        showArtists = savedInstanceState?.getBoolean("showArtists") ?: true

        chipSongs.isChecked = showSongs
        chipAlbums.isChecked = showAlbums
        chipArtists.isChecked = showArtists

        // --- RESTAURAR ESTADO ---
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

            updateAdapterList(applyActiveFilters(lastResults))
            updateFilteredResults()
            restoringState = false
        }

        // --- LISTENERS CHIPS ---
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

        var currentHomeQueueId: String = System.currentTimeMillis().toString()

        // --- LISTENERS ADAPTER RESULTADOS ---

        // 1. CLIC EN CANCIÓN
        searchResultAdapter.onSongClick = { (song, bitmap) ->
            // Guardamos historial
            searchHistoryManager.addSearch(song.title)

            val currentIndex = songResults.indexOfFirst { it.id == song.id }
            val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, song.id) }
            val songList = ArrayList(songResults)

            val playIntent = Intent(context, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.Companion.ACTION_PLAY
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_SONG, song)
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_INDEX, currentIndex)
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                putParcelableArrayListExtra(MusicPlaybackService.Companion.SONG_LIST, songList)
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE, QueueSource.SEARCH)
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE_ID, currentHomeQueueId)
            }
            requireContext().startService(playIntent)
        }

        // 2. CLIC EN ÁLBUM (Implementado en Fragment para guardar historial)
        searchResultAdapter.onAlbumClick = { album ->
            // Guardamos historial
            searchHistoryManager.addSearch(album.title ?: "")

            // Navegamos
            val bundle = Bundle().apply { putString("albumId", album.id) }
            findNavController().navigate(R.id.action_searchFragment_to_albumFragment, bundle)
        }

        // 3. CLIC EN ARTISTA (Implementado en Fragment para guardar historial)
        searchResultAdapter.onArtistClick = { artist, imageView ->
            // Guardamos historial
            searchHistoryManager.addSearch(artist.name)

            // Navegamos con animación
            val bundle = Bundle().apply {
                putString("artistId", artist.id)
                putString("artistName", artist.name)
                putString("artistImageUrl", artist.url)
                putString("artistImageTransitionName", imageView.transitionName)
            }
            val extras = FragmentNavigatorExtras(imageView to imageView.transitionName)
            findNavController().navigate(R.id.action_searchFragment_to_artistFragment, bundle, null, extras)
        }

        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        lifecycleScope.launch {
            downloadViewModel.downloadedSongIds.collect { downloadedIds ->
                searchResultAdapter.downloadedSongIds = downloadedIds
            }
        }

        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]
        favoritesViewModel.loadFavoriteSongs()

        favoritesViewModel.favoriteSongIds.observe(viewLifecycleOwner) { songIds ->
            searchResultAdapter.favoriteSongIds = songIds
        }

        searchResultAdapter.onFavoriteClick = { song, wasFavorite ->
            favoritesViewModel.toggleFavoriteSong(song)
        }

        searchResultAdapter.onSettingsClick = { song ->
            lifecycleScope.launch {
                // Use artists already embedded in the song from the API
                song.artistName = song.artists.joinToString(", ") { it.name }

                val bottomSheet = SongOptionsBottomSheet(
                    song = song,
                    onSeeSongClick = { selectedSong ->
                        val bundle = Bundle().apply { putParcelable("song", selectedSong) }
                        findNavController().navigate(R.id.action_searchFragment_to_detailedSongFragment, bundle)
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
                        val bundle = Bundle().apply { putString("albumId", albumId) }
                        findNavController().navigate(R.id.action_searchFragment_to_albumFragment, bundle)
                    },
                    onGoToArtistClick = { artist ->
                         val bundle = Bundle().apply { 
                             putString("artistId", artist.id)
                             putString("artistName", artist.name)
                             putString("artistImageUrl", artist.url)
                        }
                        findNavController().navigate(R.id.action_searchFragment_to_artistFragment, bundle)
                    }
                )
                bottomSheet.show(parentFragmentManager, "SongOptionsBottomSheet")
            }
        }

        searchResultAdapter.onAlbumSettingsClick = { album ->
             val bottomSheet = com.example.resonant.ui.bottomsheets.AlbumOptionsBottomSheet(
                 album = album,
                onGoToAlbumClick = {
                    val bundle = Bundle().apply { putString("albumId", it.id) }
                    findNavController().navigate(R.id.action_searchFragment_to_albumFragment, bundle)
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
                                findNavController().navigate(R.id.action_searchFragment_to_artistFragment, bundle)
                            }
                            selector.show(parentFragmentManager, "ArtistSelectorBottomSheet")
                        } else {
                            val artist = artists[0]
                             val bundle = Bundle().apply { 
                                 putString("artistId", artist.id)
                                 putString("artistName", artist.name)
                                 putString("artistImageUrl", artist.url)
                            }
                            findNavController().navigate(R.id.action_searchFragment_to_artistFragment, bundle)
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

        searchResultAdapter.onArtistSettingsClick = { artist ->
            val bottomSheet = com.example.resonant.ui.bottomsheets.ArtistOptionsBottomSheet(
                artist = artist,
                onGoToArtistClick = { selectedArtist ->
                    val bundle = Bundle().apply { 
                         putString("artistId", selectedArtist.id)
                         putString("artistName", selectedArtist.name)
                         putString("artistImageUrl", selectedArtist.url)
                    }
                    findNavController().navigate(R.id.action_searchFragment_to_artistFragment, bundle)
                },
                onViewDetailsClick = {
                     val bundle = Bundle().apply { 
                         putParcelable("artist", it)
                         putString("artistId", it.id)
                     }
                     findNavController().navigate(R.id.action_global_to_detailedArtistFragment, bundle)
                }
            )
            bottomSheet.show(parentFragmentManager, "ArtistOptionsBottomSheet")
        }

        // --- TEXT WATCHER ---

        // --- TEXT WATCHER ---
        editTextQuery?.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (restoringState) return
                if (s.isNullOrBlank()) {

                    lastResults = emptyList()
                    searchResultAdapter.submitList(emptyList())
                    songResults.clear()
                    albumResults.clear()
                    artistResults.clear()
                    loadingAnimation.visibility = View.INVISIBLE
                    resultsRecyclerView.visibility = View.INVISIBLE
                    suggestionsRecyclerView.visibility = View.GONE
                    noSongsFounded.visibility = View.GONE

                    // Mostrar historial si está vacío
                    loadHistory()
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(newText: CharSequence?, start: Int, before: Int, count: Int) {
                if (restoringState) return

                val query = newText?.toString()?.trim().orEmpty()

                // Ocultar historial al escribir
                if (query.isNotEmpty()) {
                    historyRecyclerView.visibility = View.GONE
                }



                queryFlow.value = query

                // Removed old coroutine logic, now handled by Flow collection below
            }
        })

        // --- SEARCH FLOW COLLECTOR ---
        viewLifecycleOwner.lifecycleScope.launch {
            queryFlow
                .debounce(300)
                .filter { it.isNotEmpty() }
                .collectLatest { query ->
                    withContext(Dispatchers.Main) {
                        if (editTextQuery?.text?.toString() != query) return@withContext
                        noSongsFounded.visibility = View.GONE
                        resultsRecyclerView.visibility = View.INVISIBLE
                        suggestionsRecyclerView.visibility = View.GONE
                        loadingAnimation.visibility = View.VISIBLE
                    }

                    try {
                        val results = withContext(Dispatchers.IO) {
                            val songsDeferred = async { songManager.searchSongs(query, limit = 30) }
                            val albumsDeferred = async { albumService.searchAlbumsByQuery(query) }
                            val artistsDeferred = async { artistService.searchArtistsByQuery(query) }

                            val songsResponse = songsDeferred.await()
                            val albumsResponse = albumsDeferred.await()
                            val artistsResponse = artistsDeferred.await()

                            Triple(songsResponse, albumsResponse, artistsResponse)
                        }

                        val songsResponse = results.first
                        val albumsResponse = results.second
                        val artistsResponse = results.third

                        val processedData = withContext(Dispatchers.Default) {
                            val combinedSuggestions = intercalateAndSortSuggestions(
                                songsResponse.suggestions,
                                albumsResponse.suggestions,
                                artistsResponse.suggestions,
                                query
                            )

                            val albums = albumsResponse.results
                            albums.forEach { album ->
                                album.artistName = album.artists.joinToString(", ") { it.name }
                            }

                            val combinedResults = intercalateAndSortResults(
                                songsResponse.results,
                                albums,
                                artistsResponse.results,
                                query
                            )
                            
                            Triple(combinedSuggestions, combinedResults, Triple(songsResponse.results, albums, artistsResponse.results))
                        }

                        val combinedSuggestions = processedData.first
                        val combinedResults = processedData.second
                        val (songs, albums, artists) = processedData.third

                        withContext(Dispatchers.Main) {
                            songResults.clear(); songResults.addAll(songs)
                            albumResults.clear(); albumResults.addAll(albums)
                            artistResults.clear(); artistResults.addAll(artists)
                            lastResults = combinedResults

                            val finalList = applyActiveFilters(lastResults)
                            loadingAnimation.visibility = View.INVISIBLE

                            when {
                                finalList.isNotEmpty() -> {
                                    resultsRecyclerView.visibility = View.VISIBLE
                                    suggestionsRecyclerView.visibility = View.GONE
                                    noSongsFounded.visibility = View.GONE
                                    searchResultAdapter.submitList(finalList)
                                }
                                combinedSuggestions.isNotEmpty() -> {
                                    resultsRecyclerView.visibility = View.GONE
                                    suggestionsRecyclerView.visibility = View.VISIBLE
                                    noSongsFounded.visibility = View.GONE
                                    suggestionAdapter.submitList(combinedSuggestions)
                                }
                                else -> {
                                    resultsRecyclerView.visibility = View.GONE
                                    suggestionsRecyclerView.visibility = View.GONE
                                    noSongsFounded.visibility = View.VISIBLE
                                    searchResultAdapter.submitList(emptyList())
                                }
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            loadingAnimation.visibility = View.INVISIBLE
                            resultsRecyclerView.visibility = View.INVISIBLE
                            suggestionsRecyclerView.visibility = View.GONE
                            noSongsFounded.visibility = View.VISIBLE
                            searchResultAdapter.submitList(emptyList())
                            Log.e("SearchFragment", "Error during search", e)
                        }
                    }
                }
        }

        // Carga inicial del historial
        if (editTextQuery?.text.isNullOrBlank()) {
            loadHistory()
        }

        return view
    }

    private fun loadHistory() {
        val list = searchHistoryManager.getHistory()
        // historyAdapter ahora espera List<HistoryItem>, así que esto funciona directo:
        if (list.isNotEmpty()) {
            historyAdapter.submitList(list)
            historyRecyclerView.visibility = View.VISIBLE
        } else {
            historyRecyclerView.visibility = View.GONE
        }
    }

    // ... (Tus funciones de ordenamiento e intercalado se mantienen igual) ...
    private fun intercalateAndSortResults(songs: List<Song>, albums: List<Album>, artists: List<Artist>, query: String): List<SearchResult> {
        val lowerQuery = query.lowercase()
        val sortedSongs = songs.sortedByDescending { song ->
            val t = song.title.lowercase(); val a = song.artistName?.lowercase()
            when { t == lowerQuery -> 40; a == lowerQuery -> 35; t.contains(lowerQuery) -> 30; a?.contains(lowerQuery) == true -> 20; else -> 0 }
        }.map { SearchResult.SongItem(it) }
        val sortedAlbums = albums.sortedByDescending { album ->
            val t = album.title?.lowercase()
            when { t == lowerQuery -> 70; t?.startsWith(lowerQuery) == true -> 60; t?.contains(lowerQuery) == true -> 50; else -> 0 }
        }.map { SearchResult.AlbumItem(it) }
        val sortedArtists = artists.sortedByDescending { artist ->
            val n = artist.name.lowercase()
            when { n == lowerQuery -> 100; n.startsWith(lowerQuery) -> 90; n.contains(lowerQuery) -> 80; else -> 0 }
        }.map { SearchResult.ArtistItem(it) }

        val lists = mutableListOf(sortedArtists, sortedSongs, sortedAlbums)
        val combined = mutableListOf<SearchResult>()
        while (lists.any { it.isNotEmpty() }) {
            lists.forEachIndexed { index, list ->
                if (list.isNotEmpty()) { combined.add(list.first()); lists[index] = list.drop(1) }
            }
        }
        return combined
    }

    private fun intercalateAndSortSuggestions(songSuggestions: List<String>, albumSuggestions: List<String>, artistSuggestions: List<String>, query: String): List<SuggestionItem> {
        val lowerQuery = query.lowercase()
        fun scoreSuggestion(text: String, baseWeight: Int): Int {
            val t = text.lowercase()
            return when { t == lowerQuery -> baseWeight + 100; t.startsWith(lowerQuery) -> baseWeight + 80; t.contains(lowerQuery) -> baseWeight + 60; else -> baseWeight }
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
                if (list.isNotEmpty()) { combined.add(list.first()); lists[index] = list.drop(1) }
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
        songViewModel.currentSongLiveData.value?.let { currentSong -> searchResultAdapter.setCurrentPlayingSong(currentSong.id) }
        val resultsAreEmpty = list.isEmpty()
        noSongsFounded.visibility = if (resultsAreEmpty && editTextQuery?.text?.isNotEmpty() == true) View.VISIBLE else View.GONE
        resultsRecyclerView.visibility = if (!resultsAreEmpty) View.VISIBLE else View.INVISIBLE
        loadingAnimation.visibility = View.INVISIBLE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        view?.let { outState.putString("query", editTextQuery?.text?.toString() ?: "") }
        outState.putParcelableArrayList("results", ArrayList(lastResults))
        outState.putBoolean("showSongs", showSongs)
        outState.putBoolean("showAlbums", showAlbums)
        outState.putBoolean("showArtists", showArtists)
    }
}