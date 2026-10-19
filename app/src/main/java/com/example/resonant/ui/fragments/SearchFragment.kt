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
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.findNavController
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.example.resonant.R
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.DataType
import com.example.resonant.data.models.Playlist
import com.example.resonant.data.models.Song
import com.example.resonant.data.models.SuggestionItem
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.services.UniversalSearchService
import com.example.resonant.managers.SearchHistoryManager
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.SearchHistoryAdapter
import com.example.resonant.ui.adapters.SearchResult
import com.example.resonant.ui.adapters.SearchResultAdapter
import com.example.resonant.ui.adapters.SuggestionAdapter
import com.example.resonant.ui.bottomsheets.SelectPlaylistBottomSheet
import com.example.resonant.ui.bottomsheets.SelectPlaymixBottomSheet
import com.example.resonant.ui.bottomsheets.SongOptionsBottomSheet
import com.example.resonant.ui.viewmodels.DownloadViewModel
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.utils.AnimationsUtils
import com.example.resonant.utils.Utils
import com.google.android.material.chip.Chip
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SearchFragment : Fragment() {

    private lateinit var sharedPref: SharedPreferences
    private val queryFlow = MutableStateFlow("")
    private var restoringState = false
    private lateinit var songViewModel: SongViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel

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
    private lateinit var sectionLabel: TextView

    private lateinit var chipSongs: Chip
    private lateinit var chipAlbums: Chip
    private lateinit var chipArtists: Chip
    private lateinit var chipPlaylists: Chip
    private lateinit var chipEssentials: Chip
    private lateinit var chipPlaymixes: Chip
    private lateinit var chipGenres: Chip

    private val songResults = mutableListOf<Song>()
    private val albumResults = mutableListOf<Album>()
    private val artistResults = mutableListOf<Artist>()

    private var showSongs = true
    private var showAlbums = true
    private var showArtists = true
    private var showPlaylists = true
    private var showEssentials = true
    private var showPlaymixes = true
    private var showGenres = true

    private var lastResults: List<SearchResult> = emptyList()

    private lateinit var universalSearchService: UniversalSearchService

    private lateinit var downloadViewModel: DownloadViewModel

    override fun onResume() {
        super.onResume()
        songViewModel.currentSongLiveData.value?.let { currentSong ->
            searchResultAdapter.setCurrentPlayingSong(currentSong.id)
        }
        // Auto-show keyboard when search opens
        editTextQuery?.post {
            editTextQuery?.requestFocus()
            val controller = WindowInsetsControllerCompat(requireActivity().window, requireView())
            controller.show(WindowInsetsCompat.Type.ime())
        }
    }

    @OptIn(FlowPreview::class)
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_search, container, false)

        val context = requireContext()
        universalSearchService = ApiClient.getUniversalSearchService(context)

        searchHistoryManager = SearchHistoryManager(requireContext())

        chipSongs = view.findViewById(R.id.chipSongs)
        chipSongs.typeface = ResourcesCompat.getFont(requireContext(), R.font.unageo_medium)
        chipAlbums = view.findViewById(R.id.chipAlbums)
        chipAlbums.typeface = ResourcesCompat.getFont(requireContext(), R.font.unageo_medium)
        chipArtists = view.findViewById(R.id.chipArtists)
        chipArtists.typeface = ResourcesCompat.getFont(requireContext(), R.font.unageo_medium)
        chipPlaylists = view.findViewById(R.id.chipPlaylists)
        chipPlaylists.typeface = ResourcesCompat.getFont(requireContext(), R.font.unageo_medium)
        chipEssentials = view.findViewById(R.id.chipEssentials)
        chipEssentials.typeface = ResourcesCompat.getFont(requireContext(), R.font.unageo_medium)
        chipPlaymixes = view.findViewById(R.id.chipPlaymixes)
        chipPlaymixes.typeface = ResourcesCompat.getFont(requireContext(), R.font.unageo_medium)
        chipGenres = view.findViewById(R.id.chipGenres)
        chipGenres.typeface = ResourcesCompat.getFont(requireContext(), R.font.unageo_medium)

        noSongsFounded = view.findViewById(R.id.noSongFoundedText)
        sectionLabel = view.findViewById(R.id.searchSectionLabel)
        resultsRecyclerView = view.findViewById(R.id.filteredListResults)
        resultsRecyclerView.layoutManager = LinearLayoutManager(context)
        searchResultAdapter = SearchResultAdapter()
        resultsRecyclerView.adapter = searchResultAdapter

        editTextQuery = view.findViewById(R.id.editTextQuery)
        loadingAnimation = view.findViewById(R.id.loadingAnimation)

        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            requireActivity().findNavController(R.id.nav_host_fragment).popBackStack()
        }

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
        showPlaylists = savedInstanceState?.getBoolean("showPlaylists") ?: true
        showEssentials = savedInstanceState?.getBoolean("showEssentials") ?: true
        showPlaymixes = savedInstanceState?.getBoolean("showPlaymixes") ?: true
        showGenres = savedInstanceState?.getBoolean("showGenres") ?: true

        chipSongs.isChecked = showSongs
        chipAlbums.isChecked = showAlbums
        chipArtists.isChecked = showArtists
        chipPlaylists.isChecked = showPlaylists
        chipEssentials.isChecked = showEssentials
        chipPlaymixes.isChecked = showPlaymixes
        chipGenres.isChecked = showGenres

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
        chipPlaylists.setOnCheckedChangeListener { chip, isChecked ->
            showPlaylists = isChecked; updateFilteredResults()
            AnimationsUtils.animateChip(chip as Chip, isChecked); AnimationsUtils.animateChipColor(chip, isChecked)
        }
        chipEssentials.setOnCheckedChangeListener { chip, isChecked ->
            showEssentials = isChecked; updateFilteredResults()
            AnimationsUtils.animateChip(chip as Chip, isChecked); AnimationsUtils.animateChipColor(chip, isChecked)
        }
        chipPlaymixes.setOnCheckedChangeListener { chip, isChecked ->
            showPlaymixes = isChecked; updateFilteredResults()
            AnimationsUtils.animateChip(chip as Chip, isChecked); AnimationsUtils.animateChipColor(chip, isChecked)
        }
        chipGenres.setOnCheckedChangeListener { chip, isChecked ->
            showGenres = isChecked; updateFilteredResults()
            AnimationsUtils.animateChip(chip as Chip, isChecked); AnimationsUtils.animateChipColor(chip, isChecked)
        }

        var currentHomeQueueId: String = System.currentTimeMillis().toString()

        // --- LISTENERS ADAPTER RESULTADOS ---

        // 1. CLIC EN CANCIÓN
        searchResultAdapter.onSongClick = { (song, bitmap) ->
            // Guardamos historial con imagen
            searchHistoryManager.addSearch(song.title, song.coverUrl, "Canción")

            val currentIndex = songResults.indexOfFirst { it.id == song.id }
            val songList = ArrayList(songResults)

            viewLifecycleOwner.lifecycleScope.launch {
                val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, song.id) }

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
        }

        // 2. CLIC EN ÁLBUM (Implementado en Fragment para guardar historial)
        searchResultAdapter.onAlbumClick = { album ->
            // Guardamos historial con imagen
            searchHistoryManager.addSearch(album.title ?: "", album.url, "Álbum")

            // Navegamos
            val bundle = Bundle().apply { putString("albumId", album.id) }
            requireActivity().findNavController(R.id.nav_host_fragment).navigate(R.id.albumFragment, bundle)
        }

        // 3. CLIC EN ARTISTA (Implementado en Fragment para guardar historial)
        searchResultAdapter.onArtistClick = { artist, imageView ->
            // Guardamos historial con imagen
            searchHistoryManager.addSearch(artist.name, artist.url, "Artista")

            // Navegamos con animación
            val bundle = Bundle().apply {
                putString("artistId", artist.id)
                putString("artistName", artist.name)
                putString("artistImageUrl", artist.url)
                putString("artistImageTransitionName", imageView.transitionName)
            }
            val extras = FragmentNavigatorExtras(imageView to imageView.transitionName)
            requireActivity().findNavController(R.id.nav_host_fragment).navigate(R.id.artistFragment, bundle, null, extras)
        }

        searchResultAdapter.onResourceClick = { resource ->
            searchHistoryManager.addSearch(resource.title, resource.imageUrl, resource.type)
            val navController = requireActivity().findNavController(R.id.nav_host_fragment)
            when (resource.type) {
                "playlist" -> {
                    val playlist = Playlist(
                        id = resource.id,
                        userId = resource.ownerId,
                        name = resource.title,
                        description = resource.description,
                        numberOfTracks = resource.trackCount ?: 0,
                        duration = resource.duration ?: 0,
                        isPublic = resource.isPublic,
                        imageUrl = resource.imageUrl,
                        isSystemPlaylist = resource.isSystemPlaylist,
                        canEdit = resource.isOwnedByCurrentUser && !resource.isSystemPlaylist
                    ).apply { ownerName = resource.ownerName }
                    val bundle = Bundle().apply {
                        putParcelable("playlist", playlist)
                        putString("playlistId", resource.id)
                        putBoolean("isReadOnly", !resource.isOwnedByCurrentUser || resource.isSystemPlaylist)
                    }
                    navController.navigate(R.id.action_global_to_playlistFragment, bundle)
                }
                "essentials" -> {
                    val bundle = Bundle().apply {
                        putString("artistId", resource.id)
                        putString("playlistType", "Essentials")
                        putString("artistName", resource.title.removePrefix("Esto es "))
                        putString("artworkUrl", resource.imageUrl)
                    }
                    navController.navigate(R.id.artistSmartPlaylistFragment, bundle)
                }
                "playmix" -> {
                    val bundle = Bundle().apply {
                        putString("playmixId", resource.id)
                        putBoolean("isReadOnly", !resource.isOwnedByCurrentUser)
                    }
                    navController.navigate(R.id.action_global_to_playmixDetailFragment, bundle)
                }
                "genre" -> {
                    val bundle = Bundle().apply {
                        putString("genreId", resource.id)
                        putString("genreName", resource.title)
                        putString("genreGradientColors", resource.gradientColors)
                    }
                    navController.navigate(R.id.genreArtistsFragment, bundle)
                }
            }
        }

        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                downloadViewModel.downloadedSongIds.collect { downloadedIds ->
                    searchResultAdapter.downloadedSongIds = downloadedIds
                }
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
                        requireActivity().findNavController(R.id.nav_host_fragment).navigate(R.id.detailedSongFragment, bundle)
                    },
                    onFavoriteToggled = { toggledSong -> favoritesViewModel.toggleFavoriteSong(toggledSong) },
                    onAddToPlaylistClick = { songToAdd ->
                        val selectPlaylistBottomSheet = SelectPlaylistBottomSheet(
                            song = songToAdd,
                            onNoPlaylistsFound = {
                        requireActivity().findNavController(R.id.nav_host_fragment).navigate(R.id.action_global_to_createPlaylistFragment)
                    }
                        )
                        selectPlaylistBottomSheet.show(parentFragmentManager, "SelectPlaylistBottomSheet")
                    },
                    onAddToPlaymixClick = { songToAdd ->
                        val sheet = SelectPlaymixBottomSheet(
                            song = songToAdd,
                            onNoPlaymixesFound = {
                                requireActivity().findNavController(R.id.nav_host_fragment)
                                    .navigate(R.id.action_global_to_playmixListFragment)
                            }
                        )
                        sheet.show(parentFragmentManager, "SelectPlaymixBottomSheet")
                    },
                    onDownloadClick = { songToDownload ->
                        downloadViewModel.downloadSong(songToDownload)
                    },
                    onRemoveDownloadClick = { songToDelete ->
                        downloadViewModel.deleteSong(songToDelete)
                    },
                    onGoToAlbumClick = { albumId ->
                        val bundle = Bundle().apply { putString("albumId", albumId) }
                        requireActivity().findNavController(R.id.nav_host_fragment).navigate(R.id.albumFragment, bundle)
                    },
                    onGoToArtistClick = { artist ->
                         val bundle = Bundle().apply { 
                             putString("artistId", artist.id)
                             putString("artistName", artist.name)
                             putString("artistImageUrl", artist.url)
                        }
                        requireActivity().findNavController(R.id.nav_host_fragment).navigate(R.id.artistFragment, bundle)
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
                    requireActivity().findNavController(R.id.nav_host_fragment).navigate(R.id.albumFragment, bundle)
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
                                requireActivity().findNavController(R.id.nav_host_fragment).navigate(R.id.artistFragment, bundle)
                            }
                            selector.show(parentFragmentManager, "ArtistSelectorBottomSheet")
                        } else {
                            val artist = artists[0]
                             val bundle = Bundle().apply { 
                                 putString("artistId", artist.id)
                                 putString("artistName", artist.name)
                                 putString("artistImageUrl", artist.url)
                            }
                            requireActivity().findNavController(R.id.nav_host_fragment).navigate(R.id.artistFragment, bundle)
                        }
                    }
                },
                onViewDetailsClick = {
                     val bundle = Bundle().apply { 
                         putParcelable("album", it)
                         putString("albumId", it.id)
                     }
                     requireActivity().findNavController(R.id.nav_host_fragment).navigate(R.id.action_global_to_detailedAlbumFragment, bundle)
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
                    requireActivity().findNavController(R.id.nav_host_fragment).navigate(R.id.artistFragment, bundle)
                },
                onViewDetailsClick = {
                     val bundle = Bundle().apply { 
                         putParcelable("artist", it)
                         putString("artistId", it.id)
                     }
                     requireActivity().findNavController(R.id.nav_host_fragment).navigate(R.id.action_global_to_detailedArtistFragment, bundle)
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
                    sectionLabel.visibility = View.GONE

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
                .collectLatest { query ->
                    if (query.isEmpty()) return@collectLatest
                    withContext(Dispatchers.Main) {
                        if (editTextQuery?.text?.toString()?.trim() != query) return@withContext
                        noSongsFounded.visibility = View.GONE
                        resultsRecyclerView.visibility = View.INVISIBLE
                        suggestionsRecyclerView.visibility = View.GONE
                        sectionLabel.visibility = View.GONE
                        loadingAnimation.visibility = View.VISIBLE
                    }

                    try {
                        val response = withContext(Dispatchers.IO) {
                            universalSearchService.search(query)
                        }

                        val combinedResults = response.results.mapNotNull { hit ->
                            when (hit.type) {
                                "song" -> hit.song?.also { song ->
                                    song.artistName = song.artists.joinToString(", ") { it.name }
                                }?.let { SearchResult.SongItem(it) }
                                "album" -> hit.album?.also { album ->
                                    album.artistName = album.artists.joinToString(", ") { it.name }
                                }?.let { SearchResult.AlbumItem(it) }
                                "artist" -> hit.artist?.let { SearchResult.ArtistItem(it) }
                                "playlist", "essentials", "playmix", "genre" -> SearchResult.ResourceItem(hit)
                                else -> null
                            }
                        }
                        val combinedSuggestions = response.suggestions.map { SuggestionItem(it, DataType.SEARCH) }
                        val songs = combinedResults.filterIsInstance<SearchResult.SongItem>().map { it.song }
                        val albums = combinedResults.filterIsInstance<SearchResult.AlbumItem>().map { it.album }
                        val artists = combinedResults.filterIsInstance<SearchResult.ArtistItem>().map { it.artist }

                        withContext(Dispatchers.Main) {
                            songResults.clear(); songResults.addAll(songs)
                            albumResults.clear(); albumResults.addAll(albums)
                            artistResults.clear(); artistResults.addAll(artists)
                            lastResults = combinedResults

                            val finalList = applyActiveFilters(lastResults)
                            loadingAnimation.visibility = View.INVISIBLE

                            when {
                                finalList.isNotEmpty() -> {
                                    sectionLabel.text = "Resultados de búsqueda"
                                    sectionLabel.visibility = View.VISIBLE
                                    resultsRecyclerView.visibility = View.VISIBLE
                                    suggestionsRecyclerView.visibility = View.GONE
                                    noSongsFounded.visibility = View.GONE
                                    searchResultAdapter.submitList(finalList)
                                }
                                combinedSuggestions.isNotEmpty() -> {
                                    sectionLabel.text = "Quizás te refieres a…"
                                    sectionLabel.visibility = View.VISIBLE
                                    resultsRecyclerView.visibility = View.GONE
                                    suggestionsRecyclerView.visibility = View.VISIBLE
                                    noSongsFounded.visibility = View.GONE
                                    suggestionAdapter.submitList(combinedSuggestions)
                                }
                                else -> {
                                    sectionLabel.visibility = View.GONE
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
                            sectionLabel.visibility = View.GONE
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
        if (list.isNotEmpty()) {
            historyAdapter.submitList(list)
            historyRecyclerView.visibility = View.VISIBLE
            sectionLabel.text = "Reciente"
            sectionLabel.visibility = View.VISIBLE
        } else {
            historyRecyclerView.visibility = View.GONE
            sectionLabel.visibility = View.GONE
        }
    }

    private fun applyActiveFilters(results: List<SearchResult>): List<SearchResult> {
        return results.filter { item ->
            when (item) {
                is SearchResult.SongItem -> showSongs
                is SearchResult.AlbumItem -> showAlbums
                is SearchResult.ArtistItem -> showArtists
                is SearchResult.ResourceItem -> when (item.resource.type) {
                    "playlist" -> showPlaylists
                    "essentials" -> showEssentials
                    "playmix" -> showPlaymixes
                    "genre" -> showGenres
                    else -> false
                }
            }
        }
    }

    /** Con solo el filtro PlayMix marcado, enlace a las PlayMix públicas (playmix-sharing-discovery). */
    private fun updatePublicPlaymixesLink() {
        val link = view?.findViewById<View>(R.id.searchPublicPlaymixesLink) ?: return
        val onlyPlaymixes = showPlaymixes && !showSongs && !showAlbums && !showArtists &&
            !showPlaylists && !showEssentials && !showGenres
        link.visibility = if (onlyPlaymixes && com.example.resonant.managers.CapabilitiesManager.isPlaymixSharingEnabled()) View.VISIBLE else View.GONE
        link.setOnClickListener { it.findNavController().navigate(R.id.action_global_to_publicPlaymixesFragment) }
    }

    private fun updateFilteredResults() {
        updatePublicPlaymixesLink()
        val filtered = applyActiveFilters(lastResults)
        searchResultAdapter.submitList(filtered)
        noSongsFounded.visibility = if (filtered.isEmpty() && lastResults.isNotEmpty()) View.VISIBLE else View.GONE
        if (filtered.isNotEmpty()) {
            sectionLabel.text = "Resultados de búsqueda"
            sectionLabel.visibility = View.VISIBLE
            resultsRecyclerView.visibility = View.VISIBLE
        } else {
            sectionLabel.visibility = View.GONE
            resultsRecyclerView.visibility = View.INVISIBLE
        }
        loadingAnimation.visibility = View.INVISIBLE
    }

    private fun updateAdapterList(list: List<SearchResult>) {
        searchResultAdapter.submitList(list)
        songViewModel.currentSongLiveData.value?.let { currentSong -> searchResultAdapter.setCurrentPlayingSong(currentSong.id) }
        val resultsAreEmpty = list.isEmpty()
        noSongsFounded.visibility = if (resultsAreEmpty && editTextQuery?.text?.isNotEmpty() == true) View.VISIBLE else View.GONE
        if (!resultsAreEmpty) {
            sectionLabel.text = "Resultados de búsqueda"
            sectionLabel.visibility = View.VISIBLE
            resultsRecyclerView.visibility = View.VISIBLE
        } else {
            sectionLabel.visibility = View.GONE
            resultsRecyclerView.visibility = View.INVISIBLE
        }
        loadingAnimation.visibility = View.INVISIBLE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        view?.let { outState.putString("query", editTextQuery?.text?.toString() ?: "") }
        outState.putParcelableArrayList("results", ArrayList(lastResults))
        outState.putBoolean("showSongs", showSongs)
        outState.putBoolean("showAlbums", showAlbums)
        outState.putBoolean("showArtists", showArtists)
        outState.putBoolean("showPlaylists", showPlaylists)
        outState.putBoolean("showEssentials", showEssentials)
        outState.putBoolean("showPlaymixes", showPlaymixes)
        outState.putBoolean("showGenres", showGenres)
    }
}
