package com.example.resonant.ui.fragments

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.ProgressBar
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.data.models.Song
import com.example.resonant.managers.ArtistManager
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.SongAdapter
import com.example.resonant.ui.bottomsheets.SelectPlaylistBottomSheet
import com.example.resonant.ui.bottomsheets.SongOptionsBottomSheet
import com.example.resonant.ui.viewmodels.DownloadViewModel
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import kotlin.math.abs

class ArtistSmartPlaylistFragment : BaseFragment(R.layout.fragment_artist_smart_playlist) {

    // --- UI ---
    private lateinit var headerImage: ImageView
    private lateinit var playlistTitle: TextView
    private lateinit var playlistLabel: TextView
    private lateinit var artistNameTextView: TextView
    private lateinit var songCountBadge: TextView
    private lateinit var accentLine: View
    private lateinit var toolbarTitle: TextView
    private lateinit var btnPlay: MaterialButton
    private lateinit var btnShuffle: MaterialButton
    private lateinit var songListRecycler: RecyclerView
    private lateinit var songAdapter: SongAdapter
    private lateinit var loadingView: ProgressBar
    private lateinit var backButton: View
    private lateinit var appBarLayout: AppBarLayout
    private lateinit var emptyStateContainer: LinearLayout
    private lateinit var actionButtonsContainer: LinearLayout

    // --- ViewModels ---
    private lateinit var songViewModel: SongViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel
    private lateinit var downloadViewModel: DownloadViewModel

    // --- Data ---
    private var artistId: String = ""
    private var playlistType: String = ""  // "Essentials" or "Radio"
    private var artistName: String = ""
    private var artworkUrl: String? = null
    private var songList: List<Song> = emptyList()
    private var isPlaying: Boolean = false

    // --- Theme Colors per type ---
    private val essentialsAccent = Color.parseColor("#D4A853") // Warm gold
    private val radioAccent = Color.parseColor("#5B8DEF")     // Electric blue

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Get arguments
        arguments?.let {
            artistId = it.getString("artistId", "")
            playlistType = it.getString("playlistType", "")
            artistName = it.getString("artistName", "")
            artworkUrl = it.getString("artworkUrl")
        }

        if (artistId.isEmpty() || playlistType.isEmpty()) {
            findNavController().popBackStack()
            return
        }

