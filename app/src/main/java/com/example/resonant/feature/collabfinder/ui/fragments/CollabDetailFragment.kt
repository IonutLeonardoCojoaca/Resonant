package com.example.resonant.feature.collabfinder.ui.fragments

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.navigation.navGraphViewModels
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.data.models.AlbumSimpleDTO
import com.example.resonant.data.models.ArtistSimpleDTO
import com.example.resonant.data.models.Song
import com.example.resonant.databinding.FragmentCollabDetailBinding
import com.example.resonant.feature.collabfinder.domain.model.CollabDetail
import com.example.resonant.feature.collabfinder.domain.model.SharedSong
import com.example.resonant.feature.collabfinder.ui.viewmodel.CollabDetailUiState
import com.example.resonant.feature.collabfinder.ui.viewmodel.CollabFinderViewModel
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.SongAdapter
import com.example.resonant.ui.bottomsheets.SelectPlaylistBottomSheet
import com.example.resonant.ui.bottomsheets.SelectPlaymixBottomSheet
import com.example.resonant.ui.bottomsheets.SongOptionsBottomSheet
import com.example.resonant.ui.viewmodels.DownloadViewModel
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.ui.views.NonScrollableLinearLayoutManager
import com.example.resonant.utils.Utils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CollabDetailFragment : Fragment() {

    private var _binding: FragmentCollabDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CollabFinderViewModel by navGraphViewModels(R.id.collab_finder_nav) {
        defaultViewModelProviderFactory
    }
    private val args: CollabDetailFragmentArgs by navArgs()

    private lateinit var songAdapter: SongAdapter
    private lateinit var songViewModel: SongViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel
    private lateinit var downloadViewModel: DownloadViewModel

    private var playableSongs: List<Song> = emptyList()
    private var collabTitle: String = "Colaboración"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCollabDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViewModels()
        setupRecyclerView()
        setupStaticUi()
        setupListeners()
        setupObservers()

        viewModel.loadSharedSongs(args.artistId, args.collaboratorId)
    }

    private fun setupViewModels() {
        songViewModel = ViewModelProvider(requireActivity())[SongViewModel::class.java]
        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        favoritesViewModel.loadFavoriteSongs()
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(SongAdapter.VIEW_TYPE_FULL)
        binding.rvSharedSongs.apply {
            layoutManager = NonScrollableLinearLayoutManager(requireContext())
            adapter = songAdapter
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            itemAnimator?.changeDuration = 120
        }
    }

    private fun setupStaticUi() {
        val centralArtist = viewModel.centralArtist.value
        val artistAName = centralArtist?.name ?: "Artista"
        collabTitle = "$artistAName x ${args.collaboratorName}"

        binding.tvToolbarTitle.text = args.collaboratorName
        binding.tvCollabTitle.text = collabTitle
        binding.tvArtistAName.text = artistAName
        binding.tvArtistBName.text = args.collaboratorName
        binding.tvSongsSubtitle.text = "Toca una canción para reproducirla o abre sus opciones."

        if (!centralArtist?.imageUrl.isNullOrEmpty()) {
            Glide.with(this).load(centralArtist?.imageUrl).circleCrop().into(binding.ivArtistA)
        } else {
            binding.ivArtistA.setImageResource(R.drawable.ic_user)
        }

        if (!args.collaboratorImageUrl.isNullOrEmpty()) {
            Glide.with(this).load(args.collaboratorImageUrl).circleCrop().into(binding.ivArtistB)
        } else {
            binding.ivArtistB.setImageResource(R.drawable.ic_user)
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnPlayAll.setOnClickListener {
            playableSongs.firstOrNull()?.let { firstSong ->
                playSong(firstSong, null)
            } ?: Toast.makeText(context, "No hay canciones para reproducir", Toast.LENGTH_SHORT).show()
        }

        songAdapter.onItemClick = { (song, bitmap) ->
            playSong(song, bitmap)
        }

        songAdapter.onFavoriteClick = { song, _ ->
            favoritesViewModel.toggleFavoriteSong(song)
        }

        songAdapter.onSettingsClick = { song ->
            showSongOptions(song)
        }
    }

    private fun setupObservers() {
        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            songAdapter.setCurrentPlayingSong(currentSong?.id)
        }

        favoritesViewModel.favoriteSongIds.observe(viewLifecycleOwner) { songIds ->
            songAdapter.favoriteSongIds = songIds
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    downloadViewModel.downloadedSongIds.collect { downloadedIds ->
                        songAdapter.downloadedSongIds = downloadedIds
                    }
                }

                launch {
                    viewModel.detailState.collect { state ->
                        when (state) {
                            is CollabDetailUiState.Loading -> showLoading()
                            is CollabDetailUiState.Success -> showDetail(state.detail)
                            is CollabDetailUiState.Error -> {
                                showContent()
                                Toast.makeText(context, "Error: ${state.message}", Toast.LENGTH_LONG).show()
                            }
                            is CollabDetailUiState.Idle -> showContent()
                        }
                    }
                }
            }
        }
    }

    private fun showLoading() {
        binding.contentScroll.visibility = View.INVISIBLE
        binding.lottieLoader.visibility = View.VISIBLE
        binding.lottieLoader.playAnimation()
    }

    private fun showContent() {
        binding.lottieLoader.cancelAnimation()
        binding.lottieLoader.visibility = View.GONE
        binding.contentScroll.visibility = View.VISIBLE
    }

    private fun showDetail(detail: CollabDetail) {
        showContent()

        collabTitle = "${detail.artistA.name} x ${detail.artistB.name}"
        binding.tvToolbarTitle.text = detail.artistB.name
        binding.tvCollabTitle.text = collabTitle
        binding.tvArtistAName.text = detail.artistA.name
        binding.tvArtistBName.text = detail.artistB.name

        Glide.with(this).load(detail.artistA.imageUrl).placeholder(R.drawable.ic_user).circleCrop().into(binding.ivArtistA)
        Glide.with(this).load(detail.artistB.imageUrl).placeholder(R.drawable.ic_user).circleCrop().into(binding.ivArtistB)

        binding.tvCollabCountDetail.text = "${detail.collaborationCount}\ncanciones"
        binding.tvYearsDetail.text = "${detail.yearsSpan}\nperiodo"
        binding.scoreGaugeView.setScore(detail.collabScore)
        binding.tvSongsSubtitle.text = "${detail.totalSongs} canciones encontradas en esta colaboración"

        playableSongs = detail.songs.map { it.toPlayableSong() }
        binding.tvEmptySongs.visibility = if (playableSongs.isEmpty()) View.VISIBLE else View.GONE
        binding.rvSharedSongs.visibility = if (playableSongs.isEmpty()) View.GONE else View.VISIBLE
        binding.btnPlayAll.isEnabled = playableSongs.isNotEmpty()
        binding.btnPlayAll.alpha = if (playableSongs.isNotEmpty()) 1f else 0.45f

        songAdapter.submitList(playableSongs) {
            songAdapter.setCurrentPlayingSong(songViewModel.currentSongLiveData.value?.id)
        }
    }

    private fun playSong(song: Song, bitmap: Bitmap?) {
        val currentIndex = playableSongs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, song.id) }

        val playIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_PLAY
            putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, song)
            putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, currentIndex)
            putExtra(MusicPlaybackService.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
            putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, ArrayList(playableSongs))
            putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE, QueueSource.COLLAB_FINDER)
            putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE_ID, "${args.artistId}:${args.collaboratorId}")
            putExtra("EXTRA_QUEUE_SOURCE_NAME", collabTitle)
        }
        requireContext().startService(playIntent)
    }

    private fun showSongOptions(song: Song) {
        song.artistName = song.artists.joinToString(", ") { it.name }

        SongOptionsBottomSheet(
            song = song,
            onSeeSongClick = { selectedSong ->
                val bundle = Bundle().apply { putParcelable("song", selectedSong) }
                findNavController().navigate(R.id.action_global_to_detailedSongFragment, bundle)
            },
            onFavoriteToggled = { toggledSong ->
                favoritesViewModel.toggleFavoriteSong(toggledSong)
            },
            onAddToPlaylistClick = { songToAdd ->
                SelectPlaylistBottomSheet(
                    song = songToAdd,
                    onNoPlaylistsFound = {
                        findNavController().navigate(R.id.action_global_to_createPlaylistFragment)
                    }
                ).show(parentFragmentManager, "SelectPlaylistBottomSheet")
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
            },
            onAddToPlaymixClick = { songToAdd ->
                SelectPlaymixBottomSheet(
                    song = songToAdd,
                    onNoPlaymixesFound = {
                        findNavController().navigate(R.id.action_global_to_playmixListFragment)
                    }
                ).show(parentFragmentManager, "SelectPlaymixBottomSheet")
            }
        ).show(parentFragmentManager, "SongOptionsBottomSheet")
    }

    private fun SharedSong.toPlayableSong(): Song {
        val artistDtos = allArtists.map { artist ->
            ArtistSimpleDTO(
                id = artist.id,
                name = artist.name,
                url = null
            )
        }

        return Song(
            id = id,
            title = title,
            duration = durationMs?.let { Utils.formatDuration((it / 1000).toInt()) },
            streams = streams?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0,
            imageFileName = albumCoverUrl,
            releaseYear = releaseYear ?: 0,
            coverUrl = albumCoverUrl,
            artists = artistDtos,
            album = AlbumSimpleDTO(
                id = albumId.orEmpty(),
                title = albumTitle.orEmpty(),
                url = albumCoverUrl
            ),
            artistName = artistDtos.joinToString(", ") { it.name }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.lottieLoader.cancelAnimation()
        _binding = null
    }
}
