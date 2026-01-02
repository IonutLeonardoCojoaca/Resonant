package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.example.resonant.R
import com.example.resonant.ui.adapters.ArtistAdapter
import com.example.resonant.ui.adapters.FavoriteArtistsHeaderAdapter
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.utils.Utils

class FavoriteArtistsFragment : BaseFragment(R.layout.fragment_favorite_artists) {

    private lateinit var recyclerFavoriteArtists: RecyclerView
    private lateinit var loadingAnimation: LottieAnimationView
    private lateinit var noLikedArtistsText: TextView
    private lateinit var userProfileImage: ImageView

    private lateinit var favoritesViewModel: FavoritesViewModel

    // Adaptadores
    private lateinit var headerAdapter: FavoriteArtistsHeaderAdapter
    private lateinit var artistAdapter: ArtistAdapter
    private lateinit var concatAdapter: ConcatAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupRecyclerView()
        setupViewModel()
    }

    private fun initViews(view: View) {
        // Asegúrate de que estos IDs coincidan con tu XML (fragment_favorite_artists.xml)
        noLikedArtistsText = view.findViewById(R.id.noLikedArtistsText)
        loadingAnimation = view.findViewById(R.id.loadingAnimation)
        recyclerFavoriteArtists = view.findViewById(R.id.favoriteArtistsList)
        userProfileImage = view.findViewById(R.id.userProfile)

        Utils.loadUserProfile(requireContext(), userProfileImage)
    }

    private fun setupRecyclerView() {
        // 1. Header (Imagen grande + Contador)
        headerAdapter = FavoriteArtistsHeaderAdapter()

        // 2. Adapter de Artistas
        // Mantenemos VIEW_TYPE_LIST como tenías
        artistAdapter = ArtistAdapter(emptyList(), ArtistAdapter.Companion.VIEW_TYPE_LIST)

        // Mantenemos tu navegación con animación compartida
        artistAdapter.onArtistClick = { artist, sharedImage ->
            val bundle = Bundle().apply { putString("artistId", artist.id) }

            // Configuración para la animación de transición de la imagen
            val extras = FragmentNavigatorExtras(sharedImage to sharedImage.transitionName)

            // Asegúrate de que esta acción existe en tu nav_graph, o usa action_global_to_artistFragment
            // Si usas action global, cambia el ID aquí abajo.
            findNavController().navigate(
                R.id.action_favoriteArtistsFragment_to_artistFragment,
                bundle,
                null,
                extras
            )
        }

        // 3. Concatenar
        concatAdapter = ConcatAdapter(headerAdapter, artistAdapter)

        recyclerFavoriteArtists.apply {
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(true)
            setItemViewCacheSize(20)
            adapter = concatAdapter
        }
    }

    private fun setupViewModel() {
        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]

        // Estado inicial de carga (si no hay datos cacheados)
        if (favoritesViewModel.favoriteArtists.value.isNullOrEmpty()) {
            loadingAnimation.visibility = View.VISIBLE
            recyclerFavoriteArtists.visibility = View.GONE
            favoritesViewModel.loadFavoriteArtists()
        } else {
            loadingAnimation.visibility = View.GONE
        }

        // Observamos SOLO la lista de artistas (adiós deprecated)
        favoritesViewModel.favoriteArtists.observe(viewLifecycleOwner) { favoriteArtists ->
            loadingAnimation.visibility = View.GONE

            if (favoriteArtists.isNullOrEmpty()) {
                // CASO VACÍO
                noLikedArtistsText.visibility = View.VISIBLE
                recyclerFavoriteArtists.visibility = View.GONE

                headerAdapter.updateCount(0)
                artistAdapter.submitArtists(emptyList())
            } else {
                // CASO CON DATOS
                noLikedArtistsText.visibility = View.GONE
                recyclerFavoriteArtists.visibility = View.VISIBLE

                // Actualizamos header y lista
                headerAdapter.updateCount(favoriteArtists.size)
                artistAdapter.submitArtists(favoriteArtists)
            }
        }
    }
}