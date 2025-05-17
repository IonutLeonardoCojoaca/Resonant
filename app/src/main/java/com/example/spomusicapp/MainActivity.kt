package com.example.spomusicapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.example.spomusicapp.databinding.ActivityMainBinding
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import java.io.File

class MainActivity : AppCompatActivity(), PlaybackUIListener {

    private lateinit var binding: ActivityMainBinding

    private lateinit var seekBar: SeekBar
    private val handler = Handler(Looper.getMainLooper())

    private var isPlaying = false
    private lateinit var playPauseButton: ImageButton
    private lateinit var previousSongButton: ImageButton
    private lateinit var nextSongButton: ImageButton
    private lateinit var songImage: ImageView
    private lateinit var songName: TextView
    private lateinit var songArtist: TextView

    private var shouldStopMusic: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom) // ← aplicar bottom
            insets
        }

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNavigationView.setupWithNavController(navController)
        bottomNavigationView.setItemIconTintList(null)

        val sharedPref = this@MainActivity.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        val savedUrl = sharedPref.getString("current_playing_url", null)
        val savedTitle = sharedPref.getString("current_playing_title", "")
        val savedArtist = sharedPref.getString("current_playing_artist", "")
        val savedAlbum = sharedPref.getString("current_playing_album", "")
        val savedDuration = sharedPref.getString("current_playing_duration", "")
        val savedImage = sharedPref.getString("current_playing_image", null)
        val savedIndex = sharedPref.getInt("current_index", -1)

        playPauseButton = binding.playPauseButton
        previousSongButton = binding.previousSongButton
        nextSongButton = binding.nextSongButton

        songImage = binding.songImage
        songName = binding.songTitle
        songArtist = binding.songArtist

        seekBar = findViewById(R.id.seekbarPlayer)
        seekBar.max = 100
        handler.post(updateSeekBarRunnable)

        songName.isSelected = true
        songArtist.isSelected = true

        PlaybackManager.addUIListener(this)

        if (savedUrl != null && savedIndex != -1) {
            val song = Song(
                title = savedTitle ?: "",
                url = savedUrl,
                artistName = savedArtist,
                albumName = savedAlbum,
                duration = savedDuration,
                localCoverPath = savedImage
            )

            val safeTitle = song.title.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val cachedFile = File(cacheDir, "cached_${safeTitle}.mp3")
            val dataSource = if (cachedFile.exists()) cachedFile.absolutePath else song.url

            MediaPlayerManager.play(this, dataSource, savedIndex, autoStart = false)

            val savedPosition = sharedPref.getInt("current_position", 0)
            val wasPlaying = sharedPref.getBoolean("is_playing", false)

            MediaPlayerManager.seekTo(savedPosition)
            if (wasPlaying) {
                MediaPlayerManager.resume()
            }

            updatePlayerUI(song, wasPlaying)
        }

        playPauseButton.setOnClickListener {
            val currentlyPlaying = MediaPlayerManager.isPlaying()
            if (currentlyPlaying) {
                MediaPlayerManager.pause()
            } else {
                MediaPlayerManager.resume()
            }
            updatePlayPauseButton(!currentlyPlaying)
        }

        previousSongButton.setOnClickListener {
            PlaybackManager.playPrevious(this@MainActivity)
            isPlaying = true
            updatePlayPauseButton(isPlaying)
        }

        nextSongButton.setOnClickListener {
            PlaybackManager.playNext(this@MainActivity)
            isPlaying = true
            updatePlayPauseButton(isPlaying)
        }

        val songDataPlayer = findViewById<FrameLayout>(R.id.songDataPlayer)

        songDataPlayer.setOnClickListener {
            val currentSong = PlaybackManager.getCurrentSong()
            if (currentSong != null) {

                val intent = Intent(this@MainActivity, SongActivity::class.java).apply {
                    putExtra("title", currentSong.title)
                    putExtra("artist", currentSong.artistName)
                    putExtra("album", currentSong.albumName)
                    putExtra("duration", currentSong.duration)
                    putExtra("url", currentSong.url)
                    putExtra("coverFileName", currentSong.localCoverPath)
                }
                startActivity(intent)
            }
        }

        isPlaying = MediaPlayerManager.isPlaying()
        updatePlayPauseButton(isPlaying)

        checkUpdate()

    }

    private val updateSeekBarRunnable = object : Runnable {
        override fun run() {
            val duration = MediaPlayerManager.getDuration()
            val position = MediaPlayerManager.getCurrentPosition()

            if (duration > 0) {
                if (seekBar.max != duration) {
                    seekBar.max = duration
                }
                seekBar.progress = position
            }
            handler.postDelayed(this, 50)
        }
    }

    fun updatePlayerUI(song: Song, isPlaying: Boolean) {
        updateDataPlayer(song)
        updatePlayPauseButton(isPlaying)
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

        songArtist.text = song.artistName ?: "Desconocido"
        Utils.getImageSongFromCache(song, this@MainActivity, songImage, song.localCoverPath.toString())
    }

    override fun onResume() {
        super.onResume()
        PlaybackManager.addUIListener(this)
        val sharedPref = getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        val savedIndex = sharedPref.getInt("current_playing_index", -1)
        if (savedIndex != -1 && savedIndex in PlaybackManager.songs.indices) {
            val currentSong = PlaybackManager.songs[savedIndex]
            isPlaying = MediaPlayerManager.isPlaying()
            updateDataPlayer(currentSong)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        updateSeekBarRunnable.let { handler.removeCallbacks(it) }
        if (shouldStopMusic) {
            MediaPlayerManager.stop()
            PlaybackManager.clearUIListener()
        }
    }

    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        shouldStopMusic = false
        moveTaskToBack(true)
    }

    override fun onSongChanged(song: Song, isPlaying: Boolean) {
        updateDataPlayer(song)
        updatePlayPauseButton(isPlaying)
        this.isPlaying = isPlaying
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
                    val updateMessageToUser = remoteConfig.getString("update_message")
                    val packageInfo = packageManager.getPackageInfo(packageName, 0)
                    val versionName = packageInfo.versionName
                    val currentVersion = versionName

                    if (latestVersion != currentVersion) {
                        AlertDialog.Builder(this)
                            .setTitle("¡Nueva actualización disponible!")
                            .setMessage("Versión: $latestVersion. $updateMessageToUser")
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

}