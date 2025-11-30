package com.example.resonant.ui.fragments

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
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
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.example.resonant.data.network.ApiClient
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.R
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.ui.adapters.SongAdapter
import com.example.resonant.utils.Utils
import com.example.resonant.data.models.Song
import com.example.resonant.managers.SongManager
import com.example.resonant.ui.adapters.ArtistAdapter
// Importamos los servicios específicos
import com.example.resonant.data.network.services.AlbumService
import com.example.resonant.data.network.services.ArtistService
import com.example.resonant.data.network.services.StorageService
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.collections.get
import kotlin.coroutines.CoroutineContext

class DetailedSongFragment : BaseFragment(R.layout.fragment_detailed_song), CoroutineScope {

    private var song: Song? = null

    private lateinit var songImage: ShapeableImageView
    private lateinit var songTitle: TextView
    private lateinit var artistList: RecyclerView
    private lateinit var songDuration: TextView
    private lateinit var songStreams: TextView
    private lateinit var songAlbum: TextView

    private var albumJob: Job? = null

    private var albumArtAnimator: ObjectAnimator? = null
    private val bitmapCache = mutableMapOf<String, Bitmap>()
    private var artworkJob: Job? = null
    override val coroutineContext: CoroutineContext get() = Dispatchers.Main + SupervisorJob()
    lateinit var songAdapter: SongAdapter
    private lateinit var songViewModel: SongViewModel

    private var isPlaying: Boolean = false
    private lateinit var playButton: MaterialButton

    // --- SERVICIOS Y MANAGERS ---
    private lateinit var albumService: AlbumService
    private lateinit var artistService: ArtistService
    private lateinit var storageService: StorageService
    private lateinit var songManager: SongManager // Instancia del Manager

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // --- 1. INICIALIZACIÓN DE DEPENDENCIAS ---
        val context = requireContext()
        albumService = ApiClient.getAlbumService(context)
        artistService = ApiClient.getArtistService(context)
        storageService = ApiClient.getStorageService(context)
        songManager = SongManager(context) // Instancia

        setupViewModelObservers()
        val songId = arguments?.getString("songId")
        song = arguments?.getParcelable("song")

