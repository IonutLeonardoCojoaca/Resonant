package com.example.spomusicapp

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.TranslateAnimation
import android.widget.Button
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.credentials.CredentialManager
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import androidx.core.net.toUri
import com.google.gson.Gson

class ActivitySongList : AppCompatActivity() {

    lateinit var recyclerView: RecyclerView
    lateinit var songAdapter: SongAdapter

    private lateinit var playPauseButton: ImageButton
    private lateinit var previousSongButton: ImageButton
    private lateinit var nextSongButton: ImageButton

    private var isPlaying = false
    private var updateSeekBarRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    private val songRepository = SongRepository()

    private lateinit var settingsButton: ImageButton
    private lateinit var auth: FirebaseAuth
    private lateinit var credentialManager: CredentialManager

    private var shouldStopMusic = true

    private var currentOffset = 0
    private var isLoading = false
    private var hasMoreItems = true
    private val songList = mutableListOf<Song>()

    private lateinit var loadingAnimation: ImageView
    private lateinit var searchSongsButton: ImageButton
    private lateinit var updateSongListButton: Button

    private lateinit var songImage: ImageView
    private lateinit var songName: TextView
    private lateinit var songArtist: TextView

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_song_list)
        enableEdgeToEdge()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        WindowCompat.setDecorFitsSystemWindows(window, true)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        checkUpdate()

        recyclerView = findViewById<RecyclerView>(R.id.allSongList)
        recyclerView.layoutManager = LinearLayoutManager(this)
        songAdapter = SongAdapter()
        recyclerView.adapter = songAdapter

        playPauseButton = findViewById(R.id.playPauseButton)
        previousSongButton = findViewById(R.id.previousSongButton)
        nextSongButton = findViewById(R.id.nextSongButton)

        songImage = findViewById(R.id.song_image)
        songName = findViewById(R.id.song_title)
        songArtist = findViewById(R.id.song_artist)

        auth = Firebase.auth
        credentialManager = CredentialManager.create(baseContext)
        settingsButton = findViewById(R.id.settingsButton)

        loadingAnimation = findViewById<ImageView>(R.id.loadingAnimation)
        searchSongsButton = findViewById(R.id.searchSongsButton)
        updateSongListButton = findViewById(R.id.updateSongListButton)

        searchSongsButton.setOnClickListener {
            val intent = Intent(this, SearchSongsActivity::class.java)
            startActivity(intent)
        }

        playPauseButton.setOnClickListener {
            if (isPlaying) {
                MediaPlayerManager.pause()
            } else {
                MediaPlayerManager.resume()

                // 🛠️ Solución asegurada:
                /*val duration = MediaPlayerManager.getDuration()
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
                }*/
            }
            isPlaying = !isPlaying
            updatePlayPauseButton(isPlaying)
        }

        PlaybackManager.onSongChanged = { song ->
            songAdapter.setCurrentPlayingSong(song.url)
            updateDataPlayer(song)
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

        val sharedPreferences = getSharedPreferences("song_cache", Context.MODE_PRIVATE)
        val cachedSongsJson = sharedPreferences.getString("cached_songs", "[]")
        val cachedSongs = Gson().fromJson(cachedSongsJson, Array<Song>::class.java).toList()

        val savedTitle = sharedPref.getString("current_playing_title", "")
        val savedArtist = sharedPref.getString("current_playing_artist", "")
        val savedAlbum = sharedPref.getString("current_playing_album", "")
        val savedDuration = sharedPref.getString("current_playing_duration", "")

        if (savedUrl != null) {
            // Crear un objeto Song con los datos recuperados
            val song = Song(
                title = savedTitle ?: "",
                url = savedUrl,
                artist = savedArtist,
                album = savedAlbum,
                duration = savedDuration
            )

            // Actualizar la UI con la canción actual
            updateDataPlayer(song)
        }

        songAdapter.setCurrentPlayingSong(savedUrl)

        isPlaying = MediaPlayerManager.isPlaying()
        updatePlayPauseButton(isPlaying)

        songAdapter.onItemClick = { song ->
            val index = songAdapter.currentList.indexOf(song)
            songAdapter.setCurrentPlayingSong(song.url)

            sharedPref.edit().apply {
                putString("current_playing_url", song.url)
                putString("current_playing_title", song.title)
                putString("current_playing_artist", song.artist)
                putString("current_playing_album", song.album)
                putString("current_playing_duration", song.duration)
                apply()
            }

            if (index != -1) {
                updateDataPlayer(song)
                songName.isSelected = true
                songArtist.isSelected = true
                PlaybackManager.playSongAt(this, index)
                isPlaying = true
                updatePlayPauseButton(isPlaying)
                NotificationManagerHelper.createNotificationChannel(this)
                NotificationManagerHelper.updateNotification(this)
            }
        }

        if (cachedSongs.isNotEmpty()) {
            songList.addAll(cachedSongs)
            currentOffset = SongCache.currentOffset
            hasMoreItems = SongCache.hasMoreSongs
            songAdapter.submitList(songList.toList())
            PlaybackManager.setSongs(songList)

            loadingAnimation.visibility = View.GONE
            isLoading = false
        } else {
            loadSongs()
        }

        updateSongListButton.setOnClickListener {
            reloadSongs()
        }

        settingsButton.setOnClickListener {
            goToSettingsActivity()
        }

    }

    private fun reloadSongs() {
        loadSongs(clearList = true)
    }

    private fun loadSongs(clearList: Boolean = true) {
        if (isLoading) return

        isLoading = true
        loadingAnimation.visibility = ImageView.VISIBLE

        lifecycleScope.launch {
            val songs = songRepository.fetchSongs() // Ya no pasamos limit ni offset
            songs?.let {
                val enrichedSongs = it.mapNotNull { song -> enrichSong(song) }

                // Limpiamos la lista antes de agregar las nuevas canciones si clearList es verdadero
                if (clearList) {
                    songList.clear()
                }
                songList.addAll(enrichedSongs)

                songAdapter.submitList(songList.toList())
                PlaybackManager.setSongs(songList)

                // Guardamos en SharedPreferences para la próxima vez
                val sharedPreferences = getSharedPreferences("song_cache", Context.MODE_PRIVATE)
                sharedPreferences.edit() {
                    val json = Gson().toJson(songList)
                    putString("cached_songs", json)
                }

                // Ya no hay paginación
                SongCache.cachedSongs = songList.toList()
                hasMoreItems = false
            } ?: run {
                Toast.makeText(this@ActivitySongList, "Error al obtener las canciones", Toast.LENGTH_SHORT).show()
            }

            loadingAnimation.visibility = ImageView.GONE
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
            playPauseButton.setImageResource(R.drawable.play_arrow_filled)
        }
    }

    private fun updateDataPlayer(song: Song) {
        songName.text = song.title
            .removeSuffix(".mp3")
            .replace(Regex("\\s*\\([^)]*\\)"), "")
            .replace("-", "–")
            .trim()

        songArtist.text = song.artist ?: "Desconocido"

        // Obtener imagen del caché o cargar si no está
        songAdapter.getBitmapFromCacheOrLoad(this, song) { bitmap ->
            if (bitmap != null) {
                songImage.setImageBitmap(bitmap)
            } else {
                songImage.setImageResource(R.drawable.album_cover)
            }
        }
    }

    /*
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
*/

    override fun onDestroy() {
        super.onDestroy()
        updateSeekBarRunnable?.let { handler.removeCallbacks(it) }
        if (shouldStopMusic) {
            MediaPlayerManager.stop()
        }
    }

    private fun goToSettingsActivity(){
        val intent = Intent(this@ActivitySongList, SettingsActivity::class.java)
        startActivity(intent)
    }

    fun checkUpdate(){
        val remoteConfig = FirebaseRemoteConfig.getInstance()

        val configSettings = FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(0)
            .build()
        remoteConfig.setConfigSettingsAsync(configSettings)

        remoteConfig.setDefaultsAsync(
            mapOf(
                "latest_version" to "1.0",
                "apk_url" to ""
            )
        )

        remoteConfig.fetchAndActivate()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val latestVersion = remoteConfig.getString("latest_version")
                    val apkUrl = remoteConfig.getString("apk_url")
                    val packageInfo = packageManager.getPackageInfo(packageName, 0)
                    val versionName = packageInfo.versionName
                    val currentVersion = versionName

                    if (latestVersion != currentVersion) {
                        AlertDialog.Builder(this)
                            .setTitle("¡Nueva actualización disponible!")
                            .setMessage("Versión: $latestVersion. Se han introducido mejoras visuales y una optimización del rendimiento de la aplicación.")
                            .setPositiveButton("Descargar") { _, _ ->
                                val intent = Intent(Intent.ACTION_VIEW, apkUrl.toUri())
                                startActivity(intent)
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                } else {
                    Toast.makeText(this, "Error al buscar actualización", Toast.LENGTH_SHORT).show()
                }
            }

    }

    override fun onBackPressed() {
        super.onBackPressed()
        shouldStopMusic = false
        moveTaskToBack(true)
    }

}