        initViews(view)
        setupViewModels()
        setupUI()
        setupAppBarBehavior()
        setupClickListeners()
        loadData()
    }

    private fun initViews(view: View) {
        headerImage = view.findViewById(R.id.headerImage)
        playlistTitle = view.findViewById(R.id.playlistTitle)
        playlistLabel = view.findViewById(R.id.playlistLabel)
        artistNameTextView = view.findViewById(R.id.artistName)
        songCountBadge = view.findViewById(R.id.songCountBadge)
        accentLine = view.findViewById(R.id.accentLine)
        toolbarTitle = view.findViewById(R.id.toolbarTitle)
        btnPlay = view.findViewById(R.id.btnPlay)
        btnShuffle = view.findViewById(R.id.btnShuffle)
        songListRecycler = view.findViewById(R.id.songList)
        loadingView = view.findViewById(R.id.loadingProgressBar)
        backButton = view.findViewById(R.id.arrowGoBackButton)
        appBarLayout = view.findViewById(R.id.appBarLayout)
        emptyStateContainer = view.findViewById(R.id.emptyStateContainer)
        actionButtonsContainer = view.findViewById(R.id.actionButtonsContainer)

        // Setup adapter
        songAdapter = SongAdapter(SongAdapter.VIEW_TYPE_FULL)
        songListRecycler.layoutManager = LinearLayoutManager(requireContext())
        songListRecycler.adapter = songAdapter
        songListRecycler.isNestedScrollingEnabled = false
    }

    private fun setupViewModels() {
        songViewModel = ViewModelProvider(requireActivity())[SongViewModel::class.java]
        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]

        favoritesViewModel.loadAllFavorites()

        // Sync favorite icons
        favoritesViewModel.favoriteSongIds.observe(viewLifecycleOwner) { songIds ->
            songAdapter.favoriteSongIds = songIds
        }

        // Sync downloaded icons
        lifecycleScope.launch {
            downloadViewModel.downloadedSongIds.collect { downloadedIds ->
                songAdapter.downloadedSongIds = downloadedIds
            }
        }

        // Sync playing state → highlight currently playing song
        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            currentSong?.let { songAdapter.setCurrentPlayingSong(it.id) }
            checkPlayButtonState()
        }

        songViewModel.isPlayingLiveData.observe(viewLifecycleOwner) {
            checkPlayButtonState()
        }
    }

    private fun setupUI() {
        val accentColor = if (playlistType == "Essentials") essentialsAccent else radioAccent
        val isEssentials = playlistType == "Essentials"

        // Accent line color
        accentLine.setBackgroundColor(accentColor)

        // Label: "RESONANT · ESSENTIALS" or "ARTISTA · RADIO"
        val labelText = if (isEssentials) {
            "RESONANT · IMPRESCINDIBLES"
        } else {
            "${artistName.uppercase()} · RADIO"
        }
        playlistLabel.text = labelText

        // Main title
        val titleText = if (isEssentials) {
            "Esto es\n$artistName"
        } else {
            "$artistName\nRadio"
        }
        playlistTitle.text = titleText
        toolbarTitle.text = if (isEssentials) "Esto es $artistName" else "$artistName Radio"

        // Subtitle
        val subtitleText = if (isEssentials) {
            "Las canciones que definen a $artistName"
        } else {
            "Lo mejor de $artistName y artistas similares"
        }
        artistNameTextView.text = subtitleText

        // Song count badge hidden until loaded
        songCountBadge.visibility = View.GONE

        // Load header image
        if (!artworkUrl.isNullOrEmpty()) {
            Glide.with(this)
                .load(artworkUrl)
                .placeholder(R.drawable.ic_disc)
                .error(R.drawable.ic_disc)
                .centerCrop()
                .into(headerImage)
        } else {
            headerImage.setImageResource(R.drawable.ic_disc)
        }

        // Entrance animation for header content
        val headerContent = requireView().findViewById<LinearLayout>(R.id.headerContent)
        headerContent.alpha = 0f
        headerContent.translationY = 30f
        headerContent.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(600)
            .setStartDelay(200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun setupAppBarBehavior() {
        appBarLayout.addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { appBar, verticalOffset ->
            val totalRange = appBar.totalScrollRange
            if (totalRange == 0) return@OnOffsetChangedListener
            val progress = abs(verticalOffset).toFloat() / totalRange.toFloat()

            // Fade in toolbar title when collapsed
            toolbarTitle.alpha = progress
        })
    }

    private fun setupClickListeners() {
        backButton.setOnClickListener {
            findNavController().popBackStack()
        }

        // Play button
        btnPlay.setOnClickListener {
            if (songList.isEmpty()) return@setOnClickListener

            if (isPlaying) {
                // Pause
                val intent = Intent(requireContext(), MusicPlaybackService::class.java)
                intent.action = MusicPlaybackService.Companion.ACTION_PAUSE
                requireContext().startService(intent)
            } else {
                playSong(songList[0], 0)
            }
        }

        // Shuffle button
        btnShuffle.setOnClickListener {
            if (songList.isEmpty()) return@setOnClickListener

            val shuffledList = ArrayList(songList).apply { shuffle() }
            val firstSong = shuffledList[0]

            val playIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.Companion.ACTION_PLAY
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_SONG, firstSong)
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_INDEX, 0)
                putParcelableArrayListExtra(MusicPlaybackService.Companion.SONG_LIST, shuffledList)
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE, QueueSource.PLAYLIST)
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE_ID, "${artistId}_${playlistType}_shuffle")
            }
            requireContext().startService(playIntent)
        }

        // Song click → play
        songAdapter.onItemClick = { (song, _) ->
            val index = songList.indexOfFirst { it.id == song.id }
            playSong(song, if (index >= 0) index else 0)
        }

        // Favorite toggle
        songAdapter.onFavoriteClick = { song, _ ->
            favoritesViewModel.toggleFavoriteSong(song)
        }

        // Settings (3-dot menu)
        songAdapter.onSettingsClick = { song ->
            lifecycleScope.launch {
                try {
                    song.artistName = song.artists.joinToString(", ") { it.name }

                    val bottomSheet = SongOptionsBottomSheet(
                        song = song,
                        onSeeSongClick = { selectedSong ->
                            val bundle = Bundle().apply { putParcelable("song", selectedSong) }
                            findNavController().navigate(
                                R.id.action_artistSmartPlaylistFragment_to_detailedSongFragment,
                                bundle
                            )
                        },
                        onFavoriteToggled = { toggledSong ->
                            favoritesViewModel.toggleFavoriteSong(toggledSong)
                        },
                        onAddToPlaylistClick = { songToAdd ->
                            val sheet = SelectPlaylistBottomSheet(
                                song = songToAdd,
                                onNoPlaylistsFound = {
                                    findNavController().navigate(R.id.action_global_to_createPlaylistFragment)
                                }
                            )
                            sheet.show(parentFragmentManager, "SelectPlaylistBottomSheet")
                        },
                        onDownloadClick = { songToDownload ->
                            downloadViewModel.downloadSong(songToDownload)
                        },
                        onRemoveDownloadClick = { songToDelete ->
                            downloadViewModel.deleteSong(songToDelete)
                        },
                        onGoToAlbumClick = { albumId ->
                            val bundle = Bundle().apply { putString("albumId", albumId) }
                            findNavController().navigate(R.id.albumFragment, bundle)
                        },
                        onGoToArtistClick = { artist ->
                             val bundle = Bundle().apply { 
                                 putString("artistId", artist.id)
                                 putString("artistName", artist.name)
                                 putString("artistImageUrl", artist.url)
                            }
                            findNavController().navigate(R.id.artistFragment, bundle)
                        }
                    )
                    bottomSheet.show(parentFragmentManager, "SongOptionsBottomSheet")
                } catch (e: Exception) {
                    Log.e("SmartPlaylistFragment", "Error opening song options", e)
                }
            }
        }
    }

    private fun loadData() {
        // Show loading
        loadingView.visibility = View.VISIBLE
        songListRecycler.visibility = View.GONE
        emptyStateContainer.visibility = View.GONE
        actionButtonsContainer.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val songs = when (playlistType) {
                    "Essentials" -> ArtistManager.getEssentials(requireContext(), artistId)
                    "Radio" -> ArtistManager.getRadios(requireContext(), artistId)
                    else -> emptyList()
                }

                // Ensure artist name is set on each song
                songs.forEach { song ->
                    if (song.artistName.isNullOrEmpty()) {
                        song.artistName = song.artists.joinToString(", ") { it.name }
                    }
                }

                songList = songs

                if (songs.isNotEmpty()) {
                    songAdapter.submitList(songs)
                    songListRecycler.visibility = View.VISIBLE
                    actionButtonsContainer.visibility = View.VISIBLE

                    // Update song count badge
                    val countText = if (songs.size == 1) "1 canción" else "${songs.size} canciones"
                    songCountBadge.text = countText
                    songCountBadge.visibility = View.VISIBLE

                    // Animate buttons entrance
                    actionButtonsContainer.alpha = 0f
                    actionButtonsContainer.translationY = 20f
                    actionButtonsContainer.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(400)
                        .setStartDelay(100)
                        .start()

                } else {
                    emptyStateContainer.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.e("SmartPlaylistFragment", "Error loading songs", e)
                emptyStateContainer.visibility = View.VISIBLE
            } finally {
                loadingView.visibility = View.GONE
            }
        }
    }

    // --- Playback helpers ---

    private fun playSong(song: Song, index: Int) {
        val playIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.Companion.ACTION_PLAY
            putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_SONG, song)
            putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_INDEX, index)
            putParcelableArrayListExtra(MusicPlaybackService.Companion.SONG_LIST, ArrayList(songList))
            putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE, QueueSource.PLAYLIST)
            putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE_ID, "${artistId}_${playlistType}")
        }
        requireContext().startService(playIntent)
    }

    private fun checkPlayButtonState() {
        val serviceIsPlaying = songViewModel.isPlayingLiveData.value ?: false
        val currentSongId = songViewModel.currentSongLiveData.value?.id
        val songInList = songAdapter.currentList.any { it.id == currentSongId }

        isPlaying = serviceIsPlaying && songInList
        updatePlayButtonIcon(isPlaying)
    }

    private fun updatePlayButtonIcon(playing: Boolean) {
        val iconRes = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        btnPlay.setIconResource(iconRes)
        btnPlay.text = if (playing) "Pausar" else "Reproducir"
    }
}
