package com.example.resonant.ui.fragments

import android.content.ComponentName
import android.content.Context

import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.DialogFragment

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.resonant.utils.AnimationsUtils
import com.example.resonant.utils.PreferenceKeys
import com.example.resonant.R
import com.example.resonant.managers.LyricLine
import com.example.resonant.managers.LyricsManager
import com.example.resonant.ui.adapters.LyricsAdapter
import com.example.resonant.ui.adapters.SongAdapter
import com.example.resonant.ui.bottomsheets.SelectPlaylistBottomSheet
import com.example.resonant.ui.bottomsheets.SongOptionsBottomSheet
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.example.resonant.utils.Utils
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.services.AlbumService
import com.example.resonant.data.network.services.ArtistService
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.viewmodels.DownloadViewModel
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.ui.viewmodels.SongViewModel
import androidx.palette.graphics.Palette
import com.example.resonant.playback.PlaybackStateRepository
import com.google.android.material.card.MaterialCardView
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.launch

class SongFragment : DialogFragment() {
    private lateinit var blurrySongImageBackground: ImageView
    private lateinit var arrowGoBackButton: FrameLayout
    private lateinit var settingsButton: FrameLayout

    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView

    private lateinit var replayButton: ImageButton
    private lateinit var shuffleButton: ImageButton
    private lateinit var playPauseButton: ImageButton
    private lateinit var previousSongButton: ImageButton
    private lateinit var nextSongButton: ImageButton
    
    private lateinit var nextSongContainer: View
    private lateinit var nextSongInfo: TextView

    private lateinit var sharedPref: SharedPreferences

    lateinit var songAdapter: SongAdapter
    private lateinit var songViewModel: SongViewModel
    private var isPlaying : Boolean = false
    private lateinit var favoritesViewModel: FavoritesViewModel
    private lateinit var favoriteButton: ImageButton

    private lateinit var downloadViewModel: DownloadViewModel

    lateinit var bottomSheet: SongOptionsBottomSheet

    private lateinit var albumService: AlbumService
    private lateinit var albumTypeView: TextView
    private lateinit var albumNameView: TextView

    private var lastDirection = 1
    private var lastSongId: String? = null
    private var isFirstLoad = true
    private var isAnimatingCover = false

    // 1. Declaramos el servicio
    private lateinit var artistService: ArtistService

    // Lyrics
    private lateinit var nestedScrollView: NestedScrollView
    private lateinit var lyricsCard: MaterialCardView
    private lateinit var lyricsHeader: View
    private lateinit var lyricsTopFade: View
    private lateinit var lyricsBottomFade: View
    private lateinit var lyricsRecyclerView: RecyclerView
    private lateinit var lyricsLoadingIndicator: ProgressBar
    private lateinit var noLyricsText: TextView
    private lateinit var lyricsAdapter: LyricsAdapter
    private var dominantColor: Int = 0xFF1A1A1A.toInt()
    private var lastLyricsSongId: String? = null

    private val lyricsHandler = Handler(Looper.getMainLooper())
    private var lyricLines: List<LyricLine> = emptyList()
    private var lastActiveLine = -1
    private var autoScrollEnabled = true
    private var hasTimedLyrics = false
    private var userIsSeeking = false
    private var ignoreUpdatesUntilMs = 0L

