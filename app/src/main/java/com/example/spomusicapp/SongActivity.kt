package com.example.spomusicapp

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.net.toUri
import java.io.File

class SongActivity : AppCompatActivity() {

    private lateinit var blurrySongImageBackground: ImageView
    private lateinit var arrowGoBackButton: ImageButton

    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView

    private lateinit var replayButton: ImageButton

    private val handler = Handler(Looper.getMainLooper())
    private var updateSeekBarRunnable: Runnable? = null

    private var isPlaying = false
    private lateinit var playPauseButton: ImageButton

    private lateinit var sharedPref: SharedPreferences

    private val backCallback = OnBackInvokedCallback {
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_song)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val title = intent.getStringExtra("title") ?: "Desconocido"
        val artist = intent.getStringExtra("artist") ?: "Desconocido"
        val url = intent.getStringExtra("url") ?: ""

        val titleView = findViewById<TextView>(R.id.song_title)
        val artistView = findViewById<TextView>(R.id.song_artist)
        val imageSong = findViewById<ImageView>(R.id.song_image)
        val imageBlurrySong = findViewById<ImageView>(R.id.blurrySongImageBackground)

        titleView.text = title
        artistView.text = artist

        val coverFileName = intent.getStringExtra("coverFileName") ?: ""
        val file = File(cacheDir, coverFileName)

        if (file.exists()) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)

            if (bitmap != null) {
                imageSong.setImageBitmap(bitmap)
                imageBlurrySong.setImageBitmap(bitmap)
                Log.e("FileFound", "El archivo de imagen existe: $coverFileName")
            } else {
                val defaultBitmap = getDefaultBitmap()
                imageSong.setImageBitmap(defaultBitmap)
                imageBlurrySong.setImageBitmap(defaultBitmap)
            }
        } else {
            Log.e("FileNotFound", "El archivo de imagen no existe: $coverFileName")
            val defaultBitmap = getDefaultBitmap()
            imageSong.setImageBitmap(defaultBitmap)
            imageBlurrySong.setImageBitmap(defaultBitmap)
        }

        seekBar = findViewById(R.id.seekBar)
        currentTimeText = findViewById(R.id.currentTimeText)
        totalTimeText = findViewById(R.id.totalTimeText)
        playPauseButton = findViewById(R.id.playPauseButton)

        val currentUrl = MediaPlayerManager.getCurrentSongUrl()
        val isCurrentlyPlaying = MediaPlayerManager.isPlaying()
        val currentPosition = MediaPlayerManager.getCurrentPosition()
        val duration = MediaPlayerManager.getDuration()

        if (currentUrl == url) {
            seekBar.max = duration
            seekBar.progress = currentPosition
            currentTimeText.text = Utils.formatTime(currentPosition)
            totalTimeText.text = Utils.formatTime(duration)

            if (isCurrentlyPlaying) {
                startSeekBarUpdater()
                isPlaying = true
            } else {
                // No está sonando, pero mostramos el progreso actual
                isPlaying = false
            }
        } else {
            // Es otra canción, la reproducimos
            MediaPlayerManager.play(this, url, 0) {
                startSeekBarUpdater()
            }
            isPlaying = true
        }

        updatePlayPauseButton(isPlaying)

        blurrySongImageBackground = findViewById(R.id.blurrySongImageBackground)
        arrowGoBackButton = findViewById(R.id.arrowGoBackButton)

        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            backCallback
        )

        arrowGoBackButton.setOnClickListener {
            backCallback.onBackInvoked()
        }

        val blurEffect = RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP)
        blurrySongImageBackground.setRenderEffect(blurEffect)

        playPauseButton.setOnClickListener {
            if (isPlaying) {
                MediaPlayerManager.pause()
            } else {
                MediaPlayerManager.resume()
            }
            isPlaying = !isPlaying
            updatePlayPauseButton(isPlaying)
        }

        replayButton = findViewById(R.id.replay_button)
        sharedPref = this@SongActivity.getSharedPreferences("music_experience", Context.MODE_PRIVATE)
        var isLooping = sharedPref.getBoolean(PreferenceKeys.IS_LOOP_ACTIVATED, false)

        if(isLooping){
            replayButton.setImageResource(R.drawable.replay_activated)
        }else{
            replayButton.setImageResource(R.drawable.replay)
        }

        replayButton.setOnClickListener {
            isLooping = !isLooping
            sharedPref.edit().apply{
                putBoolean(PreferenceKeys.IS_LOOP_ACTIVATED, isLooping)
                apply()
            }
            MediaPlayerManager.setLooping(isLooping)
            if(isLooping){
                replayButton.setImageResource(R.drawable.replay_activated)
            }else{
                replayButton.setImageResource(R.drawable.replay)
            }
        }


    }

    private fun startSeekBarUpdater() {
        val duration = MediaPlayerManager.getDuration()
        seekBar.max = duration
        totalTimeText.text = Utils.formatTime(duration)

        updateSeekBarRunnable = object : Runnable {
            override fun run() {
                val currentPos = MediaPlayerManager.getCurrentPosition()
                seekBar.progress = currentPos
                currentTimeText.text = Utils.formatTime(currentPos)
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(updateSeekBarRunnable!!)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    MediaPlayerManager.seekTo(progress)
                    currentTimeText.text = Utils.formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        if (isPlaying) {
            playPauseButton.setImageResource(R.drawable.pause)
        } else {
            playPauseButton.setImageResource(R.drawable.play_arrow_filled)
        }
    }

    fun getDefaultBitmap(): Bitmap {
        return BitmapFactory.decodeResource(resources, R.drawable.album_cover)
    }

}