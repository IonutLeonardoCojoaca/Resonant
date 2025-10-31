package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.example.resonant.ui.fragments.BaseFragment
import com.example.resonant.ui.viewmodels.FavoriteItem
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.R
import com.example.resonant.ui.adapters.ArtistAdapter

class FavoriteArtistsFragment : BaseFragment(R.layout.fragment_favorite_artists) {

    private lateinit var recyclerLikedArtists: RecyclerView
    private lateinit var loadingAnimation: LottieAnimationView
    private lateinit var noLikedArtistsText: TextView

    private lateinit var favoritesViewModel: FavoritesViewModel
    private lateinit var artistAdapter: ArtistAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_favorite_artists, container, false)

        noLikedArtistsText = view.findViewById(R.id.noLikedAlbumsText)
        loadingAnimation = view.findViewById(R.id.loadingAnimation)
        recyclerLikedArtists = view.findViewById(R.id.favoriteAlbumsList)

        recyclerLikedArtists.layoutManager = LinearLayoutManager(requireContext())
        // Inicializamos con lista vacía
        artistAdapter = ArtistAdapter(emptyList(), ArtistAdapter.Companion.VIEW_TYPE_LIST)
        recyclerLikedArtists.adapter = artistAdapter

        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]

        // Cargar artistas favoritos usando el ViewModel genérico
        favoritesViewModel.loadFavoriteArtists()

        // Observar cambios en todos los favoritos y filtrar artistas
        favoritesViewModel.favorites.observe(viewLifecycleOwner) { favorites ->
            loadingAnimation.visibility = View.GONE

            val favoriteArtists = favorites
                .filterIsInstance<FavoriteItem.ArtistItem>()
                .map { it.artist }

            if (favoriteArtists.isEmpty()) {
                noLikedArtistsText.visibility = View.VISIBLE
                recyclerLikedArtists.visibility = View.GONE
                artistAdapter.submitArtists(emptyList())
            } else {
                noLikedArtistsText.visibility = View.GONE
                recyclerLikedArtists.visibility = View.VISIBLE
                artistAdapter.submitArtists(favoriteArtists)
            }
        }

        artistAdapter.onArtistClick = { artist, sharedImage ->
            val bundle = Bundle().apply { putString("artistId", artist.id) }
            val extras = FragmentNavigatorExtras(sharedImage to sharedImage.transitionName)
            findNavController().navigate(
                R.id.action_favoriteArtistsFragment_to_artistFragment,
                bundle,
                null,
                extras
            )
        }


        return view
    }

}