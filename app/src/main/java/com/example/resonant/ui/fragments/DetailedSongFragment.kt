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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViewModelObservers()
        val songId = arguments?.getString("songId")
        song = arguments?.getParcelable("song")

        if (song == null && !songId.isNullOrBlank()) {
            // CASO 1: Solo tenemos el ID de la canción.
            // El SongManager se encargará de hacer UNA SOLA llamada para obtener
            // la canción, sus artistas, análisis, y luego las URLs.
            lifecycleScope.launch {
                val loadedSong = SongManager.getSongById(requireContext(), songId)
                if (loadedSong != null) {
                    song = loadedSong
                    setupSongUI(loadedSong)
                } else {
                    showSongNotFound()
                }
            }
        }
        else if (song != null) {
            // CASO 2: Ya tenemos el objeto Song, pero podría estar incompleto (sin url).
            // Usamos nuestro nuevo método del manager para asegurarnos de que tiene todo.
            lifecycleScope.launch {
                try {
                    // Esta llamada enriquecerá el objeto 'song' con las URLs que le falten.
                    song = SongManager.enrichSingleSong(requireContext(), song!!)
                    setupSongUI(song!!)
                } catch (e: Exception) {
                    Log.e("DetailedSongFragment", "Error al enriquecer la canción", e)
                    // Si falla, mostramos la UI con los datos que ya teníamos.
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
            // Obtenemos los valores actuales del ViewModel
            val isCurrentlyPlaying = songViewModel.isPlayingLiveData.value ?: false
            val currentlyPlayingId = songViewModel.currentSongLiveData.value?.id

            // La condición clave: ¿Está el reproductor en "play" Y el ID de la canción
            // que suena es el mismo que el de esta pantalla?
            val isThisSongPlaying = isCurrentlyPlaying && (currentlyPlayingId == song?.id)

            // Actualizamos la variable local y el botón
            this.isPlaying = isThisSongPlaying
            updatePlayPauseButton(isThisSongPlaying)
        }

        // Observador 1: Se activa cuando cambia la canción que está sonando en toda la app.
        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { _ ->
            updateButtonState()
        }

        // Observador 2: Se activa cuando el estado de reproducción cambia (Play -> Pause o viceversa).
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
        // Cancelar trabajos previos y animaciones
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
                .listener(object : RequestListener<Bitmap> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Bitmap>,
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
                        target: Target<Bitmap>?,
                        dataSource: DataSource,
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

                // 3. Actualiza los artistas con la URL real en el campo url (no fileName)
                artists.forEach { artist ->
                    artist.url = urlMap[artist.fileName]?.url ?: ""
                }

                // 4. Actualiza el adapter
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