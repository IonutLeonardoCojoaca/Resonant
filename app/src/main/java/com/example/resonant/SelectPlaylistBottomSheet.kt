package com.example.resonant

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.SnackbarUtils.showResonantSnackbar
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SelectPlaylistBottomSheet(
    private val song: Song
) : BottomSheetDialogFragment() {

    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var playlistRecyclerView: RecyclerView
    private lateinit var noPlaylistTextView: TextView
    private lateinit var selectPlaylistText: TextView
    private lateinit var songImage: ShapeableImageView
    private lateinit var songTitle: TextView
    private lateinit var songArtist: TextView
    private lateinit var songStreams: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.bottom_sheet_playlists_selector, container, false)

        songImage = view.findViewById(R.id.songImage)
        songTitle = view.findViewById(R.id.songTitle)
        songArtist = view.findViewById(R.id.songArtist)
        songStreams = view.findViewById(R.id.songStreams)

        noPlaylistTextView = view.findViewById(R.id.noPlaylistTextView)
        selectPlaylistText = view.findViewById(R.id.selectPlaylistText)

        playlistRecyclerView = view.findViewById(R.id.playlistList)
        playlistAdapter = PlaylistAdapter(
            PlaylistAdapter.VIEW_TYPE_LIST,
            onClick = { selectedPlaylist ->
                lifecycleScope.launch {
                    val context = requireContext()
                    val service = ApiClient.getService(context)
                    val playlistManager = PlaylistManager(service)
                    try {
                        val alreadyInPlaylist = playlistManager.isSongInPlaylist(song.id, selectedPlaylist.id!!)
                        if (alreadyInPlaylist) {
                            Toast.makeText(requireContext(), "La canción ya está en la playlist.", Toast.LENGTH_LONG).show()
                        } else {
                            playlistManager.addSongToPlaylist(song.id, selectedPlaylist.id)
                            showResonantSnackbar(
                                text = "¡Canción añadida correctamente!",
                                colorRes = R.color.successColor,
                                iconRes = R.drawable.success
                            )
                        }
                    } catch (e: Exception) {
                        Log.i("Error", "Hubo un error ${e.toString()}")
                    }
                    dismiss()
                }
            }
            // No necesitas el long click aquí
        )
        playlistRecyclerView.adapter = playlistAdapter
        playlistRecyclerView.layoutManager = LinearLayoutManager(requireContext())

        songTitle.text = song.title
        songArtist.text = song.artistName
        songStreams.text = "${song.streams} reproducciones"

        val placeholderRes = R.drawable.album_cover
        val errorRes = R.drawable.album_cover
        val userViewModel = ViewModelProvider(requireActivity()).get(UserViewModel::class.java)

        if (!song.albumImageUrl.isNullOrBlank()) {
            Glide.with(songImage)
                .load(song.albumImageUrl)
                .placeholder(placeholderRes)
                .error(errorRes)
                .into(songImage)
        } else if (!song.url.isNullOrBlank()) {
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    Utils.getEmbeddedPictureFromUrl(requireContext(), song.url!!)
                }
                if (bitmap != null) {
                    songImage.setImageBitmap(bitmap)
                } else {
                    songImage.setImageResource(errorRes)
                }
            }
        } else {
            songImage.setImageResource(placeholderRes)
        }

        lifecycleScope.launch {
            val playlists = withContext(Dispatchers.IO) {
                val context = requireContext()
                val service = ApiClient.getService(context)
                val playlistManager = PlaylistManager(service)
                playlistManager.getPlaylistByUserId(userViewModel.user?.id ?: "")
            }
            playlistAdapter.updateData(playlists)

            if (playlists.isEmpty()) {
                playlistRecyclerView.visibility = View.GONE
                noPlaylistTextView.visibility = View.VISIBLE
                selectPlaylistText.visibility = View.GONE

                noPlaylistTextView.setOnClickListener {
                    dismiss()
                    requireActivity().run {
                        val navController = (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment)?.navController
                        navController?.navigate(R.id.createPlaylistFragment)
                    }
                }
            } else {
                playlistRecyclerView.visibility = View.VISIBLE
                noPlaylistTextView.visibility = View.GONE
                selectPlaylistText.visibility = View.VISIBLE
            }
        }

        return view
    }
}