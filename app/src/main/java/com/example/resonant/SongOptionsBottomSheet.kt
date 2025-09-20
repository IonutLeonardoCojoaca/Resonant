package com.example.resonant

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.resonant.SnackbarUtils.showResonantSnackbar
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SongOptionsBottomSheet(
    private val song: Song,
    private val onAddToPlaylistClick: (Song) -> Unit = {},
    private val onSeeSongClick: ((Song) -> Unit)? = null,
    private val onAddToFavoriteClick: ((Song) -> Unit)? = null,
    private val onFavoriteToggled: ((Song, Boolean) -> Unit)? = null,
    private val playlistId: String? = null,
    private val onRemoveFromPlaylistClick: ((Song, String) -> Unit)? = null
) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.bottom_sheet_song_options, container, false)

        val songImage: ShapeableImageView = view.findViewById(R.id.songImage)
        val songTitle: TextView = view.findViewById(R.id.songTitle)
        val songArtist: TextView = view.findViewById(R.id.songArtist)
        val songStreams: TextView = view.findViewById(R.id.songStreams)
        val seeSongButton: TextView = view.findViewById(R.id.seeSongButton)
        val addToFavoriteButton: TextView = view.findViewById(R.id.addToFavoriteButton)
        val addToPlaylistButton: TextView = view.findViewById(R.id.addToPlaylistButton)
        val cancelButton: TextView = view.findViewById(R.id.cancelButton)
        val shareSongButton: TextView = view.findViewById(R.id.shareSongButton)

        songTitle.text = song.title
        songArtist.text = song.artistName

        if(song.streams == 0){
            songStreams.text = "Sin reproducciones"
        }else if (song.streams == 1){
            songStreams.text = "${song.streams} reproducción"
        }else{
            songStreams.text = "${song.streams} reproducciones"
        }

        if (playlistId != null) {
            addToPlaylistButton.text = "Eliminar de la playlist"
            addToPlaylistButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.delete_icon, 0, 0, 0)

            addToPlaylistButton.setOnClickListener {
                dismiss()
                onRemoveFromPlaylistClick?.invoke(song, playlistId)
            }
        } else {
            addToPlaylistButton.text = "Añadir a playlist"
            addToPlaylistButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.add, 0, 0, 0)

            addToPlaylistButton.setOnClickListener {
                dismiss()
                val selectPlaylistBottomSheet = SelectPlaylistBottomSheet(song)
                selectPlaylistBottomSheet.show(parentFragmentManager, "SelectPlaylistBottomSheet")
            }
        }


        val placeholderRes = R.drawable.album_cover
        val errorRes = R.drawable.album_cover

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

        val favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]
        var isFavorite = false

        favoritesViewModel.favorites.observe(viewLifecycleOwner) { favorites ->
            isFavorite = favorites.any { it.id == song.id }
            if (isFavorite) {
                addToFavoriteButton.text = "Eliminar de favoritos"
                addToFavoriteButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.remove_favorite, 0, 0, 0)
            } else {
                addToFavoriteButton.text = "Añadir a favoritos"
                addToFavoriteButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.add_favorite, 0, 0, 0)
            }
        }

        addToFavoriteButton.setOnClickListener {
            val oldState = isFavorite
            val newState = !oldState

            // 🔥 Actualizar instantáneamente la UI local
            isFavorite = newState
            updateFavoriteButtonUI(addToFavoriteButton, newState)

            // 🔥 Avisar al adapter inmediatamente
            onFavoriteToggled?.invoke(song, oldState)

            if (newState) {
                favoritesViewModel.addFavorite(song) { success ->
                    if (success) {
                        showResonantSnackbar(
                            text = "¡Canción añadida a favoritos!",
                            colorRes = R.color.successColor,
                            iconRes = R.drawable.success
                        )
                    } else {
                        // revertir si falla
                        isFavorite = oldState
                        updateFavoriteButtonUI(addToFavoriteButton, oldState)
                        onFavoriteToggled?.invoke(song, newState)
                        showResonantSnackbar(
                            text = "Error al añadir favorito",
                            colorRes = R.color.errorColor,
                            iconRes = R.drawable.error
                        )
                    }
                    dismiss()
                }
            } else {
                favoritesViewModel.deleteFavorite(song.id) { success ->
                    if (success) {
                        showResonantSnackbar(
                            text = "Canción eliminada de favoritos",
                            colorRes = R.color.successColor,
                            iconRes = R.drawable.success
                        )
                    } else {
                        // revertir si falla
                        isFavorite = oldState
                        updateFavoriteButtonUI(addToFavoriteButton, oldState)
                        onFavoriteToggled?.invoke(song, newState)
                        showResonantSnackbar(
                            text = "Error al eliminar favorito",
                            colorRes = R.color.errorColor,
                            iconRes = R.drawable.error
                        )
                    }
                    dismiss()
                }
            }
        }

        seeSongButton.setOnClickListener {
            dismiss()
            onSeeSongClick?.invoke(song)
        }

        shareSongButton.setOnClickListener {
            // Si tienes el bitmap cargado (por ejemplo, de Glide o tu propio loader)
            val bitmap = songImage.drawable?.let { drawable ->
                (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            }

            val shareText = buildShareText(song)

            if (bitmap != null) {
                // Guarda el bitmap como archivo temporal
                val imageFile = File(requireContext().cacheDir, "shared_song_${song.id}.png")
                FileOutputStream(imageFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                // Usa FileProvider para obtener el Uri
                val imageUri: Uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider", // Debes definir esto en el Manifest
                    imageFile
                )

                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, shareText)
                    putExtra(Intent.EXTRA_STREAM, imageUri)
                    type = "image/png"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Compartir canción"))
            } else {
                // Solo texto si no hay imagen
                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, shareText)
                    type = "text/plain"
                }
                startActivity(Intent.createChooser(shareIntent, "Compartir canción"))
            }
            dismiss()
        }

        cancelButton.setOnClickListener {
            dismiss()
        }

        return view
    }

    private fun updateFavoriteButtonUI(button: TextView, isFavorite: Boolean) {
        if (isFavorite) {
            button.text = "Eliminar de favoritos"
            button.setCompoundDrawablesWithIntrinsicBounds(R.drawable.remove_favorite, 0, 0, 0)
        } else {
            button.text = "Añadir a favoritos"
            button.setCompoundDrawablesWithIntrinsicBounds(R.drawable.add_favorite, 0, 0, 0)
        }
    }

    private fun buildShareText(song: Song): String {
        // Usa el dominio público del Worker
        val songLink = "https://workers-playground-odd-fire-9bf1.resonant-app-service.workers.dev/shared/android/song/${song.id}"

        return """
        ¡Escucha esta canción en Resonant!
        🎵 ${song.title}
        👤 ${song.artistName}
        🔗 $songLink
        #ResonantApp
    """.trimIndent()
    }


}
