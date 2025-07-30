package com.example.resonant

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class AlbumActivity : AppCompatActivity() {

    private lateinit var arrowGoBackButton: ImageButton

    private lateinit var albumName: TextView
    private lateinit var albumArtistName: TextView
    private lateinit var backgroundImage: ImageView
    private lateinit var recyclerView: RecyclerView
    private lateinit var songAdapter: SongAdapter
    private var songList: List<Song>? = null

    private lateinit var api: ApiResonantService

    private var isPlaying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_album)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        arrowGoBackButton = findViewById(R.id.arrowGoBackButton)

        albumName = findViewById(R.id.albumName)
        albumArtistName = findViewById(R.id.albumArtistName)
        backgroundImage = findViewById(R.id.backgroundImage)
        recyclerView = findViewById(R.id.albumSongsContainer) // crea este RecyclerView en tu layout

        val albumId = intent.getStringExtra("albumId") ?: return
        val albumFileName = intent.getStringExtra("albumFileName") ?: ""
        val albumTitle = intent.getStringExtra("albumTitle") ?: ""

        albumName.text = albumTitle

        api = ApiClient.getService(this)

        loadAlbumDetails(albumId, albumFileName)

        arrowGoBackButton.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }


    }

    private fun loadAlbumDetails(albumId: String, fileName: String) {
        lifecycleScope.launch {
            try {
                // Ejecutar en paralelo
                val imageDeferred = async { api.getAlbumUrl(fileName).url }
                val artistDeferred = async { api.getArtistsByAlbumId(albumId) }
                val songsDeferred = async { api.getSongsByAlbumId(albumId) }

                val imageUrl = imageDeferred.await()
                val artists = artistDeferred.await()
                val songs = songsDeferred.await()

                // Setear carátula
                Picasso.get().load(imageUrl).into(backgroundImage)

                // Setear nombre del artista
                albumArtistName.text = artists.firstOrNull()?.name ?: "Desconocido"

                // Setear canciones
                songAdapter = SongAdapter()
                recyclerView.layoutManager = LinearLayoutManager(this@AlbumActivity)
                recyclerView.adapter = songAdapter
                songAdapter.submitList(songs) // <- AQUÍ VA


            } catch (e: Exception) {
                Log.e("AlbumActivity", "Error al cargar los detalles del álbum", e)
                Toast.makeText(this@AlbumActivity, "Error al cargar el álbum", Toast.LENGTH_SHORT).show()
            }
        }
    }

}