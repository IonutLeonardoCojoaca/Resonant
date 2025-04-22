package com.example.spomusicapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class ActivitySongList : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var songAdapter: SongAdapter
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseButton: ImageButton
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView

    private var isPlaying = false
    private var updateSeekBarRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentSongUrl: String? = null // Para almacenar la URL de la canción actual

    private val songRepository = SongRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_song_list)

        // Configuración de la vista y margen para la interfaz
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Inicialización de vistas
        recyclerView = findViewById(R.id.main)
        recyclerView.layoutManager = LinearLayoutManager(this)
        songAdapter = SongAdapter()
        recyclerView.adapter = songAdapter

        seekBar = findViewById(R.id.seekBar)
        playPauseButton = findViewById(R.id.playPauseButton)
        currentTimeText = findViewById(R.id.currentTimeText)
        totalTimeText = findViewById(R.id.totalTimeText)

        // Configurar el botón Play/Pause
        playPauseButton.setOnClickListener {
            if (isPlaying) {
                MediaPlayerManager.pause()
                playPauseButton.setImageResource(R.drawable.play_button)
            } else {
                MediaPlayerManager.resume()
                playPauseButton.setImageResource(R.drawable.pause)
            }
            isPlaying = !isPlaying
        }

        // Cuando una canción se selecciona, reproducirla
        songAdapter.onItemClick = { song ->
            playSong(song)
        }

        // Obtener las canciones y mostrarlas en el RecyclerView
        lifecycleScope.launch {
            val songs = songRepository.fetchSongs()
            songs?.let {
                songAdapter.submitList(it)
            } ?: run {
                Toast.makeText(this@ActivitySongList, "Error al obtener las canciones", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playSong(song: Song) {
        // Solo reproducir la canción si no es la canción actual
        if (song.url != currentSongUrl) {
            // Reproducir la nueva canción
            MediaPlayerManager.play(this, song.url)

            // Actualizar el estado de la canción actual
            currentSongUrl = song.url
            isPlaying = true
            playPauseButton.setImageResource(R.drawable.pause)

            // Actualizar el máximo de la seekBar y el tiempo total
            seekBar.max = MediaPlayerManager.getDuration()
            totalTimeText.text = formatTime(MediaPlayerManager.getDuration())

            // Iniciar la actualización de la seekBar y el tiempo actual
            updateSeekBarRunnable = object : Runnable {
                override fun run() {
                    val currentPos = MediaPlayerManager.getCurrentPosition()
                    seekBar.progress = currentPos
                    currentTimeText.text = formatTime(currentPos)
                    handler.postDelayed(this, 1000)
                }
            }
            handler.post(updateSeekBarRunnable!!)

            // Configurar el listener para la seekBar
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        MediaPlayerManager.seekTo(progress)
                        currentTimeText.text = formatTime(progress)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
    }

    private fun formatTime(millis: Int): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / 1000) / 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        updateSeekBarRunnable?.let { handler.removeCallbacks(it) }
        MediaPlayerManager.stop()
    }
}
