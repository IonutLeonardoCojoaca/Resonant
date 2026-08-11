package com.example.resonant.ui.bottomsheets

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.data.models.Artist
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.google.android.material.imageview.ShapeableImageView

class ArtistOptionsBottomSheet(
    private val artist: Artist,
    private val onGoToArtistClick: ((Artist) -> Unit)? = null,
    private val onViewDetailsClick: ((Artist) -> Unit)? = null
) : ResonantBottomSheetDialogFragment() {

    override fun getTheme(): Int {
        return R.style.AppBottomSheetDialogTheme
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.bottom_sheet_artist_options, container, false)

        val artistImage: ShapeableImageView = view.findViewById(R.id.artistImage)
        val artistName: TextView = view.findViewById(R.id.artistName)

        val goToArtistButton: TextView = view.findViewById(R.id.goToArtistButton)
        val addToFavoriteButton: TextView = view.findViewById(R.id.addToFavoriteButton)
        val viewDetailsButton: TextView = view.findViewById(R.id.viewDetailsButton)
        val shareArtistButton: TextView = view.findViewById(R.id.shareArtistButton)

        artistName.text = artist.name

        val placeholderRes = R.drawable.ic_user
        val urlToLoad = artist.url
        if (!urlToLoad.isNullOrBlank()) {
            Glide.with(artistImage).load(urlToLoad).placeholder(placeholderRes).error(placeholderRes).into(artistImage)
        } else {
            artistImage.setImageResource(placeholderRes)
        }

        val isNetworkAvailable = isInternetAvailable(requireContext())
        val disabledAlpha = 0.4f
        val disabledColor = Color.GRAY

        // Go to Artist
        goToArtistButton.setOnClickListener {
            dismiss()
            onGoToArtistClick?.invoke(artist)
        }

        // Favorites
        if (isNetworkAvailable) {
            val favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]
            favoritesViewModel.favoriteArtistIds.observe(viewLifecycleOwner) { favoriteIds ->
                val isFavorite = favoriteIds.contains(artist.id)
                updateFavoriteButtonUI(addToFavoriteButton, isFavorite)
            }
            addToFavoriteButton.setOnClickListener {
                onFavoriteToggled(artist)
                dismiss()
            }
        } else {
            disableButton(addToFavoriteButton, disabledAlpha, disabledColor)
        }
        
        // View Details
        viewDetailsButton.setOnClickListener {
            dismiss()
            onViewDetailsClick?.invoke(artist)
        }

        // Share
        if (isNetworkAvailable) {
            shareArtistButton.setOnClickListener {
                shareArtistLogic(artist)
                dismiss()
            }
        } else {
            disableButton(shareArtistButton, disabledAlpha, disabledColor)
        }

        return view
    }

    private fun onFavoriteToggled(artist: Artist) {
        val favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]
        val isCurrentlyFavorite = favoritesViewModel.favoriteArtistIds.value?.contains(artist.id) ?: false
        if (!isCurrentlyFavorite) {
            showResonantSnackbar(text = "¡Artista añadido a favoritos!", colorRes = R.color.successColor, iconRes = R.drawable.ic_success)
            favoritesViewModel.addFavoriteArtist(artist)
        } else {
            showResonantSnackbar(text = "Artista eliminado de favoritos", colorRes = R.color.successColor, iconRes = R.drawable.ic_success)
            favoritesViewModel.deleteFavoriteArtist(artist.id)
        }
    }

    private fun updateFavoriteButtonUI(button: TextView, isFavorite: Boolean) {
        if (isFavorite) {
            button.text = "Eliminar de favoritos"
            button.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_favorite_delete, 0, 0, 0)
            button.setTextColor(Color.parseColor("#F44336"))
        } else {
            button.text = "Añadir a favoritos"
            button.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_add_favorite, 0, 0, 0)
            button.setTextColor(requireContext().getColor(R.color.white))
        }
    }

    private fun disableButton(button: TextView, alpha: Float, color: Int) {
        button.isEnabled = false
        button.alpha = alpha
        button.setTextColor(color)
        button.compoundDrawableTintList = ColorStateList.valueOf(color)
        val currentText = button.text.toString()
        if (!currentText.contains("(Offline)")) {
            button.text = "$currentText (Offline)"
        }
    }

    private fun shareArtistLogic(artist: Artist) {
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, "¡Echa un vistazo a ${artist.name} en Resonant!")
            type = "text/plain"
        }
        startActivity(Intent.createChooser(shareIntent, "Compartir artista"))
    }

    private fun isInternetAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }
}
