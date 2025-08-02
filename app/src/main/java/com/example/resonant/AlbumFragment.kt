package com.example.resonant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import kotlinx.coroutines.launch

class AlbumFragment : Fragment() {

    private lateinit var arrowGoBackButton: ImageButton

    private lateinit var albumName: TextView
    private lateinit var albumArtistName: TextView
    private lateinit var backgroundImage: ImageView
    private lateinit var albumDuration: TextView
    private lateinit var albumNumberOfTracks: TextView
    private lateinit var recyclerViewSongs: RecyclerView
    private lateinit var songAdapter: SongAdapter
    private var songList: List<Song>? = null
    private lateinit var sharedViewModel: SharedViewModel

    private lateinit var api: ApiResonantService

    private val songChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MusicPlaybackService.ACTION_SONG_CHANGED) {
                val song = intent.getParcelableExtra<Song>(MusicPlaybackService.EXTRA_CURRENT_SONG)
                song?.let {
                    songAdapter.setCurrentPlayingSong(it.id)
                    sharedViewModel.setCurrentSong(it)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_album, container, false)

        arrowGoBackButton = view.findViewById(R.id.arrowGoBackButton)
        recyclerViewSongs = view.findViewById(R.id.albumSongsContainer)
        recyclerViewSongs.layoutManager = LinearLayoutManager(requireContext())
        songAdapter = SongAdapter()
        recyclerViewSongs.adapter = songAdapter

        albumName = view.findViewById(R.id.albumName)
        albumArtistName = view.findViewById(R.id.albumArtistName)
        backgroundImage = view.findViewById(R.id.artistImage)
        albumDuration = view.findViewById(R.id.albumDuration)
        albumNumberOfTracks = view.findViewById(R.id.albumNumberOfTracks)

        val albumId = arguments?.getString("albumId") ?: return view

        api = ApiClient.getService(requireContext())
        loadAlbumDetails(albumId)

        sharedViewModel = ViewModelProvider(requireActivity())[SharedViewModel::class.java]

        sharedViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            currentSong?.let {
                songAdapter.setCurrentPlayingSong(it.id)
            }
        }

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            songChangedReceiver,
            IntentFilter(MusicPlaybackService.ACTION_SONG_CHANGED)
        )

        songAdapter.onItemClick = { (song, bitmap) ->
            val currentIndex = songAdapter.currentList.indexOfFirst { it.url == song.url }
            val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, song.id) }
            val songList = ArrayList(songAdapter.currentList)

            val playIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_PLAY
                putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, song)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, currentIndex)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, songList)
            }

            requireContext().startService(playIntent)
        }

        arrowGoBackButton.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        return view
    }

    private fun loadAlbumDetails(albumId: String) {
        lifecycleScope.launch {
            try {
                val album = api.getAlbumById(albumId)
                val albumImageUrl = api.getAlbumUrl(album.fileName).url
                val artistName = album.artistName ?: run {
                    val artistList = api.getArtistsByAlbumId(albumId)
                    artistList.firstOrNull()?.name ?: "Artista desconocido"
                }

                albumName.text = album.title ?: "Sin título"
                albumArtistName.text = artistName
                Picasso.get().load(albumImageUrl).into(backgroundImage)
                albumDuration.text = Utils.formatDuration(album.duration)
                albumNumberOfTracks.text = "${album.numberOfTracks} canciones"

                val songs = getSongsFromAlbum(requireContext(), albumId)
                songAdapter.submitList(songs)

            } catch (e: Exception) {
                Log.e("AlbumFragment", "Error al cargar los detalles del álbum", e)
                Toast.makeText(requireContext(), "Error al cargar el álbum", Toast.LENGTH_SHORT).show()
            }
        }
    }

    suspend fun getSongsFromAlbum(
        context: Context,
        albumId: String,
        cancionesAnteriores: List<Song>? = null
    ): List<Song> {
        return try {
            val service = ApiClient.getService(context)
            val cancionesDelAlbum = service.getSongsByAlbumId(albumId).toMutableList()

            for (song in cancionesDelAlbum) {
                val artistList = service.getArtistsBySongId(song.id)
                song.artistName = artistList.joinToString(", ") { it.name }

                val cachedSong = cancionesAnteriores?.find { it.id == song.id }
                if (cachedSong != null) {
                    song.url = cachedSong.url
                }
            }

            val cancionesSinUrl = cancionesDelAlbum.filter { it.url == null }
            if (cancionesSinUrl.isNotEmpty()) {
                val fileNames = cancionesSinUrl.map { it.fileName }
                val urlList = service.getMultipleSongUrls(fileNames)
                val urlMap = urlList.associateBy { it.fileName }

                cancionesSinUrl.forEach { song ->
                    song.url = urlMap[song.fileName]?.url
                }
            }

            val updateIntent = Intent(context, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.UPDATE_SONGS
                putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, ArrayList(cancionesDelAlbum))
            }
            context.startService(updateIntent)

            cancionesDelAlbum
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }


}