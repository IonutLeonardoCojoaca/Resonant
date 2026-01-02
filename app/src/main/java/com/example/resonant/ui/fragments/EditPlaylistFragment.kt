package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.data.models.Playlist
import com.example.resonant.ui.viewmodels.EditPlaylistViewModel
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.example.resonant.utils.Utils
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class EditPlaylistFragment : BaseFragment(R.layout.fragment_edit_playlist) {

    private val viewModel: EditPlaylistViewModel by viewModels()

    private lateinit var inputName: TextInputEditText
    private lateinit var saveButton: MaterialButton
    private lateinit var cancelButton: TextView
    private lateinit var loadingOverlay: View

    private var currentPlaylist: Playlist? = null

    private lateinit var playlistImage: ImageView // 1. Nueva variable
    private lateinit var userProfile: ImageView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        currentPlaylist = arguments?.getParcelable("playlist")

        initViews(view)
        setupListeners()
        observeViewModel()

        currentPlaylist?.let { playlist ->
            inputName.setText(playlist.name)

            // 3. CARGAR IMAGEN DE LA PLAYLIST
            if (!playlist.imageUrl.isNullOrEmpty()) {
                Glide.with(this)
                    .load(playlist.imageUrl)
                    .placeholder(R.drawable.ic_playlist_stack)
                    .error(R.drawable.ic_playlist_stack)
                    .centerCrop()
                    .into(playlistImage)
            } else {
                playlistImage.setImageResource(R.drawable.ic_playlist_stack)
                // Opcional: ponerle padding si es el icono vectorial para que no se vea gigante
                playlistImage.setPadding(50, 50, 50, 50)
            }
        }
    }

    private fun initViews(view: View) {
        inputName = view.findViewById(R.id.inputName)
        saveButton = view.findViewById(R.id.saveButton)
        cancelButton = view.findViewById(R.id.cancelButton)
        loadingOverlay = view.findViewById(R.id.loadingOverlay)

        // 2. Inicializar vistas nuevas
        playlistImage = view.findViewById(R.id.playlistImage)
        userProfile = view.findViewById(R.id.userProfile)

        // Cargar foto de perfil del usuario (arriba a la derecha)
        Utils.loadUserProfile(requireContext(), userProfile)
    }

    private fun setupListeners() {
        saveButton.setOnClickListener {
            val newName = inputName.text.toString().trim()
            currentPlaylist?.let { playlist ->
                viewModel.updatePlaylistName(playlist, newName)
            }
        }

        cancelButton.setOnClickListener {
            findNavController().popBackStack()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.updateState.collect { state ->
                when (state) {
                    is EditPlaylistViewModel.UpdateState.Loading -> {
                        loadingOverlay.visibility = View.VISIBLE
                        saveButton.isEnabled = false
                    }
                    is EditPlaylistViewModel.UpdateState.Success -> {
                        loadingOverlay.visibility = View.GONE
                        showResonantSnackbar(
                            text = "Playlist actualizada correctamente",
                            colorRes = R.color.successColor,
                            iconRes = R.drawable.ic_success
                        )

                        currentPlaylist?.let { playlist ->
                            // 1. Avisar a PlaylistFragment (el padre inmediato)
                            // Para que se actualice el título en la pantalla de detalle
                            findNavController().previousBackStackEntry
                                ?.savedStateHandle
                                ?.set("PLAYLIST_UPDATED_ID", playlist.id)

                            // 2. Avisar a SavedFragment (el abuelo)
                            // Usamos try-catch porque si venimos desde "Search", SavedFragment no existirá en la pila
                            try {
                                findNavController().getBackStackEntry(R.id.savedFragment)
                                    .savedStateHandle
                                    .set("PLAYLIST_UPDATED_ID", playlist.id)
                            } catch (e: IllegalArgumentException) {
                                // SavedFragment no está en la pila de atrás, no pasa nada.
                            }
                        }

                        findNavController().popBackStack()
                    }
                    is EditPlaylistViewModel.UpdateState.Error -> {
                        loadingOverlay.visibility = View.GONE
                        saveButton.isEnabled = true
                        showResonantSnackbar(
                            text = state.message,
                            colorRes = R.color.errorColor,
                            iconRes = R.drawable.ic_error
                        )
                    }
                    else -> {
                        loadingOverlay.visibility = View.GONE
                    }
                }
            }
        }
    }
}