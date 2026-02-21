package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.example.resonant.managers.PlaylistManager
import com.example.resonant.ui.viewmodels.PlaylistsListViewModel
import com.example.resonant.ui.viewmodels.PlaylistsListViewModelFactory
import com.example.resonant.R
import com.example.resonant.ui.viewmodels.UserViewModel
import com.example.resonant.data.models.Playlist
import com.example.resonant.utils.Utils
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class CreatePlaylistFragment : BaseFragment(R.layout.fragment_create_playlist) {

    private lateinit var playlistName: TextInputEditText
    private lateinit var playlistDescription: TextInputEditText
    private lateinit var switchPublic: SwitchMaterial
    private lateinit var tvVisibilityDesc: TextView
    private lateinit var tvVisibilityTitle: TextView
    private lateinit var createButton: Button

    private lateinit var playlistsViewModel: PlaylistsListViewModel
    private lateinit var userViewModel: UserViewModel
    private lateinit var userProfileImage: ImageView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        initializeViewModels()
        setupListeners()
        setupObservers()
    }

    private fun bindViews(view: View) {
        playlistName = view.findViewById(R.id.playlistName)
        playlistDescription = view.findViewById(R.id.playlistDescription)
        switchPublic = view.findViewById(R.id.switchPublic)
        tvVisibilityDesc = view.findViewById(R.id.tvVisibilityDescription)
        tvVisibilityTitle = view.findViewById(R.id.tvVisibilityTitle)
        createButton = view.findViewById(R.id.createPlaylistButton)
        userProfileImage = view.findViewById(R.id.userProfile)
        Utils.loadUserProfile(requireContext(), userProfileImage)

        // Update visibility label and description when switch changes
        switchPublic.setOnCheckedChangeListener { _, isChecked ->
            tvVisibilityTitle.text = if (isChecked) "Playlist pública" else "Playlist privada"
            tvVisibilityDesc.text = if (isChecked)
                "Cualquiera puede encontrarla y escucharla"
            else
                "Solo tú puedes verla"
        }
    }

    private fun initializeViewModels() {
        userViewModel = ViewModelProvider(requireActivity()).get(UserViewModel::class.java)
        val playlistManager = PlaylistManager(requireContext())
        val factory = PlaylistsListViewModelFactory(playlistManager)
        playlistsViewModel = ViewModelProvider(this, factory).get(PlaylistsListViewModel::class.java)
    }

    private fun setupListeners() {
        createButton.setOnClickListener {
            if (validateInputs()) {
                val playlist = Playlist(
                    name = playlistName.text.toString().trim(),
                    description = playlistDescription.text?.toString()?.trim() ?: "",
                    isPublic = switchPublic.isChecked,
                    id = null,
                    userId = userViewModel.user?.id,
                    numberOfTracks = 0,
                    duration = 0,
                    fileName = null
                )
                playlistsViewModel.createPlaylist(playlist)
            }
        }
    }

    private fun setupObservers() {
        playlistsViewModel.playlistCreated.observe(viewLifecycleOwner) { isCreated ->
            if (isCreated) {
                findNavController().previousBackStackEntry?.savedStateHandle?.set(
                    "PLAYLIST_UPDATED_ID",
                    "NEW_PLAYLIST_CREATED"
                )
                playlistsViewModel.onPlaylistCreationHandled()

                val navOptions = androidx.navigation.NavOptions.Builder()
                    .setPopUpTo(R.id.createPlaylistFragment, true)
                    .setLaunchSingleTop(true)
                    .build()
                findNavController().navigate(R.id.savedFragment, null, navOptions)
            }
        }

        playlistsViewModel.error.observe(viewLifecycleOwner) { errorMessage ->
            errorMessage?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                playlistsViewModel.onPlaylistCreationHandled()
            }
        }
    }

    private fun validateInputs(): Boolean {
        val name = playlistName.text?.toString()?.trim() ?: ""
        return when {
            name.isEmpty() -> {
                playlistName.error = "El nombre es obligatorio"
                false
            }
            name.length > 30 -> {
                playlistName.error = "Máximo 30 caracteres"
                false
            }
            else -> {
                playlistName.error = null
                true
            }
        }
    }
}