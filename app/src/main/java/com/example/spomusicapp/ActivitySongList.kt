package com.example.spomusicapp

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
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

    private lateinit var songAdapter: SongAdapter
    private lateinit var recyclerView: RecyclerView
    private val songRepository = SongRepository()

    private lateinit var seekBar: SeekBar
    private lateinit var playPauseButton: Button
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var updateSeekBarRunnable: Runnable? = null
    private var isPlaying = true



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_song_list)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        recyclerView = findViewById(R.id.main)
        recyclerView.layoutManager = LinearLayoutManager(this)
        songAdapter = SongAdapter()
        recyclerView.adapter = songAdapter

        seekBar = findViewById(R.id.seekBar)
        playPauseButton = findViewById(R.id.playPauseButton)
        currentTimeText = findViewById(R.id.currentTimeText)
        totalTimeText = findViewById(R.id.totalTimeText)

        playPauseButton.setOnClickListener {
            if (isPlaying) {
                MediaPlayerManager.pause()
                playPauseButton.text = "Play"
            } else {
                MediaPlayerManager.resume()
                playPauseButton.text = "Pause"
            }
            isPlaying = !isPlaying
        }

        songAdapter.onItemClick = { song ->
            playSong(song)
        }

        // Llamar a la función para obtener las canciones
        lifecycleScope.launch {
            val songs = songRepository.fetchSongs()
            songs?.let {
                songAdapter.submitList(it)
            } ?: run {
                // Maneja el caso de error si es necesario
                Toast.makeText(this@ActivitySongList, "Error al obtener las canciones", Toast.LENGTH_SHORT).show()
            }
        }

    }

    fun playSong(song: Song) {
        MediaPlayerManager.play(this, song.url)

        seekBar.max = MediaPlayerManager.getDuration()
        totalTimeText.text = formatTime(MediaPlayerManager.getDuration())

        updateSeekBarRunnable = object : Runnable {
            override fun run() {
                val currentPos = MediaPlayerManager.getCurrentPosition()
                seekBar.progress = currentPos
                currentTimeText.text = formatTime(currentPos)
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(updateSeekBarRunnable!!)

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