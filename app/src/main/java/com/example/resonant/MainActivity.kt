package com.example.resonant

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.example.resonant.databinding.ActivityMainBinding
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var seekBar: SeekBar

    private lateinit var playPauseButton: ImageButton
    private lateinit var previousSongButton: ImageButton
    private lateinit var nextSongButton: ImageButton
    private lateinit var songImage: ImageView
    private lateinit var songName: TextView
    private lateinit var songArtist: TextView

    private var shouldStopMusic: Boolean = true

    private var currentSongBitmap: Bitmap? = null

    private lateinit var playbackStateReceiver: BroadcastReceiver
    private lateinit var songChangedReceiver: BroadcastReceiver
    private lateinit var seekBarUpdateReceiver: BroadcastReceiver
    private lateinit var resetSeekBarReceiver: BroadcastReceiver

    private var observersRegistered = false

    private lateinit var homeFragment: HomeFragment

    private var musicService: MusicPlaybackService? = null
    private var isBound = false

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

        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id == R.id.homeFragment) {
                val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                val fragment = navHostFragment.childFragmentManager.primaryNavigationFragment
                if (fragment is HomeFragment) {
                    homeFragment = fragment
                    setupObservers()
                }
            }
        }

        val displayMetrics = Resources.getSystem().displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val drawerWidth = (screenWidth * 0.75).toInt()

        val drawer: View = findViewById(R.id.navigationView)
        val params = drawer.layoutParams
        params.width = drawerWidth
        drawer.layoutParams = params

        val intent = Intent(this, MusicPlaybackService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

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

        songName.isSelected = true
        songArtist.isSelected = true

        playPauseButton.setOnClickListener {
            val intent = Intent(this, MusicPlaybackService::class.java)
            if (musicService?.isPlaying() == true) {
                intent.action = MusicPlaybackService.ACTION_PAUSE
            } else {
                intent.action = MusicPlaybackService.ACTION_RESUME
            }
            startService(intent)
        }

        previousSongButton.setOnClickListener {
            val intent = Intent(this, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_PREVIOUS
            }
            startService(intent)
        }

        nextSongButton.setOnClickListener {
            val intent = Intent(this, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_NEXT
            }
            startService(intent)
        }

        SharedViewModelHolder.sharedViewModel.currentSongBitmapLiveData.observe(this) { bitmap ->
            currentSongBitmap = bitmap
            if (bitmap != null) {
                songImage.setImageBitmap(bitmap)
            } else {
                songImage.setImageResource(R.drawable.album_cover)
            }
        }

        songDataPlayer.setOnClickListener {
            val currentSong = musicService?.currentSongLiveData?.value
            val bitmap = currentSongBitmap

            if (currentSong != null && bitmap != null) {
                val fileName = "cover_${currentSong.id}.png"
                val uri = Utils.saveBitmapToCacheUri(this@MainActivity, bitmap, fileName)

                val intent = Intent(this, SongActivity::class.java).apply {
                    putExtra("title", currentSong.title)
                    putExtra("artist", currentSong.artistName)
                    putExtra("albumId", currentSong.albumId)
                    putExtra("duration", currentSong.duration)
                    putExtra("url", currentSong.url)
                    putExtra("coverFileName", fileName)

                }

                startActivity(intent)
            }
        }
    }

    private fun setupObservers() {
        if (observersRegistered) return

        playbackStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == MusicPlaybackService.ACTION_PLAYBACK_STATE_CHANGED) {
                    val currentSong = intent.getParcelableExtra<Song>(MusicPlaybackService.EXTRA_CURRENT_SONG)
                    val isPlaying = intent.getBooleanExtra(MusicPlaybackService.EXTRA_IS_PLAYING, false)

                    currentSong?.let {
                        homeFragment.updateCurrentSong(it)
                        updatePlayPauseButton(isPlaying)
                    }
                    updatePlayPauseButton(isPlaying)
                }
            }
        }

        songChangedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == MusicPlaybackService.ACTION_SONG_CHANGED) {
                    val currentSong = intent?.getParcelableExtra<Song>(MusicPlaybackService.EXTRA_CURRENT_SONG)
                    currentSong?.let {
                        homeFragment.updateCurrentSong(it)
                        it.url?.let { it1 -> homeFragment.setCurrentPlayingSong(it1) }
                    }
                }
            }
        }

        seekBarUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == MusicPlaybackService.ACTION_SEEK_BAR_UPDATE) {
                    val position = intent.getIntExtra(MusicPlaybackService.EXTRA_POSITION, 0)
                    val duration = intent.getIntExtra(MusicPlaybackService.EXTRA_DURATION, 0)

                    seekBar.max = duration
                    seekBar.progress = position
                }
            }
        }

        resetSeekBarReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == MusicPlaybackService.ACTION_SEEK_BAR_RESET) {
                    seekBar.progress = 0
                }
            }
        }

        LocalBroadcastManager.getInstance(this).apply {
            registerReceiver(playbackStateReceiver, IntentFilter(MusicPlaybackService.ACTION_PLAYBACK_STATE_CHANGED))
            registerReceiver(songChangedReceiver, IntentFilter(MusicPlaybackService.ACTION_SONG_CHANGED))
            registerReceiver(seekBarUpdateReceiver, IntentFilter(MusicPlaybackService.ACTION_SEEK_BAR_UPDATE))
            registerReceiver(resetSeekBarReceiver, IntentFilter(MusicPlaybackService.ACTION_SEEK_BAR_RESET))
        }

        observersRegistered = true
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicPlaybackService.MusicServiceBinder
            musicService = binder.getService()
            isBound = true

            musicService?.currentSongLiveData?.observe(this@MainActivity) { song ->
                song?.let {
                    updateDataPlayer(it)
                }
            }

            musicService?.isPlayingLiveData?.observe(this@MainActivity) { playing ->
                updatePlayPauseButton(playing)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            musicService = null
        }
    }

    fun updatePlayPauseButton(isPlaying: Boolean) {
        if (isPlaying) {
            playPauseButton.setImageResource(R.drawable.pause)
        } else {
            playPauseButton.setImageResource(R.drawable.play_arrow_filled)
        }
    }

    fun updateDataPlayer(song: Song) {
        songName.text = song.title
            .removeSuffix(".mp3")
            .replace(Regex("\\s*\\([^)]*\\)"), "")
            .replace("-", "–")
            .trim()

        songArtist.text = song.artistName ?: "Desconocido"
    }

    override fun onStart() {
        super.onStart()
        Intent(this, MusicPlaybackService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onResume() {
        super.onResume()

        LocalBroadcastManager.getInstance(this).apply {
            registerReceiver(playbackStateReceiver, IntentFilter(MusicPlaybackService.ACTION_PLAYBACK_STATE_CHANGED))
            registerReceiver(songChangedReceiver, IntentFilter(MusicPlaybackService.ACTION_SONG_CHANGED))
            registerReceiver(seekBarUpdateReceiver, IntentFilter(MusicPlaybackService.ACTION_SEEK_BAR_UPDATE))
            registerReceiver(resetSeekBarReceiver, IntentFilter(MusicPlaybackService.ACTION_SEEK_BAR_RESET))
        }
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(playbackStateReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(songChangedReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(seekBarUpdateReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(resetSeekBarReceiver)
    }

    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        shouldStopMusic = false
        moveTaskToBack(true)
    }

}