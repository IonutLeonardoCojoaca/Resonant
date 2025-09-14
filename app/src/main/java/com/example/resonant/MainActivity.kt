package com.example.resonant

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.auth.FirebaseAuth
import java.net.URL
import android.Manifest
import android.graphics.drawable.AnimatedVectorDrawable
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import com.example.resonant.updates.AppUpdateManager
import kotlinx.coroutines.launch
import kotlin.concurrent.thread
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), UpdateDialogFragment.UpdateDialogListener {

    private val REQUEST_NOTIFICATION_PERMISSION = 123

    private lateinit var prefs: SharedPreferences
    private lateinit var userPhotoImage: ImageView
    private lateinit var seekBar: SeekBar
    private lateinit var songDataPlayer: FrameLayout

    private lateinit var playPauseButton: ImageButton
    private lateinit var previousSongButton: ImageButton
    private lateinit var nextSongButton: ImageButton
    private lateinit var songImage: ImageView
    private lateinit var songName: TextView
    private lateinit var songArtist: TextView

    private var currentSongBitmap: Bitmap? = null

    private lateinit var playbackStateReceiver: BroadcastReceiver
    private lateinit var songChangedReceiver: BroadcastReceiver
    private lateinit var seekBarUpdateReceiver: BroadcastReceiver
    private lateinit var resetSeekBarReceiver: BroadcastReceiver

    private var observersRegistered = false

    private lateinit var homeFragment: HomeFragment
    private lateinit var drawerLayout: DrawerLayout

    private var musicService: MusicPlaybackService? = null
    private var isBound = false
    private lateinit var miniPlayer: View
    private var shouldShowMiniPlayer = true

    private val api by lazy { ApiClient.getService(this) }
    private val updateManager by lazy { AppUpdateManager(this, api, ApiClient.baseUrl()) }

    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val userViewModel = ViewModelProvider(this)[UserViewModel::class.java]
        if (userViewModel.user == null) {
            val prefs = getSharedPreferences("user_data", Context.MODE_PRIVATE)
            val name = prefs.getString("NAME", null)
            val email = prefs.getString("EMAIL", null)
            val userId = prefs.getString("USER_ID", null)
            val isBanned = prefs.getBoolean("IS_BANNED", false)
            if (email != null && userId != null) {
                userViewModel.user = User(email = email, name = name, id = userId, isBanned = isBanned)
            }
        }

        checkBanStatus()

        checkAppUpdate()

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)

        bottomNavigationView.setupWithNavController(navController)
        bottomNavigationView.itemIconTintList = null

        val superiorToolbar = findViewById<View>(R.id.superiorToolbar)
        val bottomNavigation = findViewById<View>(R.id.bottom_navigation)
        val gradientBottom = findViewById<View>(R.id.gradientBottom)

        val displayMetrics = Resources.getSystem().displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val drawerWidth = (screenWidth * 0.85).toInt()

        val drawer: View = findViewById(R.id.navigationView)
        val params = drawer.layoutParams
        params.width = drawerWidth
        drawer.layoutParams = params

        val intent = Intent(this, MusicPlaybackService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        prefs = this@MainActivity.getSharedPreferences("user_data", AppCompatActivity.MODE_PRIVATE)

        val sharedPref = this@MainActivity.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        val savedUrl = sharedPref.getString(PreferenceKeys.CURRENT_SONG_URL, null)
        val savedId = sharedPref.getString(PreferenceKeys.CURRENT_SONG_ID, "")
        val savedTitle = sharedPref.getString(PreferenceKeys.CURRENT_SONG_TITLE, "")
        val savedArtist = sharedPref.getString(PreferenceKeys.CURRENT_SONG_ARTIST, "")
        val savedAlbum = sharedPref.getString(PreferenceKeys.CURRENT_SONG_ALBUM, "")
        val savedDuration = sharedPref.getString(PreferenceKeys.CURRENT_SONG_DURATION, "")
        val savedImage = sharedPref.getString(PreferenceKeys.CURRENT_SONG_COVER, null)  // OK
        val savedIndex = sharedPref.getInt(PreferenceKeys.CURRENT_SONG_INDEX, -1)

        songDataPlayer = findViewById(R.id.songDataPlayer)
        playPauseButton = findViewById(R.id.playPauseButton)
        previousSongButton = findViewById(R.id.previousSongButton)
        nextSongButton = findViewById(R.id.nextSongButton)
        songImage = findViewById(R.id.songImage)
        songName = findViewById(R.id.songTitle)
        songArtist = findViewById(R.id.songArtist)
        miniPlayer = findViewById(R.id.mini_player)

        userPhotoImage = findViewById(R.id.userProfile)
        drawerLayout = findViewById(R.id.drawerLayout)
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

                // 🎨 Aplicar color predominante al mini reproductor
                val miniPlayerContainer = findViewById<View>(R.id.miniPlayerContainer)
                MiniPlayerColorizer.applyFromImageView(
                    imageView = songImage,
                    targets = MiniPlayerColorizer.Targets(
                        container = miniPlayerContainer,
                        title = songName,
                        subtitle = songArtist,
                        iconButtons = listOf(previousSongButton, playPauseButton, nextSongButton),
                        seekBar = seekBar,
                        gradientOverlay = findViewById(R.id.gradientText)
                    ),
                    fallbackColor = getColor(R.color.secondaryColorTheme) // color por defecto
                )

            } else {
                songImage.setImageResource(R.drawable.album_cover)
            }
        }

        userPhotoImage.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        songDataPlayer.setOnClickListener {
            val currentSong = musicService?.currentSongLiveData?.value
            val bitmap = currentSongBitmap

            if (currentSong != null && bitmap != null) {
                val fileName = "cover_${currentSong.id}.png"
                val uri = Utils.saveBitmapToCacheUri(this@MainActivity, bitmap, fileName)

                val bundle = Bundle().apply {
                    putString("title", currentSong.title)
                    putString("artist", currentSong.artistName)
                    putString("url", currentSong.url)
                    putString("coverFileName", fileName)
                }

                val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                val navController = navHostFragment.navController
                navController.navigate(R.id.songFragment, bundle)
            }
        }

        val fragmentsConToolbar = setOf(
            R.id.homeFragment,
            R.id.searchFragment,
            R.id.savedFragment,
            R.id.favoriteSongsFragment
        )

        val fragmentsSinToolbar = setOf(
            R.id.artistFragment,
            R.id.albumFragment
        )

        navController.addOnDestinationChangedListener { _, destination, _ ->

            when (destination.id) {
                R.id.homeFragment -> bottomNavigationView.menu.findItem(R.id.homeFragment).isChecked = true
                R.id.searchFragment -> bottomNavigationView.menu.findItem(R.id.searchFragment).isChecked = true
                R.id.savedFragment -> bottomNavigationView.menu.findItem(R.id.savedFragment).isChecked = true
            }

            when (destination.id) {
                in fragmentsConToolbar -> {
                    superiorToolbar.visibility = View.VISIBLE
                    bottomNavigation.visibility = View.VISIBLE
                    gradientBottom.visibility = View.VISIBLE
                    shouldShowMiniPlayer = true
                }
                in fragmentsSinToolbar -> {
                    superiorToolbar.visibility = View.GONE
                    bottomNavigation.visibility = View.VISIBLE
                    gradientBottom.visibility = View.VISIBLE
                    shouldShowMiniPlayer = true
                }
                R.id.songFragment -> {
                    superiorToolbar.visibility = View.GONE
                    shouldShowMiniPlayer = false
                    bottomNavigation.visibility = View.GONE
                    gradientBottom.visibility = View.GONE
                }
                else -> {
                    superiorToolbar.visibility = View.GONE
                    shouldShowMiniPlayer = false
                    bottomNavigation.visibility = View.GONE
                }
            }

            if (destination.id == R.id.homeFragment) {
                val currentFragment = navHostFragment.childFragmentManager.primaryNavigationFragment
                if (currentFragment is HomeFragment) {
                    homeFragment = currentFragment
                    setupObservers()
                }
            }

            // ✅ Evaluar si mostrar mini player cuando se navega
            val currentSong = musicService?.currentSongLiveData?.value
            if (shouldShowMiniPlayer && currentSong != null && !currentSong.title.isNullOrEmpty()) {
                AnimationsUtils.setMiniPlayerVisibility(true, miniPlayer, this@MainActivity)
            } else {
                AnimationsUtils.setMiniPlayerVisibility(false, miniPlayer, this@MainActivity)
            }
        }

        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.homeFragment -> {
                    // Elimina todo lo que haya encima del fragmento raíz
                    navController.popBackStack(R.id.homeFragment, false)
                    if (navController.currentDestination?.id != R.id.homeFragment) {
                        navController.navigate(R.id.homeFragment)
                    }
                    true
                }
                R.id.searchFragment -> {
                    navController.popBackStack(R.id.searchFragment, false)
                    if (navController.currentDestination?.id != R.id.searchFragment) {
                        navController.navigate(R.id.searchFragment)
                    }
                    true
                }
                R.id.savedFragment -> {
                    navController.popBackStack(R.id.savedFragment, false)
                    if (navController.currentDestination?.id != R.id.savedFragment) {
                        navController.navigate(R.id.savedFragment)
                    }
                    true
                }
                else -> false
            }
        }

        getProfileImage()

        checkNotificationPermission()
    }

    @Deprecated("This method has been deprecated in favor of using the\n      {@link OnBackPressedDispatcher} via {@link #getOnBackPressedDispatcher()}.\n      The OnBackPressedDispatcher controls how back button events are dispatched\n      to one or more {@link OnBackPressedCallback} objects.")
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
            return
        }

        val mainFragmentId = R.id.homeFragment
        val currentFragmentId = navController.currentDestination?.id
        val topLevelFragments = setOf(R.id.homeFragment, R.id.searchFragment, R.id.savedFragment, R.id.settingsFragment)

        if (currentFragmentId != null && currentFragmentId !in topLevelFragments) {
            navController.popBackStack()
        } else if (currentFragmentId != null && currentFragmentId != mainFragmentId) {
            navController.navigate(mainFragmentId)
        } else {
            moveTaskToBack(true)
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
                        it.url?.let { url -> homeFragment.setCurrentPlayingSong(url) }

                        // 🔥 Aquí faltaba esto
                        songName.text = it.title
                        songArtist.text = it.artistName
                    }
                }
            }
        }

        seekBarUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == MusicPlaybackService.ACTION_SEEK_BAR_UPDATE) {
                    val position = intent.getIntExtra(MusicPlaybackService.EXTRA_SEEK_POSITION, 0)
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

            // Observers
            musicService?.currentSongLiveData?.observe(this@MainActivity) { song ->
                if (song != null && !song.title.isNullOrEmpty() && shouldShowMiniPlayer) {
                    updateDataPlayer(song)
                    AnimationsUtils.setMiniPlayerVisibility(true, miniPlayer, this@MainActivity)
                } else {
                    AnimationsUtils.setMiniPlayerVisibility(false, miniPlayer, this@MainActivity)
                }
            }

            musicService?.isPlayingLiveData?.observe(this@MainActivity) { playing ->
                updatePlayPauseButton(playing)
            }

            // ✅ Forzar evaluación inicial si ya hay canción activa
            val currentSong = musicService?.currentSongLiveData?.value
            if (currentSong != null && !currentSong.title.isNullOrEmpty()) {
                updateDataPlayer(currentSong)
                if (shouldShowMiniPlayer) {
                    AnimationsUtils.setMiniPlayerVisibility(true, miniPlayer, this@MainActivity)
                }
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

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            } else {
                // Permiso concedido, puedes iniciar el servicio aquí
                startMusicService()
            }
        } else {
            // Permiso no requerido para versiones anteriores
            startMusicService()
        }
    }

    private fun startMusicService() {
        val intent = Intent(this, MusicPlaybackService::class.java)
        startService(intent)  // o startForegroundService según sea necesario
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permiso concedido, iniciar el servicio
                startMusicService()
            } else {
                // Permiso denegado, mostrar mensaje o manejar el caso
                Toast.makeText(this, "Necesitas permitir notificaciones para usar la app", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkAppUpdate() {
        lifecycleScope.launch {
            val decision = runCatching { updateManager.checkForUpdate() }
                .onFailure { e -> Log.e("AppUpdate", "Error checking update", e) }
                .getOrNull() ?: return@launch

            when (decision) {
                is UpdateDecision.NoUpdate -> {
                    WorkManager.getInstance(this@MainActivity)
                        .cancelUniqueWork("HourlyNotification")
                }
                is UpdateDecision.Forced -> {
                    showUpdateDialog(
                        title = decision.latest.title ?: "Actualización obligatoria",
                        message = decision.latest.description ?: "Hay una nueva versión ${decision.latest.version}. Debes actualizar para continuar.",
                        forced = true,
                        downloadUrl = decision.downloadUrl,
                        version = decision.latest.version
                    )
                }
                is UpdateDecision.Optional -> {
                    showUpdateDialog(
                        title = decision.latest.title ?: "Actualización disponible",
                        message = decision.latest.description ?: "Nueva versión ${decision.latest.version} disponible. ¿Deseas actualizar ahora?",
                        forced = false,
                        downloadUrl = decision.downloadUrl,
                        version = decision.latest.version
                    )
                }
            }
        }
    }

    private fun showUpdateDialog(
        title: String,
        message: String,
        forced: Boolean,
        downloadUrl: String,
        version: String
    ) {
        val tag = "UpdateDialog"
        if (supportFragmentManager.findFragmentByTag(tag) == null) {
            UpdateDialogFragment.newInstance(title, message, forced, downloadUrl, version)
                .show(supportFragmentManager, tag)
        }
    }

    override fun onUpdateConfirmed(downloadUrl: String, version: String) {
        lifecycleScope.launch {
            try {
                val presigned = updateManager.getPresignedDownloadUrl(version)
                val minimal = AppUpdate(
                    version = version,
                    platform = "Android",
                    fileName = "resonant-$version.apk",
                    title = "Resonant $version",
                    description = "Descargando actualización"
                )
                updateManager.enqueueDownload(minimal, presigned)
                Toast.makeText(this@MainActivity, "Descarga iniciada", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("AppUpdate", "Error resolviendo URL prefirmada", e)
                Toast.makeText(this@MainActivity, "No se pudo iniciar la descarga", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onUpdateDeferred() {
        // Programar la notificación cada hora SOLO mientras haya actualización pendiente
        val workRequest = PeriodicWorkRequestBuilder<HourlyNotificationWorker>(1, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(this@MainActivity).enqueueUniquePeriodicWork(
            "HourlyNotification",
            androidx.work.ExistingPeriodicWorkPolicy.REPLACE,
            workRequest
        )
    }

    private fun checkBanStatus() {
        val userViewModel = ViewModelProvider(this)[UserViewModel::class.java]
        val email = userViewModel.user?.email
        if (email == null) {
            userViewModel.user = null
            FirebaseAuth.getInstance().signOut()
            Toast.makeText(this, "Tu cuenta ha sido restringida", Toast.LENGTH_LONG).show()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
            return
        }

        lifecycleScope.launch {
            val service = ApiClient.getService(this@MainActivity)
            val userData = service.getUserByEmail(email)
            userViewModel.user = userData // Actualiza el ViewModel
            if (userData.isBanned == true) {
                Toast.makeText(this@MainActivity, "Tu cuenta ha sido restringida.", Toast.LENGTH_LONG).show()
                userViewModel.user = null
                FirebaseAuth.getInstance().signOut()
                val prefs = getSharedPreferences("user_data", Context.MODE_PRIVATE)
                prefs.edit().clear().apply()
                val intent = Intent(this@MainActivity, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
        }
    }

    private fun getProfileImage() {
        val headerUserName = drawerLayout.findViewById<TextView>(R.id.headerUserName)
        val headerUserPhoto = drawerLayout.findViewById<ShapeableImageView>(R.id.headerUserPhoto)

        var name = prefs.getString("name", null)
        var urlPhoto = prefs.getString("urlPhoto", null)

        if (name == null || urlPhoto == null) {
            val user = FirebaseAuth.getInstance().currentUser
            if (user != null) {
                name = user.displayName
                urlPhoto = user.photoUrl?.toString()

                prefs.edit()
                    .putString("name", name)
                    .putString("urlPhoto", urlPhoto)
                    .apply()
            }
        }

        headerUserName.text = name ?: "Invitado"

        if (!urlPhoto.isNullOrEmpty()) {
            thread {
                try {
                    val inputStream = URL(urlPhoto).openStream()
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    runOnUiThread {
                        userPhotoImage.setImageBitmap(bitmap)
                        headerUserPhoto.setImageBitmap(bitmap)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            userPhotoImage.setImageResource(R.drawable.user)
            headerUserPhoto.setImageResource(R.drawable.user)
        }
    }

}