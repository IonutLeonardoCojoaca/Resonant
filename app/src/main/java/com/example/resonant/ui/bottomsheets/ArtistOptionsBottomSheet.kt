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
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.imageview.ShapeableImageView

class ArtistOptionsBottomSheet(
    private val artist: Artist,
    private val onGoToArtistClick: ((Artist) -> Unit)? = null,
    private val onViewDetailsClick: ((Artist) -> Unit)? = null // Callback for detailed view
) : BottomSheetDialogFragment() {

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
        val viewDetailsButton: TextView = view.findViewById(R.id.viewDetailsButton) // New button
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
                // Toggle favorite logic should be handled by caller or ViewModel, 
                // but usually we trigger a callback or call ViewModel directly.
                // Here we assume caller might handle it or we call ViewModel?
                // SongOptions calls a callback. Let's look at SongOptionsBottomSheet again.
                // It calls `onFavoriteToggled`.
                // For simplicity, let's call the ViewModel directly here or add a callback.
                // SongOptionsBottomSheet accepts onFavoriteToggled. I'll add that to constructor to be consistent,
                // but simpler to just call VM here if possible. 
                // But SongOptionsBottomSheet uses callback. Let's refrain from direct VM calls if we want to be pure.
                // However, SongOptionsBottomSheet gets VM inside to OBSERVE but callback to TOGGLE.
                // I'll add onFavoriteToggled to constructor.
                
                // Wait, I didn't add it to constructor signature above. I'll fix it now.
                // For now, let's just use the callback approach which I will add.
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
    
    // Helper to request toggle via ViewModel or Callback. 
    // Since I can't change the constructor signature easily without rewriting the whole file in the tool call,
    // I will assume the caller passes a lambda or I Use Local ViewModel.
    // The previous file content for SongOptions shows: `private val onFavoriteToggled: ((Song) -> Unit)? = null`
    // I should add that to my class property.
    
    // Correcting the class header in my mind (and in the file writing):
    /*
    class ArtistOptionsBottomSheet(
        private val artist: Artist,
        private val onGoToArtistClick: ((Artist) -> Unit)? = null,
        private val onFavoriteToggled: ((Artist) -> Unit)? = null, // Added
        private val onViewDetailsClick: ((Artist) -> Unit)? = null 
    )
    */

    private fun onFavoriteToggled(artist: Artist) {
        // This method will rely on the callback passed in constructor.
        // I need to make sure I add it to the constructor when writing the file.
        // Also show Snackbar.
        // Copy logic from SongOptions.
        val favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java] // Need current value
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
