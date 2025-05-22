package com.example.spomusicapp

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ImageButton
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
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.credentials.CredentialManager
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import kotlinx.coroutines.Dispatchers
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import androidx.core.net.toUri
import com.google.gson.Gson
import java.io.File


class ActivitySongList : AppCompatActivity() {

    lateinit var recyclerView: RecyclerView
    lateinit var songAdapter: SongAdapter

    //private lateinit var playPauseButton: ImageButton
    //private lateinit var previousSongButton: ImageButton
    //private lateinit var nextSongButton: ImageButton

    //private var isPlaying = false
    //private var updateSeekBarRunnable: Runnable? = null
    //private val handler = Handler(Looper.getMainLooper())

    private val songRepository = SongRepository()

    private lateinit var settingsButton: ImageButton
    private lateinit var auth: FirebaseAuth
    private lateinit var credentialManager: CredentialManager

    private var shouldStopMusic = true

    private var currentOffset = 0
    private var isLoading = false
    private var hasMoreItems = true
    private val songList = mutableListOf<Song>()

    //private lateinit var loadingAnimation: ImageView
    private lateinit var searchSongsButton: ImageButton
    //private lateinit var updateSongListButton: Button

    //private lateinit var songImage: ImageView
    //private lateinit var songName: TextView
    //private lateinit var songArtist: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_song_list)
        enableEdgeToEdge()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        checkUpdate()

        showAdviseDialog()

        recyclerView = findViewById<RecyclerView>(R.id.allSongList)
        recyclerView.layoutManager = LinearLayoutManager(this)
        songAdapter = SongAdapter()
        recyclerView.adapter = songAdapter

        //playPauseButton = findViewById(R.id.playPauseButton)
        //previousSongButton = findViewById(R.id.previousSongButton)
        //nextSongButton = findViewById(R.id.nextSongButton)

        //songImage = findViewById(R.id.song_image)
        //songName = findViewById(R.id.song_title)
        //songArtist = findViewById(R.id.song_artist)

        auth = Firebase.auth
        credentialManager = CredentialManager.create(baseContext)
        settingsButton = findViewById(R.id.settingsButton)

        //loadingAnimation = findViewById<ImageView>(R.id.loadingAnimation)
        searchSongsButton = findViewById(R.id.searchSongsButton)
        //updateSongListButton = findViewById(R.id.updateSongListButton)

        /*
        playPauseButton.setOnClickListener {
            if (isPlaying) {
                MediaPlayerManager.pause()
            } else {
                MediaPlayerManager.resume()
            }
            isPlaying = !isPlaying
            updatePlayPauseButton(isPlaying)
        }
        */
        /*
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
        */
        val sharedPref = this@ActivitySongList.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        val savedUrl = sharedPref.getString("current_playing_url", null)

        val sharedPreferences = getSharedPreferences("song_cache", Context.MODE_PRIVATE)
        val cachedSongsJson = sharedPreferences.getString("cached_songs", "[]")
        val cachedSongs = Gson().fromJson(cachedSongsJson, Array<Song>::class.java).toList()

        val savedTitle = sharedPref.getString("current_playing_title", "")
        val savedArtist = sharedPref.getString("current_playing_artist", "")
        val savedAlbum = sharedPref.getString("current_playing_album", "")
        val savedDuration = sharedPref.getString("current_playing_duration", "")
        val savedImage = sharedPref.getString("current_playing_image", null)  // Recupera la imagen
        /*
        if (savedUrl != null) {
            // Crear un objeto Song con los datos recuperados
            val song = Song(
                title = savedTitle ?: "",
                url = savedUrl,
                artistName = savedArtist,
                albumName = savedAlbum,
                duration = savedDuration,
                localCoverPath = savedImage // Asigna la imagen
            )

            // Actualizar la UI con la canción actual
            //updateDataPlayer(song)
        }
        */
        songAdapter.setCurrentPlayingSong(savedUrl)

        //isPlaying = MediaPlayerManager.isPlaying()
        //updatePlayPauseButton(isPlaying)

