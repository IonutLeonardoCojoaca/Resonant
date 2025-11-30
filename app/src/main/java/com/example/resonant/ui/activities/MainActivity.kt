package com.example.resonant.ui.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.resonant.utils.AnimationsUtils
import com.example.resonant.data.network.ApiClient
import com.example.resonant.workers.HourlyNotificationWorker
import com.example.resonant.utils.MiniPlayerColorizer
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.R
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.ui.fragments.SongFragment
import com.example.resonant.data.models.UpdateDecision
import com.example.resonant.ui.fragments.UpdateDialogFragment
import com.example.resonant.ui.viewmodels.UserViewModel
import com.example.resonant.utils.Utils
import com.example.resonant.data.models.AppUpdate
import com.example.resonant.data.models.Song
import com.example.resonant.data.models.User
import com.example.resonant.ui.fragments.HomeFragment
import com.example.resonant.managers.AppUpdateManager
import com.example.resonant.ui.fragments.CreationMenuDialog
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), UpdateDialogFragment.UpdateDialogListener {

    private val REQUEST_NOTIFICATION_PERMISSION = 123

    private lateinit var prefs: SharedPreferences
    private lateinit var seekBar: SeekBar
    private lateinit var songDataPlayer: ConstraintLayout
    private lateinit var playPauseButton: ImageButton
    private lateinit var previousSongButton: ImageButton
    private lateinit var nextSongButton: ImageButton
    private lateinit var songImage: ImageView
    private lateinit var songName: TextView
    private lateinit var songArtist: TextView
    private var lastDialogDismissTime: Long = 0

    private lateinit var homeFragment: HomeFragment
    private lateinit var drawerLayout: DrawerLayout

    private lateinit var dimOverlay: View // Variable para la sombra
    private var musicService: MusicPlaybackService? = null
    private var isBound = false
    private lateinit var miniPlayer: View
    private var shouldShowMiniPlayer = true

    private val userService by lazy { ApiClient.getUserService(this) } // <-- NUEVO
    private val appService by lazy { ApiClient.getAppService(this) }
    private val updateManager by lazy { AppUpdateManager(this, appService, ApiClient.baseUrl()) }

    private lateinit var songViewModel: SongViewModel
    private lateinit var navController: NavController

    private var activeCreationDialog: CreationMenuDialog? = null

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
            val prefs = getSharedPreferences("user_data", MODE_PRIVATE)
            val name = prefs.getString("NAME", null)
            val email = prefs.getString("EMAIL", null)
            val userId = prefs.getString("USER_ID", null)
            val isBanned = prefs.getBoolean("IS_BANNED", false)
            if (email != null && userId != null) {
                userViewModel.user =
                    User(email = email, name = name, id = userId, isBanned = isBanned)
            }
        }

        checkBanStatus()

        checkAppUpdate()

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)

        bottomNavigationView.setupWithNavController(navController)
        bottomNavigationView.itemIconTintList = null

        val bottomNavigation = findViewById<View>(R.id.bottom_navigation)
        val gradientBottom = findViewById<View>(R.id.gradientBottom)

        setupDrawerNavigation()

        val intent = Intent(this, MusicPlaybackService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)

        prefs = this@MainActivity.getSharedPreferences("user_data", MODE_PRIVATE)

        songDataPlayer = findViewById(R.id.songDataPlayer)
        playPauseButton = findViewById(R.id.playPauseButton)
        previousSongButton = findViewById(R.id.previousSongButton)
        nextSongButton = findViewById(R.id.nextSongButton)
        songImage = findViewById(R.id.songImage)
        songName = findViewById(R.id.songTitle)
        songArtist = findViewById(R.id.songArtist)
        miniPlayer = findViewById(R.id.mini_player)
        dimOverlay = findViewById(R.id.dim_overlay)
        drawerLayout = findViewById(R.id.drawerLayout)
        seekBar = findViewById(R.id.seekbarPlayer)
        seekBar.max = 100

        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

        songName.isSelected = true
        songArtist.isSelected = true

        playPauseButton.setOnClickListener {
            val intent = Intent(this, MusicPlaybackService::class.java)
            if (musicService?.isPlaying() == true) {
                intent.action = MusicPlaybackService.Companion.ACTION_PAUSE
            } else {
                intent.action = MusicPlaybackService.Companion.ACTION_RESUME
            }
            startService(intent)
        }

        previousSongButton.setOnClickListener {
            val intent = Intent(this, MusicPlaybackService::class.java).apply {
                // 👇 CORREGIDO
                action = MusicPlaybackService.ACTION_PREVIOUS
            }
            startService(intent)
        }

        nextSongButton.setOnClickListener {
            val intent = Intent(this, MusicPlaybackService::class.java).apply {
                // 👇 CORREGIDO
                action = MusicPlaybackService.ACTION_NEXT
            }
            startService(intent)
        }

        songViewModel = ViewModelProvider(this).get(SongViewModel::class.java)
        setupViewModelObservers()

        val miniPlayerContainer = findViewById<ConstraintLayout>(R.id.miniPlayerContainer) // Asegúrate de tener la referencia

        miniPlayerContainer.setOnClickListener {
            val currentSong = songViewModel.currentSongLiveData.value
            val bitmap = songViewModel.currentSongBitmapLiveData.value

            if (currentSong != null && bitmap != null) {
                val fileName = "cover_${currentSong.id}.png"
                Utils.saveBitmapToCacheUri(this@MainActivity, bitmap, fileName)

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
                Log.w("MiniPlayerClick", "No se pudo abrir SongFragment: currentSong is ${if(currentSong == null) "null" else "OK"}, bitmap is ${if(bitmap == null) "null" else "OK"}")
            }
        }

        val fragmentsWithToolbar = setOf(
            R.id.homeFragment,
            R.id.searchFragment,
            R.id.savedFragment,
            R.id.favoriteSongsFragment,
            R.id.favoriteArtistsFragment,
            R.id.favoriteAlbumsFragment,
        )

        val fragmentsNoToolbar = setOf(
            R.id.artistFragment,
            R.id.albumFragment,
            R.id.detailedSongFragment,
            R.id.playlistFragment,
            R.id.createPlaylistFragment
        )

        val fragmentsNoToolbarNoBottomNav = setOf(
            R.id.songFragment
        )

        navController.addOnDestinationChangedListener { _, destination, _ ->

            // 1. LÓGICA DE SELECCIÓN DE TABS NORMALES
            // Esto asegura que si navegas, el tab se actualice solo.
            when (destination.id) {
                R.id.homeFragment -> bottomNavigationView.menu.findItem(R.id.homeFragment).isChecked = true
                R.id.searchFragment -> bottomNavigationView.menu.findItem(R.id.searchFragment).isChecked = true
                R.id.savedFragment -> bottomNavigationView.menu.findItem(R.id.savedFragment).isChecked = true
            }

            // 2. GESTIÓN DEL ICONO "CREAR"
            val createItemView = bottomNavigationView.findViewById<View>(R.id.createPlaylistFragment)
            val createIconView = createItemView?.findViewById<View>(com.google.android.material.R.id.navigation_bar_item_icon_view)
            val createMenuItem = bottomNavigationView.menu.findItem(R.id.createPlaylistFragment)

            if (destination.id == R.id.createPlaylistFragment) {
                // Estamos en la pantalla de crear playlist
                createMenuItem.isChecked = true
                createMenuItem.setIcon(R.drawable.ic_menu_add_selected)

                // Aseguramos la rotación (45 grados)
                createIconView?.animate()?.rotation(45f)?.setDuration(100)?.start()
            } else {
                // NO estamos en crear playlist
                // Solo revertimos la animación si NO hay un diálogo activo.
                // Si hay diálogo, el control de la X lo tiene el diálogo, no la navegación.
                if (activeCreationDialog == null || activeCreationDialog?.isVisible == false) {
                    if (createIconView != null && createIconView.rotation != 0f) {
                        createIconView.animate().rotation(0f).setDuration(300).start()
                    }
                    createMenuItem.setIcon(R.drawable.ic_menu_add)
                }
            }

            // 3. VISIBILIDAD UI (Tu código original, sin cambios)
            when (destination.id) {
                R.id.settingsFragment -> {
                    bottomNavigation.visibility = View.GONE
                    gradientBottom.visibility = View.GONE
                    shouldShowMiniPlayer = false
                }
                in fragmentsWithToolbar -> {
                    bottomNavigation.visibility = View.VISIBLE
                    gradientBottom.visibility = View.VISIBLE
                    shouldShowMiniPlayer = true
                }
                in fragmentsNoToolbar -> {
                    bottomNavigation.visibility = View.VISIBLE
                    gradientBottom.visibility = View.VISIBLE
                    shouldShowMiniPlayer = true
                }
                in fragmentsNoToolbarNoBottomNav -> {
                    shouldShowMiniPlayer = false
                    bottomNavigation.visibility = View.GONE
                    gradientBottom.visibility = View.GONE
                }
                else -> {
                    shouldShowMiniPlayer = false
                    bottomNavigation.visibility = View.GONE
                }
            }

            // 4. GESTIÓN HOME (Tu código original)
            if (destination.id == R.id.homeFragment) {
                val currentFragment = navHostFragment.childFragmentManager.primaryNavigationFragment
                if (currentFragment is HomeFragment) {
                    homeFragment = currentFragment
                }
            }

            // 5. GESTIÓN MINIPLAYER (Tu código original)
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
                    if (navController.currentDestination?.id != R.id.homeFragment) {
                        navController.navigate(R.id.homeFragment)
                    }
                    true
                }
                R.id.searchFragment -> {
                    if (navController.currentDestination?.id != R.id.searchFragment) {
                        navController.navigate(R.id.searchFragment)
                    }
                    true
                }
                R.id.savedFragment -> {
                    if (navController.currentDestination?.id != R.id.savedFragment) {
                        navController.navigate(R.id.savedFragment)
                    }
                    true
                }
                R.id.createPlaylistFragment -> {
                    val itemView = bottomNavigationView.findViewById<View>(R.id.createPlaylistFragment)
                    val iconView = itemView.findViewById<View>(com.google.android.material.R.id.navigation_bar_item_icon_view)

                    if (System.currentTimeMillis() - lastDialogDismissTime < 300) {
                        return@setOnItemSelectedListener false
                    }

                    // Si ya está abierto, lo cerramos
                    if (activeCreationDialog != null && activeCreationDialog?.isVisible == true) {
                        activeCreationDialog?.dismiss()
                        activeCreationDialog = null
                        return@setOnItemSelectedListener false // IMPORTANTE: false
                    } else {
                        // Abrimos el menú

                        // 1. Animación visual manual (ya que devolveremos false)
                        iconView?.animate()?.rotation(45f)?.setDuration(300)?.start()
                        item.setIcon(R.drawable.ic_menu_add_selected)

                        // 2. Dim Overlay
                        dimOverlay.visibility = View.VISIBLE
                        dimOverlay.animate().alpha(1f).setDuration(300).start()

                        // 3. Crear Diálogo
                        val menuDialog = CreationMenuDialog()
                        activeCreationDialog = menuDialog

                        menuDialog.onDismissListener = {
                            lastDialogDismissTime = System.currentTimeMillis()

                            bottomNavigationView.post {
                                val currentDestId = navController.currentDestination?.id
                                val menuItem = bottomNavigationView.menu.findItem(R.id.createPlaylistFragment)

                                // Solo revertimos la animación si NO hemos navegado a "Crear Playlist"
                                if (currentDestId != R.id.createPlaylistFragment) {
                                    iconView?.animate()?.rotation(0f)?.setDuration(300)?.start()
                                    menuItem.setIcon(R.drawable.ic_menu_add)

                                    // Forzamos visualmente que el ícono correcto esté marcado
                                    // basado estrictamente en dónde estamos AHORA.
                                    if (currentDestId != null) {
                                        bottomNavigationView.menu.findItem(currentDestId)?.isChecked = true
                                    }
                                }
                            }

                            // Ocultar Overlay
                            dimOverlay.animate()
                                .alpha(0f)
                                .setDuration(300)
                                .withEndAction { dimOverlay.visibility = View.GONE }
                                .start()

                            activeCreationDialog = null
                        }

                        menuDialog.show(supportFragmentManager, "CreationMenuDialog")

                        false
                    }
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
        songViewModel.currentSongLiveData.observe(this) { song ->
            if (song != null && shouldShowMiniPlayer) {
                updateDataPlayer(song) // ✅ CORRECTO: Solo actualiza texto
                AnimationsUtils.setMiniPlayerVisibility(true, miniPlayer, this)
            } else {
                AnimationsUtils.setMiniPlayerVisibility(false, miniPlayer, this)
            }
        }

        songViewModel.isPlayingLiveData.observe(this) { isPlaying ->
            updatePlayPauseButton(isPlaying) // Actualiza el icono del botón
        }

        songViewModel.playbackPositionLiveData.observe(this) { positionInfo ->
            if (positionInfo.duration > 0) {
                seekBar.max = positionInfo.duration.toInt()
            }
            seekBar.progress = positionInfo.position.toInt()
        }

        songViewModel.currentSongBitmapLiveData.observe(this) { bitmap ->
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
                songImage.setImageResource(R.drawable.ic_disc)
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
            playPauseButton.setImageResource(R.drawable.ic_pause)
        } else {
            playPauseButton.setImageResource(R.drawable.ic_play)
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

    fun openDrawer() {
        if (::drawerLayout.isInitialized) {
            drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, MusicPlaybackService::class.java).also { intent ->
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
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
            UpdateDialogFragment.Companion.newInstance(title, message, forced, downloadUrl, version)
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
            ExistingPeriodicWorkPolicy.REPLACE,
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
            try {
                val userData = userService.getUserByEmail(email)
                userViewModel.user = userData

                if (userData.isBanned == true) {
                    Toast.makeText(this@MainActivity, "Tu cuenta ha sido restringida.", Toast.LENGTH_LONG).show()
                    userViewModel.user = null
                    FirebaseAuth.getInstance().signOut()
                    getSharedPreferences("user_data", MODE_PRIVATE).edit().clear().apply()
                    val intent = Intent(this@MainActivity, LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }
                // Si no está baneado, no hace nada.

            } catch (e: Exception) {
                // 4. Tu manejo de errores offline está perfecto, no se toca.
                Log.w("MainActivity", "No se pudo verificar el ban status (offline?): ${e.message}")
            }
        }
    }

    private fun getProfileImage() {
        // Aseguramos que el drawer esté listo
        if (!::drawerLayout.isInitialized) return

        val headerUserName = drawerLayout.findViewById<TextView>(R.id.headerUserName)
        val headerUserPhoto = drawerLayout.findViewById<ShapeableImageView>(R.id.headerUserPhoto)

        // Referencia al archivo local
        val localFileName = "profile_user.png"
        val file = File(filesDir, localFileName)

        val user = FirebaseAuth.getInstance().currentUser

        // 1. Gestionar el Nombre
        val name = user?.displayName ?: prefs.getString("name", "Invitado")
        headerUserName.text = name
        if (user?.displayName != null) {
            prefs.edit().putString("name", name).apply()
        }

        // 2. Gestionar la Foto con Corrutinas
        lifecycleScope.launch(Dispatchers.IO) {
            // PASO A: CARGA RÁPIDA (Caché)
            // Si ya tenemos una foto guardada, la mostramos inmediatamente mientras cargamos la nueva
            if (file.exists()) {
                try {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        withContext(Dispatchers.Main) {
                            headerUserPhoto.setImageBitmap(bitmap)
                        }
                    } else {
                        // El archivo existe pero está corrupto (bitmap null), lo borramos
                        file.delete()
                    }
                } catch (e: Exception) {
                    Log.e("ProfileImage", "Error leyendo caché local", e)
                }
            }

            // PASO B: SINCRONIZACIÓN (Red)
            val urlPhoto = user?.photoUrl?.toString()
            Log.d("ProfileImage", "URL de foto: $urlPhoto")

            if (!urlPhoto.isNullOrEmpty()) {
                try {
                    // Descargamos SIEMPRE para asegurar que tenemos la última versión
                    // (Opcional: Podrías guardar la URL en prefs y comparar si cambió para ahorrar datos)
                    val inputStream = URL(urlPhoto).openStream()
                    val bitmapNetwork = BitmapFactory.decodeStream(inputStream)

                    if (bitmapNetwork != null) {
                        // 1. Guardar/Sobrescribir en memoria interna
                        val fos = openFileOutput(localFileName, MODE_PRIVATE)
                        bitmapNetwork.compress(Bitmap.CompressFormat.PNG, 100, fos)
                        fos.close()
                        Log.d("ProfileImage", "Imagen descargada y guardada correctamente")

                        // 2. Actualizar la UI con la imagen fresca
                        withContext(Dispatchers.Main) {
                            headerUserPhoto.setImageBitmap(bitmapNetwork)
                            val userViewModel = ViewModelProvider(this@MainActivity)[UserViewModel::class.java]
                            userViewModel.profileImageUpdated.value = true
                        }
                    } else {
                        Log.e("ProfileImage", "La imagen descargada es null")
                    }
                } catch (e: Exception) {
                    Log.e("ProfileImage", "Error descargando imagen: ${e.message}")
                    e.printStackTrace()
                    // Si falló la descarga y no tenemos archivo local, ponemos el default
                    if (!file.exists()) {
                        withContext(Dispatchers.Main) {
                            headerUserPhoto.setImageResource(R.drawable.ic_user)
                        }
                    }
                }
            } else {
                // No hay URL de foto (usuario sin foto)
                if (!file.exists()) {
                    withContext(Dispatchers.Main) {
                        headerUserPhoto.setImageResource(R.drawable.ic_user)
                    }
                }
            }
        }
    }

}