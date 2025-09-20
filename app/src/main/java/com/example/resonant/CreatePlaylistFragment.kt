package com.example.resonant

import android.graphics.drawable.TransitionDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class CreatePlaylistFragment : BaseFragment(R.layout.fragment_create_playlist) {

    private lateinit var arrowGoBackButton: ImageButton
    private lateinit var playlistName: TextInputEditText
    private lateinit var playlistDescription: TextInputEditText
    private lateinit var cardOptionPublic: CardView
    private lateinit var cardOptionPrivate: CardView
    lateinit var layoutOption1: RelativeLayout
    lateinit var layoutOption2: RelativeLayout
    private lateinit var createButton: Button

    private lateinit var publicIconCheck: ImageView
    private lateinit var privateIconCheck: ImageView

    private var selectedOption = 1

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_create_playlist, container, false)

        publicIconCheck = view.findViewById(R.id.publicIconCheck)
        privateIconCheck = view.findViewById(R.id.privateIconCheck)

        arrowGoBackButton = view.findViewById(R.id.arrowGoBackButton)
        playlistName = view.findViewById(R.id.playlistName)
        playlistDescription = view.findViewById(R.id.playlistDescription)
        cardOptionPublic = view.findViewById(R.id.cardOption1)
        cardOptionPrivate = view.findViewById(R.id.cardOption2)
        layoutOption1 = view.findViewById(R.id.checkBoxPublic)
        layoutOption2 = view.findViewById(R.id.checkBoxPrivate)
        createButton = view.findViewById(R.id.createPlaylistButton)

        cardOptionPublic.setOnClickListener { updateSelection(1) }
        cardOptionPrivate.setOnClickListener { updateSelection(2) }
        arrowGoBackButton.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }

        val userViewModel = ViewModelProvider(requireActivity()).get(UserViewModel::class.java)
        val service = ApiClient.getService(requireContext())

        createButton.setOnClickListener {
            if (validateInputs()) {
                val playlistManager = PlaylistManager(service)
                lifecycleScope.launch {
                    val playlist = Playlist(
                        name = playlistName.text.toString().trim(),
                        description = playlistDescription.text.toString().trim(),
                        isPublic = (selectedOption == 1),
                        id = null,
                        userId = userViewModel.user?.id,
                        numberOfTracks = 0,
                        duration = 0,
                        fileName = null
                    )
                    try {
                        playlistManager.createPlaylist(playlist)
                        PlaylistCreatedDialogFragment {
                            findNavController().navigate(R.id.action_createPlaylistFragment_to_savedFragment)
                        }.show(parentFragmentManager, "playlist_created")
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Error al crear la playlist: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        return view
    }

    fun updateSelection(selected: Int) {

        if (selected == selectedOption) return

        if (selected == 1) {
            layoutOption1.setBackgroundResource(R.drawable.public_to_selected_transition)
            val transitionDrawable1 = layoutOption1.background as? TransitionDrawable
            transitionDrawable1?.startTransition(200)

            layoutOption2.setBackgroundResource(R.drawable.public_to_unselected_transition)
            val transitionDrawable2 = layoutOption2.background as? TransitionDrawable
            transitionDrawable2?.startTransition(200)

            publicIconCheck.setImageResource(R.drawable.icon_public_selected)
            privateIconCheck.setImageResource(R.drawable.icon_private)
        } else {
            layoutOption1.setBackgroundResource(R.drawable.public_to_unselected_transition)
            val transitionDrawable1 = layoutOption1.background as? TransitionDrawable
            transitionDrawable1?.startTransition(200)

            layoutOption2.setBackgroundResource(R.drawable.public_to_selected_transition)
            val transitionDrawable2 = layoutOption2.background as? TransitionDrawable
            transitionDrawable2?.startTransition(200)

            publicIconCheck.setImageResource(R.drawable.icon_public)
            privateIconCheck.setImageResource(R.drawable.icon_private_selected)
        }
        selectedOption = selected
    }

    private fun validateInputs(): Boolean {
        var isValid = true

        val name = playlistName.text?.toString()?.trim() ?: ""
        val description = playlistDescription.text?.toString()?.trim() ?: ""

        val nameRegex = Regex("^[A-Za-z0-9áéíóúÁÉÍÓÚüÜñÑ ]{3,20}$")
        val descriptionRegex = Regex("^[A-Za-z0-9áéíóúÁÉÍÓÚüÜñÑ ,.\\-_'\"!?()\\[\\]@#\$%&/]{0,250}$")

        if (name.isEmpty()) {
            playlistName.error = "El nombre es obligatorio"
            isValid = false
        } else if (name.length > 20) {
            playlistName.error = "El nombre no puede tener más de 20 caracteres"
            isValid = false
        } else if (!nameRegex.matches(name)) {
            playlistName.error = "El nombre solo puede contener letras, números y espacios"
            isValid = false
        } else {
            playlistName.error = null
        }

        if (description.length > 250) {
            playlistDescription.error = "La descripción no puede tener más de 250 caracteres"
            isValid = false
        } else if (description.isNotEmpty() && !descriptionRegex.matches(description)) {
            playlistDescription.error = "La descripción contiene caracteres inválidos"
            isValid = false
        } else {
            playlistDescription.error = null
        }

        if (selectedOption != 1 && selectedOption != 2) {
            Toast.makeText(requireContext(), "Selecciona la privacidad de la playlist", Toast.LENGTH_SHORT).show()
            isValid = false
        }

        return isValid
    }

}