package com.example.resonant.ui.fragments

import android.content.Context
import android.animation.ObjectAnimator

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.DialogFragment

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
import com.example.resonant.managers.SongManager
import com.example.resonant.ui.adapters.LyricsAdapter
import com.example.resonant.ui.adapters.SongAdapter
import com.example.resonant.ui.PlaybackQueueController
import com.example.resonant.ui.bottomsheets.SelectPlaylistBottomSheet
import com.example.resonant.ui.bottomsheets.SongOptionsBottomSheet
import com.example.resonant.ui.bottomsheets.ArtistSelectorBottomSheet
import com.example.resonant.ui.bottomsheets.PlaybackDevicesBottomSheet
import com.example.resonant.utils.MiniPlayerColorizer
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.example.resonant.utils.Utils
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.services.AlbumService
import com.example.resonant.data.network.services.ArtistService
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.viewmodels.DownloadViewModel
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.playback.PlaybackStateRepository
import com.example.resonant.playback.PlaybackConnectRepository
import com.example.resonant.playback.QueueSource
import com.example.resonant.data.models.Artist
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.tabs.TabLayout
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.launch

class SongFragment : DialogFragment() {

    companion object {
        /** Horizontal swipe-to-skip on the cover, matching MiniPlayerSwipeHandler's feel. */
        private const val IMAGE_SWIPE_THRESHOLD_DP = 60f
        private const val IMAGE_SWIPE_RESISTANCE = 0.45f
        private const val IMAGE_SWIPE_SNAP_BACK_DURATION_MS = 250L
        /** Live drag feedback shrinks/fades toward the same 0.8 scale AnimationsUtils.animateSongImage commits to. */
        private const val IMAGE_SWIPE_SHRINK_AMOUNT = 0.2f
        private const val IMAGE_SWIPE_FADE_AMOUNT = 0.5f

        /** Vertical drag-to-dismiss, modeled on AriaQuickSheet's expansion gesture. */
        private const val DISMISS_COMMIT_PROGRESS = 0.28f
        private const val DISMISS_COMMIT_VELOCITY_PX_PER_SECOND = 900f
        private const val DISMISS_SNAP_BACK_DURATION_MS = 250L
        private const val DISMISS_ANIMATION_DURATION_MS = 220L
    }

    private lateinit var blurrySongImageBackground: ImageView
    private lateinit var arrowGoBackButton: FrameLayout
    private lateinit var settingsButton: FrameLayout

    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView

    private lateinit var replayButton: ImageButton
    private lateinit var shuffleButton: ImageButton
    private lateinit var playPauseButton: ImageButton
    private var playmixRingAnimator: ObjectAnimator? = null
    private lateinit var previousSongButton: ImageButton
    private lateinit var nextSongButton: ImageButton
    
    private lateinit var nextSongContainer: View
    private lateinit var nextSongInfo: TextView
    private lateinit var playbackDeviceContainer: View
    private lateinit var playbackDeviceText: TextView

    private lateinit var sharedPref: SharedPreferences

    lateinit var songAdapter: SongAdapter
    private lateinit var songViewModel: SongViewModel
    private var isPlaying : Boolean = false
    private lateinit var favoritesViewModel: FavoritesViewModel
    private lateinit var favoriteButton: ImageButton
    private lateinit var shareButton: ImageButton

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
    private lateinit var lyricsTopFade: View
    private lateinit var lyricsBottomFade: View
    private lateinit var lyricsRecyclerView: RecyclerView
    private lateinit var lyricsLoadingIndicator: ProgressBar
    private lateinit var noLyricsText: TextView
    private lateinit var lyricsAdapter: LyricsAdapter
    private var lastLyricsSongId: String? = null

    // Tabs (Up Next / Lyrics / Related)
    private lateinit var songTabLayout: TabLayout
    private lateinit var songTabContent: View
    private lateinit var upNextTabContent: View
    private lateinit var lyricsTabContent: View
    private lateinit var relatedTabContent: View
    private lateinit var queueController: PlaybackQueueController