    // Service binding for getCurrentPosition()
    private var musicService: MusicPlaybackService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as MusicPlaybackService.MusicServiceBinder
            musicService = binder.getService()
            serviceBound = true
            if (lyricLines.isNotEmpty()) {
                startLyricsSync()
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            musicService = null
            serviceBound = false
            stopLyricsSync()
        }
    }

    private val lyricsUpdateRunnable = object : Runnable {
        override fun run() {
            if (userIsSeeking || System.currentTimeMillis() < ignoreUpdatesUntilMs) {
                Log.d("SongFragmentSync", "Skipping update: userIsSeeking=$userIsSeeking, ignoreUpdatesUntilMs=$ignoreUpdatesUntilMs")
                lyricsHandler.postDelayed(this, if (hasTimedLyrics) 350 else 16)
                return
            }
            
            val service = musicService ?: return
            
            val positionMs = service.getCurrentPosition().toLong()
            val durationMs = service.getDuration().toLong()
            Log.d("SongFragmentSync", "Syncing lyrics to pos: $positionMs")
            syncLyricsToPosition(positionMs, durationMs, forceScroll = false)
            lyricsHandler.postDelayed(this, if (hasTimedLyrics) 350 else 16)
        }
    }

    private val reenableAutoScrollRunnable = Runnable {
        autoScrollEnabled = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setWindowAnimations(R.style.DialogAnimationUpDown)

        Intent(requireContext(), MusicPlaybackService::class.java).also { intent ->
            requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            requireContext().unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun onResume() {
        super.onResume()
        if (lyricLines.isNotEmpty() && serviceBound) {
            startLyricsSync()
        }
    }

    override fun onPause() {
        super.onPause()
        stopLyricsSync()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopLyricsSync()
        lyricsHandler.removeCallbacksAndMessages(null)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        AnimationsUtils.animateOpenFragment(view)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_song, container, false)

        // 2. Inicializamos el servicio específico
        artistService = ApiClient.getArtistService(requireContext())
        artistService = ApiClient.getArtistService(requireContext())
        albumService = ApiClient.getAlbumService(requireContext())

        val titleView = view.findViewById<TextView>(R.id.song_title)
        val artistView = view.findViewById<TextView>(R.id.songArtist)
        val imageSong = view.findViewById<ImageView>(R.id.song_image)

        seekBar = view.findViewById(R.id.seekBar)
        currentTimeText = view.findViewById(R.id.currentTimeText)
        totalTimeText = view.findViewById(R.id.totalTimeText)
        playPauseButton = view.findViewById(R.id.playPauseButton)
        previousSongButton = view.findViewById(R.id.previousSongButton)
        nextSongButton = view.findViewById(R.id.nextSongButton)
        replayButton = view.findViewById(R.id.replay_button)
        shuffleButton = view.findViewById(R.id.shuffleButton)
        songAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_FULL)
        blurrySongImageBackground = view.findViewById(R.id.blurrySongImageBackground)
        arrowGoBackButton = view.findViewById(R.id.arrowGoBackBackground)
        settingsButton = view.findViewById(R.id.settingsBackground)
        favoriteButton = view.findViewById(R.id.likeButton)
        albumTypeView = view.findViewById(R.id.songAlbumType)
        albumTypeView = view.findViewById(R.id.songAlbumType)
        albumNameView = view.findViewById(R.id.songAlbumName)
        nextSongContainer = view.findViewById(R.id.nextSongContainer)
        nextSongInfo = view.findViewById(R.id.nextSongInfo)

        // Lyrics views
        nestedScrollView = view.findViewById(R.id.nestedScrollView)
        lyricsCard = view.findViewById(R.id.lyricsCard)
        lyricsHeader = view.findViewById(R.id.header_lyrics)
        lyricsTopFade = view.findViewById(R.id.lyricsTopFade)
        lyricsBottomFade = view.findViewById(R.id.lyricsBottomFade)
        lyricsRecyclerView = view.findViewById(R.id.lyricsRecyclerView)
        lyricsLoadingIndicator = view.findViewById(R.id.lyricsLoadingIndicator)
        noLyricsText = view.findViewById(R.id.noLyricsText)

        lyricsAdapter = LyricsAdapter()
        lyricsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        lyricsRecyclerView.adapter = lyricsAdapter
        lyricsRecyclerView.itemAnimator = null

        // Pause auto-scroll when user manually drags the lyrics RecyclerView
        lyricsRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    autoScrollEnabled = false
                    lyricsHandler.removeCallbacks(reenableAutoScrollRunnable)
                    lyricsHandler.postDelayed(reenableAutoScrollRunnable, 3000)
                }
            }
        })

        val lyricsTapDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean = true
        })
        lyricsRecyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (lyricsTapDetector.onTouchEvent(e)) {
                    openExpandedLyrics()
                    return true
                }
                return false
            }
        })

        lyricsCard.setOnClickListener { openExpandedLyrics() }

        // Use the already-in-memory bitmap from PlaybackStateRepository — no disk I/O needed
        val currentBitmap = PlaybackStateRepository.currentSongBitmapLiveData.value
        if (currentBitmap != null) {
            imageSong.setImageBitmap(currentBitmap)
            blurrySongImageBackground.setImageBitmap(currentBitmap)
        } else {
            imageSong.setImageResource(R.drawable.ic_disc)
            blurrySongImageBackground.setImageResource(R.drawable.ic_disc)
        }

        songViewModel = ViewModelProvider(requireActivity()).get(SongViewModel::class.java)
        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]

        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        lifecycleScope.launch {
            downloadViewModel.downloadedSongIds.collect { downloadedIds ->
                songAdapter.downloadedSongIds = downloadedIds
                if (songAdapter.currentList.isNotEmpty()) {
                    songAdapter.notifyDataSetChanged()
                }
            }
        }

        setupViewModelObservers()

        val blurEffect = RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP)
        blurrySongImageBackground.setRenderEffect(blurEffect)

        arrowGoBackButton.setOnClickListener {
            dismiss()
        }

        favoritesViewModel.favoriteSongIds.observe(viewLifecycleOwner) { favoriteIds ->
            val currentSong = songViewModel.currentSongLiveData.value
            val isFavorite = currentSong?.id?.let { favoriteIds.contains(it) } ?: false
            updateFavoriteButtonUI(isFavorite)
        }

        favoriteButton.setOnClickListener {
            val song = songViewModel.currentSongLiveData.value ?: return@setOnClickListener

            favoritesViewModel.toggleFavoriteSong(song) { success, isNowFavorite ->
                if (success) {
                    updateFavoriteButtonUI(isNowFavorite)

                    showResonantSnackbar(
                        text = if (isNowFavorite) "¡Canción añadida a favoritos!" else "Canción eliminada de favoritos",
                        colorRes = R.color.successColor,
                        iconRes = R.drawable.ic_success
                    )
                } else {
                    showResonantSnackbar(
                        text = "Error al actualizar favoritos",
                        colorRes = R.color.errorColor,
                        iconRes = R.drawable.ic_error
                    )
                }
            }
        }

        settingsButton.setOnClickListener {
            val currentSong = songViewModel.currentSongLiveData.value
            currentSong?.let { song ->
                lifecycleScope.launch {
                    // Use artists already embedded in the song from the API
                    song.artistName = song.artists.joinToString(", ") { it.name }

                    bottomSheet = SongOptionsBottomSheet(
                        song = song,
                        onSeeSongClick = { selectedSong ->
                            val bundle = Bundle().apply { putParcelable("song", selectedSong) }
                            requireActivity().findNavController(R.id.nav_host_fragment)
                                .navigate(R.id.action_global_to_detailedSongFragment, bundle)

                            bottomSheet.dismiss()
                            this@SongFragment.dismiss()
                        },
                        onFavoriteToggled = { toggledSong ->
                            favoritesViewModel.toggleFavoriteSong(toggledSong)
                        },
                        onAddToPlaylistClick = { songToAdd ->
                            val selectPlaylistBottomSheet = SelectPlaylistBottomSheet(
                                song = songToAdd,
                                onNoPlaylistsFound = {
                                    this@SongFragment.dismiss()
                                    requireActivity().findNavController(R.id.nav_host_fragment)
                                        .navigate(R.id.action_global_to_createPlaylistFragment)
                                }
                            )
                            selectPlaylistBottomSheet.show(
                                parentFragmentManager,
                                "SelectPlaylistBottomSheet"
                            )
                        },
                        onDownloadClick = { songToDownload ->
                            downloadViewModel.downloadSong(songToDownload)
                        },
                        onRemoveDownloadClick = { songToDelete ->
                            downloadViewModel.deleteSong(songToDelete)
                        },
                        onGoToAlbumClick = { albumId ->
                            val bundle = Bundle().apply { putString("albumId", albumId) }
                            // Using direct ID navigation as action might not be defined for SongFragment -> AlbumFragment
                            requireActivity().findNavController(R.id.nav_host_fragment).navigate(R.id.albumFragment, bundle)
                            bottomSheet.dismiss()
                            this@SongFragment.dismiss()
                        },
                        onGoToArtistClick = { artist ->
                             val bundle = Bundle().apply { 
                                 putString("artistId", artist.id)
                                 putString("artistName", artist.name)
                                 putString("artistImageUrl", artist.url)
                            }
                            requireActivity().findNavController(R.id.nav_host_fragment).navigate(R.id.artistFragment, bundle)
                            bottomSheet.dismiss()
                            this@SongFragment.dismiss()
                        }
                    )
                    bottomSheet.show(parentFragmentManager, "SongOptionsBottomSheet")
                }
            }
        }

        playPauseButton.setOnClickListener {
            val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = if (isPlaying) MusicPlaybackService.Companion.ACTION_PAUSE else MusicPlaybackService.Companion.ACTION_RESUME
            }
            requireContext().startService(intent)
        }

        previousSongButton.setOnClickListener {
            lastDirection = -1
            val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.Companion.ACTION_PREVIOUS
            }
            requireContext().startService(intent)
        }

        nextSongButton.setOnClickListener {
            lastDirection = 1
            val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.Companion.ACTION_NEXT
            }
            requireContext().startService(intent)
        }

        replayButton.setOnClickListener {
            val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.Companion.ACTION_TOGGLE_REPEAT
            }
            requireContext().startService(intent)
        }

        shuffleButton.setOnClickListener {
            val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.Companion.ACTION_TOGGLE_SHUFFLE
            }
            requireContext().startService(intent)
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentTimeText.text = Utils.formatTime(progress)
                    if (lyricLines.isNotEmpty()) {
                        val durationMs = musicService?.getDuration()?.toLong() ?: 0L
                        syncLyricsToPosition(progress.toLong(), durationMs, forceScroll = true)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userIsSeeking = true
                stopLyricsSync()
                // Pause UI updates from the service side if needed, but since we ignore them here, it's fine.
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userIsSeeking = false
                val seekProgress = seekBar?.progress ?: 0
                
                // Synchronous direct call to service eliminates Intent queuing latency
                musicService?.seekTo(seekProgress.toLong())

                autoScrollEnabled = true
                ignoreUpdatesUntilMs = System.currentTimeMillis() + 800L
                
                if (lyricLines.isNotEmpty()) {
                    val durationMs = musicService?.getDuration()?.toLong() ?: 0L
                    lastActiveLine = -1 
                    lyricsAdapter.clearActiveLine() // Force adapter to forget previous highlight
                    syncLyricsToPosition(seekProgress.toLong(), durationMs, forceScroll = true)
                }

                startLyricsSync()
            }
        })
        return view
    }

    private fun loadAlbumInfo(song: com.example.resonant.data.models.Song) {
        // Estado de carga inicial
        albumTypeView.text = ""
        albumNameView.text = ""

        lifecycleScope.launch {
            try {
                val albumId = song.album!!.id

                // Si no hay albumId, es un Single (o archivo local suelto)
                if (albumId == null) {
                    setSingleMode()
                    return@launch
                }

                val album = albumService.getAlbumById(albumId)

                if (album != null) {
                    // Lógica: Si el título del álbum es igual al de la canción, es un Single
                    if (album.title.equals(song.title, ignoreCase = true)) {
                        setSingleMode()
                    } else {
                        // ES UN ÁLBUM REAL
                        albumTypeView.text = "ÁLBUM"
                        albumTypeView.visibility = View.VISIBLE

                        albumNameView.text = album.title
                        albumNameView.visibility = View.VISIBLE
                    }
                } else {
                    setSingleMode()
                }

            } catch (e: Exception) {
                setSingleMode()
            }
        }
    }

    // Función auxiliar para cuando es Single
    private fun setSingleMode() {
        // OPCIÓN A: Poner "SINGLE" arriba y ocultar el nombre (porque es redundante con el título de la canción)
        albumTypeView.text = "SINGLE"
        albumTypeView.visibility = View.VISIBLE
        albumNameView.visibility = View.GONE

        // OPCIÓN B: Si prefieres que ponga "SINGLE" y abajo el nombre igual:
        /*
        albumTypeView.text = "SINGLE"
        albumNameView.text = songViewModel.currentSongLiveData.value?.title
        */
    }

    private fun setupViewModelObservers() {
        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            updateNextSongInfo()
            currentSong?.let { song ->
                view?.findViewById<TextView>(R.id.song_title)?.text = song.title ?: "Desconocido"
                view?.findViewById<TextView>(R.id.songArtist)?.text = song.artistName ?: "Desconocido"

                loadAlbumInfo(song)

                val favoriteIds = favoritesViewModel.favoriteSongIds.value
                val isFavorite = song.id.let { favoriteIds?.contains(it) } ?: false
                updateFavoriteButtonUI(isFavorite)

                // Carga de imagen con Glide y animaciones
                val albumCoverRes = R.drawable.ic_disc
                val url = song.coverUrl

                if (!url.isNullOrBlank()) {
                    Glide.with(requireContext())
                        .asBitmap()
                        .load(url)
                        .placeholder(albumCoverRes)
                        .error(albumCoverRes)
                        .into(object : CustomTarget<Bitmap>() {
                            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                if (isAnimatingCover) return

                                if (isFirstLoad || song.id == lastSongId) {
                                    blurrySongImageBackground.setImageBitmap(resource)
                                    view?.findViewById<ImageView>(R.id.song_image)?.setImageBitmap(resource)
                                    isFirstLoad = false
                                } else {
                                    isAnimatingCover = true
                                    AnimationsUtils.animateBlurryBackground(blurrySongImageBackground, resource)
                                    // Asegurar que song_image existe
                                    val songImage = view?.findViewById<ImageView>(R.id.song_image)
                                    if (songImage != null) {
                                        AnimationsUtils.animateSongImage(songImage, resource, lastDirection) {
                                            isAnimatingCover = false
                                        }
                                    } else {
                                        isAnimatingCover = false
                                    }
                                }
                                lastSongId = song.id
                                applyDominantColorToCard(resource)
                            }
                            override fun onLoadCleared(placeholder: Drawable?) {}
                        })
                } else {
                    val bitmap = BitmapFactory.decodeResource(resources, albumCoverRes)
                    blurrySongImageBackground.setImageBitmap(bitmap)
                    view?.findViewById<ImageView>(R.id.song_image)?.setImageBitmap(bitmap)
                }
                
                // Load lyrics for the new song (tracked separately from animation lastSongId)
                if (song.id != lastLyricsSongId) {
                    lastLyricsSongId = song.id
                    loadLyricsForCurrentSong(song)
                }
            }
        }

        songViewModel.isPlayingLiveData.observe(viewLifecycleOwner) { isPlayingUpdate ->
            this.isPlaying = isPlayingUpdate
            updatePlayPauseButton(isPlayingUpdate)
        }

        songViewModel.playbackPositionLiveData.observe(viewLifecycleOwner) { positionInfo ->
            if (!seekBar.isPressed && !userIsSeeking && System.currentTimeMillis() > ignoreUpdatesUntilMs) {
                if (positionInfo.duration > 0) {
                    seekBar.max = positionInfo.duration.toInt()
                }
                seekBar.progress = positionInfo.position.toInt()
                currentTimeText.text = Utils.formatTime(positionInfo.position.toInt())
                totalTimeText.text = Utils.formatTime(positionInfo.duration.toInt())
            }
        }

        songViewModel.repeatModeLiveData.observe(viewLifecycleOwner) { mode ->
            updateNextSongInfo()
            when (mode) {
                PlaybackStateRepository.REPEAT_MODE_OFF -> replayButton.setImageResource(R.drawable.ic_replay)
                PlaybackStateRepository.REPEAT_MODE_ALL -> replayButton.setImageResource(R.drawable.ic_replay_selected)
                PlaybackStateRepository.REPEAT_MODE_ONE -> replayButton.setImageResource(R.drawable.ic_replay_one_selected)
            }
        }

        songViewModel.isShuffleEnabledLiveData.observe(viewLifecycleOwner) { isEnabled ->
            updateNextSongInfo()
            shuffleButton.setBackgroundResource(
                if (isEnabled) R.drawable.ic_random_selected else R.drawable.ic_random
            )
        }
    }

    // ── Lyrics methods ──

    private fun loadLyricsForCurrentSong(song: com.example.resonant.data.models.Song) {
        viewLifecycleOwner.lifecycleScope.launch {
            lyricsLoadingIndicator.isVisible = true
            lyricsRecyclerView.isVisible = false
            noLyricsText.isVisible = false

            val lines = LyricsManager.getLyrics(requireContext(), song.id)

            lyricsLoadingIndicator.isVisible = false

            if (lines.isEmpty()) {
                hasTimedLyrics = false
                noLyricsText.isVisible = true
                noLyricsText.text = "No hay letra disponible para esta canción"
                lyricLines = emptyList()
                lastActiveLine = -1
                stopLyricsSync()
            } else {
                lyricLines = lines
                hasTimedLyrics = lines.any { it.timeMs >= 0 }
                lastActiveLine = -1
                lyricsAdapter.submitLines(lines)
                lyricsRecyclerView.scrollToPosition(0)
                lyricsRecyclerView.isVisible = true

                if (!hasTimedLyrics) {
                    lyricsAdapter.clearActiveLine()
                    updateLinearLyricsProgress(
                        musicService?.getCurrentPosition()?.toLong() ?: 0L,
                        musicService?.getDuration()?.toLong() ?: 0L
                    )
                }

                if (serviceBound) {
                    startLyricsSync()
                }
            }

            // Prefetch next song's lyrics so they're ready when it plays
            prefetchNextSongLyrics(song.id)
        }
    }

    private fun prefetchNextSongLyrics(currentSongId: String) {
        val queue = PlaybackStateRepository.activeQueue ?: return
        val songs = queue.songs
        val currentIdx = songs.indexOfFirst { it.id == currentSongId }
        if (currentIdx < 0) return
        val repeatMode = songViewModel.repeatModeLiveData.value ?: PlaybackStateRepository.REPEAT_MODE_OFF
        val nextSong = songs.getOrNull(currentIdx + 1)
            ?: songs.getOrNull(0).takeIf { repeatMode != PlaybackStateRepository.REPEAT_MODE_OFF }
            ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            LyricsManager.getLyrics(requireContext(), nextSong.id)
        }
    }

    private fun startLyricsSync() {
        lyricsHandler.removeCallbacks(lyricsUpdateRunnable)
        lyricsHandler.post(lyricsUpdateRunnable)
    }

    private fun stopLyricsSync() {
        lyricsHandler.removeCallbacks(lyricsUpdateRunnable)
    }

    private fun syncLyricsToPosition(positionMs: Long, durationMs: Long, forceScroll: Boolean) {
        if (lyricLines.isEmpty()) return

        if (hasTimedLyrics) {
            val newIndex = LyricsManager.getCurrentLineIndex(lyricLines, positionMs)
            val shouldScroll = forceScroll || (autoScrollEnabled && newIndex != lastActiveLine)

            if (newIndex != lastActiveLine) {
                lastActiveLine = newIndex
                lyricsAdapter.updateActiveLine(newIndex)
            }

            if (shouldScroll && newIndex >= 0) {
                scrollToActiveLine(newIndex)
            }
        } else {
            updateLinearLyricsProgress(positionMs, durationMs)
        }
    }

    private fun scrollToActiveLine(index: Int) {
        if (!autoScrollEnabled) return
        val lm = lyricsRecyclerView.layoutManager as? LinearLayoutManager ?: return
        val availableHeight = lyricsRecyclerView.height - lyricsRecyclerView.paddingTop - lyricsRecyclerView.paddingBottom
        if (availableHeight <= 0) return
        val centerOffset = lyricsRecyclerView.paddingTop + (availableHeight / 2)
        val itemView = lm.findViewByPosition(index)
        if (itemView != null) {
            val itemCenter = (itemView.top + itemView.bottom) / 2
            lyricsRecyclerView.smoothScrollBy(0, itemCenter - centerOffset)
        } else {
            lm.scrollToPositionWithOffset(index, centerOffset)
        }
    }

    private fun updateLinearLyricsProgress(positionMs: Long, durationMs: Long) {
        if (lyricLines.isEmpty() || durationMs <= 0) return

        val progress = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        if (lastActiveLine != -1) {
            lastActiveLine = -1
            lyricsAdapter.clearActiveLine()
        }

        if (!autoScrollEnabled) return

        lyricsRecyclerView.post {
            val scrollRange = (lyricsRecyclerView.computeVerticalScrollRange() - lyricsRecyclerView.height).coerceAtLeast(0)
            val targetOffset = (scrollRange * progress).toInt()
            val currentOffset = lyricsRecyclerView.computeVerticalScrollOffset()
            if (kotlin.math.abs(targetOffset - currentOffset) <= 1) return@post

            if (kotlin.math.abs(targetOffset - currentOffset) > 120) {
                lyricsRecyclerView.scrollBy(0, targetOffset - currentOffset)
            } else {
                val nextOffset = currentOffset + ((targetOffset - currentOffset) * 0.18f).toInt()
                lyricsRecyclerView.scrollBy(0, nextOffset - currentOffset)
            }
        }
    }

    private fun applyDominantColorToCard(bitmap: Bitmap) {
        Palette.from(bitmap).generate { palette ->
            val raw = palette?.run {
                getDarkVibrantColor(0)
                    .takeIf { it != 0 }
                    ?: getVibrantColor(0)
                    .takeIf { it != 0 }
                    ?: getDarkMutedColor(0)
                    .takeIf { it != 0 }
                    ?: getDominantColor(0xFF1A1A1A.toInt())
            } ?: 0xFF1A1A1A.toInt()
            dominantColor = ColorUtils.blendARGB(raw, Color.BLACK, 0.35f)
            lyricsCard.setCardBackgroundColor(dominantColor)
            lyricsHeader.setBackgroundColor(dominantColor)
            val topGrad = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(dominantColor, Color.TRANSPARENT)
            )
            val botGrad = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(dominantColor, Color.TRANSPARENT)
            )
            lyricsTopFade.background = topGrad
            lyricsBottomFade.background = botGrad
        }
    }

    private fun openExpandedLyrics() {
        val song = songViewModel.currentSongLiveData.value ?: return
        LyricsExpandedFragment.newInstance(song.id, dominantColor)
            .show(parentFragmentManager, "LyricsExpanded")
    }

    // ── End lyrics methods ──

    private fun updateFavoriteButtonUI(isFavorite: Boolean) {
        favoriteButton.setBackgroundResource(
            if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        playPauseButton.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun setSongImage(imageView: ImageView, file: File) {
         if (file.exists()) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
                return
            }
        }
        imageView.setImageResource(R.drawable.ic_disc)
    }

    private fun updateNextSongInfo() {
        val queue = PlaybackStateRepository.activeQueue
        val songs = queue?.songs ?: emptyList()
        val currentSong = songViewModel.currentSongLiveData.value ?: PlaybackStateRepository.currentSong
        
        // Robustez: Buscar el índice real por ID, ya que currentIndex podría no estar sincronizado aún
        val currentIndex = if (currentSong != null) {
            songs.indexOfFirst { it.id == currentSong.id }
        } else {
            queue?.currentIndex ?: -1
        }

        val repeatMode = songViewModel.repeatModeLiveData.value ?: PlaybackStateRepository.REPEAT_MODE_OFF

        if (songs.isEmpty() || currentIndex == -1) {
            nextSongContainer.visibility = View.INVISIBLE
            return
        }

        var nextIndex = currentIndex + 1
        var showNext = true

        if (repeatMode == PlaybackStateRepository.REPEAT_MODE_ONE) {
            nextIndex = currentIndex
        } else if (nextIndex >= songs.size) {
            if (repeatMode == PlaybackStateRepository.REPEAT_MODE_ALL) {
                nextIndex = 0
            } else {
                showNext = false
            }
        }

        if (showNext) {
            val nextSong = songs.getOrNull(nextIndex)
            if (nextSong != null) {
                val artist = nextSong.artistName ?: nextSong.artists.joinToString(", ") { it.name }
                nextSongInfo.text = "${nextSong.title} • $artist"
                nextSongContainer.visibility = View.VISIBLE
            } else {
                nextSongContainer.visibility = View.INVISIBLE
            }
        } else {
            nextSongContainer.visibility = View.INVISIBLE
        }
    }
}