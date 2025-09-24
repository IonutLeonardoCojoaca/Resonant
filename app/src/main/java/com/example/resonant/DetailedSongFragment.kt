package com.example.resonant

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

class DetailedSongFragment : BaseFragment(R.layout.fragment_detailed_song), CoroutineScope {

    private var song: Song? = null

    // Elementos del layout
    private lateinit var songImage: ShapeableImageView
    private lateinit var songTitle: TextView
    private lateinit var artistList: RecyclerView
    private lateinit var songDuration: TextView
    private lateinit var songStreams: TextView
    private lateinit var songAlbum: TextView

    private var albumJob: Job? = null

    // Para animación y caché
    private var albumArtAnimator: ObjectAnimator? = null
    private val bitmapCache = mutableMapOf<String, Bitmap>()
    private var artworkJob: Job? = null
    private val ioScope = CoroutineScope(Dispatchers.IO)
    override val coroutineContext: CoroutineContext get() = Dispatchers.Main + SupervisorJob()
    lateinit var songAdapter: SongAdapter
    private lateinit var sharedViewModel: SharedViewModel

    private var isPlaying: Boolean = false
    private lateinit var playButton: MaterialButton

    private val playbackStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MusicPlaybackService.ACTION_PLAYBACK_STATE_CHANGED) {
                val isPlayingNow = intent.getBooleanExtra(MusicPlaybackService.EXTRA_IS_PLAYING, false)
                val currentSong = intent.getParcelableExtra<Song>(MusicPlaybackService.EXTRA_CURRENT_SONG)

                // Compara IDs de la canción
                val isThisSongPlaying = currentSong?.id == song?.id
                isPlaying = isPlayingNow && isThisSongPlaying
                updatePlayPauseButton(isPlaying)
            }
        }
    }

    private val songChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MusicPlaybackService.ACTION_SONG_CHANGED) {
                val song = intent.getParcelableExtra<Song>(MusicPlaybackService.EXTRA_CURRENT_SONG)
                song?.let {
                    songAdapter.setCurrentPlayingSong(it.id)
                    sharedViewModel.setCurrentSong(it)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            playbackStateReceiver,
            IntentFilter(MusicPlaybackService.ACTION_PLAYBACK_STATE_CHANGED)
        )

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            songChangedReceiver,
            IntentFilter(MusicPlaybackService.ACTION_SONG_CHANGED)
        )

        // Solicitar el estado actual al servicio
        val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_REQUEST_STATE
        }

        requireContext().startService(intent)

        val songId = arguments?.getString("songId")
        song = arguments?.getParcelable("song")

        if (song == null && !songId.isNullOrBlank()) {
            val service = ApiClient.getService(requireContext())
            lifecycleScope.launch {
                try {
                    var loadedSong = service.getSongById(songId)

                    if (loadedSong != null) {
                        // Completar URL de audio si falta
                        if (loadedSong.url.isNullOrBlank() && !loadedSong.fileName.isNullOrBlank()) {
                            val songUrls = service.getMultipleSongUrls(listOf(loadedSong.fileName!!))
                            val urlDto = songUrls.firstOrNull { it.fileName == loadedSong.fileName }
                            if (urlDto != null) {
                                loadedSong = loadedSong.copy(url = urlDto.url)
                            }
                        }

                        // ✅ Completar portada si falta
                        if (loadedSong.coverUrl.isNullOrBlank() && !loadedSong.imageFileName.isNullOrBlank()) {
                            try {
                                val coverDto = service.getSongCoverUrl(
                                    loadedSong.imageFileName!!,
                                    loadedSong.albumId
                                )
                                loadedSong = loadedSong.copy(coverUrl = coverDto.url)
                            } catch (e: Exception) {
                                Log.w("DetailedSongFragment", "No se pudo cargar portada: ${e.message}")
                            }
                        }

                        // Completar artistas
                        val artists = runCatching { service.getArtistsBySongId(loadedSong.id) }.getOrNull() ?: emptyList()
                        val artistNames = artists.joinToString(", ") { it.name }
                        loadedSong = loadedSong.copy(artistName = artistNames)

                        song = loadedSong
                        setupSongUI(loadedSong)
                    } else {
                        showSongNotFound()
                    }
                } catch (e: Exception) {
                    showSongNotFound()
                }
            }
        }
        else if (song != null) {
            val service = ApiClient.getService(requireContext())
            if (song?.url.isNullOrBlank() && !song?.fileName.isNullOrBlank()) {
                lifecycleScope.launch {
                    try {
                        val songUrls = service.getMultipleSongUrls(listOf(song!!.fileName!!))
                        val urlDto = songUrls.firstOrNull { it.fileName == song!!.fileName }
                        if (urlDto != null) {
                            song = song!!.copy(url = urlDto.url)
                        }
                        setupSongUI(song!!)
                    } catch (e: Exception) {
                        setupSongUI(song!!)
                    }
                }
            } else {
                setupSongUI(song!!)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_detailed_song, container, false)

        // Inicializa los elementos del XML
        songImage = view.findViewById(R.id.songImage)
        songTitle = view.findViewById(R.id.songTitle)
        artistList = view.findViewById(R.id.artistList)
        songDuration = view.findViewById(R.id.songDuration)
        songStreams = view.findViewById(R.id.songStreams)
        songAlbum = view.findViewById(R.id.songAlbum)
        playButton = view.findViewById(R.id.playButton)

        artistList.layoutManager = LinearLayoutManager(requireContext())
        val artistAdapter = ArtistAdapter(emptyList())
        artistList.adapter = artistAdapter

        sharedViewModel = ViewModelProvider(requireActivity()).get(SharedViewModel::class.java)
        songAdapter = SongAdapter()

        playButton.setOnClickListener {
            if (isPlaying) {
                val intent = Intent(requireContext(), MusicPlaybackService::class.java)
                intent.action = MusicPlaybackService.ACTION_PAUSE
                requireContext().startService(intent)
            } else {
                song?.let { currentSong ->
                    val songList = arrayListOf(currentSong)
                    val bitmapPath = bitmapCache[currentSong.id]?.let {
                        Utils.saveBitmapToCache(requireContext(), it, currentSong.id)
                    }
                    val playIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                        action = MusicPlaybackService.ACTION_PLAY
                        putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, currentSong)
                        putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, 0)
                        putExtra(MusicPlaybackService.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                        putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, songList)
                    }
                    requireContext().startService(playIntent)
                }
            }
        }

        return view
    }

    private fun animateBackgroundColorFade(view: View, targetColor: Int, duration: Long = 500) {
        val currentColor = (view.background as? ColorDrawable)?.color ?: Color.TRANSPARENT
        val colorAnimator = ValueAnimator.ofArgb(currentColor, targetColor)
        colorAnimator.duration = duration
        colorAnimator.addUpdateListener { animator ->
            val animatedColor = animator.animatedValue as Int
            view.background = ColorDrawable(animatedColor)
        }
        colorAnimator.start()
    }

    private fun setBackgroundColorFromBitmapFade(view: View, bitmap: Bitmap) {
        Palette.from(bitmap).generate { palette ->
            val dominantColor = palette?.getDominantColor(
                requireContext().getColor(R.color.appBackgroundTheme)
            ) ?: requireContext().getColor(R.color.appBackgroundTheme)
            animateBackgroundColorFade(view, dominantColor)
        }
    }

    private fun setupSongUI(song: Song) {
        // Cancelar trabajos previos y animaciones
        cancelJobs()
        albumArtAnimator?.cancel()
        songImage.rotation = 0f
        songImage.visibility = View.VISIBLE
        Glide.with(requireContext()).clear(songImage)

        val placeholderRes = R.drawable.album_cover
        val errorRes = R.drawable.album_cover
        val rootView = view?.findViewById<ConstraintLayout>(R.id.rootConstraint) ?: return

        val albumUrl = song.coverUrl
        if (!albumUrl.isNullOrBlank()) {
            // Animación de rotación mientras carga
            albumArtAnimator = ObjectAnimator.ofFloat(songImage, "rotation", 0f, 360f).apply {
                duration = 3000
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }

            Glide.with(requireContext())
                .asBitmap()
                .load(albumUrl)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC) // Glide se encarga del cache
                .dontAnimate()
                .placeholder(placeholderRes)
                .error(errorRes)
                .listener(object : com.bumptech.glide.request.RequestListener<Bitmap> {
                    override fun onLoadFailed(
                        e: com.bumptech.glide.load.engine.GlideException?,
                        model: Any?,
                        target: com.bumptech.glide.request.target.Target<Bitmap>,
                        isFirstResource: Boolean
                    ): Boolean {
                        albumArtAnimator?.cancel()
                        songImage.rotation = 0f
                        Log.w(
                            "DetailedSongFragment",
                            "Album art load failed: $model -> ${e?.rootCauses?.firstOrNull()?.message}"
                        )
                        return false // Glide seguirá mostrando placeholder/error
                    }

                    override fun onResourceReady(
                        resource: Bitmap,
                        model: Any,
                        target: com.bumptech.glide.request.target.Target<Bitmap>?,
                        dataSource: com.bumptech.glide.load.DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        albumArtAnimator?.cancel()
                        songImage.rotation = 0f
                        // Actualiza el background usando el bitmap cargado
                        setBackgroundColorFromBitmapFade(rootView, resource)
                        return false // Glide asigna el Bitmap automáticamente al ImageView
                    }
                })
                .into(songImage)
        } else {
            songImage.setImageResource(placeholderRes)
        }

        songTitle.text = song.title
        songDuration.text = "Duración: ${Utils.formatSecondsToMinSec(song.duration.toString().toInt())}"
        songStreams.text = "Reproducciones: ${song.streams}"

        loadAlbumName(song.albumId)
        loadArtists(song.id)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cancelJobs()
        albumArtAnimator?.cancel()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(playbackStateReceiver)
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(songChangedReceiver)
    }

    private fun loadAlbumName(albumId: String?) {
        albumJob?.cancel()

        if (albumId.isNullOrBlank()) {
            songAlbum.visibility = View.GONE
            return
        }

        val service = ApiClient.getService(requireContext())
        albumJob = lifecycleScope.launch {
            try {
                val album = service.getAlbumById(albumId)
                val albumName = album.title ?: "Sin título"
                songAlbum.text = "Álbum: $albumName"
                songAlbum.visibility = View.VISIBLE
            } catch (e: Exception) {
                songAlbum.visibility = View.GONE
            }
        }
    }

    private fun loadArtists(songId: String, asList: Boolean = true) {
        val service = ApiClient.getService(requireContext())
        lifecycleScope.launch {
            try {
                // 1. Obtén los artistas por canción
                val artists = service.getArtistsBySongId(songId)

                // 2. Extrae los fileNames para pedir las URLs
                val fileNames = artists.mapNotNull { it.fileName }
                val urlList = if (fileNames.isNotEmpty()) service.getMultipleArtistUrls(fileNames) else emptyList()
                val urlMap = urlList.associateBy { it.fileName }

                // 3. Actualiza los artistas con la URL real en el campo fileName
                artists.forEach { artist ->
                    artist.fileName = urlMap[artist.fileName]?.url ?: ""
                }

                // 4. Actualiza el adapter
                (artistList.adapter as? ArtistAdapter)?.apply {
                    submitArtists(artists)
                    setViewType(if (asList) ArtistAdapter.VIEW_TYPE_LIST else ArtistAdapter.VIEW_TYPE_GRID)
                }
            } catch (e: Exception) {
                (artistList.adapter as? ArtistAdapter)?.submitArtists(emptyList())
            }
        }
    }

    fun updatePlayPauseButton(isPlaying: Boolean) {
        if (isPlaying) {
            playButton.setIconResource(R.drawable.pause)
        } else {
            playButton.setIconResource(R.drawable.play_arrow_filled)
        }
    }

    private fun cancelJobs() {
        artworkJob?.cancel()
        artworkJob = null
    }

    private fun showSongNotFound() {
        songTitle.text = "Canción no encontrada"
        songDuration.text = ""
        songStreams.text = ""
        songAlbum.visibility = View.GONE
        songImage.setImageResource(R.drawable.album_cover)
        (artistList.adapter as? ArtistAdapter)?.submitArtists(emptyList())
        playButton.visibility = View.GONE
    }
}