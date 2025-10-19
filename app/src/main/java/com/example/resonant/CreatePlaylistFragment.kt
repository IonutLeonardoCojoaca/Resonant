package com.example.resonant

import android.graphics.drawable.TransitionDrawable
import android.os.Bundle
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
import androidx.navigation.fragment.findNavController
import com.google.android.material.textfield.TextInputEditText

class CreatePlaylistFragment : BaseFragment(R.layout.fragment_create_playlist) {

    // --- Propiedades de la UI ---
    private lateinit var arrowGoBackButton: ImageButton
    private lateinit var playlistName: TextInputEditText
    private lateinit var playlistDescription: TextInputEditText
    private lateinit var cardOptionPublic: CardView
    private lateinit var cardOptionPrivate: CardView
    private lateinit var layoutOption1: RelativeLayout
    private lateinit var layoutOption2: RelativeLayout
    private lateinit var createButton: Button
    private lateinit var publicIconCheck: ImageView
    private lateinit var privateIconCheck: ImageView

    // --- ViewModels ---
    private lateinit var playlistsViewModel: PlaylistsListViewModel
    private lateinit var userViewModel: UserViewModel

    // --- Estado ---
    private var selectedOption = 1

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // La única responsabilidad es inflar la vista
        return inflater.inflate(R.layout.fragment_create_playlist, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        initializeViewModels()
        setupListeners()
        setupObservers()
    }

    private fun bindViews(view: View) {
        arrowGoBackButton = view.findViewById(R.id.arrowGoBackButton)
        playlistName = view.findViewById(R.id.playlistName)
        playlistDescription = view.findViewById(R.id.playlistDescription)
        cardOptionPublic = view.findViewById(R.id.cardOption1)
        cardOptionPrivate = view.findViewById(R.id.cardOption2)
        layoutOption1 = view.findViewById(R.id.checkBoxPublic)
        layoutOption2 = view.findViewById(R.id.checkBoxPrivate)
        createButton = view.findViewById(R.id.createPlaylistButton)
        publicIconCheck = view.findViewById(R.id.publicIconCheck)
        privateIconCheck = view.findViewById(R.id.privateIconCheck)
    }

    private fun initializeViewModels() {
        // Obtiene el UserViewModel compartido desde la Activity
        userViewModel = ViewModelProvider(requireActivity()).get(UserViewModel::class.java)

        // Crea nuestro ViewModel local usando la Factory para inyectar dependencias
        val service = ApiClient.getService(requireContext())
        val playlistManager = PlaylistManager(service)
        val factory = PlaylistsListViewModelFactory(playlistManager)
        playlistsViewModel = ViewModelProvider(this, factory).get(PlaylistsListViewModel::class.java)
    }

    private fun setupListeners() {
        arrowGoBackButton.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        cardOptionPublic.setOnClickListener { updateSelection(1) }
        cardOptionPrivate.setOnClickListener { updateSelection(2) }

        createButton.setOnClickListener {
            if (validateInputs()) {
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
                // Se delega la acción al ViewModel. El Fragment no sabe cómo se crea.
                playlistsViewModel.createPlaylist(playlist)
            }
        }
    }

    private fun setupObservers() {
        // Observador para el éxito de la creación
        playlistsViewModel.playlistCreated.observe(viewLifecycleOwner) { isCreated ->
            if (isCreated) {
                findNavController().navigate(R.id.action_createPlaylistFragment_to_savedFragment)


                // Reseteamos el estado para evitar que el diálogo se muestre de nuevo
                playlistsViewModel.onPlaylistCreationHandled()
            }
        }

        // Observador para los errores
        playlistsViewModel.error.observe(viewLifecycleOwner) { errorMessage ->
            errorMessage?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                // Reseteamos el estado del error una vez mostrado
                playlistsViewModel.onPlaylistCreationHandled()
            }
        }
    }

    fun updateSelection(selected: Int) {
        if (selected == selectedOption) return

        if (selected == 1) {
            layoutOption1.setBackgroundResource(R.drawable.public_to_selected_transition)
            (layoutOption1.background as? TransitionDrawable)?.startTransition(200)

            layoutOption2.setBackgroundResource(R.drawable.public_to_unselected_transition)
            (layoutOption2.background as? TransitionDrawable)?.startTransition(200)

            publicIconCheck.setImageResource(R.drawable.icon_public_selected)
            privateIconCheck.setImageResource(R.drawable.icon_private)
        } else {
            layoutOption1.setBackgroundResource(R.drawable.public_to_unselected_transition)
            (layoutOption1.background as? TransitionDrawable)?.startTransition(200)

            layoutOption2.setBackgroundResource(R.drawable.public_to_selected_transition)
            (layoutOption2.background as? TransitionDrawable)?.startTransition(200)

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
            playlistName.error = "Nombre no válido (solo letras, números, espacios)"
            isValid = false
        } else {
            playlistName.error = null
        }

        if (description.length > 250) {
            playlistDescription.error = "La descripción no puede superar los 250 caracteres"
            isValid = false
        } else if (description.isNotEmpty() && !descriptionRegex.matches(description)) {
            playlistDescription.error = "La descripción contiene caracteres inválidos"
            isValid = false
        } else {
            playlistDescription.error = null
        }

        return isValid
    }
}