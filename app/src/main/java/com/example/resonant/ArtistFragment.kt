package com.example.resonant

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
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.squareup.picasso.Picasso
import kotlinx.coroutines.launch

class ArtistFragment : Fragment() {

    private lateinit var artistImage: ImageView
    private lateinit var artistNameTextView: TextView
    private lateinit var arrowGoBackButton: ImageButton
    private lateinit var nestedScroll: NestedScrollView

    private lateinit var api: ApiResonantService

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_artist, container, false)

        artistImage = view.findViewById(R.id.artistImage)
        artistNameTextView = view.findViewById(R.id.artistName)
        arrowGoBackButton = view.findViewById(R.id.arrowGoBackButton)
        nestedScroll = view.findViewById(R.id.nested_scroll)

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
                val artist = api.getArtistById(artistId)

                val artistName = artist.name ?: "Artista desconocido"
                artistNameTextView.text = artistName

                val artistImageUrl = if (artist.imageUrl.isNotEmpty()) {
                    api.getArtistUrl(artist.imageUrl).url
                } else {
                    null
                }

                if (!artistImageUrl.isNullOrEmpty()) {
                    Picasso.get().load(artistImageUrl).into(artistImage)
                } else {
                    Picasso.get().load(R.drawable.user).into(artistImage)
                }

                // Opcional: si quieres mostrar los álbumes del artista
                // val albums = api.getAlbumsByArtistId(artistId)
                // albumAdapter.submitList(albums)

            } catch (e: Exception) {
                Log.e("ArtistFragment", "Error al cargar los detalles del artista", e)
                Toast.makeText(requireContext(), "Error al cargar el artista", Toast.LENGTH_SHORT).show()
            }
        }
    }



}