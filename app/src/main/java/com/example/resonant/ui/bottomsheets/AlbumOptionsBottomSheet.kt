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
import com.example.resonant.data.models.Album
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.imageview.ShapeableImageView

class AlbumOptionsBottomSheet(
    private val album: Album,
    private val onGoToAlbumClick: ((Album) -> Unit)? = null,
    private val onGoToArtistClick: ((Album) -> Unit)? = null,
    private val onViewDetailsClick: ((Album) -> Unit)? = null
) : BottomSheetDialogFragment() {

    override fun getTheme(): Int {
        return R.style.AppBottomSheetDialogTheme
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.bottom_sheet_album_options, container, false)

        val albumImage: ShapeableImageView = view.findViewById(R.id.albumImage)
        val albumTitle: TextView = view.findViewById(R.id.albumTitle)
        val artistName: TextView = view.findViewById(R.id.artistName)

        val goToAlbumButton: TextView = view.findViewById(R.id.goToAlbumButton)
        val goToArtistButton: TextView = view.findViewById(R.id.goToArtistButton)
        val addToFavoriteButton: TextView = view.findViewById(R.id.addToFavoriteButton)
        val viewDetailsButton: TextView = view.findViewById(R.id.viewDetailsButton)
        val shareAlbumButton: TextView = view.findViewById(R.id.shareAlbumButton)

        albumTitle.text = album.title
        artistName.text = album.artistName ?: album.artists.joinToString(", ") { it.name }.takeIf { it.isNotEmpty() } ?: "Unknown Artist"

        val placeholderRes = R.drawable.ic_album_stack
        val urlToLoad = album.url
        if (!urlToLoad.isNullOrBlank()) {
            Glide.with(albumImage).load(urlToLoad).placeholder(placeholderRes).error(placeholderRes).into(albumImage)
        } else {
            albumImage.setImageResource(placeholderRes)
        }

        val isNetworkAvailable = isInternetAvailable(requireContext())
        val disabledAlpha = 0.4f
        val disabledColor = Color.GRAY

        // Go to Album
        goToAlbumButton.setOnClickListener {
            dismiss()
            onGoToAlbumClick?.invoke(album)
        }
        
        // Go to Artist
        goToArtistButton.setOnClickListener {
            dismiss()
            onGoToArtistClick?.invoke(album)
        }

        // Favorites
        if (isNetworkAvailable) {
            val favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]
            favoritesViewModel.favoriteAlbumIds.observe(viewLifecycleOwner) { favoriteIds ->
                val isFavorite = favoriteIds.contains(album.id)
                updateFavoriteButtonUI(addToFavoriteButton, isFavorite)
            }
            addToFavoriteButton.setOnClickListener {
                onFavoriteToggled(album)
                dismiss()
            }
        } else {
            disableButton(addToFavoriteButton, disabledAlpha, disabledColor)
        }

        // View Details
        viewDetailsButton.setOnClickListener {
            dismiss()
            onViewDetailsClick?.invoke(album)
        }

        // Share
        if (isNetworkAvailable) {
            shareAlbumButton.setOnClickListener {
                shareAlbumLogic(album)
                dismiss()
            }
        } else {
            disableButton(shareAlbumButton, disabledAlpha, disabledColor)
        }

        return view
    }

    private fun onFavoriteToggled(album: Album) {
        val favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]
        val isCurrentlyFavorite = favoritesViewModel.favoriteAlbumIds.value?.contains(album.id) ?: false
        if (!isCurrentlyFavorite) {
            showResonantSnackbar(text = "¡Álbum añadido a favoritos!", colorRes = R.color.successColor, iconRes = R.drawable.ic_success)
            favoritesViewModel.addFavoriteAlbum(album)
        } else {
            showResonantSnackbar(text = "Álbum eliminado de favoritos", colorRes = R.color.successColor, iconRes = R.drawable.ic_success)
             favoritesViewModel.deleteFavoriteAlbum(album.id)
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
            button.setTextColor(Color.parseColor("#FFFFFF"))
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

    private fun shareAlbumLogic(album: Album) {
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            val artistFormatted = album.artistName ?: album.artists.joinToString(", ") { it.name }.takeIf { it.isNotEmpty() } ?: "Unknown Artist"
            putExtra(Intent.EXTRA_TEXT, "¡Escucha el álbum ${album.title} de $artistFormatted en Resonant!")
            type = "text/plain"
        }
        startActivity(Intent.createChooser(shareIntent, "Compartir álbum"))
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
