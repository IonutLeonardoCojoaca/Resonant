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
import android.graphics.drawable.Drawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import com.example.resonant.updates.AppUpdateManager
import kotlinx.coroutines.launch
import kotlin.concurrent.thread
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), UpdateDialogFragment.UpdateDialogListener {

    private val REQUEST_NOTIFICATION_PERMISSION = 123

    private lateinit var prefs: SharedPreferences
    private lateinit var userPhotoImage: ImageView
    private lateinit var seekBar: SeekBar
    private lateinit var songDataPlayer: ConstraintLayout
    private lateinit var playPauseButton: ImageButton
    private lateinit var previousSongButton: ImageButton
    private lateinit var nextSongButton: ImageButton
    private lateinit var songImage: ImageView
    private lateinit var songName: TextView
    private lateinit var songArtist: TextView

    private var currentSongBitmap: Bitmap? = null
    private lateinit var homeFragment: HomeFragment
    private lateinit var drawerLayout: DrawerLayout

    private var musicService: MusicPlaybackService? = null
    private var isBound = false
    private lateinit var miniPlayer: View
    private var shouldShowMiniPlayer = true

    private val api by lazy { ApiClient.getService(this) }
    private val updateManager by lazy { AppUpdateManager(this, api, ApiClient.baseUrl()) }

    private lateinit var sharedViewModel: SharedViewModel
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

        setupDrawerNavigation()

        val intent = Intent(this, MusicPlaybackService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        prefs = this@MainActivity.getSharedPreferences("user_data", MODE_PRIVATE)

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

        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

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

        sharedViewModel = ViewModelProvider(this).get(SharedViewModel::class.java)
        setupViewModelObservers()

        userPhotoImage.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        val miniPlayerContainer = findViewById<ConstraintLayout>(R.id.miniPlayerContainer) // Asegúrate de tener la referencia

        miniPlayerContainer.setOnClickListener {
            // ✅ OBTENEMOS TODO DESDE LA ÚNICA FUENTE DE VERDAD: el ViewModel
            val currentSong = sharedViewModel.currentSongLiveData.value
            val bitmap = sharedViewModel.currentSongBitmapLiveData.value

            // Ahora la comprobación es fiable y está sincronizada
            if (currentSong != null && bitmap != null) {
                val fileName = "cover_${currentSong.id}.png"
                // Guardamos el bitmap en un archivo para pasárselo al SongFragment
                Utils.saveBitmapToCacheUri(this@MainActivity, bitmap, fileName)

                // El resto de tu código para crear el Bundle y mostrar el fragmento está perfecto
                val bundle = Bundle().apply {
                    putString("title", currentSong.title)
                    putString("artist", currentSong.artistName)
                    putString("url", currentSong.url)
                    putString("coverFileName", fileName)
                }

                val songFragment = SongFragment()
                songFragment.arguments = bundle

                songFragment.show(supportFragmentManager, "SongFragment")
            } else {
                // (Opcional) Añade un log para depurar si sigue fallando
                Log.w("MiniPlayerClick", "No se pudo abrir SongFragment: currentSong is ${if(currentSong == null) "null" else "OK"}, bitmap is ${if(bitmap == null) "null" else "OK"}")
            }
        }

        val fragmentsWithToolbar = setOf(
            R.id.homeFragment,
            R.id.searchFragment,
            R.id.savedFragment,
            R.id.favoriteSongsFragment,
            R.id.favoriteArtistsFragment,
            R.id.favoriteAlbumsFragment
        )

        val fragmentsNoToolbar = setOf(
            R.id.artistFragment,
            R.id.albumFragment,
            R.id.detailedSongFragment,
            R.id.playlistFragment
        )

        val fragmentsNoToolbarNoBottomNav = setOf(
            R.id.songFragment,
            R.id.createPlaylistFragment
        )

        navController.addOnDestinationChangedListener { _, destination, _ ->

            when (destination.id) {
                R.id.homeFragment -> bottomNavigationView.menu.findItem(R.id.homeFragment).isChecked = true
                R.id.searchFragment -> bottomNavigationView.menu.findItem(R.id.searchFragment).isChecked = true
                R.id.savedFragment -> bottomNavigationView.menu.findItem(R.id.savedFragment).isChecked = true
            }

            when (destination.id) {
                R.id.settingsFragment -> {
                    superiorToolbar.visibility = View.VISIBLE
                    bottomNavigation.visibility = View.GONE // Ocultamos la navegación inferior
                    gradientBottom.visibility = View.GONE
                    shouldShowMiniPlayer = false
                }
                in fragmentsWithToolbar -> {
                    superiorToolbar.visibility = View.VISIBLE
                    bottomNavigation.visibility = View.VISIBLE
                    gradientBottom.visibility = View.VISIBLE
                    shouldShowMiniPlayer = true
                }
                in fragmentsNoToolbar -> {
                    superiorToolbar.visibility = View.GONE
                    bottomNavigation.visibility = View.VISIBLE
                    gradientBottom.visibility = View.VISIBLE
                    shouldShowMiniPlayer = true
                }
                in fragmentsNoToolbarNoBottomNav -> {
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
                }
            }

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

    @Deprecated("This method has been deprecated in favor of using the\n      " +
            "{@link OnBackPressedDispatcher} via {@link #getOnBackPressedDispatcher()}.\n      " +
            "The OnBackPressedDispatcher controls how back button events are dispatched\n      " +
            "to one or more {@link OnBackPressedCallback} objects.")
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
        }
        else if (currentFragmentId != null && currentFragmentId != mainFragmentId) {
            navController.popBackStack()
        } else {
            moveTaskToBack(true)
        }
    }

    private fun setupDrawerNavigation() {
        val displayMetrics = Resources.getSystem().displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val drawerWidth = (screenWidth * 0.85).toInt()

        val drawer: View = findViewById(R.id.navigationView)
        val params = drawer.layoutParams
        params.width = drawerWidth
        drawer.layoutParams = params

        val settingsButton = findViewById<TextView>(R.id.settingsButton)
        val searchButton = findViewById<TextView>(R.id.searchButton)
        val savedButton = findViewById<TextView>(R.id.savedButton)
        val favoriteSongsButton = findViewById<TextView>(R.id.favoriteSongsButton)

        settingsButton.setOnClickListener {
            navController.navigate(R.id.settingsFragment)
            drawerLayout.closeDrawers()
        }

        searchButton.setOnClickListener {
            navController.navigate(R.id.searchFragment)
            drawerLayout.closeDrawers()
        }

        savedButton.setOnClickListener {
            navController.navigate(R.id.savedFragment)
            drawerLayout.closeDrawers()
        }

        favoriteSongsButton.setOnClickListener {
            navController.navigate(R.id.favoriteSongsFragment)
            drawerLayout.closeDrawers()
        }
    }

    private fun setupViewModelObservers() {
        sharedViewModel.currentSongLiveData.observe(this) { song ->
            if (song != null && shouldShowMiniPlayer) {
                updateDataPlayer(song) // ✅ CORRECTO: Solo actualiza texto
                AnimationsUtils.setMiniPlayerVisibility(true, miniPlayer, this)
            } else {
                AnimationsUtils.setMiniPlayerVisibility(false, miniPlayer, this)
            }
        }

        sharedViewModel.isPlayingLiveData.observe(this) { isPlaying ->
            updatePlayPauseButton(isPlaying) // Actualiza el icono del botón
        }

        sharedViewModel.playbackPositionLiveData.observe(this) { positionInfo ->
            if (positionInfo.duration > 0) {
                seekBar.max = positionInfo.duration.toInt()
            }
            seekBar.progress = positionInfo.position.toInt()
        }

        sharedViewModel.currentSongBitmapLiveData.observe(this) { bitmap ->
            if (bitmap != null) {
                songImage.setImageBitmap(bitmap)
                MiniPlayerColorizer.applyFromImageView(
                    imageView = songImage,
                    targets = MiniPlayerColorizer.Targets(
                        container = findViewById<View>(R.id.miniPlayerContainer),
                        title = songName,
                        subtitle = songArtist,
                        iconButtons = listOf(previousSongButton, playPauseButton, nextSongButton),
                        seekBar = seekBar,
                        gradientOverlay = findViewById(R.id.gradientText)
                    ),
                    fallbackColor = getColor(R.color.secondaryColorTheme)
                )
            } else {
                songImage.setImageResource(R.drawable.album_cover)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent?.let { handleDeepLink(it) }
    }

    private fun handleDeepLink(intent: Intent) {
        val data: Uri? = intent.data
        if (data != null) {
            val segments = data.pathSegments
            if (segments.size >= 4 &&
                segments[0] == "shared" &&
                segments[1] == "android" &&
                segments[2] == "song") {

                val songId = segments[3]
                val bundle = Bundle().apply { putString("songId", songId) }
                navController.navigate(R.id.detailedSongFragment, bundle)
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicPlaybackService.MusicServiceBinder
            musicService = binder.getService()
            isBound = true
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
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 3. Solo nos desvinculamos cuando la actividad está siendo destruida permanentemente.
        //    Esto previene fugas de memoria.
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
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

    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,grantResults: IntArray) {
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

    private fun showUpdateDialog(title: String,message: String,forced: Boolean,downloadUrl: String,version: String) {
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