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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    private val songRepository = SongRepository()

    private var isPlaying = false

    private var isLoading = false
    private var hasMoreItems = true
    private val songList = mutableListOf<Song>()

    private lateinit var rechargeSongs: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        val sharedPref = requireActivity().getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        val savedUrl = sharedPref.getString("current_playing_url", null)

        val sharedPreferences = requireContext().getSharedPreferences("song_cache", Context.MODE_PRIVATE)
        val cachedSongsJson = sharedPreferences.getString("cached_songs", "[]")
        val cachedSongs = Gson().fromJson(cachedSongsJson, Array<Song>::class.java).toList()

        val savedTitle = sharedPref.getString("current_playing_title", "")
        val savedArtist = sharedPref.getString("current_playing_artist", "")
        val savedAlbum = sharedPref.getString("current_playing_album", "")
        val savedDuration = sharedPref.getString("current_playing_duration", "")
        val savedImage = sharedPref.getString("current_playing_image", null)  // Recupera la imagen

        userPhotoImage = view.findViewById(R.id.userProfile)
        prefs = requireContext().getSharedPreferences("user_data", AppCompatActivity.MODE_PRIVATE)

        recyclerViewArtists = view.findViewById(R.id.listArtistsRecycler)
        recyclerViewArtists.layoutManager = GridLayoutManager(context, 3)
        artistAdapter = ArtistAdapter(artistsList)
        recyclerViewArtists.adapter = artistAdapter

        recyclerViewSongs = view.findViewById<RecyclerView>(R.id.allSongList)
        recyclerViewSongs.layoutManager = LinearLayoutManager(requireContext())
        songAdapter = SongAdapter()
        recyclerViewSongs.adapter = songAdapter

        rechargeSongs = view.findViewById<ImageButton>(R.id.rechargeSongs)

        PlaybackManager.addUIListener(this)

        if (savedUrl != null) {
            val song = Song(
                title = savedTitle ?: "",
                url = savedUrl,
                artistName = savedArtist,
                albumName = savedAlbum,
                duration = savedDuration,
                localCoverPath = savedImage
            )
        }

        songAdapter.setCurrentPlayingSong(savedUrl)

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
                putString("current_playing_image", song.localCoverPath)
                apply()
            }

            if (index != -1) {
                PlaybackManager.playSongAt(requireContext(), index)
                isPlaying = true
                (requireActivity() as? MainActivity)?.updatePlayerUI(song, isPlaying)
                NotificationManagerHelper.createNotificationChannel(requireContext())
            }

            songAdapter.notifyItemChanged(index)
        }

        rechargeSongs.setOnClickListener {
            reloadSongs()
        }

        if(cachedSongs.isNotEmpty()){
            reloadSongs()
        }

        initSongs()

        getProfileImage()

        loadArtists()

        return view
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

                    val randomSixArtists = allArtists.shuffled().take(9)
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

    private fun reloadSongs() {
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

                    preloadSongsInBackground(requireContext(), songList)

                    songAdapter.submitList(songList.toList())
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
            }
        }
    }

    private fun loadSongs(callback: (List<Song>) -> Unit) {
        val firestore = FirebaseFirestore.getInstance()
        val songRef = firestore.collection("songs")

        songRef.get()
            .addOnSuccessListener { result ->
                val allSongs = result.documents.mapNotNull { it.toObject(Song::class.java) }
                val randomSongs = allSongs.shuffled().take(7)

                Log.d("SongRepository", "Canciones aleatorias cargadas: ${randomSongs.size}")
                randomSongs.forEach { song ->
                    Log.d("SongRepository", "Título: ${song.title}")
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
        }
    }

    private fun getSongsFromCache(cachedSongs: List<Song>){
        songList.clear()
        songList.addAll(cachedSongs)
        songAdapter.submitList(songList.toList())
        PlaybackManager.updateSongs(songList)
        isLoading = false
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

    private fun saveCurrentSong (){

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

    override fun onDestroyView() {
        super.onDestroyView()
        PlaybackManager.clearUIListener() // Importante evitar fugas
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
