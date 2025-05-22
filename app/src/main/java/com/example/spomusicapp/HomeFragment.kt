package com.example.spomusicapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.facebook.shimmer.Shimmer
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL
import kotlin.concurrent.thread

class HomeFragment : Fragment(), PlaybackUIListener {

    private lateinit var userPhotoImage: ImageView
    private lateinit var prefs: SharedPreferences
    private lateinit var recyclerViewArtists: RecyclerView
    private lateinit var artistAdapter: ArtistAdapter
    private var artistsList: MutableList<Artist> = mutableListOf()

    lateinit var recyclerViewSongs: RecyclerView
    lateinit var songAdapter: SongAdapter

    private var isPlaying = false

    private var isLoading = false
    private var hasMoreItems = true
    private val songList = mutableListOf<Song>()

    private lateinit var rechargeSongs: ImageButton

    private lateinit var shimmerLayout: ShimmerFrameLayout

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (songList.isEmpty()) {
            initSongs()
        } else {
            hideShimmer()
            songAdapter.submitList(songList.toList())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        val sharedPref = requireActivity().getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        val savedUrl = sharedPref.getString("current_playing_url", null)

        userPhotoImage = view.findViewById(R.id.userProfile)
        prefs = requireContext().getSharedPreferences("user_data", AppCompatActivity.MODE_PRIVATE)

        recyclerViewArtists = view.findViewById(R.id.listArtistsRecycler)
        recyclerViewArtists.layoutManager = GridLayoutManager(context, 3)
        artistAdapter = ArtistAdapter(artistsList)
        recyclerViewArtists.adapter = artistAdapter

        recyclerViewSongs = view.findViewById(R.id.allSongList)
        recyclerViewSongs.layoutManager = LinearLayoutManager(requireContext())
        songAdapter = SongAdapter()
        recyclerViewSongs.adapter = songAdapter
        shimmerLayout = view.findViewById(R.id.shimmerLayout)

        rechargeSongs = view.findViewById(R.id.rechargeSongs)

        PlaybackManager.addUIListener(this)

        songAdapter.setCurrentPlayingSong(savedUrl)

        songAdapter.onItemClick = { (song, imageUri) ->
            val index = songAdapter.currentList.indexOf(song)
            songAdapter.setCurrentPlayingSong(song.url)
            if (imageUri != null) {
                songAdapter.imageUriCache[song.url] = imageUri
                sharedPref.edit() { putString("current_playing_image_uri", imageUri.toString()) }
            }

            sharedPref.edit().apply {
                putString(PreferenceKeys.CURRENT_SONG_ID, song.id)
                putString("current_song_url", song.url)
                putString("current_song_title", song.title)
                putString("current_song_artist", song.artistName)
                putString("current_song_album", song.albumName)
                putString("current_song_duration", song.duration)
                putString("current_playing_image", song.localCoverPath)
                putBoolean("is_playing", true)
                putInt("current_song_index", index)
                apply()
            }

            if (index != -1) {
                PlaybackManager.updateSongs(songAdapter.currentList)
                PlaybackManager.playSong(requireContext(), song)
                isPlaying = true
                (requireActivity() as? MainActivity)?.updatePlayerUI(song, isPlaying)
                NotificationManagerHelper.createNotificationChannel(requireContext())
            }

            songAdapter.notifyItemChanged(index)
        }

        rechargeSongs.setOnClickListener {
            reloadSongs()
        }

        getProfileImage()

        loadArtists()

        return view
    }

