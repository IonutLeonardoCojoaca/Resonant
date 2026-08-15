package com.example.resonant.ui.activities

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavOptions
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
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.ui.viewmodels.FavoritesViewModel
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
import com.example.resonant.managers.SettingsManager
import com.example.resonant.ui.fragments.CreationMenuDialog
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit
import androidx.core.graphics.toColorInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.resonant.managers.DownloadStatus
import com.example.resonant.playback.QueueSource
import com.example.resonant.playback.PlaybackStateRepository
import com.example.resonant.playback.PlaybackConnectRepository
import com.example.resonant.playback.PlaybackConnectUiState
import com.example.resonant.ui.bottomsheets.PlaybackDevicesBottomSheet
import com.example.resonant.ui.bottomsheets.PlaybackQueueBottomSheet
import com.example.resonant.ui.views.MiniPlayerSwipeHandler
import com.example.resonant.ui.viewmodels.DownloadViewModel
import com.example.resonant.ui.viewmodels.AriaViewModel
import com.example.resonant.aria.AriaClientActionExecutor
import com.example.resonant.aria.AriaScreenContextHolder
import com.example.resonant.aria.AriaScreenMapper
import com.example.resonant.aria.ForegroundAriaWakeWordController
import com.example.resonant.ui.bottomsheets.AriaQuickSheet
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), UpdateDialogFragment.UpdateDialogListener {

    private val REQUEST_NOTIFICATION_PERMISSION = 123

    private lateinit var prefs: SharedPreferences
    private lateinit var seekBar: SeekBar
    private lateinit var songDataPlayer: LinearLayout
    private lateinit var miniPlayerSwipeHandler: MiniPlayerSwipeHandler
    private lateinit var playPauseButton: ImageButton
    private var playmixRingAnimator: ObjectAnimator? = null
    private lateinit var miniPlayerLikeButton: ImageButton
    private lateinit var miniPlayerQueueButton: ImageButton
    private lateinit var swipeHintLeft: ImageView
    private lateinit var swipeHintRight: ImageView
    private lateinit var songImage: ImageView
    private lateinit var songName: TextView
    private lateinit var songArtist: TextView
    private var lastDialogDismissTime: Long = 0

    private lateinit var downloadViewModel: DownloadViewModel // Asegúrate de inicializarlo
    private lateinit var downloadProgressCard: View
    private lateinit var downloadProgressBar: LinearProgressIndicator
    private lateinit var downloadText: TextView
    private lateinit var downloadPercentText: TextView

    private lateinit var homeFragment: HomeFragment
    private lateinit var drawerLayout: DrawerLayout

    private lateinit var dimOverlay: View // Variable para la sombra
    private lateinit var miniPlayer: View
    private lateinit var playbackDeviceBar: View
    private lateinit var playbackDeviceText: TextView
    private var shouldShowMiniPlayer = true
    private var latestConnectState: PlaybackConnectUiState? = null
    /**
     * Set to true when this device transfers playback to another device.
     * Prevents the mini-player from reappearing until the user explicitly
     * starts playback locally again (via the conflict dialog or a TRANSFER_IN).
     */
    private var playbackTransferredAway = false

    private val userService by lazy { ApiClient.getUserService(this) } // <-- NUEVO
    private val appService by lazy { ApiClient.getAppService(this) }
    private val updateManager by lazy { AppUpdateManager(this, appService, ApiClient.baseUrl()) }

    private lateinit var songViewModel: SongViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel
    private lateinit var navController: NavController
    private lateinit var ariaViewModel: AriaViewModel
    private lateinit var ariaFloatingLauncher: View
    private lateinit var ariaFloatingLabel: TextView
    private lateinit var ariaFloatingScrim: View
    private lateinit var ariaFloatingOverlay: android.widget.FrameLayout
    private var ariaFloatingAssistantEnabled = true
    private var ariaForegroundWakeWordEnabled = false
    private var ariaQuickSheetVisible = false
    private var activityIsForeground = false
    private var currentDestinationId: Int? = null
    private val playbackConnectRepository by lazy {
        PlaybackConnectRepository.get(applicationContext)
    }

    private val ariaClientActionExecutor by lazy {
        AriaClientActionExecutor(this, lifecycleScope) { message, success ->
            ariaViewModel.updateLastAriaMessage(message, isComplete = true)
            showResonantSnackbar(
                text = message,
                colorRes = if (success) R.color.successColor else R.color.secondaryColorTheme,
                iconRes = if (success) R.drawable.ic_success else R.drawable.ic_warning
            )
        }
    }
    private val ariaWakeWordController by lazy {
        ForegroundAriaWakeWordController(applicationContext) {
            if (
                activityIsForeground &&
                !isFinishing &&
                !isDestroyed &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) {
                showAriaQuickSheet(startVoice = true)
            }
        }
    }

    private var activeCreationDialog: CreationMenuDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Solo top y lados — NO bottom en el contenedor raíz
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        // Listener específico para BottomNavigationView
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.bottom_navigation)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                systemBars.bottom
            )
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

        prefs = this@MainActivity.getSharedPreferences("user_data", MODE_PRIVATE)

        miniPlayer = findViewById(R.id.mini_player)
        // Several fragment/recycler layouts reuse songTitle, songArtist and
        // songImage. Resolve every mini-player child from its own root so Home
        // cards can never receive playback-state updates by mistake.
        songDataPlayer = miniPlayer.findViewById(R.id.songDataPlayer)
        playPauseButton = miniPlayer.findViewById(R.id.playPauseButton)
        miniPlayerLikeButton = miniPlayer.findViewById(R.id.miniPlayerLikeButton)
        miniPlayerQueueButton = miniPlayer.findViewById(R.id.miniPlayerQueueButton)
        swipeHintLeft = miniPlayer.findViewById(R.id.swipeHintLeft)
        swipeHintRight = miniPlayer.findViewById(R.id.swipeHintRight)
        songImage = miniPlayer.findViewById(R.id.songImage)
        songName = miniPlayer.findViewById(R.id.songTitle)
        songArtist = miniPlayer.findViewById(R.id.songArtist)
        playbackDeviceBar = findViewById(R.id.playbackDeviceBar)
        playbackDeviceText = playbackDeviceBar.findViewById(R.id.playbackDeviceText)
        dimOverlay = findViewById(R.id.dim_overlay)
        drawerLayout = findViewById(R.id.drawerLayout)
        seekBar = miniPlayer.findViewById(R.id.seekbarPlayer)
        seekBar.max = 100

        ariaViewModel = ViewModelProvider(this)[AriaViewModel::class.java]
        ariaFloatingLauncher = findViewById(R.id.ariaFloatingLauncher)
        ariaFloatingLabel = findViewById(R.id.ariaFloatingLabel)
        ariaFloatingScrim = findViewById(R.id.ariaFloatingScrim)
        ariaFloatingOverlay = findViewById(R.id.ariaFloatingOverlay)
        setupGlobalAria()

        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        drawerLayout.setScrimColor(Color.TRANSPARENT)

        val mainContent = findViewById<View>(R.id.main)
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                val slideX = drawerView.width * slideOffset
                mainContent.translationX = slideX
                val scale = 1f - (slideOffset * 0.06f)
                mainContent.scaleX = scale
                mainContent.scaleY = scale
            }

            override fun onDrawerOpened(drawerView: View) {}

            override fun onDrawerClosed(drawerView: View) {
                mainContent.translationX = 0f
                mainContent.scaleX = 1f
                mainContent.scaleY = 1f
            }

            override fun onDrawerStateChanged(newState: Int) {}
        })

        songName.isSelected = true
        songArtist.isSelected = true

        playbackConnectRepository.synchronizeUserScope()
        playbackDeviceBar.setOnClickListener {
            PlaybackDevicesBottomSheet().show(
                supportFragmentManager,
                "PlaybackDevicesBottomSheet"
            )
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playbackConnectRepository.uiState.collect { state ->
                    latestConnectState = state

                    val active = state.activeDevice
                    val remoteIsActive = active != null &&
                        active.deviceId != state.localDeviceId

                    if (remoteIsActive && !PlaybackStateRepository.isPlaying) {
                        // Another device owns playback and we're not playing →
                        // hide mini-player and mark as transferred away.
                        playbackTransferredAway = true
                        hideMiniPlayerForTransfer()
                    } else if (!remoteIsActive && PlaybackStateRepository.isPlaying) {
                        // We're the active device again (TRANSFER_IN or user
                        // confirmed local play) → restore mini-player.
                        playbackTransferredAway = false
                    }
                    renderPlaybackDeviceState(state)
                }
            }
        }
        playbackConnectRepository.refreshAsync()

        downloadViewModel = ViewModelProvider(this)[DownloadViewModel::class.java]

        downloadProgressCard = findViewById(R.id.downloadProgressCard)
        downloadProgressBar = findViewById(R.id.downloadProgressBar)
        downloadText = findViewById(R.id.downloadText)
        downloadPercentText = findViewById(R.id.downloadPercentText)

        lifecycleScope.launch {
            // Usamos repeatOnLifecycle para evitar actualizaciones en background
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                downloadViewModel.downloadStatus.collect { status ->
                    when (status) {
                        is DownloadStatus.Idle -> {
                            // Tu lógica de salida (está bien)
                            if (downloadProgressCard.visibility == View.VISIBLE) {
                                downloadProgressCard.animate()
                                    .alpha(0f)
                                    .translationY(20f)
                                    .withEndAction { downloadProgressCard.visibility = View.GONE }
                                    .start()
                            }
                        }
                        is DownloadStatus.Started -> {
                            // 1. Resetear visuales ANTES de animar entrada
                            downloadProgressCard.animate().cancel() // Cancelar animaciones previas
                            downloadProgressCard.alpha = 1f
                            downloadProgressCard.translationY = 0f
                            downloadProgressCard.visibility = View.VISIBLE

                            downloadText.text = "Iniciando descarga..."
                            downloadPercentText.text = "0%"

                            // 2. LA CLAVE: Desactivar indeterminado y forzar 0 sin animación
                            downloadProgressBar.isIndeterminate = false
                            downloadProgressBar.setProgressCompat(0, false)

                            // Resetear color por si hubo error antes (opcional pero bueno)
                            // downloadProgressBar.setIndicatorColor(getColor(R.color.secondaryColorTheme))
                        }
                        is DownloadStatus.Progress -> {
                            downloadProgressCard.visibility = View.VISIBLE
                            downloadProgressBar.isIndeterminate = false // Asegurar

                            downloadPercentText.text = "${status.percent}%"
                            downloadText.text = "Descargando..."

                            // Animación suave solo si ya avanzó algo
                            downloadProgressBar.setProgressCompat(status.percent, true)
                        }
                        is DownloadStatus.Success -> {
                            downloadPercentText.text = "100%"
                            downloadText.text = "¡Descarga completada!"
                            downloadProgressBar.setProgressCompat(100, true)
                        }
                        is DownloadStatus.Error -> {
                            downloadText.text = "Error: ${status.message}"
                            downloadProgressBar.setIndicatorColor(Color.RED)
                            downloadProgressBar.setProgressCompat(0, false)
                        }
                    }
                }
            }
        }

        playPauseButton.setOnClickListener {
            val intent = Intent(this, MusicPlaybackService::class.java)
            if (PlaybackStateRepository.isPlaying) {
                intent.action = MusicPlaybackService.Companion.ACTION_PAUSE
            } else {
                intent.action = MusicPlaybackService.Companion.ACTION_RESUME
            }
            startService(intent)
        }

        songViewModel = ViewModelProvider(this).get(SongViewModel::class.java)
        favoritesViewModel = ViewModelProvider(this).get(FavoritesViewModel::class.java)

        miniPlayerLikeButton.setOnClickListener {
            val song = songViewModel.currentSongLiveData.value ?: return@setOnClickListener
            favoritesViewModel.toggleFavoriteSong(song) { success, isNowFavorite ->
                if (success) {
                    showResonantSnackbar(
                        text = if (isNowFavorite) "¡Canción añadida a favoritos!" else "Canción eliminada de favoritos",
                        colorRes = R.color.successColor,
                        iconRes = R.drawable.ic_success
                    )
                } else {
                    showResonantSnackbar(
                        text = "Error al actualizar favoritos",
                        colorRes = R.color.errorColor,
                        iconRes = R.drawable.ic_error
                    )
                }
            }
        }

        miniPlayerQueueButton.setOnClickListener {
            if (supportFragmentManager.findFragmentByTag(QUEUE_SHEET_TAG) == null) {
                PlaybackQueueBottomSheet().show(supportFragmentManager, QUEUE_SHEET_TAG)
            }
        }
        setupViewModelObservers()

        val miniPlayerContainer = findViewById<MaterialCardView>(R.id.miniPlayerContainer)

        miniPlayerContainer.setOnClickListener {
            val currentSong = songViewModel.currentSongLiveData.value
            if (currentSong != null) {
                SongFragment().show(supportFragmentManager, "SongFragment")
            } else {
                Log.w("MiniPlayerClick", "No se pudo abrir SongFragment: currentSong is null")
            }
        }

        // ── Swipe-to-skip on the song info area ────────────────────────────
        // Swipe left = next track, swipe right = previous.  The handler also
        // forwards single taps to open the NowPlaying (SongFragment) so that
        // the user experience is seamless with or without the gesture.
        miniPlayerSwipeHandler = MiniPlayerSwipeHandler(
            context = this,
            songDataContainer = miniPlayer.findViewById(R.id.songTextContainer),
            onSkipNext = {
                val intent = Intent(this, MusicPlaybackService::class.java).apply {
                    action = MusicPlaybackService.ACTION_NEXT
                }
                startService(intent)
            },
            onSkipPrevious = {
                val intent = Intent(this, MusicPlaybackService::class.java).apply {
                    action = MusicPlaybackService.ACTION_PREVIOUS
                }
                startService(intent)
            },
            onClick = {
                val currentSong = songViewModel.currentSongLiveData.value
                if (currentSong != null) {
                    SongFragment().show(supportFragmentManager, "SongFragment")
                }
            }
        )
        songDataPlayer.setOnTouchListener(miniPlayerSwipeHandler)

        val fragmentsWithToolbar = setOf(
            R.id.homeFragment,
            R.id.savedFragment,
            R.id.downloadedSongsFragment,
            R.id.exploreFragment
        )

        val fragmentsWithToolbarNoHeader = setOf(
            R.id.ariaFragment
        )

        val fragmentsNoToolbar = setOf(
            R.id.artistFragment,
            R.id.albumFragment,
            R.id.detailedAlbumFragment,
            R.id.detailedSongFragment,
            R.id.playlistFragment,
            R.id.createPlaylistFragment,
            R.id.genreArtistsFragment,
            R.id.topChartsFragment,
            R.id.artistSmartPlaylistFragment,
            R.id.allGenresFragment,
            R.id.topArtistsFragment,
            R.id.topAlbumsFragment,
            R.id.publicPlaylistsFragment,
            R.id.historyFragment,
            R.id.detailedArtistFragment,
            R.id.searchFragment,
            R.id.playmixListFragment,
            R.id.playmixDetailFragment,
            R.id.crossfadeEditorFragment,
            R.id.collabBubbleFragment,
            R.id.collabDetailFragment,
            R.id.collabPathFragment
        )

        val fragmentsNoToolbarNoBottomNav = setOf(
            R.id.songFragment,
            R.id.collabSearchFragment
        )

        navController.addOnDestinationChangedListener { _, destination, _ ->
            currentDestinationId = destination.id
            if (AriaScreenMapper.isAriaDestination(destination.id)) {
                AriaScreenContextHolder.enterAriaDestination()
            } else {
                AriaScreenContextHolder.updateDestination(
                    AriaScreenMapper.fromDestinationId(destination.id)
                )
            }
            updateAriaFloatingLauncher(destination.id)
            updateAriaWakeWordListening()

            // 1. LÓGICA DE SELECCIÓN DE TABS NORMALES
            // Esto asegura que si navegas, el tab se actualice solo.
            when (destination.id) {
                R.id.homeFragment -> bottomNavigationView.menu.findItem(R.id.homeFragment).isChecked = true
                R.id.savedFragment -> bottomNavigationView.menu.findItem(R.id.savedFragment).isChecked = true
                R.id.exploreFragment -> bottomNavigationView.menu.findItem(R.id.exploreFragment).isChecked = true
                R.id.ariaFragment -> bottomNavigationView.menu.findItem(R.id.ariaFragment)?.isChecked = true
            }

            // 2. GESTIÓN DEL ICONO "CREAR"
            val createItemView = bottomNavigationView.findViewById<View>(R.id.createPlaylistFragment)
            val createIconView = createItemView?.findViewById<View>(com.google.android.material.R.id.navigation_bar_item_icon_view)
            val createMenuItem = bottomNavigationView.menu.findItem(R.id.createPlaylistFragment)

            if (destination.id == R.id.createPlaylistFragment) {
                // Estamos en la pantalla de crear playlist
                createMenuItem.isChecked = true
                createMenuItem.setIcon(R.drawable.ic_menu_add_selected)

                // --- APLICAR COLOR ROJO ---
                createMenuItem.icon?.setTint(Color.parseColor("#E21616")) // <--- NUEVO
                // --------------------------

                // Aseguramos la rotación (45 grados)
                createIconView?.animate()?.rotation(45f)?.setDuration(100)?.start()
            } else {
                // NO estamos en crear playlist
                if (activeCreationDialog == null || activeCreationDialog?.isVisible == false) {
                    if (createIconView != null && createIconView.rotation != 0f) {
                        createIconView.animate().rotation(0f).setDuration(300).start()
                    }
                    createMenuItem.setIcon(R.drawable.ic_menu_add)

                    // --- QUITAR COLOR ROJO ---
                    createMenuItem.icon?.setTintList(null) // <--- NUEVO: Asegura que al salir se limpie el color
                    // -------------------------
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
                in fragmentsWithToolbarNoHeader -> {
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
            val currentSong = PlaybackStateRepository.currentSongLiveData.value
            if (shouldShowMiniPlayer && !playbackTransferredAway &&
                currentSong != null && !currentSong.title.isNullOrEmpty()
            ) {
                AnimationsUtils.setMiniPlayerVisibility(true, miniPlayer, this@MainActivity)
            } else {
                AnimationsUtils.setMiniPlayerVisibility(false, miniPlayer, this@MainActivity)
            }
            latestConnectState?.let { state ->
                renderPlaybackDeviceState(state, destination.id)
            }
        }

        bottomNavigationView.setOnItemSelectedListener { item ->
            // Bounce animation on icon click
            animateBottomNavIcon(item.itemId)
            
            when (item.itemId) {
                R.id.homeFragment -> {
                    navigateToTopLevelDestination(R.id.homeFragment)
                    true
                }
                R.id.exploreFragment -> {
                    navigateToTopLevelDestination(R.id.exploreFragment)
                    true
                }
                R.id.savedFragment -> {
                    navigateToTopLevelDestination(R.id.savedFragment)
                    true
                }
                R.id.ariaFragment -> {
                    navigateToTopLevelDestination(R.id.ariaFragment)
                    true
                }
                R.id.createPlaylistFragment -> {
                    val itemView = bottomNavigationView.findViewById<View>(R.id.createPlaylistFragment)
                    val iconView = itemView.findViewById<View>(com.google.android.material.R.id.navigation_bar_item_icon_view)

                    if (System.currentTimeMillis() - lastDialogDismissTime < 300) {
                        return@setOnItemSelectedListener false
                    }

                    if (activeCreationDialog != null && activeCreationDialog?.isVisible == true) {
                        activeCreationDialog?.dismiss()
                        activeCreationDialog = null
                        return@setOnItemSelectedListener false
                    } else {
                        // Abrimos el menú

                        // 1. Animación visual
                        iconView?.animate()?.rotation(45f)?.setDuration(300)?.start()
                        item.setIcon(R.drawable.ic_menu_add_selected)

                        // --- APLICAR COLOR ROJO ---
                        item.icon?.setTint("#E21616".toColorInt()) // <--- NUEVO: Pinta el icono de rojo
                        // --------------------------

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

                                if (currentDestId != R.id.createPlaylistFragment) {
                                    iconView?.animate()?.rotation(0f)?.setDuration(300)?.start()
                                    menuItem.setIcon(R.drawable.ic_menu_add)

                                    // --- QUITAR COLOR ROJO (RESETEAR) ---
                                    menuItem.icon?.setTintList(null) // <--- NUEVO: Elimina el tinte para volver al color original
                                    // ------------------------------------

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

        // handleDeepLink() was previously only wired from onNewIntent(), which
        // never fires on a cold start (app not already running) — exactly the
        // common case when a user taps a shared song link. That silently
        // dropped the link and opened straight to Home instead of the song.
        handleDeepLink(intent)
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
        val topLevelFragments = setOf(R.id.homeFragment, R.id.savedFragment, R.id.settingsFragment, R.id.ariaFragment, R.id.exploreFragment)

        if (currentFragmentId != null && currentFragmentId !in topLevelFragments) {
            navController.popBackStack()
        }
        else if (currentFragmentId != null && currentFragmentId != mainFragmentId) {
            navController.popBackStack()
        } else {
            moveTaskToBack(true)
        }
    }

    private fun navigateToTopLevelDestination(destinationId: Int) {
        if (navController.currentDestination?.id == destinationId) return

        val navOptions = NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setRestoreState(true)
            .setPopUpTo(navController.graph.findStartDestination().id, false, true)
            .build()

        navController.navigate(destinationId, null, navOptions)
    }

    private fun setupDrawerNavigation() {
        val displayMetrics = Resources.getSystem().displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val drawerWidth = (screenWidth * 0.85).toInt()

        val drawer: View = findViewById(R.id.navigationView)
        val params = drawer.layoutParams
        params.width = drawerWidth
        drawer.layoutParams = params

        val version = this@MainActivity.packageManager
            .getPackageInfo(this@MainActivity.packageName, 0)
            .versionName

        // Sincronizar versión con BuildConfig
        val versionText = findViewById<TextView>(R.id.versionText)
        versionText?.text = "Resonant $version"

        // Referencias a los botones del menú lateral
        val homeButton = findViewById<TextView>(R.id.homeButton)
        val exploreButton = findViewById<TextView>(R.id.exploreButton)
        val settingsButton = findViewById<TextView>(R.id.settingsButton)
        val searchButton = findViewById<TextView>(R.id.searchButton)
        val savedButton = findViewById<TextView>(R.id.savedButton)
        val downloadsButton = findViewById<TextView>(R.id.downloadsButton)
        val historyButton = findViewById<TextView>(R.id.historyButton)

        homeButton?.setOnClickListener {
            navigateToTopLevelDestination(R.id.homeFragment)
            drawerLayout.closeDrawers()
        }

        exploreButton?.setOnClickListener {
            navigateToTopLevelDestination(R.id.exploreFragment)
            drawerLayout.closeDrawers()
        }

        settingsButton?.setOnClickListener {
            navigateToTopLevelDestination(R.id.settingsFragment)
            drawerLayout.closeDrawers()
        }

        searchButton?.setOnClickListener {
            val navOptions = NavOptions.Builder()
                .setEnterAnim(R.anim.slide_in_up)
                .setExitAnim(R.anim.scale_down_fade_out)
                .setPopEnterAnim(R.anim.scale_up_fade_in)
                .setPopExitAnim(R.anim.slide_out_down)
                .build()
            navController.navigate(R.id.searchFragment, null, navOptions)
            drawerLayout.closeDrawers()
        }

        savedButton?.setOnClickListener {
            navigateToTopLevelDestination(R.id.savedFragment)
            drawerLayout.closeDrawers()
        }

        downloadsButton?.setOnClickListener {
            navController.navigate(R.id.downloadedSongsFragment)
            drawerLayout.closeDrawers()
        }

        historyButton?.setOnClickListener {
            navController.navigate(R.id.historyFragment)
            drawerLayout.closeDrawers()
        }

        // Action on Header (Profile)
        val profileButton = findViewById<View>(R.id.headerDrawer)
        profileButton?.setOnClickListener {
            drawerLayout.closeDrawers()
            navController.navigate(R.id.settingsFragment)
        }

        val logoutButton = findViewById<TextView>(R.id.logoutButton)
        logoutButton?.setOnClickListener {
            // Detener servicio de música
            val stopServiceIntent = Intent(this, com.example.resonant.services.MusicPlaybackService::class.java).apply {
                action = com.example.resonant.services.MusicPlaybackService.ACTION_SHUTDOWN
            }
            startService(stopServiceIntent)

            // Cerrar sesión Firebase
            FirebaseAuth.getInstance().signOut()

            // Limpiar preferencias
            getSharedPreferences("Auth", MODE_PRIVATE).edit().clear().apply()
            getSharedPreferences("user_data", MODE_PRIVATE).edit().clear().apply()

            // Navegar al login
            val intent = Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
        }
    }

    private fun setupViewModelObservers() {
        favoritesViewModel.favoriteSongIds.observe(this) { favoriteIds ->
            val currentSong = songViewModel.currentSongLiveData.value
            val isFavorite = currentSong?.id?.let { favoriteIds.contains(it) } ?: false
            miniPlayerLikeButton.setImageResource(if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
        }

        songViewModel.currentSongLiveData.observe(this) { song ->
            val favoriteIds = favoritesViewModel.favoriteSongIds.value
            val isFavorite = song?.id?.let { favoriteIds?.contains(it) } ?: false
            miniPlayerLikeButton.setImageResource(if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border)

            if (song != null && shouldShowMiniPlayer && !playbackTransferredAway) {
                updateDataPlayer(song)
                AnimationsUtils.setMiniPlayerVisibility(true, miniPlayer, this)
                miniPlayerSwipeHandler.maybeShowHint()
            } else if (playbackTransferredAway || song == null) {
                AnimationsUtils.setMiniPlayerVisibility(false, miniPlayer, this)
            }
        }

        songViewModel.isPlayingLiveData.observe(this) { isPlaying ->
            updatePlayPauseButton(isPlaying)
            // When playback resumes locally (TRANSFER_IN or user confirmed
            // conflict dialog), clear the transfer-away flag so the
            // mini-player can reappear.
            if (isPlaying && playbackTransferredAway) {
                val state = latestConnectState
                val remoteIsActive = state?.activeDevice?.let {
                    it.deviceId != state.localDeviceId
                } ?: false
                if (!remoteIsActive) {
                    playbackTransferredAway = false
                    val currentSong = songViewModel.currentSongLiveData.value
                    if (currentSong != null && shouldShowMiniPlayer) {
                        updateDataPlayer(currentSong)
                        AnimationsUtils.setMiniPlayerVisibility(true, miniPlayer, this)
                    }
                }
            }
        }

        songViewModel.queueSourceLiveData.observe(this) { source ->
            updatePlaymixMode(source == QueueSource.PLAYMIX)
        }

        // Refresh the mini-player subtitle when Resonant Radio starts or
        // changes seed, so "Basado en X · Artist" appears/updates without
        // needing to wait for a song transition.
        PlaybackStateRepository.radioSessionLiveData.observe(this) { _ ->
            songViewModel.currentSongLiveData.value?.let(::updateDataPlayer)
        }

        songViewModel.playbackPositionLiveData.observe(this) { positionInfo ->
            if (positionInfo.duration > 0) {
                seekBar.max = positionInfo.duration.toInt()
                seekBar.progress = positionInfo.position
                    .coerceIn(0L, positionInfo.duration.toLong())
                    .toInt()
                seekBar.isEnabled = true
            } else {
                // Avoid rendering a restored position against SeekBar's default
                // max (100), which makes an unknown duration appear complete.
                seekBar.max = 1
                seekBar.progress = 0
                seekBar.isEnabled = false
            }
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
                        iconButtons = listOf(
                            miniPlayerLikeButton,
                            playPauseButton,
                            miniPlayerQueueButton,
                            swipeHintLeft,
                            swipeHintRight
                        ),
                        seekBar = seekBar,
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
        this.intent = intent
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent) {
        val data: Uri = intent.data ?: return
        if (data.host == "resonantapp.ddns.net") {
            val segments = data.pathSegments
            if (segments.isNotEmpty() && segments[0] == "song") {
                val songId = segments.getOrNull(1)
                if (songId != null) {
                    val bundle = Bundle().apply { putString("songId", songId) }
                    try {
                        navController.navigate(R.id.detailedSongFragment, bundle)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun updatePlayPauseButton(isPlaying: Boolean) {
        if (isPlaying) {
            playPauseButton.setImageResource(R.drawable.ic_pause)
        } else {
            playPauseButton.setImageResource(R.drawable.ic_play)
        }
    }

    private fun updatePlaymixMode(isPlaymix: Boolean) {
        val ring = findViewById<View>(R.id.playmixRing)
        if (isPlaymix) {
            playPauseButton.setBackgroundResource(R.drawable.bg_play_background_playmix)
            ring?.visibility = View.VISIBLE
            if (playmixRingAnimator == null || playmixRingAnimator?.isRunning == false) {
                playmixRingAnimator = ObjectAnimator.ofFloat(ring, "rotation", 0f, 360f).apply {
                    duration = 3000
                    repeatCount = ObjectAnimator.INFINITE
                    interpolator = android.view.animation.LinearInterpolator()
                }
                playmixRingAnimator?.start()
            }
        } else {
            playPauseButton.setBackgroundResource(R.drawable.bg_play_background)
            ring?.visibility = View.GONE
            playmixRingAnimator?.cancel()
            playmixRingAnimator = null
        }
    }

    fun updateDataPlayer(song: Song) {
        songName.text = song.title
            .removeSuffix(".mp3")
            .trim()

        val artistText = song.artistName
            ?.takeIf { it.isNotBlank() }
            ?: song.artists
                .joinToString(", ") { it.name }
                .takeIf { it.isNotBlank() }
            ?: getString(R.string.unknown_artist)

        // If Resonant Radio is running, prefix the subtitle so the user
        // knows autoplay is on and which seed is driving it. The observer
        // set up in setupViewModelObservers() re-invokes updateDataPlayer
        // when the radio session changes, so this stays in sync.
        val radioSubtitle = PlaybackStateRepository.radioSessionLiveData
            .value
            ?.let { session -> session.reasonText?.takeIf { it.isNotBlank() } }
        songArtist.text = if (radioSubtitle != null) {
            "$radioSubtitle · $artistText"
        } else {
            artistText
        }
    }

    companion object {
        private const val QUEUE_SHEET_TAG = "PlaybackQueueBottomSheet"
        private const val ARIA_SCRIM_COLLAPSED_ALPHA = 0x52
        private const val ARIA_SCRIM_EXPANDED_ALPHA = 0xA0

        private val CONNECT_EXCLUDED_FRAGMENTS = setOf(
            R.id.settingsFragment,
            R.id.createPlaylistFragment,
            R.id.editPlaylistFragment,
            R.id.createPlaymixFragment,
            R.id.playmixListFragment,
            R.id.playmixDetailFragment,
            R.id.crossfadeEditorFragment,
            R.id.collabSearchFragment,
            R.id.collabBubbleFragment,
            R.id.collabDetailFragment,
            R.id.collabPathFragment,
            R.id.songFragment
        )

        private val FRAGMENTS_NO_TOOLBAR_NO_BOTTOM_NAV = setOf(
            R.id.songFragment,
            R.id.collabSearchFragment
        )
    }

    private fun isConnectAllowedForCurrentDestination(destinationId: Int? = null): Boolean {
        if (!::navController.isInitialized) return true
        val destId = destinationId ?: navController.currentDestination?.id ?: return true
        if (destId in CONNECT_EXCLUDED_FRAGMENTS) return false
        if (destId in FRAGMENTS_NO_TOOLBAR_NO_BOTTOM_NAV) return false
        if (!shouldShowMiniPlayer) return false
        return true
    }

    private fun renderPlaybackDeviceState(state: PlaybackConnectUiState, destinationId: Int? = null) {
        if (!::playbackDeviceBar.isInitialized) return

        val isAllowed = isConnectAllowedForCurrentDestination(destinationId)
        if (!isAllowed) {
            playbackDeviceBar.visibility = View.GONE
            return
        }

        val active = state.activeDevice
        val remoteIsActive = active != null && active.deviceId != state.localDeviceId

        // Show the bar if:
        // 1. A remote device is currently playing (the user needs this to see where playback went and to get it back).
        // 2. OR Connect is confirmed supported, there are alternative devices,
        //    playback is not transferred away, and the mini-player is visible.
        val shouldShow = remoteIsActive ||
            (shouldShowMiniPlayer && !playbackTransferredAway &&
                state.supported && state.hasAlternativeDevice)

        if (!shouldShow) {
            playbackDeviceBar.visibility = View.GONE
            return
        }
        playbackDeviceText.text = when {
            remoteIsActive -> "Reproduciendo en ${active?.name}"
            state.hasAlternativeDevice -> "Elegir otro dispositivo"
            else -> "Este dispositivo"
        }
        playbackDeviceBar.visibility = View.VISIBLE
    }

    /**
     * Hides mini-player after a successful transfer and re-anchors
     * the device bar to sit just above the bottom navigation.
     */
    private fun hideMiniPlayerForTransfer() {
        AnimationsUtils.setMiniPlayerVisibility(false, miniPlayer, this)
    }

    private fun setupGlobalAria() {
        val ariaSettings = SettingsManager(applicationContext)
        ariaFloatingLauncher.setOnClickListener {
            showAriaQuickSheet(startVoice = false)
        }
        findViewById<View>(R.id.ariaFloatingMicButton).setOnClickListener {
            showAriaQuickSheet(startVoice = true)
        }
        ariaFloatingScrim.setOnClickListener {
            (supportFragmentManager.findFragmentByTag(AriaQuickSheet.TAG) as? AriaQuickSheet)
                ?.dismiss()
                ?: completeAriaQuickSheetDismissal()
        }
        supportFragmentManager.setFragmentResultListener(
            AriaQuickSheet.RESULT_DISMISSED,
            this
        ) { _, _ ->
            completeAriaQuickSheetDismissal()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ariaSettings.ariaFloatingAssistantEnabledFlow
                        .collect { enabled ->
                            ariaFloatingAssistantEnabled = enabled
                            updateAriaFloatingLauncher(currentDestinationId)
                        }
                }
                launch {
                    ariaSettings.ariaForegroundWakeWordEnabledFlow.collect { enabled ->
                        ariaForegroundWakeWordEnabled = enabled
                        updateAriaWakeWordListening()
                    }
                }
                launch {
                    ariaViewModel.actionStream.collect { payload ->
                        ariaViewModel.parseActionPayload(payload)
                            ?.takeIf(ariaViewModel::shouldExecuteClientAction)
                            ?.let(ariaClientActionExecutor::execute)
                    }
                }
                launch {
                    ariaViewModel.isStreaming.collect { streaming ->
                        ariaFloatingLauncher.isActivated = streaming
                        if (streaming) {
                            ariaFloatingLabel.text = "Pensando…"
                        } else {
                            updateAriaFloatingLabel(currentDestinationId)
                        }
                    }
                }
            }
        }
    }

    private fun showAriaQuickSheet(startVoice: Boolean) {
        if (
            ariaQuickSheetVisible ||
            isFinishing ||
            isDestroyed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) return
        ariaQuickSheetVisible = true
        ariaWakeWordController.stop()

        val fragment = AriaQuickSheet.newInstance(startVoice)
        fragment.onDismissRequest = dismissRequest@{
            if (isFinishing || isDestroyed) return@dismissRequest
            completeAriaQuickSheetDismissal()
        }
        fragment.onExpansionProgressChanged = ::renderAriaQuickSheetExpansion

        ariaFloatingScrim.animate().cancel()
        renderAriaQuickSheetExpansion(0f)
        ariaFloatingScrim.alpha = 0f
        ariaFloatingScrim.visibility = View.VISIBLE
        ariaFloatingScrim.animate()
            .alpha(1f)
            .setDuration(220L)
            .start()

        // Show the overlay container before adding the fragment so it is measured
        ariaFloatingOverlay.visibility = android.view.View.VISIBLE
        ariaFloatingOverlay.alpha = 0f
        ariaFloatingOverlay.translationY = ariaFloatingOverlay.height.toFloat().coerceAtLeast(200f)

        supportFragmentManager.beginTransaction()
            .replace(R.id.ariaFloatingOverlay, fragment, AriaQuickSheet.TAG)
            .commitAllowingStateLoss()

        // Animate slide-up + fade-in after layout
        ariaFloatingOverlay.post {
            ariaFloatingOverlay.translationY = ariaFloatingOverlay.height.toFloat().coerceAtLeast(200f)
            ariaFloatingOverlay.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(300)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun completeAriaQuickSheetDismissal() {
        ariaFloatingOverlay.animate().cancel()
        ariaFloatingOverlay.visibility = View.GONE
        ariaFloatingOverlay.alpha = 1f
        ariaFloatingOverlay.translationY = 0f

        ariaFloatingScrim.animate().cancel()
        ariaFloatingScrim.animate()
            .alpha(0f)
            .setDuration(160L)
            .withEndAction {
                ariaFloatingScrim.visibility = View.GONE
                ariaFloatingScrim.alpha = 1f
                renderAriaQuickSheetExpansion(0f)
            }
            .start()

        (supportFragmentManager.findFragmentByTag(AriaQuickSheet.TAG) as? AriaQuickSheet)
            ?.let { fragment ->
                supportFragmentManager.beginTransaction()
                    .remove(fragment)
                    .commitAllowingStateLoss()
            }
        ariaQuickSheetVisible = false
        updateAriaWakeWordListening()
    }

    private fun renderAriaQuickSheetExpansion(progress: Float) {
        val clampedProgress = progress.coerceIn(0f, 1f)
        val alpha = (
            ARIA_SCRIM_COLLAPSED_ALPHA +
                ((ARIA_SCRIM_EXPANDED_ALPHA - ARIA_SCRIM_COLLAPSED_ALPHA) * clampedProgress)
            ).toInt()
        ariaFloatingScrim.setBackgroundColor(Color.argb(alpha, 0, 0, 0))
    }

    private fun updateAriaWakeWordListening() {
        val destinationAllowsWakeWord = currentDestinationId != R.id.ariaFragment &&
            currentDestinationId != R.id.ariaInfoFragment &&
            currentDestinationId != R.id.settingsFragment
        val hasMicrophonePermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val shouldListen = activityIsForeground &&
            ariaForegroundWakeWordEnabled &&
            !ariaQuickSheetVisible &&
            destinationAllowsWakeWord &&
            hasMicrophonePermission

        if (shouldListen) ariaWakeWordController.start() else ariaWakeWordController.stop()
    }

    private fun updateAriaFloatingLauncher(destinationId: Int?) {
        updateAriaFloatingLabel(destinationId)
        val shouldShow = ariaFloatingAssistantEnabled &&
            destinationId != R.id.ariaFragment &&
            destinationId != R.id.ariaInfoFragment &&
            destinationId != R.id.settingsFragment
        if (shouldShow && ariaFloatingLauncher.visibility != View.VISIBLE) {
            ariaFloatingLauncher.alpha = 0f
            ariaFloatingLauncher.translationY = 16f * resources.displayMetrics.density
            ariaFloatingLauncher.visibility = View.VISIBLE
            ariaFloatingLauncher.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .start()
        } else if (!shouldShow && ariaFloatingLauncher.visibility == View.VISIBLE) {
            ariaFloatingLauncher.animate()
                .alpha(0f)
                .translationY(12f * resources.displayMetrics.density)
                .setDuration(160L)
                .withEndAction {
                    ariaFloatingLauncher.visibility = View.GONE
                    ariaFloatingLauncher.translationY = 0f
                }
                .start()
        }
    }

    private fun updateAriaFloatingLabel(destinationId: Int?) {
        if (::ariaViewModel.isInitialized && ariaViewModel.isStreaming.value) return
        ariaFloatingLabel.text = when (destinationId) {
            R.id.songFragment -> "¿Qué quieres hacer?"
            R.id.playlistFragment,
            R.id.artistFragment,
            R.id.detailedArtistFragment,
            R.id.albumFragment,
            R.id.detailedAlbumFragment,
            R.id.detailedSongFragment -> "Pregunta por esto"
            R.id.settingsFragment -> "Estoy aquí"
            else -> "¿Alguna pregunta?"
        }
    }

    fun openDrawer() {
        if (::drawerLayout.isInitialized) {
            drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    override fun onStart() {
        super.onStart()
        // Announce this device's presence immediately so other devices (e.g.
        // the Xiaomi) see the emulator in their picker without waiting for the
        // next 4s heartbeat from MusicPlaybackService.
        playbackConnectRepository.announcePresenceAsync()
        playbackConnectRepository.refreshAsync()
    }

    override fun onStop() {
        ariaWakeWordController.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        activityIsForeground = true
        updateAriaWakeWordListening()
    }

    override fun onPause() {
        activityIsForeground = false
        ariaWakeWordController.stop()
        super.onPause()
    }

    override fun onDestroy() {
        ariaWakeWordController.release()
        super.onDestroy()
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
                startMusicService()
            } else {
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

        lifecycleScope.launch {
            try {
                val userData = userService.getCurrentUser()
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

            } catch (e: Exception) {
                Log.w("MainActivity", "No se pudo verificar el ban status (offline?): ${e.message}")
            }
        }
    }

    private fun getProfileImage() {
        // Aseguramos que el drawer esté listo
        if (!::drawerLayout.isInitialized) return

        val headerUserName = drawerLayout.findViewById<TextView>(R.id.headerUserName)
        val headerUserPhoto = drawerLayout.findViewById<ShapeableImageView>(R.id.headerUserPhoto)

        val localFileName = "profile_user.png"
        val file = File(filesDir, localFileName)

        val user = FirebaseAuth.getInstance().currentUser

        val name = user?.displayName ?: prefs.getString("name", "Invitado")
        headerUserName.text = name
        if (user?.displayName != null) {
            prefs.edit().putString("name", name).apply()
        }

        lifecycleScope.launch(Dispatchers.IO) {
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

    private fun animateBottomNavIcon(itemId: Int) {
        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        val menuItem = bottomNavigationView.menu.findItem(itemId) ?: return
        val itemView = bottomNavigationView.findViewById<View>(itemId) ?: return

        // Pulse animation: scale 1.0 → 0.90 → 1.0 (icon + label)
        itemView.animate()
            .scaleX(0.90f)
            .scaleY(0.90f)
            .setDuration(150)
            .withEndAction {
                itemView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150)
                    .start()
            }
            .start()
    }

}
