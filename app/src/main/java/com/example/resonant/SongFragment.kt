package com.example.resonant

import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.Shader
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
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File

class SongFragment : Fragment() {

    private lateinit var blurrySongImageBackground: ImageView
    private lateinit var parallaxRotatingImage : ImageView
    private lateinit var arrowGoBackButton: FrameLayout

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
    private val _currentSongLiveData = MutableLiveData<Song>()
    private var isPlaying : Boolean = false

    private var musicService: MusicPlaybackService? = null

    private var lastDirection = 1
    private var isFirstLoad = true

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

    private val playbackStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MusicPlaybackService.ACTION_PLAYBACK_STATE_CHANGED) {
                val isPlayingNow = intent.getBooleanExtra(MusicPlaybackService.EXTRA_IS_PLAYING, false)
                isPlaying = isPlayingNow
                updatePlayPauseButton(isPlayingNow)
            }
        }
    }

    private val seekBarUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MusicPlaybackService.ACTION_SEEK_BAR_UPDATE) {
                val position = intent.getIntExtra(MusicPlaybackService.EXTRA_SEEK_POSITION, 0)
                val duration = intent.getIntExtra(MusicPlaybackService.EXTRA_DURATION, 0)

                seekBar.max = duration
                seekBar.progress = position

                currentTimeText.text = Utils.formatTime(position)
                totalTimeText.text = Utils.formatTime(duration)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        AnimationsUtils.animateOpenFragment(view)

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            songChangedReceiver,
            IntentFilter(MusicPlaybackService.ACTION_SONG_CHANGED)
        )
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            seekBarUpdateReceiver,
            IntentFilter(MusicPlaybackService.ACTION_SEEK_BAR_UPDATE)
        )
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            playbackStateReceiver,
            IntentFilter(MusicPlaybackService.ACTION_PLAYBACK_STATE_CHANGED)
        )
        val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_REQUEST_STATE
        }
        requireContext().startService(intent)
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
        songAdapter = SongAdapter()
        blurrySongImageBackground = view.findViewById(R.id.blurrySongImageBackground)
        arrowGoBackButton = view.findViewById(R.id.arrowGoBackBackground)
        parallaxRotatingImage = view.findViewById(R.id.parallaxRotatingImage)

        arguments?.getString("coverFileName")?.let { fileName ->
            val file = File(requireContext().cacheDir, fileName)
            setSongImage(imageSong, file)
            setSongImage(blurrySongImageBackground, file)
        }

        sharedViewModel = ViewModelProvider(requireActivity()).get(SharedViewModel::class.java)

        sharedViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            currentSong?.let {
                titleView.text = it.title ?: "Desconocido"
                artistView.text = it.artistName ?: "Desconocido"

                val coverFile = File(requireContext().cacheDir, "cover_${it.id}.png")
                val bitmap = BitmapFactory.decodeFile(coverFile.absolutePath)
                    ?: BitmapFactory.decodeResource(resources, R.drawable.album_cover)

                if (view != null && bitmap != null) {
                    if (isFirstLoad) {
                        blurrySongImageBackground.setImageBitmap(bitmap)
                        imageSong?.setImageBitmap(bitmap)
                        isFirstLoad = false
                    } else {
                        AnimationsUtils.animateBlurryBackground(blurrySongImageBackground, bitmap)
                        AnimationsUtils.animateSongImage(imageSong!!, bitmap, lastDirection)
                    }
                }

            }
        }

        // Efecto blur
        val blurEffect = RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP)
        blurrySongImageBackground.setRenderEffect(blurEffect)

        // Apply the rotation animation
        val rotateAnimator = ObjectAnimator.ofFloat(parallaxRotatingImage, "rotation", 0f, 360f)
        rotateAnimator.duration = 40000 // 40 seconds
        rotateAnimator.interpolator = LinearInterpolator()
        rotateAnimator.repeatCount = ObjectAnimator.INFINITE
        rotateAnimator.start()

        // Botón de volver atrás
        arrowGoBackButton.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        // Controles de reproducción
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

        // Loop
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

        // SeekBar
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val intent = Intent(context, MusicPlaybackService::class.java).apply {
                        action = MusicPlaybackService.ACTION_SEEK_TO
                        putExtra(MusicPlaybackService.EXTRA_SEEK_POSITION, progress)
                    }
                    context?.startService(intent)
                    currentTimeText.text = Utils.formatTime(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        return view
    }

    // Función para aplicar el desenfoque a un Bitmap


    override fun onDestroyView() {
        super.onDestroyView()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(songChangedReceiver)
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(seekBarUpdateReceiver)
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(playbackStateReceiver)
    }

    fun updateCurrentSong(song: Song) {
        _currentSongLiveData.postValue(song)
    }

    fun setCurrentPlayingSong(url: String) {
        songAdapter.setCurrentPlayingSong(url)
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
