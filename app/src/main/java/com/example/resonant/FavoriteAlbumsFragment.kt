package com.example.resonant

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView

class FavoriteAlbumsFragment : BaseFragment(R.layout.fragment_favorite_albums) {

    private lateinit var recyclerFavoriteAlbums: RecyclerView
    private lateinit var loadingAnimation: LottieAnimationView
    private lateinit var noLikedAlbumsText: TextView

    private lateinit var favoritesViewModel: FavoritesViewModel
    private lateinit var albumAdapter: AlbumAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_favorite_albums, container, false)

        noLikedAlbumsText = view.findViewById(R.id.noLikedAlbumsText)
        loadingAnimation = view.findViewById(R.id.loadingAnimation)
        recyclerFavoriteAlbums = view.findViewById(R.id.favoriteAlbumsList)

        recyclerFavoriteAlbums.layoutManager = LinearLayoutManager(requireContext())
        albumAdapter = AlbumAdapter(emptyList(), 2)
        recyclerFavoriteAlbums.adapter = albumAdapter

        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]

        // Cargar álbumes favoritos usando el ViewModel genérico
        favoritesViewModel.loadFavoriteAlbums()

        // Observar cambios en todos los favoritos y filtrar álbumes
        favoritesViewModel.favorites.observe(viewLifecycleOwner) { favorites ->
            loadingAnimation.visibility = View.GONE

            val favoriteAlbums = favorites
                .filterIsInstance<FavoriteItem.AlbumItem>()
                .map { it.album }

            if (favoriteAlbums.isEmpty()) {
                noLikedAlbumsText.visibility = View.VISIBLE
                recyclerFavoriteAlbums.visibility = View.GONE
                albumAdapter.submitAlbums(emptyList())
            } else {
                noLikedAlbumsText.visibility = View.GONE
                recyclerFavoriteAlbums.visibility = View.VISIBLE
                albumAdapter.submitAlbums(favoriteAlbums)
            }
        }

        return view
    }

}