package com.example.spomusicapp

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class ActivitySongList : AppCompatActivity() {

    private lateinit var songAdapter: SongAdapter
    private lateinit var recyclerView: RecyclerView
    private val songRepository = SongRepository()

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

    override fun onDestroy() {
        super.onDestroy()
        MediaPlayerManager.stop()
    }

}