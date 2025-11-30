package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
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
import com.google.android.material.textfield.TextInputEditText

class CreatePlaylistFragment : BaseFragment(R.layout.fragment_create_playlist) {

    private lateinit var playlistName: TextInputEditText
    private lateinit var createButton: Button

    private lateinit var playlistsViewModel: PlaylistsListViewModel
    private lateinit var userViewModel: UserViewModel

    private lateinit var userProfileImage: ImageView

    private var selectedOption = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        initializeViewModels()
        setupListeners()
        setupObservers()
    }

    private fun bindViews(view: View) {
        playlistName = view.findViewById(R.id.playlistName)
        createButton = view.findViewById(R.id.createPlaylistButton)
        userProfileImage = view.findViewById(R.id.userProfile)
        Utils.loadUserProfile(requireContext(), userProfileImage)
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
                    description = "",
                    isPublic = (selectedOption == 1),
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
        var isValid = true
        val name = playlistName.text?.toString()?.trim() ?: ""

        if (name.isEmpty()) {
            playlistName.error = "El nombre es obligatorio"
            isValid = false
        } else if (name.length > 20) {
            playlistName.error = "Máximo 20 caracteres"
            isValid = false
        } else {
            playlistName.error = null
        }

        return isValid
    }
}