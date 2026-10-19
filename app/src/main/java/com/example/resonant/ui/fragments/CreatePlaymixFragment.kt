package com.example.resonant.ui.fragments

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.example.resonant.R
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.ui.components.CreateCoverPreview
import com.example.resonant.ui.components.CreateCta
import com.example.resonant.ui.components.CreateNameField
import com.example.resonant.ui.components.ResonantAccent
import com.example.resonant.ui.viewmodels.PlaymixListViewModel
import com.example.resonant.ui.viewmodels.PlaymixListViewModelFactory
import com.google.android.material.button.MaterialButton

class CreatePlaymixFragment : BaseFragment(R.layout.fragment_create_playmix) {

    private companion object {
        const val MAX_NAME = 30
        const val CTA_TEXT = "Crear PlayMix"
        const val PLACEHOLDER = "Mi PlayMix"
        val SUGGESTIONS = listOf("Sesión de noche", "Warm up", "After", "Sin pausas", "Mezcla del finde")
        val STEPS = listOf(
            "Añade canciones" to "Desde el menú ⋯ de cualquier canción",
            "Ajusta las transiciones" to "Elige cómo se funde cada tema con el siguiente",
            "Dale al play" to "Suena como una sesión continua, sin cortes"
        )
    }

    private val accent = ResonantAccent.PLAYMIX

    private lateinit var nameField: CreateNameField
    private lateinit var coverPreview: View
    private lateinit var createButton: MaterialButton

    private lateinit var playmixViewModel: PlaymixListViewModel

    private var isCreating = false
    private var visibilityGroup: com.google.android.material.chip.ChipGroup? = null

    /** null con la capacidad apagada: el servidor la crea privada. */
    private fun selectedVisibility(): String? {
        if (!com.example.resonant.managers.CapabilitiesManager.isPlaymixSharingEnabled()) return null
        return when (visibilityGroup?.checkedChipId) {
            R.id.visibilityUnlistedChip -> com.example.resonant.data.network.PlaymixVisibility.UNLISTED
            R.id.visibilityPublicChip -> com.example.resonant.data.network.PlaymixVisibility.PUBLIC
            else -> com.example.resonant.data.network.PlaymixVisibility.PRIVATE
        }
    }

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
        CreateCoverPreview.bind(coverPreview, accent, "PlayMix", PLACEHOLDER)

        createButton = view.findViewById(R.id.createPlaymixButton)
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
            onDone = { createPlaymix() }
        )

        bindSteps(view)

        // Visibilidad al crear (playmix-sharing-discovery): solo con la capacidad activa; por defecto, privada.
        view.findViewById<View>(R.id.visibilitySection).visibility =
            if (com.example.resonant.managers.CapabilitiesManager.isPlaymixSharingEnabled()) View.VISIBLE else View.GONE
        visibilityGroup = view.findViewById(R.id.visibilityChipGroup)

        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            nameField.hideKeyboard()
            findNavController().popBackStack()
        }
    }

    private fun bindSteps(view: View) {
        listOf(R.id.step1, R.id.step2, R.id.step3).forEachIndexed { index, id ->
            val step = view.findViewById<View>(id)
            val (title, subtitle) = STEPS[index]
            step.findViewById<TextView>(R.id.stepNumber).apply {
                text = "${index + 1}"
                setTextColor(accent.color)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ColorUtils.setAlphaComponent(accent.color, 0x2E))
                }
            }
            step.findViewById<TextView>(R.id.stepTitle).text = title
            step.findViewById<TextView>(R.id.stepSubtitle).text = subtitle
        }
    }

    private fun initializeViewModels() {
        val playmixManager = PlaymixManager(requireContext())
        val factory = PlaymixListViewModelFactory(playmixManager)
        playmixViewModel = ViewModelProvider(this, factory).get(PlaymixListViewModel::class.java)
    }

    private fun setupListeners() {
        createButton.setOnClickListener { createPlaymix() }
    }

    private fun createPlaymix() {
        // Evita crear duplicados con pulsaciones repetidas mientras la petición está en curso.
        if (isCreating || !nameField.validate()) return
        isCreating = true
        CreateCta.setLoading(createButton, true, CTA_TEXT)
        nameField.hideKeyboard()
        playmixViewModel.createPlaymix(nameField.text, visibility = selectedVisibility())
    }

    private fun setupObservers() {
        playmixViewModel.playmixCreated.observe(viewLifecycleOwner) { isCreated ->
            if (isCreated) {
                playmixViewModel.onPlaymixCreationHandled()

                val navOptions = androidx.navigation.NavOptions.Builder()
                    .setPopUpTo(R.id.createPlaymixFragment, true)
                    .setLaunchSingleTop(true)
                    .build()
                findNavController().navigate(R.id.savedFragment, null, navOptions)
            }
        }

        playmixViewModel.error.observe(viewLifecycleOwner) { errorMessage ->
            errorMessage?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                playmixViewModel.onPlaymixCreationHandled()
                isCreating = false
                CreateCta.setLoading(createButton, false, CTA_TEXT)
                CreateCta.setEnabled(createButton, nameField.isValid)
            }
        }
    }
}