        songAdapter.onItemClick = { (song, imageUri) ->
            val index = songAdapter.currentList.indexOf(song)
            songAdapter.setCurrentPlayingSong(song.url)

            if (imageUri != null) {
                songAdapter.imageUriCache[song.url] = imageUri
                sharedPref.edit() { putString("current_playing_image_uri", imageUri.toString()) }
            }

            sharedPref.edit().apply {
                putString("current_playing_url", song.url)
                putString("current_playing_title", song.title)
                putString("current_playing_artist", song.artistName)
                putString("current_playing_album", song.albumName)
                putString("current_playing_duration", song.duration)
                putString("current_playing_image", song.localCoverPath)  // Aquí guardas la imagen también
                apply()
            }
            /*
            if (index != -1) {
                //updateDataPlayer(song)
                //songName.isSelected = true
                //songArtist.isSelected = true
                PlaybackManager.playSongAt(this, index)
                //isPlaying = true
                //updatePlayPauseButton(isPlaying)
                NotificationManagerHelper.createNotificationChannel(this)
                NotificationManagerHelper.updateNotification(this)
            }
            */
            songAdapter.notifyItemChanged(index)

        }

        //val songDataPlayer = findViewById<FrameLayout>(R.id.songDataPlayer)
        /*
        songDataPlayer.setOnClickListener {
            val currentSong = PlaybackManager.getCurrentSong()
            if (currentSong != null) {

                val intent = Intent(this@ActivitySongList, SongActivity::class.java).apply {
                    putExtra("title", currentSong.title)
                    putExtra("artist", currentSong.artist)
                    putExtra("album", currentSong.album)
                    putExtra("duration", currentSong.duration)
                    putExtra("url", currentSong.url)
                    putExtra("coverFileName", currentSong.localCoverPath)
                }
                startActivity(intent)
            }
        }
        */
        /*
        if (cachedSongs.isNotEmpty()) {
            songList.addAll(cachedSongs)
            currentOffset = SongCache.currentOffset
            hasMoreItems = SongCache.hasMoreSongs
            songAdapter.submitList(songList.toList())
            PlaybackManager.updateSongs(songList)

            //loadingAnimation.visibility = View.GONE
            isLoading = false
        } else {
            loadSongs()
        }
        */
        /*
        updateSongListButton.setOnClickListener {
            reloadSongs()
        }
        */
        settingsButton.setOnClickListener {
            goToSettingsActivity()
        }

    }
    /*
        private fun reloadSongs() {
            loadSongs(clearList = true)
        }

        private fun loadSongs(clearList: Boolean = true) {
            if (isLoading) return

            isLoading = true
            //loadingAnimation.visibility = ImageView.VISIBLE

            lifecycleScope.launch {
                val songs = songRepository.fetchSongs()

                songs?.let {
                    val enrichedSongs = it.map { song ->
                        async(Dispatchers.IO) { Utils.enrichSong(this@ActivitySongList, song) }
                    }.awaitAll().filterNotNull()

                    if (clearList) {
                        songList.clear()
                    }
                    songList.addAll(enrichedSongs)

                    preloadSongsInBackground(this@ActivitySongList, songList)

                    songAdapter.submitList(songList.toList())
                    PlaybackManager.updateSongs(songList)

                    val sharedPreferences = getSharedPreferences("song_cache", MODE_PRIVATE)
                    sharedPreferences.edit() {
                        val json = Gson().toJson(songList)
                        putString("cached_songs", json)
                    }

                    SongCache.cachedSongs = songList.toList()
                    hasMoreItems = false

                } ?: run {
                    Toast.makeText(this@ActivitySongList, "Error al obtener las canciones", Toast.LENGTH_SHORT).show()
                }

                //loadingAnimation.visibility = ImageView.GONE
                isLoading = false
            }
        }
        */
    suspend fun downloadAndCacheSong(context: Context, song: Song) {
        try {
            val file = Utils.cacheSongIfNeeded(context, song.url)

            Log.d("Cache", "Canción guardada en caché: ${song.title}")
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("Cache", "Error al intentar descargar y guardar la canción ${song.title}", e)
        }
    }

    private fun preloadSongsInBackground(context: Context, songs: List<Song>) {
        lifecycleScope.launch(Dispatchers.IO) { // Usamos lifecycleScope para evitar fugas de memoria
            songs.forEach { song ->
                val cachedFile = File(context.cacheDir, "cached_${song.title}.mp3")
                if (!cachedFile.exists()) {
                    downloadAndCacheSong(context, song)
                }

                if (!songAdapter.bitmapCache.containsKey(song.url)) {
                    val bitmap = songAdapter.getEmbeddedPictureFromUrl(context, song.url)
                    bitmap?.let {
                        songAdapter.bitmapCache[song.url] = it
                        val uri = songAdapter.saveBitmapToCache(context, it, "album_${song.title}.png")
                        if (uri != null) {
                            songAdapter.imageUriCache[song.url] = uri
                        }
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permiso de notificaciones concedido", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No se podrán mostrar notificaciones", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /*
    private fun updatePlayPauseButton(isPlaying: Boolean) {
        if (isPlaying) {
            playPauseButton.setImageResource(R.drawable.pause)
        } else {
            playPauseButton.setImageResource(R.drawable.play_arrow_filled)
        }
    }
    */
    /*
    private fun updateDataPlayer(song: Song) {
        songName.text = song.title
            .removeSuffix(".mp3")
            .replace(Regex("\\s*\\([^)]*\\)"), "")
            .replace("-", "–")
            .trim()

        //songArtist.text = song.artist ?: "Desconocido"
        //Utils.getImageSongFromCache(song, this@ActivitySongList, songImage, song.localCoverPath.toString())
    }
    */
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

    private fun showAdviseDialog(){
        if (!Utils.hasShownDialog(this)) {
            val dialog = AdviseDialog()
            dialog.show(supportFragmentManager, "MyDialogFragment")
            Utils.setDialogShown(this)
        }
    }

    private fun goToSettingsActivity(){
        val intent = Intent(this@ActivitySongList, SettingsActivity::class.java)
        startActivity(intent)
    }
    /*
    override fun onResume() {
        super.onResume()

        val sharedPref = getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        val savedIndex = sharedPref.getInt("current_playing_index", -1)

        if (savedIndex != -1 && savedIndex in PlaybackManager.songs.indices) {
            val currentSong = PlaybackManager.songs[savedIndex]
            //isPlaying = MediaPlayerManager.isPlaying()
            //updateDataPlayer(currentSong)
            /*
            playPauseButton.setOnClickListener {
                if (isPlaying) {
                    MediaPlayerManager.pause()
                } else {
                    PlaybackManager.playSongAt(this@ActivitySongList, savedIndex)
                }
                isPlaying = !isPlaying
                //updatePlayPauseButton(isPlaying)
            }
            */

        }
    }

    /*
    override fun onDestroy() {
        super.onDestroy()
        updateSeekBarRunnable?.let { handler.removeCallbacks(it) }
        if (shouldStopMusic) {
            MediaPlayerManager.stop()
        }
    }
    */
    override fun onBackPressed() {
        super.onBackPressed()
        shouldStopMusic = false
        moveTaskToBack(true)
    }
º   */


    /*
    private fun loadSongs(clearList: Boolean = true) {
        if (isLoading) return

        isLoading = true
        //loadingAnimation.visibility = ImageView.VISIBLE

        lifecycleScope.launch {
            val songs = songRepository.fetchSongs()

            songs?.let {
                val enrichedSongs = it.map { song ->
                    async(Dispatchers.IO) { Utils.enrichSong(requireContext(), song) }
                }.awaitAll().filterNotNull()

                if (clearList) {
                    songList.clear()
                }
                songList.addAll(enrichedSongs)

                preloadSongsInBackground(requireContext(), songList)

                songAdapter.submitList(songList.toList())
                PlaybackManager.updateSongs(songList)

                val sharedPreferences = requireContext().getSharedPreferences("song_cache", MODE_PRIVATE)
                sharedPreferences.edit() {
                    val json = Gson().toJson(songList)
                    putString("cached_songs", json)
                }

                SongCache.cachedSongs = songList.toList()
                hasMoreItems = false

            } ?: run {
                Toast.makeText(requireContext(), "Error al obtener las canciones", Toast.LENGTH_SHORT).show()
            }

            //loadingAnimation.visibility = ImageView.GONE
            isLoading = false
        }
    }
    */

}