    // Related tab
    private lateinit var relatedSongsRecyclerView: RecyclerView
    private lateinit var relatedSongsShimmer: ShimmerFrameLayout
    private lateinit var relatedSongsEmpty: TextView
    private lateinit var relatedSongsHeaderRow: View
    private lateinit var btnPlayRelated: ImageButton
    private var relatedSongsRequested = false
    private var relatedSongsSongId: String? = null

    private val lyricsHandler = Handler(Looper.getMainLooper())
    private var lyricLines: List<LyricLine> = emptyList()
    private var lastActiveLine = -1
    private var autoScrollEnabled = true
    private var hasTimedLyrics = false
    private var userIsSeeking = false
    private var ignoreUpdatesUntilMs = 0L
    private val playbackConnectRepository by lazy {
        PlaybackConnectRepository.get(requireContext())
    }

    // Cover-image swipe-to-skip + header/cover drag-to-dismiss
    private lateinit var songRootView: View
    private val touchSlop by lazy { ViewConfiguration.get(requireContext()).scaledTouchSlop }
    private var imageGestureStartX = 0f
    private var imageGestureStartY = 0f
    private var imageGestureDirection = 0 // 0=undecided, 1=horizontal skip, 2=vertical dismiss, -1=ignore (scroll)
    private var dismissVelocityTracker: VelocityTracker? = null
    private var dismissDragStartRawY = 0f
    private var isDraggingDismiss = false
    private var dismissInProgress = false

    // Contrast-safe icon/text color resolved per song by MiniPlayerColorizer
    // (always Color.BLACK or Color.WHITE) — applied to buttons that carry
    // their own selection state (shuffle/repeat/like) and therefore can't be
    // handed to MiniPlayerColorizer.Targets.iconButtons directly.
    private var dynamicIconTint: Int = Color.WHITE
    // Raw per-song background hue resolved alongside dynamicIconTint, passed
    // to LyricsExpandedFragment (which blends it toward black on its own).
    private var dynamicBackgroundColor: Int = Color.BLACK