    private fun reloadSongs() {
        showShimmer(true)
        recyclerViewSongs.visibility = View.GONE   // Ocultar el listado viejo

        loadSongs { randomSongs ->
            if (randomSongs.isNotEmpty()) {
                val enrichedSongsDeferred = randomSongs.map { song ->
                    lifecycleScope.async(Dispatchers.IO) {
                        Utils.enrichSong(requireContext(), song)
                    }
                }
                lifecycleScope.launch {
                    val enrichedSongs = enrichedSongsDeferred.awaitAll().filterNotNull()

                    songList.clear()
                    songList.addAll(enrichedSongs)

                    val safeCopy = songList.toList()
                    preloadSongsInBackground(requireContext(), safeCopy)

                    val shimmerStart = System.currentTimeMillis()

                    songAdapter.submitList(songList.toList()) {
                        val elapsed = System.currentTimeMillis() - shimmerStart
                        val remaining = (700 - elapsed).coerceAtLeast(0)

                        recyclerViewSongs.visibility = View.INVISIBLE

                        lifecycleScope.launch {
                            delay(remaining)
                            hideShimmer()
                            val controller = AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_animation_fade_slide)
                            recyclerViewSongs.layoutAnimation = controller
                            recyclerViewSongs.scheduleLayoutAnimation()
                            recyclerViewSongs.visibility = View.VISIBLE
                        }
                    }
                    PlaybackManager.updateSongs(songList)

                    val sharedPreferences = requireContext().getSharedPreferences("song_cache", MODE_PRIVATE)
                    sharedPreferences.edit {
                        val json = Gson().toJson(songList)
                        putString("cached_songs", json)
                    }

                    SongCache.cachedSongs = songList.toList()
                    hasMoreItems = false

                }
            } else {
                Toast.makeText(requireContext(), "Error al obtener las canciones", Toast.LENGTH_SHORT).show()
                hideShimmer()
                recyclerViewSongs.visibility = View.VISIBLE
            }
        }
    }


    private fun loadSongs(callback: (List<Song>) -> Unit) {
        val firestore = FirebaseFirestore.getInstance()
        val songRef = firestore.collection("songs")

        songRef.get()
            .addOnSuccessListener { result ->
                val allSongs = result.documents.mapNotNull {
                    it.toObject(Song::class.java)
                }
                val randomSongs = allSongs.shuffled().take(7)

                randomSongs.forEach { song ->
                    Log.d("SongRepository", "Id: ${song.id}")
                }

                Log.d("SongRepository", "Canciones aleatorias cargadas: ${randomSongs.size}")
                randomSongs.forEach { song ->
                    Log.d("SongRepository", "Id: ${song.id}")
                }

                callback(randomSongs)
            }
            .addOnFailureListener { exception ->
                Log.e("SongRepository", "Error fetching random songs: ${exception.message}")
                callback(emptyList())
            }
    }

    private fun initSongs() {
        val sharedPreferences = requireContext().getSharedPreferences("song_cache", MODE_PRIVATE)
        val json = sharedPreferences.getString("cached_songs", null)
        val cachedSongs = json?.let {
            Gson().fromJson(it, Array<Song>::class.java).toList()
        }
        if (cachedSongs.isNullOrEmpty()) {
            reloadSongs()
        } else {
            getSongsFromCache(cachedSongs)
            showShimmer(false)
            recyclerViewSongs.visibility = View.VISIBLE
        }
    }

    private fun getSongsFromCache(cachedSongs: List<Song>){
        songList.clear()
        songList.addAll(cachedSongs)
        songAdapter.submitList(songList.toList())
        PlaybackManager.updateSongs(songList)
        isLoading = false
        hideShimmer()
    }

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
        lifecycleScope.launch(Dispatchers.IO) {
            songs.forEach { song ->
                val cachedFile = File(context.cacheDir, "cached_${song.title}.mp3")
                if (!cachedFile.exists()) {
                    downloadAndCacheSong(context, song)
                }

                synchronized(songAdapter.bitmapCache) {
                    if (!songAdapter.bitmapCache.containsKey(song.url)) {
                        val bitmap = songAdapter.getEmbeddedPictureFromUrl(context, song.url)
                        bitmap?.let {
                            songAdapter.bitmapCache[song.url] = it

                            synchronized(songAdapter.imageUriCache) {
                                val uri = songAdapter.saveBitmapToCache(context, it, "album_${song.title}.png")
                                if (uri != null) {
                                    songAdapter.imageUriCache[song.url] = uri
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadArtists() {
        val firestore = FirebaseFirestore.getInstance()
        val artistRef = firestore.collection("artist")

        if (artistsList.isEmpty()) {
            artistRef.get()
                .addOnSuccessListener { result ->
                    val allArtists = result.documents.mapNotNull { document ->
                        document.toObject(Artist::class.java)
                    }

                    val randomSixArtists = allArtists.shuffled().take(6)
                    artistsList.clear()
                    artistsList.addAll(randomSixArtists)

                    Log.d("HomeFragment", "Artistas cargados: ${artistsList.size}")
                    artistsList.forEach { artist ->
                        Log.d("HomeFragment", "Nombre del artista: ${artist.name}")
                    }

                    artistAdapter.notifyDataSetChanged()
                }
                .addOnFailureListener { exception ->
                    println("Error al obtener artistas: ${exception.message}")
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
                Toast.makeText(requireContext(), "Permiso de notificaciones concedido", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "No se podrán mostrar notificaciones", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun showShimmer(show: Boolean) {
        if (show) {
            shimmerLayout.visibility = View.VISIBLE
            shimmerLayout.startShimmer()
        } else {
            shimmerLayout.stopShimmer()
            shimmerLayout.visibility = View.GONE
        }
    }

    private fun hideShimmer() {
        shimmerLayout.stopShimmer()
        shimmerLayout.visibility = View.GONE
        recyclerViewSongs.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        PlaybackManager.clearUIListener()
    }

    override fun onResume() {
        super.onResume()
        PlaybackManager.addUIListener(this)
    }

    override fun onPause() {
        super.onPause()
        PlaybackManager.clearUIListener()
    }

    private fun getProfileImage() {
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
        if (urlPhoto != null) {
            thread {
                try {
                    val inputStream = URL(urlPhoto).openStream()
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    activity?.runOnUiThread {
                        userPhotoImage.setImageBitmap(bitmap)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        this.isPlaying = isPlaying
    }

    override fun onSongChanged(song: Song, isPlaying: Boolean) {
        songAdapter.setCurrentPlayingSong(song.url)
        this.isPlaying = isPlaying
        val index = songAdapter.currentList.indexOfFirst { it.url == song.url }
        if (index != -1) {
            songAdapter.notifyItemChanged(index)
        }
        (requireActivity() as? MainActivity)?.updatePlayerUI(song, isPlaying)
    }


}
