package com.example.resonant.ui.fragments

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.example.resonant.R
import com.example.resonant.data.models.Playlist
import com.example.resonant.managers.PlaylistManager
import com.example.resonant.ui.components.CreateCoverPreview
import com.example.resonant.ui.components.CreateCta
import com.example.resonant.ui.components.CreateNameField
import com.example.resonant.ui.components.ResonantAccent
import com.example.resonant.ui.viewmodels.PlaylistsListViewModel
import com.example.resonant.ui.viewmodels.PlaylistsListViewModelFactory
import com.example.resonant.ui.viewmodels.UserViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.example.resonant.utils.SnackType
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar

class CreatePlaylistFragment : BaseFragment(R.layout.fragment_create_playlist) {

    private companion object {
        const val MAX_NAME = 30
        const val CTA_TEXT = "Crear playlist"
        const val PLACEHOLDER = "Mi playlist"
        val SUGGESTIONS = listOf("Mis favoritas", "Para entrenar", "Chill", "Road trip", "Fiesta", "Para concentrarme")
    }

    private val accent = ResonantAccent.PLAYLISTS

    private lateinit var nameField: CreateNameField
    private lateinit var coverPreview: View
    private lateinit var playlistDescription: TextInputEditText
    private lateinit var optionPrivate: View
    private lateinit var optionPublic: View
    private lateinit var createButton: MaterialButton

    private lateinit var playlistsViewModel: PlaylistsListViewModel
    private lateinit var userViewModel: UserViewModel

    private var isPublic = false
    private var isCreating = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        initializeViewModels()
        setupListeners()
        setupObservers()
        nameField.focusWithKeyboard()
    }

    private fun bindViews(view: View) {
        coverPreview = view.findViewById(R.id.coverPreview)
        CreateCoverPreview.bind(coverPreview, accent, eyebrowFor(isPublic), PLACEHOLDER)

        createButton = view.findViewById(R.id.createPlaylistButton)
        CreateCta.style(createButton, accent)
        CreateCta.setEnabled(createButton, false)

        nameField = CreateNameField(
            view.findViewById(R.id.nameField),
            accent,
            hint = "Ponle un nombre",
            maxLength = MAX_NAME,
            suggestions = SUGGESTIONS,
            onChanged = { name ->
                CreateCoverPreview.setTitle(coverPreview, name.ifEmpty { PLACEHOLDER })
                if (!isCreating) CreateCta.setEnabled(createButton, name.isNotEmpty())
            },
            onDone = { createPlaylist() }
        )

        playlistDescription = view.findViewById(R.id.playlistDescription)
        val descriptionLayout = view.findViewById<View>(R.id.inputDescriptionLayout)
        view.findViewById<View>(R.id.btnAddDescription).setOnClickListener { button ->
            button.isVisible = false
            descriptionLayout.alpha = 0f
            descriptionLayout.isVisible = true
            descriptionLayout.animate().alpha(1f).setDuration(200).start()
            playlistDescription.requestFocus()
        }

        optionPrivate = view.findViewById(R.id.optionPrivate)
        optionPublic = view.findViewById(R.id.optionPublic)
        bindVisibilityOption(optionPrivate, R.drawable.ic_private, "Privada", "Solo tú puedes verla")
        bindVisibilityOption(optionPublic, R.drawable.ic_public, "Pública", "Cualquiera puede encontrarla")
        optionPrivate.setOnClickListener { setVisibility(false) }
        optionPublic.setOnClickListener { setVisibility(true) }
        setVisibility(false)

        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            nameField.hideKeyboard()
            findNavController().popBackStack()
        }
    }

    private fun bindVisibilityOption(option: View, @DrawableRes icon: Int, title: String, subtitle: String) {
        option.findViewById<ImageView>(R.id.visibilityIcon).setImageResource(icon)
        option.findViewById<TextView>(R.id.visibilityTitle).text = title
        option.findViewById<TextView>(R.id.visibilitySubtitle).text = subtitle
        option.contentDescription = "$title. $subtitle"
    }

    /** Marca la opción elegida (borde y fondo de acento) y lo refleja en la portada. */
    private fun setVisibility(public: Boolean) {
        isPublic = public
        val density = resources.displayMetrics.density
        val idleFill = ContextCompat.getColor(requireContext(), R.color.cardsTheme)
        val idleStroke = ContextCompat.getColor(requireContext(), R.color.discTheme)
        val idleIcon = ContextCompat.getColor(requireContext(), R.color.textTheme)

        listOf(optionPrivate to !public, optionPublic to public).forEach { (option, selected) ->
            option.isSelected = selected
            option.background = GradientDrawable().apply {
                cornerRadius = 18 * density
                setColor(if (selected) ColorUtils.setAlphaComponent(accent.color, 0x24) else idleFill)
                setStroke(
                    ((if (selected) 2 else 1) * density).toInt(),
                    if (selected) accent.color else idleStroke
                )
            }
            option.findViewById<ImageView>(R.id.visibilityIcon).imageTintList =
                ColorStateList.valueOf(if (selected) accent.color else idleIcon)
        }
        CreateCoverPreview.setEyebrow(coverPreview, eyebrowFor(public))
    }

    private fun eyebrowFor(public: Boolean) = if (public) "Playlist · Pública" else "Playlist · Privada"

    private fun initializeViewModels() {
        userViewModel = ViewModelProvider(requireActivity()).get(UserViewModel::class.java)
        val playlistManager = PlaylistManager(requireContext())
        val factory = PlaylistsListViewModelFactory(playlistManager)
        playlistsViewModel = ViewModelProvider(this, factory).get(PlaylistsListViewModel::class.java)
    }

    private fun setupListeners() {
        createButton.setOnClickListener { createPlaylist() }
    }

    private fun createPlaylist() {
        // Evita crear duplicados con pulsaciones repetidas mientras la petición está en curso.
        if (isCreating || !nameField.validate()) return
        isCreating = true
        CreateCta.setLoading(createButton, true, CTA_TEXT)
        nameField.hideKeyboard()

        val playlist = Playlist(
            name = nameField.text,
            description = playlistDescription.text?.toString()?.trim() ?: "",
            isPublic = isPublic,
            id = null,
            userId = userViewModel.user?.id,
            numberOfTracks = 0,
            duration = 0,
            fileName = null
        )
        playlistsViewModel.createPlaylist(playlist)
    }

    private fun setupObservers() {
        playlistsViewModel.playlistCreated.observe(viewLifecycleOwner) { isCreated ->
            if (isCreated) {
                findNavController().previousBackStackEntry?.savedStateHandle?.set(
                    "PLAYLIST_UPDATED_ID",
                    "NEW_PLAYLIST_CREATED"
                )
                playlistsViewModel.onPlaylistCreationHandled()
                showResonantSnackbar(nameField.text, SnackType.SUCCESS, title = "Playlist creada")

                val navOptions = androidx.navigation.NavOptions.Builder()
                    .setPopUpTo(R.id.createPlaylistFragment, true)
                    .setLaunchSingleTop(true)
                    .build()
                findNavController().navigate(R.id.savedFragment, null, navOptions)
            }
        }

        playlistsViewModel.error.observe(viewLifecycleOwner) { errorMessage ->
            errorMessage?.let {
                showResonantSnackbar(it, SnackType.ERROR, title = "No se pudo crear la playlist")
                playlistsViewModel.onPlaylistCreationHandled()
                isCreating = false
                CreateCta.setLoading(createButton, false, CTA_TEXT)
                CreateCta.setEnabled(createButton, nameField.isValid)
            }
        }
    }
}
