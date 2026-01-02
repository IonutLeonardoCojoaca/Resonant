package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.example.resonant.R
import com.example.resonant.ui.adapters.AlbumAdapter
import com.example.resonant.ui.adapters.FavoriteAlbumsHeaderAdapter
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.utils.Utils

class FavoriteAlbumsFragment : BaseFragment(R.layout.fragment_favorite_albums) {

    private lateinit var recyclerFavoriteAlbums: RecyclerView
    private lateinit var loadingAnimation: LottieAnimationView
    private lateinit var noLikedAlbumsText: TextView
    private lateinit var userProfileImage: ImageView

    private lateinit var favoritesViewModel: FavoritesViewModel

    // Adaptadores
    private lateinit var headerAdapter: FavoriteAlbumsHeaderAdapter
    private lateinit var albumAdapter: AlbumAdapter
    private lateinit var concatAdapter: ConcatAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupRecyclerView()
        setupViewModel()
    }

    private fun initViews(view: View) {
        noLikedAlbumsText = view.findViewById(R.id.noLikedAlbumsText)
        loadingAnimation = view.findViewById(R.id.loadingAnimation)
        recyclerFavoriteAlbums = view.findViewById(R.id.favoriteAlbumsList)
        userProfileImage = view.findViewById(R.id.userProfile)

        Utils.loadUserProfile(requireContext(), userProfileImage)
    }

    private fun setupRecyclerView() {
        // 1. Header
        headerAdapter = FavoriteAlbumsHeaderAdapter()

        // 2. Lista de Álbumes (Sin pasar lambda de navegación, ya lo gestiona el adapter)
        // El '2' es el viewType para diseño horizontal/grid adaptado a lista vertical
        albumAdapter = AlbumAdapter(emptyList(), 2)

        // 3. Concatenar
        concatAdapter = ConcatAdapter(headerAdapter, albumAdapter)

        recyclerFavoriteAlbums.apply {
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(true)
            setItemViewCacheSize(20)
            adapter = concatAdapter
        }
    }

    private fun setupViewModel() {
        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]

        // Carga inicial inteligente
        if (favoritesViewModel.favoriteAlbums.value.isNullOrEmpty()) {
            loadingAnimation.visibility = View.VISIBLE
            recyclerFavoriteAlbums.visibility = View.GONE
            favoritesViewModel.loadFavoriteAlbums()
        } else {
            loadingAnimation.visibility = View.GONE
        }

        // Observer
        favoritesViewModel.favoriteAlbums.observe(viewLifecycleOwner) { favoriteAlbums ->
            loadingAnimation.visibility = View.GONE

            if (favoriteAlbums.isNullOrEmpty()) {
                noLikedAlbumsText.visibility = View.VISIBLE
                recyclerFavoriteAlbums.visibility = View.GONE

                // Limpiar adaptadores
                headerAdapter.updateCount(0)
                albumAdapter.submitAlbums(emptyList())
            } else {
                noLikedAlbumsText.visibility = View.GONE
                recyclerFavoriteAlbums.visibility = View.VISIBLE

                // Actualizar Header y Lista
                headerAdapter.updateCount(favoriteAlbums.size)
                albumAdapter.submitAlbums(favoriteAlbums)
            }
        }
    }
}