    private val lyricsUpdateRunnable = object : Runnable {
        override fun run() {
            if (userIsSeeking || System.currentTimeMillis() < ignoreUpdatesUntilMs) {
                Log.d("SongFragmentSync", "Skipping update: userIsSeeking=$userIsSeeking, ignoreUpdatesUntilMs=$ignoreUpdatesUntilMs")
                lyricsHandler.postDelayed(this, if (hasTimedLyrics) 350 else 16)
                return
            }
            
            val position = PlaybackStateRepository.playbackPositionLiveData.value
            val positionMs = position?.position ?: 0L
            val durationMs = position?.duration?.toLong() ?: 0L
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
        if (lyricLines.isNotEmpty()) startLyricsSync()
    }

    override fun onResume() {
        super.onResume()
        if (lyricLines.isNotEmpty()) {
            startLyricsSync()
        }
    }

    override fun onPause() {
        super.onPause()
        stopLyricsSync()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        playmixRingAnimator?.cancel()
        playmixRingAnimator = null
        stopLyricsSync()
        lyricsHandler.removeCallbacksAndMessages(null)
        dismissVelocityTracker?.recycle()
        dismissVelocityTracker = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        AnimationsUtils.animateOpenFragment(view)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                playbackConnectRepository.uiState.collect { state ->
                    val active = state.activeDevice
                    val remoteIsActive =
                        active != null &&
                            active.deviceId != state.localDeviceId
                    val visible = state.supported &&
                        (remoteIsActive || state.hasAlternativeDevice)
                    playbackDeviceContainer.isVisible = visible
                    playbackDeviceText.text = when {
                        remoteIsActive ->
                            "Reproduciendo en ${active?.name}"
                        state.hasAlternativeDevice ->
                            "Elegir otro dispositivo"
                        else -> ""
                    }
                }
            }
        }
        playbackConnectRepository.refreshAsync()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_song, container, false)
        songRootView = view

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
        shareButton = view.findViewById(R.id.shareButton)
        albumTypeView = view.findViewById(R.id.songAlbumType)
        albumTypeView = view.findViewById(R.id.songAlbumType)
        albumNameView = view.findViewById(R.id.songAlbumName)
        nextSongContainer = view.findViewById(R.id.nextSongContainer)
        nextSongInfo = view.findViewById(R.id.nextSongInfo)
        playbackDeviceContainer =
            view.findViewById(R.id.playbackDeviceContainer)
        playbackDeviceText = view.findViewById(R.id.playbackDeviceText)

        artistView.isClickable = true
        artistView.isFocusable = true
        artistView.setOnClickListener { openCurrentSongArtists() }
        // La tarjeta "siguiente" ahora solo selecciona el tab "Up Next" en vez
        // de abrir el bottom sheet — la cola ya vive inline en la pantalla.
        nextSongContainer.setOnClickListener {
            songTabLayout.getTabAt(0)?.select()
        }
        playbackDeviceContainer.setOnClickListener {
            PlaybackDevicesBottomSheet().show(
                parentFragmentManager,
                "PlaybackDevicesBottomSheet"
            )
        }

        shareButton.setOnClickListener {
            val song = songViewModel.currentSongLiveData.value ?: return@setOnClickListener
            shareSong(song)
        }

        // Lyrics views
        nestedScrollView = view.findViewById(R.id.nestedScrollView)
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

        setupSongTabs(view)

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
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                downloadViewModel.downloadedSongIds.collect { downloadedIds ->
                    songAdapter.downloadedSongIds = downloadedIds
                }
            }
        }

        setupViewModelObservers()

        val blurEffect = RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP)
        blurrySongImageBackground.setRenderEffect(blurEffect)

        arrowGoBackButton.setOnClickListener {
            dismiss()
        }

        setupSongImageGestures(imageSong)
        setupTopBarDismissDrag(view.findViewById(R.id.topBar))

        favoritesViewModel.favoriteSongIds.observe(viewLifecycleOwner) { favoriteIds ->
            val currentSong = songViewModel.currentSongLiveData.value
            val isFavorite = currentSong?.id?.let { favoriteIds.contains(it) } ?: false
            updateFavoriteButtonUI(isFavorite)

            // El adapter del tab "Related" reutiliza este mismo estado para
            // pintar (o no) el corazón de cada fila — antes nunca se le
            // pasaba nada y el hueco del botón quedaba siempre reservado
            // pero vacío.
            songAdapter.favoriteSongIds = favoriteIds
            if (songAdapter.currentList.isNotEmpty()) {
                songAdapter.notifyDataSetChanged()
            }
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
                        val durationMs = PlaybackStateRepository.playbackPositionLiveData.value
                            ?.duration?.toLong() ?: 0L
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
                
                val seekIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                    action = MusicPlaybackService.ACTION_SEEK_TO
                    putExtra(MusicPlaybackService.EXTRA_SEEK_POSITION, seekProgress)
                }
                requireContext().startService(seekIntent)

                autoScrollEnabled = true
                ignoreUpdatesUntilMs = System.currentTimeMillis() + 800L
                
                if (lyricLines.isNotEmpty()) {
                    val durationMs = PlaybackStateRepository.playbackPositionLiveData.value
                        ?.duration?.toLong() ?: 0L
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
                val albumId = song.album?.id

                // Si no hay albumId, es un Single (o archivo local suelto)
                if (albumId.isNullOrBlank()) {
                    setSingleMode()
                    return@launch
                }

                val album = albumService.getAlbumById(albumId)

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
                view?.findViewById<TextView>(R.id.songArtist)?.text =
                    song.artistName
                        ?: song.artists.joinToString(", ") { it.name }
                            .ifBlank { "Desconocido" }

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
                                applyDynamicBackground(resource)
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
            updateRelatedPlayButtonIcon()
        }

        songViewModel.queueSourceLiveData.observe(viewLifecycleOwner) { source ->
            updatePlaymixMode(source == com.example.resonant.playback.QueueSource.PLAYMIX)
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
            applyIconTintToStatefulButtons()
        }

        songViewModel.isShuffleEnabledLiveData.observe(viewLifecycleOwner) { isEnabled ->
            updateNextSongInfo()
            shuffleButton.setImageResource(
                if (isEnabled) R.drawable.ic_random_selected else R.drawable.ic_random
            )
            applyIconTintToStatefulButtons()
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
                    val position = PlaybackStateRepository.playbackPositionLiveData.value
                    updateLinearLyricsProgress(
                        position?.position ?: 0L,
                        position?.duration?.toLong() ?: 0L
                    )
                }

                startLyricsSync()
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

    /**
     * Single color-extraction pipeline for the whole screen: tints the root
     * background + title/artist text via MiniPlayerColorizer using the actual
     * dominant/vibrant swatch of the album art — no forced blend toward the
     * brand color, each song gets its own real color. Supersedes the old
     * applyDominantColorToCard, which only recolored the lyrics card using a
     * separate, independent Palette pipeline — one extraction now drives the
     * entire screen instead of two that could disagree with each other.
     *
     * Takes [bitmap] directly (not read from the ImageView) because the cover
     * swap can be animated (see AnimationsUtils.animateSongImage above): the
     * ImageView's own drawable may not reflect the new bitmap yet at this point.
     */
    private fun applyDynamicBackground(bitmap: Bitmap) {
        val root = view ?: return
        MiniPlayerColorizer.applyFromBitmap(
            bitmap = bitmap,
            targets = MiniPlayerColorizer.Targets(
                container = root,
                title = root.findViewById(R.id.song_title),
                subtitle = root.findViewById(R.id.songArtist),
                iconButtons = listOf(previousSongButton, nextSongButton, shareButton),
                seekBar = seekBar
            ),
            fallbackColor = ContextCompat.getColor(requireContext(), R.color.songBackgroundFallback),
            onColorResolved = { iconColor, backgroundColor ->
                dynamicIconTint = iconColor
                dynamicBackgroundColor = backgroundColor
                applyIconTintToStatefulButtons()
                applyDynamicTabPanelColor(backgroundColor)
            }
        )
    }

    /**
     * El panel de tabs (Up Next/Lyrics/Related) tenía un fondo estático
     * (bg_song_tab_panel, playerCardBackground) — se mezcla hacia negro un
     * 70% (mismo criterio que ya usa LyricsExpandedFragment para su fondo)
     * para que herede el color de la canción sin perder contraste con el
     * texto claro de las filas de dentro (cola, letras, relacionadas).
     */
    private fun applyDynamicTabPanelColor(backgroundColor: Int) {
        if (!::songTabContent.isInitialized) return
        val panelColor = ColorUtils.blendARGB(backgroundColor, Color.BLACK, 0.7f)
        ViewCompat.setBackgroundTintList(songTabContent, ColorStateList.valueOf(panelColor))
    }

    /**
     * Re-applies [dynamicIconTint] to the buttons that carry their own
     * selection-state color (shuffle/repeat "on" = brand red, like = filled
     * heart) so a light per-song background doesn't wash out their "off"
     * icon the way a plain hardcoded white icon would.
     */
    private fun applyIconTintToStatefulButtons() {
        val tint = ColorStateList.valueOf(dynamicIconTint)

        val isShuffleEnabled = songViewModel.isShuffleEnabledLiveData.value ?: false
        shuffleButton.imageTintList = if (isShuffleEnabled) null else tint

        val repeatMode = songViewModel.repeatModeLiveData.value ?: PlaybackStateRepository.REPEAT_MODE_OFF
        replayButton.imageTintList = if (repeatMode == PlaybackStateRepository.REPEAT_MODE_OFF) tint else null

        favoriteButton.imageTintList = tint
    }

    // ── Cover swipe-to-skip + drag-to-dismiss ──

    /**
     * Horizontal drag on the cover skips to the next/previous song, with the
     * same feel as MiniPlayerSwipeHandler (60dp threshold, 0.45 damping,
     * overshoot snap-back) — but unlike that handler, it doesn't animate its
     * own "slide new content in": the real transition is already driven by
     * [com.example.resonant.utils.AnimationsUtils.animateSongImage] once the
     * new cover bitmap loads (see setupViewModelObservers), exactly the same
     * path the prev/next buttons already use via [lastDirection]. A vertical
     * downward drag on the same view is handed off to the shared
     * drag-to-dismiss gesture instead; a vertical upward drag is released so
     * the NestedScrollView can scroll normally.
     */
    private fun setupSongImageGestures(songImage: View) {
        songImage.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    imageGestureStartX = event.rawX
                    imageGestureStartY = event.rawY
                    imageGestureDirection = 0
                    beginDismissDrag(event)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - imageGestureStartX
                    val dy = event.rawY - imageGestureStartY

                    if (imageGestureDirection == 0) {
                        imageGestureDirection = when {
                            abs(dx) > abs(dy) * 1.5f && abs(dx) > touchSlop -> 1
                            dy > abs(dx) * 1.2f && dy > touchSlop -> 2
                            dy < -touchSlop -> -1
                            else -> 0
                        }
                        if (imageGestureDirection == 1 || imageGestureDirection == 2) {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }

                    when (imageGestureDirection) {
                        1 -> {
                            v.translationX = dx * IMAGE_SWIPE_RESISTANCE
                            // Same shrink+fade look as the committed skip
                            // transition (AnimationsUtils.animateSongImage),
                            // just driven continuously by drag progress
                            // instead of jumping straight there on release.
                            val progress = (abs(dx) * IMAGE_SWIPE_RESISTANCE /
                                (IMAGE_SWIPE_THRESHOLD_DP * resources.displayMetrics.density)).coerceIn(0f, 1f)
                            val scale = 1f - progress * IMAGE_SWIPE_SHRINK_AMOUNT
                            v.scaleX = scale
                            v.scaleY = scale
                            v.alpha = 1f - progress * IMAGE_SWIPE_FADE_AMOUNT
                            true
                        }
                        2 -> updateDismissDrag(event, v)
                        -1 -> false
                        else -> true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    when (imageGestureDirection) {
                        1 -> {
                            commitOrSnapBackImageSwipe(v)
                            dismissVelocityTracker?.recycle()
                            dismissVelocityTracker = null
                        }
                        2 -> endDismissDrag()
                    }
                    imageGestureDirection = 0
                    true
                }

                else -> false
            }
        }
    }

    private fun commitOrSnapBackImageSwipe(imageView: View) {
        val thresholdPx = IMAGE_SWIPE_THRESHOLD_DP * resources.displayMetrics.density
        val rawDx = imageView.translationX / IMAGE_SWIPE_RESISTANCE

        if (abs(rawDx) >= thresholdPx) {
            val isNext = rawDx < 0
            // The real transition (AnimationsUtils.animateSongImage) takes
            // over once the new cover bitmap loads, animating smoothly from
            // whatever translation/scale/alpha the drag left behind — reset
            // quickly here so there's no stale offset if that takes a beat.
            imageView.animate()
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(150L)
                .start()

            lastDirection = if (isNext) 1 else -1
            val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = if (isNext) MusicPlaybackService.Companion.ACTION_NEXT else MusicPlaybackService.Companion.ACTION_PREVIOUS
            }
            requireContext().startService(intent)
        } else {
            imageView.animate()
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(IMAGE_SWIPE_SNAP_BACK_DURATION_MS)
                .setInterpolator(OvershootInterpolator(2f))
                .start()
        }
    }

    /**
     * Second drag zone for dismissing the screen — the top bar sits outside
     * the NestedScrollView entirely, so it needs no direction-lock/scroll
     * hand-off logic, unlike the cover.
     */
    private fun setupTopBarDismissDrag(topBar: View) {
        topBar.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    beginDismissDrag(event)
                    true
                }
                MotionEvent.ACTION_MOVE -> updateDismissDrag(event, v)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    endDismissDrag()
                    true
                }
                else -> false
            }
        }
    }

    private fun beginDismissDrag(event: MotionEvent) {
        dismissDragStartRawY = event.rawY
        isDraggingDismiss = false
        dismissVelocityTracker?.recycle()
        dismissVelocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
    }

    /** Returns true once the drag has been claimed as a dismiss gesture. */
    private fun updateDismissDrag(event: MotionEvent, originView: View): Boolean {
        dismissVelocityTracker?.addMovement(event)
        val dy = event.rawY - dismissDragStartRawY

        if (!isDraggingDismiss && dy > touchSlop) {
            isDraggingDismiss = true
            originView.parent?.requestDisallowInterceptTouchEvent(true)
        }

        if (isDraggingDismiss) {
            val translation = dy.coerceAtLeast(0f)
            songRootView.translationY = translation
            val heightPx = songRootView.height.coerceAtLeast(1)
            songRootView.alpha = (1f - (translation / heightPx) * 0.6f).coerceIn(0.4f, 1f)
        }
        return isDraggingDismiss
    }

    private fun endDismissDrag() {
        val wasDragging = isDraggingDismiss
        dismissVelocityTracker?.computeCurrentVelocity(1000)
        val velocityY = dismissVelocityTracker?.yVelocity ?: 0f
        dismissVelocityTracker?.recycle()
        dismissVelocityTracker = null
        isDraggingDismiss = false

        if (!wasDragging) return

        val heightPx = songRootView.height.coerceAtLeast(1)
        val progress = songRootView.translationY / heightPx
        val shouldDismiss = progress >= DISMISS_COMMIT_PROGRESS ||
            velocityY >= DISMISS_COMMIT_VELOCITY_PX_PER_SECOND

        if (shouldDismiss) performGestureDismiss() else snapBackDismissDrag()
    }

    private fun snapBackDismissDrag() {
        songRootView.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(DISMISS_SNAP_BACK_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Finishes the drag off-screen by hand, then dismisses without the window's own slide-down so the two don't stack. */
    private fun performGestureDismiss() {
        if (dismissInProgress) return
        dismissInProgress = true
        songRootView.animate()
            .translationY(songRootView.height.toFloat())
            .alpha(0f)
            .setDuration(DISMISS_ANIMATION_DURATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                dialog?.window?.setWindowAnimations(0)
                dismiss()
            }
            .start()
    }

    // ── End cover swipe-to-skip + drag-to-dismiss ──

    // ── End lyrics methods ──

    private fun setupSongTabs(view: View) {
        songTabLayout = view.findViewById(R.id.songTabLayout)
        songTabContent = view.findViewById(R.id.songTabContent)
        upNextTabContent = view.findViewById(R.id.upNextTabContent)
        lyricsTabContent = view.findViewById(R.id.lyricsTabContent)
        relatedTabContent = view.findViewById(R.id.relatedTabContent)

        lyricsTabContent.findViewById<View>(R.id.expandLyricsButton).setOnClickListener {
            val song = songViewModel.currentSongLiveData.value ?: PlaybackStateRepository.currentSong ?: return@setOnClickListener
            LyricsExpandedFragment.newInstance(song.id, dynamicBackgroundColor)
                .show(parentFragmentManager, "LyricsExpandedFragment")
        }

        queueController = PlaybackQueueController(
            fragment = this,
            recycler = view.findViewById(R.id.playback_queue_list),
            subtitle = view.findViewById(R.id.queue_subtitle),
            hint = view.findViewById(R.id.queue_reorder_hint),
            clear = view.findViewById(R.id.clear_upcoming),
            shuffle = view.findViewById(R.id.shuffle_upcoming),
            actions = view.findViewById(R.id.queue_actions),
            empty = view.findViewById(R.id.empty_queue),
            // SongFragment already has its own dedicated Related tab, so the
            // queue tab doesn't need the "Relacionadas" mini-list too.
            relatedSection = null,
            relatedRecycler = null
        )
        queueController.start()

        relatedSongsRecyclerView = view.findViewById(R.id.relatedSongsRecyclerView)
        relatedSongsShimmer = view.findViewById(R.id.relatedSongsShimmer)
        relatedSongsEmpty = view.findViewById(R.id.relatedSongsEmpty)
        relatedSongsHeaderRow = view.findViewById(R.id.relatedSongsHeaderRow)
        btnPlayRelated = view.findViewById(R.id.btnPlayRelated)
        relatedSongsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        relatedSongsRecyclerView.adapter = songAdapter
        songAdapter.onItemClick = { (song, _) ->
            val bundle = Bundle().apply { putParcelable("song", song) }
            requireActivity().findNavController(R.id.nav_host_fragment)
                .navigate(R.id.action_global_to_detailedSongFragment, bundle)
        }
        songAdapter.onFavoriteClick = { song, _ -> favoritesViewModel.toggleFavoriteSong(song) }
        songAdapter.showPlayButton = true
        songAdapter.onPlayClick = { _, position -> playRelatedSongAt(position) }
        btnPlayRelated.setOnClickListener { playOrPauseRelatedSongs() }

        songTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                upNextTabContent.isVisible = tab.position == 0
                lyricsTabContent.isVisible = tab.position == 1
                relatedTabContent.isVisible = tab.position == 2
                if (tab.position == 2) loadRelatedSongsIfNeeded()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun shareSong(song: com.example.resonant.data.models.Song) {
        com.example.resonant.utils.ShareUtils.shareSong(requireContext(), song)
    }

    private fun loadRelatedSongsIfNeeded() {
        val song = songViewModel.currentSongLiveData.value ?: return
        if (relatedSongsRequested && relatedSongsSongId == song.id) return
        relatedSongsRequested = true
        relatedSongsSongId = song.id

        relatedSongsRecyclerView.isVisible = false
        relatedSongsEmpty.isVisible = false
        relatedSongsHeaderRow.isVisible = false
        relatedSongsShimmer.isVisible = true
        relatedSongsShimmer.startShimmer()

        viewLifecycleOwner.lifecycleScope.launch {
            val related = SongManager(requireContext()).getRelatedSongs(song.id, limit = 20)

            relatedSongsShimmer.stopShimmer()
            relatedSongsShimmer.isVisible = false

            if (related.isEmpty()) {
                relatedSongsEmpty.isVisible = true
            } else {
                songAdapter.submitList(related)
                relatedSongsRecyclerView.isVisible = true
                relatedSongsHeaderRow.isVisible = true
                updateRelatedPlayButtonIcon()
            }
        }
    }

    /** Reproduce la lista de "Relacionadas" desde el principio, o pausa si ya es esa la lista sonando — mismo patrón que AlbumFragment/ArtistFragment.playButton. */
    private fun playOrPauseRelatedSongs() {
        val relatedList = ArrayList(songAdapter.currentList)
        if (relatedList.isEmpty()) return

        val isRelatedListPlaying = songViewModel.isPlayingLiveData.value == true &&
            relatedList.any { it.id == songViewModel.currentSongLiveData.value?.id }

        if (isRelatedListPlaying) {
            val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.Companion.ACTION_PAUSE
            }
            requireContext().startService(intent)
        } else {
            val firstSong = relatedList[0]
            val seedSongId = relatedSongsSongId ?: songViewModel.currentSongLiveData.value?.id ?: "RELATED_UNKNOWN"
            val playIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.Companion.ACTION_PLAY
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_SONG, firstSong)
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_INDEX, 0)
                putParcelableArrayListExtra(MusicPlaybackService.Companion.SONG_LIST, relatedList)
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE, QueueSource.RELATED_SONGS)
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE_ID, seedSongId)
            }
            requireContext().startService(playIntent)
        }
    }

    /** Botón de play de una fila concreta en "Related" — reproduce esa canción, con el resto de la lista de relacionadas como cola a partir de ahí. */
    private fun playRelatedSongAt(position: Int) {
        val relatedList = ArrayList(songAdapter.currentList)
        if (position !in relatedList.indices) return

        val seedSongId = relatedSongsSongId ?: songViewModel.currentSongLiveData.value?.id ?: "RELATED_UNKNOWN"
        val playIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.Companion.ACTION_PLAY
            putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_SONG, relatedList[position])
            putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_INDEX, position)
            putParcelableArrayListExtra(MusicPlaybackService.Companion.SONG_LIST, relatedList)
            putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE, QueueSource.RELATED_SONGS)
            putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE_ID, seedSongId)
        }
        requireContext().startService(playIntent)
    }

    private fun updateRelatedPlayButtonIcon() {
        if (!::btnPlayRelated.isInitialized) return
        val isRelatedListPlaying = songViewModel.isPlayingLiveData.value == true &&
            songAdapter.currentList.any { it.id == songViewModel.currentSongLiveData.value?.id }
        btnPlayRelated.setImageResource(if (isRelatedListPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateFavoriteButtonUI(isFavorite: Boolean) {
        favoriteButton.setImageResource(
            if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
        // Ambos estados usan el color de contraste calculado por
        // MiniPlayerColorizer en vez de sus colores propios (rojo de marca /
        // blanco semántico), para que el corazón siga siendo visible sobre
        // fondos dinámicos muy claros.
        favoriteButton.imageTintList = ColorStateList.valueOf(dynamicIconTint)
    }

    private fun openCurrentSongArtists() {
        val song = songViewModel.currentSongLiveData.value
            ?: PlaybackStateRepository.currentSong
            ?: return
        val embeddedArtists = song.artists
            .filter { it.id.isNotBlank() }
            .map { it.toArtist() }
        when {
            embeddedArtists.size == 1 -> navigateToArtist(embeddedArtists.first())
            embeddedArtists.size > 1 -> {
                ArtistSelectorBottomSheet(embeddedArtists, ::navigateToArtist)
                    .show(parentFragmentManager, "ArtistSelectorBottomSheet")
            }
            !song.artistName.isNullOrBlank() -> {
                val expectedName = song.artistName!!.trim()
                viewLifecycleOwner.lifecycleScope.launch {
                    val exactArtist = runCatching {
                        artistService.searchArtistsByQuery(expectedName)
                            .results
                            .firstOrNull { it.name.equals(expectedName, ignoreCase = true) }
                    }.getOrNull()
                    if (exactArtist != null) {
                        navigateToArtist(exactArtist)
                    } else {
                        showResonantSnackbar(
                            "No se encontró la ficha del artista",
                            R.color.adviseColor,
                            R.drawable.ic_warning
                        )
                    }
                }
            }
        }
    }

    private fun navigateToArtist(artist: Artist) {
        if (artist.id.isBlank()) return
        val bundle = Bundle().apply {
            putString("artistId", artist.id)
            putString("artistName", artist.name)
            putString("artistImageUrl", artist.url)
        }
        requireActivity()
            .findNavController(R.id.nav_host_fragment)
            .navigate(R.id.artistFragment, bundle)
        dismiss()
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        playPauseButton.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun updatePlaymixMode(isPlaymix: Boolean) {
        val container = view?.findViewById<android.widget.FrameLayout>(R.id.playButtonContainer) ?: return
        val ring = view?.findViewById<android.view.View>(R.id.playmixRing)
        if (isPlaymix) {
            container.setBackgroundResource(R.drawable.bg_play_background_playmix)
            ring?.visibility = android.view.View.VISIBLE
            if (playmixRingAnimator == null || playmixRingAnimator?.isRunning == false) {
                playmixRingAnimator = ObjectAnimator.ofFloat(ring, "rotation", 0f, 360f).apply {
                    duration = 3000
                    repeatCount = ObjectAnimator.INFINITE
                    interpolator = android.view.animation.LinearInterpolator()
                }
                playmixRingAnimator?.start()
            }
        } else {
            container.setBackgroundResource(R.drawable.bg_play_button_circle)
            ring?.visibility = android.view.View.GONE
            playmixRingAnimator?.cancel()
            playmixRingAnimator = null
        }
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
        val currentIndex = if (
            queue != null &&
            queue.currentIndex in songs.indices &&
            songs[queue.currentIndex].id == currentSong?.id
        ) {
            queue.currentIndex
        } else if (currentSong != null) {
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