        if (song == null && !songId.isNullOrBlank()) {
            // CASO 1: Solo tenemos el ID
            lifecycleScope.launch {
                // Usamos la instancia songManager
                val loadedSong = songManager.getSongById(songId)
                if (loadedSong != null) {
                    song = loadedSong
                    setupSongUI(loadedSong)
                } else {
                    showSongNotFound()
                }
            }
        }
        else if (song != null) {
            // CASO 2: Ya tenemos el objeto Song
            lifecycleScope.launch {
                try {
                    // Usamos la instancia songManager
                    song = songManager.enrichSingleSong(song!!)
                    setupSongUI(song!!)
                } catch (e: Exception) {
                    Log.e("DetailedSongFragment", "Error al enriquecer la canción", e)
                    setupSongUI(song!!)
                }
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
        songViewModel = ViewModelProvider(requireActivity()).get(SongViewModel::class.java)

        songAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_FULL)

        playButton.setOnClickListener {
            if (isPlaying) {
                val intent = Intent(requireContext(), MusicPlaybackService::class.java)
                intent.action = MusicPlaybackService.Companion.ACTION_PAUSE
                requireContext().startService(intent)
            } else {
                song?.let { currentSong ->
                    val songList = arrayListOf(currentSong)
                    val bitmapPath = bitmapCache[currentSong.id]?.let {
                        Utils.saveBitmapToCache(requireContext(), it, currentSong.id)
                    }
                    val playIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                        action = MusicPlaybackService.Companion.ACTION_PLAY
                        putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_SONG, currentSong)
                        putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_INDEX, 0)
                        putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                        putParcelableArrayListExtra(MusicPlaybackService.Companion.SONG_LIST, songList)
                    }
                    requireContext().startService(playIntent)
                }
            }
        }

        artistAdapter.onArtistClick = { artist, sharedImage ->
            val bundle = Bundle().apply { putString("artistId", artist.id) }
            val extras = FragmentNavigatorExtras(sharedImage to sharedImage.transitionName)
            findNavController().navigate(
                R.id.action_detailedSongFragment_to_artistFragment,
                bundle,
                null,
                extras
            )
        }

        return view
    }

    private fun setupViewModelObservers() {
        fun updateButtonState() {
            val isCurrentlyPlaying = songViewModel.isPlayingLiveData.value ?: false
            val currentlyPlayingId = songViewModel.currentSongLiveData.value?.id
            val isThisSongPlaying = isCurrentlyPlaying && (currentlyPlayingId == song?.id)
            this.isPlaying = isThisSongPlaying
            updatePlayPauseButton(isThisSongPlaying)
        }

        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { _ ->
            updateButtonState()
        }

        songViewModel.isPlayingLiveData.observe(viewLifecycleOwner) { _ ->
            updateButtonState()
        }
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
        cancelJobs()
        albumArtAnimator?.cancel()
        songImage.rotation = 0f
        songImage.visibility = View.VISIBLE
        Glide.with(requireContext()).clear(songImage)

        val placeholderRes = R.drawable.ic_disc
        val errorRes = R.drawable.ic_disc
        val rootView = view?.findViewById<ConstraintLayout>(R.id.rootConstraint) ?: return

        val albumUrl = song.coverUrl
        if (!albumUrl.isNullOrBlank()) {
            albumArtAnimator = ObjectAnimator.ofFloat(songImage, "rotation", 0f, 360f).apply {
                duration = 3000
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }

            Glide.with(requireContext())
                .asBitmap()
                .load(albumUrl)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .dontAnimate()
                .placeholder(placeholderRes)
                .error(errorRes)
                .listener(object : RequestListener<Bitmap> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Bitmap>,
                        isFirstResource: Boolean
                    ): Boolean {
                        albumArtAnimator?.cancel()
                        songImage.rotation = 0f
                        Log.w("DetailedSongFragment", "Album art load failed")
                        return false
                    }

                    override fun onResourceReady(
                        resource: Bitmap,
                        model: Any,
                        target: Target<Bitmap>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        albumArtAnimator?.cancel()
                        songImage.rotation = 0f
                        setBackgroundColorFromBitmapFade(rootView, resource)
                        return false
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
    }

    private fun loadAlbumName(albumId: String?) {
        albumJob?.cancel()

        if (albumId.isNullOrBlank()) {
            songAlbum.visibility = View.GONE
            return
        }

        albumJob = lifecycleScope.launch {
            try {
                // --- 2. USO DE ALBUM SERVICE ---
                val album = albumService.getAlbumById(albumId)
                val albumName = album.title ?: "Sin título"
                songAlbum.text = "Álbum: $albumName"
                songAlbum.visibility = View.VISIBLE
            } catch (e: Exception) {
                songAlbum.visibility = View.GONE
            }
        }
    }

    private fun loadArtists(songId: String, asList: Boolean = true) {
        lifecycleScope.launch {
            try {
                // --- 3. USO DE ARTIST Y STORAGE SERVICE ---

                // Obtener datos de artistas
                val artists = artistService.getArtistsBySongId(songId)

                // Obtener imágenes de artistas
                val fileNames = artists.mapNotNull { it.fileName }
                val urlList = if (fileNames.isNotEmpty()) {
                    storageService.getMultipleArtistUrls(fileNames)
                } else emptyList()

                val urlMap = urlList.associateBy { it.fileName }

                artists.forEach { artist ->
                    artist.url = urlMap[artist.fileName]?.url ?: ""
                }

                (artistList.adapter as? ArtistAdapter)?.apply {
                    submitArtists(artists)
                    setViewType(if (asList) ArtistAdapter.Companion.VIEW_TYPE_LIST else ArtistAdapter.Companion.VIEW_TYPE_GRID)
                }
            } catch (e: Exception) {
                (artistList.adapter as? ArtistAdapter)?.submitArtists(emptyList())
            }
        }
    }

    fun updatePlayPauseButton(isPlaying: Boolean) {
        if (isPlaying) {
            playButton.setIconResource(R.drawable.ic_pause)
        } else {
            playButton.setIconResource(R.drawable.ic_play)
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
        songImage.setImageResource(R.drawable.ic_disc)
        (artistList.adapter as? ArtistAdapter)?.submitArtists(emptyList())
        playButton.visibility = View.GONE
    }
}