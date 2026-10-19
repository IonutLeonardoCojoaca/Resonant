package com.example.resonant.ui.bottomsheets

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.SessionResult
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.example.resonant.data.models.Song
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.imageview.ShapeableImageView
import java.io.File
import java.io.FileOutputStream
import androidx.core.graphics.toColorInt
import com.example.resonant.data.models.Artist
import com.example.resonant.playback.PlaybackControllerConnection
import com.example.resonant.playback.QueueCommands
import kotlinx.coroutines.launch
import com.example.resonant.utils.SnackType
import com.example.resonant.utils.SnackbarUtils.favoriteSongFeedback

class SongOptionsBottomSheet(
    private val song: Song,
    private val onSeeSongClick: ((Song) -> Unit)? = null,
    private val onFavoriteToggled: ((Song) -> Unit)? = null,
    private val playlistId: String? = null,
    private val onRemoveFromPlaylistClick: ((Song, String) -> Unit)? = null,
    private val onAddToPlaylistClick: ((Song) -> Unit)? = null,
    private val onDownloadClick: ((Song) -> Unit)? = null,
    // 🔥 NUEVO CALLBACK: Acción para borrar la descarga
    private val onRemoveDownloadClick: ((Song) -> Unit)? = null,
    private val onGoToAlbumClick: ((String) -> Unit)? = null,
    private val onGoToArtistClick: ((Artist) -> Unit)? = null,
    private val onAddToPlaymixClick: ((Song) -> Unit)? = null

) : ResonantBottomSheetDialogFragment() {

    override val hasOptionRows = true

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

        // Botones de acción
        val seeSongButton: TextView = view.findViewById(R.id.seeSongButton)
        val playNextButton: TextView = view.findViewById(R.id.playNextButton)
        val addToQueueButton: TextView = view.findViewById(R.id.addToQueueButton)
        val goToAlbumButton: TextView = view.findViewById(R.id.goToAlbumButton)
        val goToArtistButton: TextView = view.findViewById(R.id.goToArtistButton)
        val addToFavoriteButton: TextView = view.findViewById(R.id.addToFavoriteButton)
        val addToPlaylistButton: TextView = view.findViewById(R.id.addToPlaylistButton)
        val addToPlaymixButton: TextView = view.findViewById(R.id.addToPlaymixButton)
        val downloadSongButton: TextView = view.findViewById(R.id.downloadSongButton)
        val shareSongButton: TextView = view.findViewById(R.id.shareSongButton)
        val cancelButton: TextView = view.findViewById(R.id.cancelButton)

        bindAddToRoom(view.findViewById(R.id.addToRoomButton), SOURCE_SONG, song.id)
        playNextButton.setOnClickListener {
            sendQueueCommand(QueueCommands.PLAY_NEXT)
        }
        addToQueueButton.setOnClickListener {
            sendQueueCommand(QueueCommands.ADD)
        }

        // Rellenar datos básicos
        songTitle.text = song.title
        songArtist.text = song.artistName ?: song.artists.joinToString(", ") { it.name }.takeIf { it.isNotEmpty() } ?: "Desconocido"
        if(song.streams == 0){
            songStreams.text = "Sin reproducciones"
        }else if (song.streams == 1){
            songStreams.text = "${song.streams} reproducción"
        }else{
            songStreams.text = "${song.streams} reproducciones"
        }

        // Carga de Imagen
        val placeholderRes = R.drawable.ic_disc
        val errorRes = R.drawable.ic_disc
        val urlToLoad = song.coverUrl ?: song.imageFileName
        if (!urlToLoad.isNullOrBlank()) {
            Glide.with(songImage).load(urlToLoad).placeholder(placeholderRes).error(errorRes).into(songImage)
        } else {
            songImage.setImageResource(placeholderRes)
        }

        // --- DETECCIÓN DE RED ---
        val isNetworkAvailable = isInternetAvailable(requireContext())
        val disabledAlpha = 0.4f
        val disabledColor = Color.GRAY

        // ============================
        //  NAVEGACIÓN (ÁLBUM / ARTISTA)
        // ============================

        // Ir al Álbum
        val albumId = song.album?.id
        if (!albumId.isNullOrBlank()) {
            goToAlbumButton.visibility = View.VISIBLE
            goToAlbumButton.setOnClickListener {
                dismiss()
                onGoToAlbumClick?.invoke(albumId)
            }
        } else {
            goToAlbumButton.visibility = View.GONE
        }

        // Ir al Artista
        goToArtistButton.setOnClickListener {
           val artists = song.artists.map { it.toArtist() }
           if (artists.isNotEmpty()) {
               if (artists.size > 1) {
                   val selector = ArtistSelectorBottomSheet(artists) { selectedArtist ->
                       dismiss()
                       onGoToArtistClick?.invoke(selectedArtist)
                   }
                   selector.show(parentFragmentManager, "ArtistSelectorBottomSheet")
               } else {
                   dismiss()
                   onGoToArtistClick?.invoke(artists[0])
               }
           } else {
               dismiss()
           }
        }

        // -----------------------------------------------------------------------
        // 1. LÓGICA DE DESCARGA / ELIMINAR (Híbrida)
        // -----------------------------------------------------------------------
        val localFile = File(requireContext().filesDir, "${song.id}.mp3")

        // Hacemos visible el botón siempre, pero cambiamos su función
        downloadSongButton.visibility = View.VISIBLE

        if (localFile.exists()) {
            // --- MODO ELIMINAR ---
            downloadSongButton.text = "Eliminar descarga"
            OptionRows.setIcon(downloadSongButton, R.drawable.ic_download_delete)
            OptionRows.setTone(downloadSongButton, OptionRows.Tone.DESTRUCTIVE)

            downloadSongButton.setOnClickListener {
                onRemoveDownloadClick?.invoke(song)
                dismiss()
            }
        } else {
            // --- MODO DESCARGAR ---
            downloadSongButton.text = "Descargar"
            OptionRows.setIcon(downloadSongButton, R.drawable.ic_download_done)
            OptionRows.setTone(downloadSongButton, OptionRows.Tone.NORMAL)

            if (isNetworkAvailable) {
                downloadSongButton.setOnClickListener {
                    onDownloadClick?.invoke(song)
                    dismiss()
                }
            } else {
                disableButton(downloadSongButton, disabledAlpha, disabledColor)
            }
        }

        // 2. PLAYLIST
        if (isNetworkAvailable) {
            if (playlistId != null) {
                addToPlaylistButton.text = "Eliminar de la lista"
                OptionRows.setIcon(addToPlaylistButton, R.drawable.ic_delete)
                OptionRows.setTone(addToPlaylistButton, OptionRows.Tone.DESTRUCTIVE)
                addToPlaylistButton.setOnClickListener {
                    onRemoveFromPlaylistClick?.invoke(song, playlistId)
                    dismiss()
                }
            } else {
                addToPlaylistButton.text = "Añadir a lista"
                OptionRows.setIcon(addToPlaylistButton, R.drawable.ic_menu_add_selected)
                OptionRows.setTone(addToPlaylistButton, OptionRows.Tone.NORMAL)
                addToPlaylistButton.setOnClickListener {
                    onAddToPlaylistClick?.invoke(song)
                    dismiss()
                }
            }
        } else {
            disableButton(addToPlaylistButton, disabledAlpha, disabledColor)
        }

        // 2.5 PLAYMIX
        if (isNetworkAvailable) {
            addToPlaymixButton.setOnClickListener {
                onAddToPlaymixClick?.invoke(song)
                dismiss()
            }
        } else {
            disableButton(addToPlaymixButton, disabledAlpha, disabledColor)
        }

        // 3. FAVORITOS
        if (isNetworkAvailable) {
            val favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]
            favoritesViewModel.favoriteSongIds.observe(viewLifecycleOwner) { favoriteIds ->
                val isFavorite = favoriteIds.contains(song.id)
                updateFavoriteButtonUI(addToFavoriteButton, isFavorite)
            }
            addToFavoriteButton.setOnClickListener {
                // El resultado lo comunica quien hace el toggle (favoriteSongFeedback), cuando el
                // servidor responde: aquí aún no se sabe si saldrá bien.
                val toggle = onFavoriteToggled
                if (toggle != null) {
                    toggle(song)
                } else {
                    favoritesViewModel.toggleFavoriteSong(song, requireActivity().favoriteSongFeedback(song))
                }
                dismiss()
            }
        } else {
            OptionRows.setFavorite(addToFavoriteButton, isFavorite = false)
            disableButton(addToFavoriteButton, disabledAlpha, disabledColor)
        }

        // 4. VER CANCIÓN
        if (isNetworkAvailable) {
            seeSongButton.setOnClickListener {
                dismiss()
                onSeeSongClick?.invoke(song)
            }
        } else {
            disableButton(seeSongButton, disabledAlpha, disabledColor)
        }

        // 5. COMPARTIR
        if (isNetworkAvailable) {
            shareSongButton.setOnClickListener {
                shareSongLogic(song)
                dismiss()
            }
        } else {
            disableButton(shareSongButton, disabledAlpha, disabledColor)
        }

        cancelButton.setOnClickListener { dismiss() }

        return view
    }

    private fun disableButton(button: TextView, alpha: Float, color: Int) {
        OptionRows.disable(button)
        val currentText = button.text.toString()
        if (!currentText.contains("(Offline)")) {
            button.text = "$currentText (Offline)"
        }
    }

    private fun updateFavoriteButtonUI(button: TextView, isFavorite: Boolean) {
        OptionRows.setFavorite(button, isFavorite)
    }

    private fun shareSongLogic(song: Song) {
        com.example.resonant.utils.ShareUtils.shareSong(requireContext(), song)
    }

    private fun sendQueueCommand(action: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                PlaybackControllerConnection.sendQueueCommand(
                    requireContext(),
                    action,
                    Bundle().apply {
                        putString(QueueCommands.ARG_SONG_ID, song.id)
                    }
                )
            }.getOrElse { error ->
                showResonantSnackbar(
                    error.localizedMessage ?: "No se pudo modificar la cola",
                    R.color.errorColor,
                    R.drawable.ic_warning
                )
                return@launch
            }
            val message = result.extras
                .getString(QueueCommands.RESULT_MESSAGE)
                .orEmpty()
            if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                showResonantSnackbar(
                    message.ifBlank { "Cola actualizada" },
                    R.color.successColor,
                    R.drawable.ic_success
                )
                dismiss()
            } else {
                showResonantSnackbar(
                    message.ifBlank { "No se pudo modificar la cola" },
                    R.color.errorColor,
                    R.drawable.ic_warning
                )
            }
        }
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
