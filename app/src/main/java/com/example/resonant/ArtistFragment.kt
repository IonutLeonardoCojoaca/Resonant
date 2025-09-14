package com.example.resonant

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.facebook.shimmer.ShimmerFrameLayout
import com.squareup.picasso.Picasso
import kotlinx.coroutines.launch

class ArtistFragment : Fragment() {

    private lateinit var api: ApiResonantService

    private lateinit var artistImage: ImageView
    private lateinit var artistNameTextView: TextView
    private lateinit var arrowGoBackButton: FrameLayout
    private lateinit var nestedScroll: NestedScrollView

    private lateinit var shimmerLayout: ShimmerFrameLayout
    private lateinit var recyclerViewAlbums: RecyclerView
    private lateinit var albumsAdapter: AlbumAdapter
    private var albumList: MutableList<Album> = mutableListOf()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_artist, container, false)

        artistImage = view.findViewById(R.id.artistImage)
        artistNameTextView = view.findViewById(R.id.artistName)
        arrowGoBackButton = view.findViewById(R.id.arrowGoBackBackground)
        nestedScroll = view.findViewById(R.id.nested_scroll)

        recyclerViewAlbums = view.findViewById(R.id.listAlbumsRecycler)
        recyclerViewAlbums.layoutManager = LinearLayoutManager(requireContext())
        albumsAdapter = AlbumAdapter(albumList, 1)
        recyclerViewAlbums.adapter = albumsAdapter
        shimmerLayout = view.findViewById(R.id.shimmerLayout)

        shimmerLayout.bringToFront()

        api = ApiClient.getService(requireContext())
        val artistId = arguments?.getString("artistId") ?: return view
        loadArtistDetails(artistId)

        artistImage.scaleX = 1.1f
        artistImage.scaleY = 1.1f
        artistImage.alpha = 0f

        artistImage.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(1000)
            .start()

        nestedScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val parallaxFactor = 0.3f
            val offset = -scrollY * parallaxFactor
            artistImage.translationY = offset
        }

        arrowGoBackButton.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        return view
    }

    private fun loadArtistDetails(artistId: String) {
        lifecycleScope.launch {
            try {
                // Mostrar shimmer y ocultar RecyclerView
                shimmerLayout.visibility = View.VISIBLE
                shimmerLayout.startShimmer()
                recyclerViewAlbums.visibility = View.GONE

                // Obtener artista
                val artist = api.getArtistById(artistId)
                artistNameTextView.text = artist.name ?: "Artista desconocido"

                val artistImageUrl = artist.fileName?.takeIf { it.isNotEmpty() }?.let { file ->
                    api.getArtistUrl(file).url
                }

                if (!artistImageUrl.isNullOrEmpty()) {
                    Picasso.get().load(artistImageUrl).into(artistImage)
                } else {
                    Picasso.get().load(R.drawable.user).into(artistImage)
                }

                // ✅ Obtener álbumes del artista
                val albums = api.getByArtistId(artistId).toMutableList()

                // ✅ Asignar nombre del artista a cada álbum
                albums.forEach { it.artistName = artist.name ?: "Desconocido" }

                // ✅ Resolver URLs de carátulas si faltan
                val albumsSinUrl = albums.filter { it.url.isNullOrEmpty() }

                if (albumsSinUrl.isNotEmpty()) {
                    val fileNames = albumsSinUrl.map { album ->
                        val fileName = album.fileName
                        fileName.takeIf { it?.isNotBlank() == true } ?: "${album.id}.jpg"
                    }

                    val urlList = api.getMultipleAlbumUrls(fileNames)
                    val urlMap = urlList.associateBy { it.fileName }

                    albumsSinUrl.forEach { album ->
                        val fileName = album.fileName.takeIf { it?.isNotBlank() == true } ?: "${album.id}.jpg"
                        album.fileName = fileName
                        album.url = urlMap[fileName]?.url
                    }
                }

                // ✅ Mostrar los álbumes
                albumsAdapter = AlbumAdapter(albums, 1)
                recyclerViewAlbums.adapter = albumsAdapter

                // Ocultar shimmer y mostrar lista
                shimmerLayout.stopShimmer()
                shimmerLayout.visibility = View.GONE
                recyclerViewAlbums.visibility = View.VISIBLE

            } catch (e: Exception) {
                Log.e("ArtistFragment", "Error al cargar detalles del artista", e)
                Toast.makeText(requireContext(), "Error al cargar el artista", Toast.LENGTH_SHORT).show()

                shimmerLayout.stopShimmer()
                shimmerLayout.visibility = View.GONE
            }
        }
    }






}