package com.example.resonant

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import com.example.resonant.SnackbarUtils.showResonantSnackbar
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.imageview.ShapeableImageView
import java.io.File
import java.io.FileOutputStream

class SongOptionsBottomSheet(
    private val song: Song,
    private val onSeeSongClick: ((Song) -> Unit)? = null,
    private val onFavoriteToggled: ((Song) -> Unit)? = null,
    private val playlistId: String? = null,
    private val onRemoveFromPlaylistClick: ((Song, String) -> Unit)? = null,
    private val onAddToPlaylistClick: ((Song) -> Unit)? = null

) : BottomSheetDialogFragment() {

    // AÑADE ESTE MÉTODO PARA APLICAR EL TEMA PERSONALIZADO
    override fun getTheme(): Int {
        return R.style.AppBottomSheetDialogTheme
    }

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
            // --- MODO: "Eliminar de la lista actual" ---
            addToPlaylistButton.text = "Eliminar de la lista"
            addToPlaylistButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.delete, 0, 0, 0)
            addToPlaylistButton.setTextColor(Color.parseColor("#F44336"))

            addToPlaylistButton.setOnClickListener {
                // Usamos el ViewModel de DETALLE para eliminar la canción
                onRemoveFromPlaylistClick?.invoke(song, playlistId)

                // Mostramos feedback al usuario
                showResonantSnackbar(
                    text = "Canción eliminada de la playlist",
                    colorRes = R.color.successColor, iconRes = R.drawable.success
                )
                dismiss()
            }
        } else {
            // --- MODO: "Añadir a una lista" ---
            addToPlaylistButton.text = "Añadir a lista"
            addToPlaylistButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.add, 0, 0, 0)

            addToPlaylistButton.setOnClickListener {
                // Ahora, simplemente llama a la nueva lambda y cierra
                onAddToPlaylistClick?.invoke(song)
                dismiss()
            }
        }

        val placeholderRes = R.drawable.album_cover
        val errorRes = R.drawable.album_cover

        val urlToLoad = song.coverUrl ?: song.imageFileName
        if (!urlToLoad.isNullOrBlank()) {
            Glide.with(songImage)
                .load(urlToLoad)
                .placeholder(placeholderRes)
                .error(errorRes)
                .into(songImage)
        } else {
            songImage.setImageResource(placeholderRes)
        }


        // 1. Obtenemos el ViewModel
        val favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]

        // 2. Observamos el LiveData de IDs para saber el estado real y actualizar la UI del botón
        favoritesViewModel.favoriteSongIds.observe(viewLifecycleOwner) { favoriteIds ->
            val isFavorite = favoriteIds.contains(song.id)
            updateFavoriteButtonUI(addToFavoriteButton, isFavorite)
        }

        // 3. El OnClickListener ahora es mucho más simple
        addToFavoriteButton.setOnClickListener {
            // A. Simplemente llamamos a la lambda para notificar al Fragment.
            onFavoriteToggled?.invoke(song)

            // B. (Opcional) Mostramos un snackbar de éxito inmediatamente (actualización optimista del feedback)
            val isCurrentlyFavorite = favoritesViewModel.favoriteSongIds.value?.contains(song.id) ?: false
            if (!isCurrentlyFavorite) {
                showResonantSnackbar(
                    text = "¡Añadida a favoritos!",
                    colorRes = R.color.successColor, iconRes = R.drawable.success
                )
            } else {
                showResonantSnackbar(
                    text = "Eliminada de favoritos",
                    colorRes = R.color.successColor, iconRes = R.drawable.success
                )
            }

            // C. Cerramos el BottomSheet.
            dismiss()
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
            button.setTextColor(Color.parseColor("#F44336"))
        } else {
            button.text = "Añadir a favoritos"
            button.setCompoundDrawablesWithIntrinsicBounds(R.drawable.add_favorite, 0, 0, 0)
            button.setTextColor(Color.parseColor("#FFFFFF"))
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
    """.trimIndent()
    }

}
