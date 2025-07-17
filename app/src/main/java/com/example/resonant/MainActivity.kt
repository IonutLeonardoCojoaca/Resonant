package com.example.resonant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
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
import com.example.resonant.databinding.ActivityMainBinding
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings

class MainActivity : AppCompatActivity(), PlaybackUIListener {

    private lateinit var binding: ActivityMainBinding

    private lateinit var seekBar: SeekBar
    private val handler = Handler(Looper.getMainLooper())

    var isPlaying = false
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
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNavigationView.setupWithNavController(navController)
        bottomNavigationView.itemIconTintList = null

        val displayMetrics = Resources.getSystem().displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val drawerWidth = (screenWidth * 0.75).toInt() // 75%

        val drawer: View = findViewById(R.id.navigationView)
        val params = drawer.layoutParams
        params.width = drawerWidth
        drawer.layoutParams = params

        val sharedPref = this@MainActivity.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        val savedUrl = sharedPref.getString(PreferenceKeys.CURRENT_SONG_URL, null)
        val savedId = sharedPref.getString(PreferenceKeys.CURRENT_SONG_ID, "")
        val savedTitle = sharedPref.getString(PreferenceKeys.CURRENT_SONG_TITLE, "")
        val savedArtist = sharedPref.getString(PreferenceKeys.CURRENT_SONG_ARTIST, "")
        val savedAlbum = sharedPref.getString(PreferenceKeys.CURRENT_SONG_ALBUM, "")
        val savedDuration = sharedPref.getString(PreferenceKeys.CURRENT_SONG_DURATION, "")
        val savedImage = sharedPref.getString(PreferenceKeys.CURRENT_SONG_COVER, null)  // OK
        val savedIndex = sharedPref.getInt(PreferenceKeys.CURRENT_SONG_INDEX, -1)

        val songDataPlayer = findViewById<FrameLayout>(R.id.songDataPlayer)

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
        /*
        if (savedUrl != null && savedIndex != -1) {
            val song = Song(
                id = savedId.toString(),
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

            PlaybackManager.setCurrentSong(song)
            MediaPlayerManager.play(this, dataSource, savedIndex, autoStart = false)

            val savedPosition = sharedPref.getInt(PreferenceKeys.CURRENT_SONG_INDEX, 0)
            val wasPlaying = sharedPref.getBoolean(PreferenceKeys.CURRENT_ISPLAYING, false)

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
 */
        isPlaying = MediaPlayerManager.isPlaying()
        Log.i("sonando", isPlaying.toString())
        //updatePlayPauseButton(isPlaying)



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

    fun updatePlayPauseButton(isPlaying: Boolean) {
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

        //songArtist.text = song.artistName ?: "Desconocido"
        //Utils.getImageSongFromCache(song, this@MainActivity, songImage, song.localCoverPath.toString())
    }

    override fun onResume() {
        super.onResume()
        PlaybackManager.addUIListener(this)
        val currentSong = PlaybackManager.getCurrentSong()
        if (currentSong != null) {
            isPlaying = MediaPlayerManager.isPlaying()
            updateDataPlayer(currentSong)
            updatePlayPauseButton(isPlaying)
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

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        this.isPlaying = isPlaying
        updatePlayPauseButton(isPlaying)
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