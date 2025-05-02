package com.example.spomusicapp

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.Image
import android.media.MediaMetadataRetriever
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.ClearCredentialException
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import androidx.core.view.WindowCompat

class ActivitySongList : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    lateinit var songAdapter: SongAdapter

    lateinit var seekBar: SeekBar
    lateinit var currentTimeText: TextView
    lateinit var totalTimeText: TextView

    private lateinit var playPauseButton: ImageButton
    private lateinit var previousSongButton: ImageButton
    private lateinit var nextSongButton: ImageButton

    private var isPlaying = false
    private var updateSeekBarRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    private val songRepository = SongRepository()

    private lateinit var signOutButton: ImageButton
    private lateinit var auth: FirebaseAuth
    private lateinit var credentialManager: CredentialManager

    private var shouldStopMusic = true

    private var currentOffset = 0
    private val pageSize = 10
    private var isLoading = false
    private var hasMoreItems = true
    private val songList = mutableListOf<Song>()

    private lateinit var loadingAnimation: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_song_list)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        WindowCompat.setDecorFitsSystemWindows(window, true)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())

        recyclerView = findViewById(R.id.main)
        recyclerView.layoutManager = LinearLayoutManager(this)
        songAdapter = SongAdapter()
        recyclerView.adapter = songAdapter

        seekBar = findViewById(R.id.seekBar)

        // Buttons
        playPauseButton = findViewById(R.id.playPauseButton)
        previousSongButton = findViewById(R.id.previousSongButton)
        nextSongButton = findViewById(R.id.nextSongButton)

        // Time
        currentTimeText = findViewById(R.id.currentTimeText)
        totalTimeText = findViewById(R.id.totalTimeText)

        val layoutManager = LinearLayoutManager(this)
        recyclerView.layoutManager = layoutManager

        auth = Firebase.auth
        credentialManager = CredentialManager.create(baseContext)
        signOutButton = findViewById(R.id.signOutButton)

        loadingAnimation = findViewById<ImageView>(R.id.loadingAnimation)

        playPauseButton.setOnClickListener {
            if (isPlaying) {
                MediaPlayerManager.pause()
            } else {
                MediaPlayerManager.resume()

                // 🛠️ Solución asegurada:
                val duration = MediaPlayerManager.getDuration()
                if (duration > 0) {
                    seekBar.max = duration
                    totalTimeText.text = formatTime(duration)
                    startSeekBarUpdater()
                } else {
                    // 🧠 Si todavía no ha devuelto duración, esperar un poco
                    handler.postDelayed({
                        val newDuration = MediaPlayerManager.getDuration()
                        seekBar.max = newDuration
                        totalTimeText.text = formatTime(newDuration)
                        startSeekBarUpdater()
                    }, 200) // esperar 200 ms
                }
            }
            isPlaying = !isPlaying
            updatePlayPauseButton(isPlaying)
        }

        previousSongButton.setOnClickListener {
            PlaybackManager.playPrevious(this@ActivitySongList)
            isPlaying = true
            updatePlayPauseButton(isPlaying)
        }

        nextSongButton.setOnClickListener {
            PlaybackManager.playNext(this@ActivitySongList)
            isPlaying = true
            updatePlayPauseButton(isPlaying)
        }

        val sharedPref = this@ActivitySongList.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        val savedUrl = sharedPref.getString("current_playing_url", null)
        songAdapter.setCurrentPlayingSong(savedUrl)
        startSeekBarUpdater()

        if (isPlaying) {
            MediaPlayerManager.pause()
        } else {
            MediaPlayerManager.resume()

            // 🛠️ Solución asegurada:
            val duration = MediaPlayerManager.getDuration()
            if (duration > 0) {
                seekBar.max = duration
                totalTimeText.text = formatTime(duration)
                startSeekBarUpdater()
            } else {
                // 🧠 Si todavía no ha devuelto duración, esperar un poco
                handler.postDelayed({
                    val newDuration = MediaPlayerManager.getDuration()
                    seekBar.max = newDuration
                    totalTimeText.text = formatTime(newDuration)
                    startSeekBarUpdater()
                }, 200) // esperar 200 ms
            }
        }
        updatePlayPauseButton(isPlaying)

        songAdapter.onItemClick = { song ->
            val index = songAdapter.currentList.indexOf(song)
            songAdapter.setCurrentPlayingSong(song.url)

            sharedPref.edit() { putString("current_playing_url", song.url) }
            songAdapter.setCurrentPlayingSong(song.url)

            if (index != -1) {
                startSeekBarUpdater()
                PlaybackManager.playSongAt(this, index)
                isPlaying = true
                updatePlayPauseButton(isPlaying)
                NotificationManagerHelper.createNotificationChannel(this)
                NotificationManagerHelper.updateNotification(this)
            }
        }

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                val totalItemCount = layoutManager.itemCount

                if (lastVisibleItem >= totalItemCount - 2 && !isLoading) {
                    loadSongs()
                }
            }
        })

        signOutButton.setOnClickListener {
            signOut()
        }

        if (SongCache.cachedSongs.isNotEmpty()) {
            songList.addAll(SongCache.cachedSongs)
            currentOffset = SongCache.currentOffset
            hasMoreItems = SongCache.hasMoreSongs
            songAdapter.submitList(songList.toList())
            PlaybackManager.setSongs(songList)
        } else {
            loadSongs(initialLoad = true)
        }

        val searchEditText = findViewById<EditText>(R.id.searchEditText)

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterSongs(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

    }

    private fun filterSongs(query: String) {
        val filteredList = if (query.isEmpty()) {
            songList
        } else {
            songList.filter {
                it.title.contains(query, ignoreCase = true) == true
            }
        }

        songAdapter.submitList(filteredList.toList())
        PlaybackManager.setSongs(filteredList.toList()) // 🔥 AÑADE ESTA LÍNEA

        if (query.isEmpty()) {
            songAdapter.submitList(songList.toList())
            PlaybackManager.setSongs(songList.toList()) // 👈 También aquí
        }
    }


    private fun loadSongs(initialLoad: Boolean = false) {
        if (isLoading || !hasMoreItems) return

        isLoading = true
        loadingAnimation.visibility = ImageView.VISIBLE // ⬅️ MOSTRAR animación

        lifecycleScope.launch {
            val songs = songRepository.fetchSongs(limit = pageSize, offset = currentOffset)
            songs?.let {
                val enrichedSongs = it.mapNotNull { song -> enrichSong(song) }
                songList.addAll(enrichedSongs)
                songAdapter.submitList(songList.toList()) // inmutable para DiffUtil
                PlaybackManager.setSongs(songList)

                // Actualiza la caché
                SongCache.cachedSongs = songList.toList()
                SongCache.currentOffset = currentOffset + enrichedSongs.size
                SongCache.hasMoreSongs = enrichedSongs.size == pageSize

                if (enrichedSongs.size < pageSize) {
                    hasMoreItems = false
                } else {
                    currentOffset += pageSize
                }
            } ?: run {
                Toast.makeText(this@ActivitySongList, "Error al obtener las canciones", Toast.LENGTH_SHORT).show()
            }

            loadingAnimation.visibility = ImageView.INVISIBLE // ⬅️ OCULTAR animación
            isLoading = false
        }
    }


    suspend fun enrichSong(song: Song): Song? {
        return withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(song.url, HashMap())
                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: song.title
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Artista desconocido"
                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Álbum desconocido"
                retriever.release()

                Song(
                    title = title,
                    artist = artist,
                    album = album,
                    url = song.url
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 1) { // El mismo número que pusiste en requestPermissions
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permiso concedido
                Toast.makeText(this, "Permiso de notificaciones concedido", Toast.LENGTH_SHORT).show()
            } else {
                // Permiso denegado
                Toast.makeText(this, "No se podrán mostrar notificaciones", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        if (isPlaying) {
            playPauseButton.setImageResource(R.drawable.pause)
        } else {
            playPauseButton.setImageResource(R.drawable.play)
        }
    }

    fun startSeekBarUpdater() {
        // Establecer la duración total de la canción en el SeekBar y en el TextView
        val duration = MediaPlayerManager.getDuration()  // Obtén la duración total de la canción
        seekBar.max = duration
        totalTimeText.text = formatTime(duration) // Actualiza el TextView con la duración

        // Iniciar la actualización de la SeekBar y el tiempo actual
        updateSeekBarRunnable = object : Runnable {
            override fun run() {
                val currentPos = MediaPlayerManager.getCurrentPosition()
                seekBar.progress = currentPos
                currentTimeText.text = formatTime(currentPos)
                handler.postDelayed(this, 1000)  // Actualiza cada segundo
            }
        }
        handler.post(updateSeekBarRunnable!!)

        // Configurar el listener para el SeekBar
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    MediaPlayerManager.seekTo(progress)  // Si el usuario mueve el SeekBar, actualizar la posición
                    currentTimeText.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    fun formatTime(millis: Int): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / 1000) / 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        updateSeekBarRunnable?.let { handler.removeCallbacks(it) }
        if (shouldStopMusic) {
            MediaPlayerManager.stop()
        }
    }

    private fun signOut() {
        auth.signOut()
        lifecycleScope.launch {
            try {
                val clearRequest = ClearCredentialStateRequest()
                credentialManager.clearCredentialState(clearRequest)
                intent = Intent(applicationContext, MainActivity::class.java)
                startActivity(intent)
                finish()
            } catch (e: ClearCredentialException) {
                Log.i("ErrorSingOut", "Couldn't clear user credentials: ${e.localizedMessage}")
            }
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        shouldStopMusic = false
        moveTaskToBack(true)
    }

}
