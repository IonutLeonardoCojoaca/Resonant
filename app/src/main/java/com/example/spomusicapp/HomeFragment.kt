package com.example.spomusicapp

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
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.spomusicapp.ActivitySongList
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
    private var artistsList: MutableList<Artist> = mutableListOf() // Lista de artistas

    lateinit var recyclerViewSongs: RecyclerView
    lateinit var songAdapter: SongAdapter
    private val songRepository = SongRepository()

    private var isPlaying = false

    private var currentOffset = 0
    private var isLoading = false
    private var hasMoreItems = true
    private val songList = mutableListOf<Song>()

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

        PlaybackManager.addUIListener(this)

        if (savedUrl != null) {
            // Crear un objeto Song con los datos recuperados
            val song = Song(
                title = savedTitle ?: "",
                url = savedUrl,
                artist = savedArtist,
                album = savedAlbum,
                duration = savedDuration,
                localCoverPath = savedImage // Asigna la imagen
            )

            // Actualizar la UI con la canción actual
            //updateDataPlayer(song)
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
                putString("current_playing_artist", song.artist)
                putString("current_playing_album", song.album)
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

        if (cachedSongs.isNotEmpty()) {
            songList.clear()
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

        getProfileImage()
        loadArtists()

        return view
    }

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

                    // Log para ver los datos
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
