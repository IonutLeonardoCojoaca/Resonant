package com.example.resonant

import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.example.resonant.SnackbarUtils.showResonantSnackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SongFragment : DialogFragment() {
    private lateinit var blurrySongImageBackground: ImageView
    private lateinit var arrowGoBackButton: FrameLayout
    private lateinit var settingsButton: FrameLayout

    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView

    private lateinit var replayButton: ImageButton
    private lateinit var playPauseButton: ImageButton
    private lateinit var previousSongButton: ImageButton
    private lateinit var nextSongButton: ImageButton

    private lateinit var sharedPref: SharedPreferences

    lateinit var songAdapter: SongAdapter
    private lateinit var sharedViewModel: SharedViewModel
    private var isPlaying : Boolean = false
    private lateinit var favoritesViewModel: FavoritesViewModel
    private lateinit var favoriteButton: ImageButton

    lateinit var bottomSheet: SongOptionsBottomSheet

    private var musicService: MusicPlaybackService? = null

    private var lastDirection = 1
    private var lastSongId: String? = null
    private var isFirstLoad = true
    private var isAnimatingCover = false // <-- AÑADE ESTA LÍNEA

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(DialogFragment.STYLE_NORMAL, R.style.FullScreenDialogStyle)
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
        songAdapter = SongAdapter(SongAdapter.VIEW_TYPE_FULL)
        blurrySongImageBackground = view.findViewById(R.id.blurrySongImageBackground)
        arrowGoBackButton = view.findViewById(R.id.arrowGoBackBackground)
        settingsButton = view.findViewById(R.id.settingsBackground)
        favoriteButton = view.findViewById(R.id.likeButton)

        arguments?.getString("coverFileName")?.let { fileName ->
            val file = File(requireContext().cacheDir, fileName)
            setSongImage(imageSong, file)
            setSongImage(blurrySongImageBackground, file)
        }

        sharedViewModel = ViewModelProvider(requireActivity()).get(SharedViewModel::class.java)
        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]

        setupViewModelObservers()

        val blurEffect = RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP)
        blurrySongImageBackground.setRenderEffect(blurEffect)

        arrowGoBackButton.setOnClickListener {
            dismiss()
        }

        favoritesViewModel.favoriteSongIds.observe(viewLifecycleOwner) { favoriteIds ->
            val currentSong = sharedViewModel.currentSongLiveData.value
            val isFavorite = currentSong?.id?.let { favoriteIds.contains(it) } ?: false
            updateFavoriteButtonUI(isFavorite)
        }

        favoriteButton.setOnClickListener {
            val song = sharedViewModel.currentSongLiveData.value ?: return@setOnClickListener

            favoritesViewModel.toggleFavoriteSong(song) { success, isNowFavorite ->
                if (success) {
                    updateFavoriteButtonUI(isNowFavorite)

                    showResonantSnackbar(
                        text = if (isNowFavorite) "¡Canción añadida a favoritos!" else "Canción eliminada de favoritos",
                        colorRes = R.color.successColor,
                        iconRes = R.drawable.success
                    )
                } else {
                    showResonantSnackbar(
                        text = "Error al actualizar favoritos",
                        colorRes = R.color.errorColor,
                        iconRes = R.drawable.error
                    )
                }
            }
        }

        settingsButton.setOnClickListener {
            val currentSong = sharedViewModel.currentSongLiveData.value
            currentSong?.let { song ->
                lifecycleScope.launch {
                    val service = ApiClient.getService(requireContext())
                    val artistList = service.getArtistsBySongId(song.id)
                    song.artistName = artistList.joinToString(", ") { it.name }

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
                        // --- AQUÍ IMPLEMENTAMOS LA NUEVA LAMBDA CON LA LÓGICA ESPECIAL ---
                        onAddToPlaylistClick = { songToAdd ->
                            val selectPlaylistBottomSheet = SelectPlaylistBottomSheet(
                                song = songToAdd,
                                // La lógica aquí es la compleja: primero cierra, luego navega
                                onNoPlaylistsFound = {
                                    this@SongFragment.dismiss()
                                    requireActivity().findNavController(R.id.nav_host_fragment)
                                        .navigate(R.id.action_global_to_createPlaylistFragment)
                                }
                            )
                            selectPlaylistBottomSheet.show(parentFragmentManager, "SelectPlaylistBottomSheet")
                        }
                    )
                    bottomSheet.show(parentFragmentManager, "SongOptionsBottomSheet")
                }
            }
        }

        playPauseButton.setOnClickListener {
            val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = if (isPlaying) MusicPlaybackService.ACTION_PAUSE else MusicPlaybackService.ACTION_RESUME
            }
            requireContext().startService(intent)
        }

        previousSongButton.setOnClickListener {
            lastDirection = -1
            val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_PREVIOUS
            }
            requireContext().startService(intent)
        }

        nextSongButton.setOnClickListener {
            lastDirection = 1
            val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_NEXT
            }
            requireContext().startService(intent)
        }

        sharedPref = requireContext().getSharedPreferences("music_experience", Context.MODE_PRIVATE)
        var isLooping = sharedPref.getBoolean(PreferenceKeys.IS_LOOP_ACTIVATED, false)

        replayButton.setImageResource(
            if (isLooping) R.drawable.replay_activated else R.drawable.replay
        )

        replayButton.setOnClickListener {
            isLooping = !isLooping
            sharedPref.edit().putBoolean(PreferenceKeys.IS_LOOP_ACTIVATED, isLooping).apply()
            musicService?.setLooping(isLooping)
            replayButton.setImageResource(
                if (isLooping) R.drawable.replay_activated else R.drawable.replay
            )
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {

            // Se llama continuamente mientras el usuario arrastra el dedo.
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Solo actuamos si el cambio lo ha iniciado el usuario (no el sistema).
                if (fromUser) {
                    // 🔥 YA NO ENVIAMOS EL INTENT DESDE AQUÍ 🔥
                    // Esta función ahora solo tiene UNA responsabilidad: actualizar el texto
                    // del tiempo para que el usuario vea a dónde está saltando en tiempo real.
                    currentTimeText.text = Utils.formatTime(progress)
                }
            }

            // Se llama UNA SOLA VEZ cuando el usuario toca el SeekBar.
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Le decimos al servicio que hemos empezado a arrastrar, para que ignore
                // las "micro-pausas" y el botón de play/pause no parpadee.
                val intent = Intent(context, MusicPlaybackService::class.java).apply {
                    action = MusicPlaybackService.ACTION_START_SEEK
                }
                context?.startService(intent)
            }

            // Se llama UNA SOLA VEZ cuando el usuario levanta el dedo del SeekBar.
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // 🔥 AQUÍ ES DONDE ENVIAMOS LA ORDEN FINAL Y ÚNICA 🔥

                // 1. Enviamos la posición final a la que queremos que salte el reproductor.
                val seekIntent = Intent(context, MusicPlaybackService::class.java).apply {
                    action = MusicPlaybackService.ACTION_SEEK_TO
                    // Usamos el progreso final del seekBar en el momento de soltar.
                    putExtra(MusicPlaybackService.EXTRA_SEEK_POSITION, seekBar?.progress ?: 0)
                }
                context?.startService(seekIntent)

                // 2. Le decimos al servicio que hemos terminado de arrastrar, para que
                //    vuelva a su comportamiento normal y reanude las actualizaciones automáticas.
                val stopIntent = Intent(context, MusicPlaybackService::class.java).apply {
                    action = MusicPlaybackService.ACTION_STOP_SEEK
                }
                context?.startService(stopIntent)
            }
        })
        return view
    }

    private fun setupViewModelObservers() {
        // 1. OBSERVADOR PARA LA CANCIÓN ACTUAL (ESTE YA LO TENÍAS, LO INTEGRAMOS AQUÍ)
        // Reemplaza a ACTION_SONG_CHANGED. Se encarga de actualizar título, artista, carátula, etc.
        sharedViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            currentSong?.let { song ->
                // Actualiza los textos
                view?.findViewById<TextView>(R.id.song_title)?.text = song.title ?: "Desconocido"
                view?.findViewById<TextView>(R.id.songArtist)?.text = song.artistName ?: "Desconocido"

                // Actualiza el estado del botón de favoritos
                val favoriteIds = favoritesViewModel.favoriteSongIds.value
                val isFavorite = song.id.let { favoriteIds?.contains(it) } ?: false
                updateFavoriteButtonUI(isFavorite)

                // Lógica para cargar y animar la imagen de la carátula
                val albumCoverRes = R.drawable.album_cover
                val url = song.coverUrl

                if (!url.isNullOrBlank()) {
                    Glide.with(requireContext())
                        .asBitmap()
                        .load(url)
                        .placeholder(albumCoverRes)
                        .error(albumCoverRes)
                        .into(object : com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                            override fun onResourceReady(resource: Bitmap, transition: com.bumptech.glide.request.transition.Transition<in Bitmap>?) {
                                if (isAnimatingCover) return

                                if (isFirstLoad || song.id == lastSongId) {
                                    blurrySongImageBackground.setImageBitmap(resource)
                                    view?.findViewById<ImageView>(R.id.song_image)?.setImageBitmap(resource)
                                    isFirstLoad = false
                                } else {
                                    isAnimatingCover = true
                                    AnimationsUtils.animateBlurryBackground(blurrySongImageBackground, resource)
                                    AnimationsUtils.animateSongImage(view!!.findViewById(R.id.song_image), resource, lastDirection) {
                                        isAnimatingCover = false
                                    }
                                }
                                lastSongId = song.id
                            }
                            override fun onLoadCleared(placeholder: Drawable?) {}
                        })
                } else {
                    val bitmap = BitmapFactory.decodeResource(resources, albumCoverRes)
                    blurrySongImageBackground.setImageBitmap(bitmap)
                    view?.findViewById<ImageView>(R.id.song_image)?.setImageBitmap(bitmap)
                }
            }
        }

        // 2. OBSERVADOR PARA EL ESTADO DE REPRODUCCIÓN (PLAY/PAUSE)
        // Reemplaza a ACTION_PLAYBACK_STATE_CHANGED
        sharedViewModel.isPlayingLiveData.observe(viewLifecycleOwner) { isPlayingUpdate ->
            this.isPlaying = isPlayingUpdate // Actualiza la variable de estado local
            updatePlayPauseButton(isPlayingUpdate) // Actualiza el icono del botón
        }

        // 3. OBSERVADOR PARA EL PROGRESO DEL SEEKBAR
        // Reemplaza a ACTION_SEEK_BAR_UPDATE y ACTION_SEEK_BAR_RESET
        sharedViewModel.playbackPositionLiveData.observe(viewLifecycleOwner) { positionInfo ->
            // Evita que el SeekBar se actualice si el usuario lo está arrastrando
            if (!seekBar.isPressed) {
                if (positionInfo.duration > 0) {
                    seekBar.max = positionInfo.duration.toInt()
                }
                seekBar.progress = positionInfo.position.toInt()
                currentTimeText.text = Utils.formatTime(positionInfo.position.toInt())
                totalTimeText.text = Utils.formatTime(positionInfo.duration.toInt())
            }
        }
    }

    private fun updateFavoriteButtonUI(isFavorite: Boolean) {
        if (isFavorite) {
            favoriteButton.setBackgroundResource(R.drawable.favorite)
        } else {
            favoriteButton.setBackgroundResource(R.drawable.favorite_border)
        }
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        playPauseButton.setImageResource(
            if (isPlaying) R.drawable.pause else R.drawable.play_arrow_filled
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
        imageView.setImageResource(R.drawable.album_cover)
    }


}
